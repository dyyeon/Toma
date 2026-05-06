package com.capstone.toma

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*

/**
 * Optimized openWakeWord 3-stage pipeline implementation.
 * Pipeline: PCM -> MelSpectrogram -> Embedding -> Classifier
 */
class WakeWordManager(
    private val context: Context,
    private val personalizer: OnDevicePersonalizer,
    private val onWakeWordDetected: () -> Unit
) {
    private val TAG = "WakeWord"
    
    // Configuration
    var detectionThreshold: Float = 0.05f
    var verboseLogging: Boolean = true
    private var consecutiveDetections = 0
    private val requiredConsecutive = 3  // 3 frames (approx 240ms) must be above threshold
    
    private val SAMPLE_RATE = 16000
    private val CHUNK_SIZE = 1280 // 80ms at 16kHz
    private val MEL_WINDOW_SIZE = 76
    private val EMBEDDING_WINDOW_SIZE = 16
    private val MEL_CHANNELS = 32
    private val EMBEDDING_DIM = 96

    // ONNX Resources
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var clfSession: OrtSession? = null
    private var isPersonalModel = false
    
    // On-device personalizer
    var useOnDevicePersonalizer = false
        private set
    private var lastDetectionTime = 0L
    private val detectionCooldownMs = 3000L  // 3초 쿨다운

    // State Buffers
    private val pcmBuffer = mutableListOf<Short>()
    private val melBuffer = mutableListOf<FloatArray>()
    private val embeddingBuffer = LinkedList<FloatArray>()
    
    // 최근 1536차원 embedding 캐시 (Personalizer 학습용)
    private var lastEmbedding: FloatArray? = null

    // Ambient collection for negative samples
    private val ambientEmbeddings = mutableListOf<FloatArray>()
    private var isCollectingAmbient = false

    init {
        loadModels()
    }

    private fun loadModels() {
        try {
            // Load Base Models (Mel & Embedding) directly from assets as ByteArrays
            melSession = ortEnv.createSession(context.assets.open("melspectrogram.onnx").readBytes())
            embSession = ortEnv.createSession(context.assets.open("embedding_model.onnx").readBytes())
            Log.d(TAG, "✅ Base models (Mel, Embedding) loaded from assets")

            // Load Classifier Model (Check Personal first, then Default)
            val personalWeights = File(context.filesDir, "personal_weights.bin")
            if (personalWeights.exists()) {
                if (personalizer.loadFromFile(personalWeights)) {
                    useOnDevicePersonalizer = true
                    Log.d(TAG, "✅ Existing on-device weights activated on startup")
                }
            }

            if (!useOnDevicePersonalizer) {
                val personalModel = File(context.filesDir, "hey_toma_personal.onnx")
                if (personalModel.exists()) {
                    loadPersonalModel(personalModel.absolutePath)
                } else {
                    loadDefaultModel(context)
                }
            }
            
            Log.d(TAG, "✅ 3-Stage ONNX Pipeline initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model load failed: ${e.message}")
        }
    }

    private fun loadDefaultModel(context: Context) {
        try {
            val modelBytes = context.assets.open("hey_toma.onnx").readBytes()
            clfSession?.close()
            clfSession = ortEnv.createSession(modelBytes)
            isPersonalModel = false
            Log.d(TAG, "✅ Default model loaded from assets")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Default model load failed: ${e.message}")
        }
    }

    fun loadPersonalModel(path: String) {
        try {
            val modelFile = File(path)
            if (!modelFile.exists()) {
                Log.e(TAG, "❌ Personal model file not found: $path")
                return
            }
            val modelBytes = modelFile.readBytes()
            val newSession = ortEnv.createSession(modelBytes)
            clfSession?.close()
            clfSession = newSession
            isPersonalModel = true
            Log.d(TAG, "✅ Personal model loaded: $path")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Personal model load failed: ${e.message}")
        }
    }

    fun processFrame(pcmData: ByteArray) = synchronized(this) {
        if (melSession == null || embSession == null) return@synchronized
        if (!useOnDevicePersonalizer && clfSession == null) return@synchronized

        val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shortBuffer.hasRemaining()) {
            pcmBuffer.add(shortBuffer.get())
        }

        while (pcmBuffer.size >= CHUNK_SIZE) {
            val chunk = pcmBuffer.take(CHUNK_SIZE).toShortArray()
            repeat(CHUNK_SIZE) { pcmBuffer.removeAt(0) }
            runPipeline(chunk)
        }
    }

    private fun runPipeline(chunk: ShortArray) {
        try {
            val floatPcm = FloatArray(CHUNK_SIZE) { chunk[it].toFloat() / 32768.0f }
            val pcmTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatPcm), longArrayOf(1, CHUNK_SIZE.toLong()))
            
            pcmTensor.use {
                val melInputName = melSession?.inputNames?.iterator()?.next() ?: "input"
                val melOutput = melSession?.run(Collections.singletonMap(melInputName, pcmTensor))
                melOutput?.use {
                    val melValue = it[0].value as Array<Array<Array<FloatArray>>>
                    val frames = melValue[0][0]
                    for (frame in frames) {
                        melBuffer.add(frame)
                        if (melBuffer.size > MEL_WINDOW_SIZE) melBuffer.removeAt(0)
                        if (melBuffer.size == MEL_WINDOW_SIZE) runEmbedding()
                    }
                }
            }
        } catch (e: Exception) {
            if (verboseLogging) Log.e(TAG, "Pipeline error (Mel): ${e.message}")
        }
    }

    private fun runEmbedding() {
        try {
            val flattenedMel = FloatArray(MEL_WINDOW_SIZE * MEL_CHANNELS)
            for (i in 0 until MEL_WINDOW_SIZE) {
                System.arraycopy(melBuffer[i], 0, flattenedMel, i * MEL_CHANNELS, MEL_CHANNELS)
            }
            val melInputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flattenedMel), longArrayOf(1, MEL_WINDOW_SIZE.toLong(), MEL_CHANNELS.toLong(), 1))
            melInputTensor.use {
                val embInputName = embSession?.inputNames?.iterator()?.next() ?: "input"
                val embOutput = embSession?.run(Collections.singletonMap(embInputName, melInputTensor))
                embOutput?.use {
                    val embValue = it[0].value as Array<Array<Array<FloatArray>>>
                    val embedding = embValue[0][0][0]
                    embeddingBuffer.add(embedding)
                    if (embeddingBuffer.size > EMBEDDING_WINDOW_SIZE) embeddingBuffer.removeFirst()
                    if (embeddingBuffer.size == EMBEDDING_WINDOW_SIZE) runClassifier()
                }
            }
        } catch (e: Exception) {
            if (verboseLogging) Log.e(TAG, "Pipeline error (Embedding): ${e.message}")
        }
    }

    fun loadPersonalWeights(personalizer: OnDevicePersonalizer) {
        // The personalizer is already the one passed in constructor
        this.useOnDevicePersonalizer = true
        Log.d(TAG, "✅ On-device personal model weights activated")
    }

    /**
     * Returns the most recently computed 1536-dimensional embedding.
     */
    fun getLastEmbedding(): FloatArray? = lastEmbedding

    /**
     * Clears the cached embedding and all internal pipeline buffers to prevent bias or stuck detections.
     */
    fun clearLastEmbedding() = synchronized(this) {
        lastEmbedding = null
        pcmBuffer.clear()
        melBuffer.clear()
        embeddingBuffer.clear()
        consecutiveDetections = 0
        Log.d(TAG, "🧹 All buffers and cached embedding cleared")
    }

    fun startAmbientCollection() {
        synchronized(this) {
            ambientEmbeddings.clear()
            isCollectingAmbient = true
        }
        Log.d(TAG, "🎙️ Collecting ambient samples...")
    }

    fun stopAmbientCollection(): List<FloatArray> {
        return synchronized(this) {
            isCollectingAmbient = false
            Log.d(TAG, "✅ Collected ${ambientEmbeddings.size} ambient samples")
            ambientEmbeddings.toList()
        }
    }

    private fun runClassifier() {
        if (embeddingBuffer.size < EMBEDDING_WINDOW_SIZE) return
        
        val currentEmb = embeddingBuffer.takeLast(EMBEDDING_WINDOW_SIZE).flatMap { it.toList() }.toFloatArray()
        lastEmbedding = currentEmb

        // Collect ambient embedding if enabled
        synchronized(this) {
            if (isCollectingAmbient && ambientEmbeddings.size < 100) {
                lastEmbedding?.let { ambientEmbeddings.add(it.copyOf()) }
            }
        }

        if (verboseLogging) {
            Log.v(TAG, "runClassifier() triggered [ONNX_Personal=$isPersonalModel, OnDevice_Personal=$useOnDevicePersonalizer]")
        }

        var score = 0f

        try {
            if (useOnDevicePersonalizer) {
                if (verboseLogging) Log.v(TAG, "Executing: On-Device Personalizer (Logistic Regression)")
                score = personalizer?.predict(currentEmb) ?: 0f
                if (verboseLogging && score > 0.001f) {
                    Log.d(TAG, "🎤 On-Device Personal Score: $score")
                }
            } else if (isPersonalModel) {
                if (verboseLogging) Log.v(TAG, "Executing: ONNX Personal Model")
                val lastWindow = embeddingBuffer.takeLast(EMBEDDING_WINDOW_SIZE)
                // Personal model: flatten [16, 96] → [1, 1536]
                val flat = lastWindow.flatMap { it.toList() }.toFloatArray()
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(flat),
                    longArrayOf(1, (EMBEDDING_WINDOW_SIZE * EMBEDDING_DIM).toLong())
                )
                inputTensor.use {
                    val clfInputName = clfSession?.inputNames?.iterator()?.next() ?: "input"
                    clfSession?.run(mapOf(clfInputName to it))?.use { res ->
                        val probValue = res.get("output_probability")
                        if (probValue.isPresent) {
                            val rawValue = probValue.get().value

                            // rawValue is List<OnnxMap> — need to unwrap
                            val onnxMap = when (rawValue) {
                                is List<*> -> rawValue.firstOrNull()
                                else -> rawValue
                            }

                            // OnnxMap has a getValue() method that returns Map<K,V>
                            val innerMap = when (onnxMap) {
                                is ai.onnxruntime.OnnxMap -> onnxMap.value
                                is Map<*, *> -> onnxMap
                                else -> null
                            }

                            score = innerMap?.let { map ->
                                (map[1L]
                                    ?: map[1]
                                    ?: map.entries.find { it.key.toString() == "1" }?.value
                                )?.let {
                                    when (it) {
                                        is Float -> it
                                        is Double -> it.toFloat()
                                        is Number -> it.toFloat()
                                        else -> 0f
                                    }
                                }
                            } ?: 0f

                            if (verboseLogging && score > 0.001f) {
                                Log.d(TAG, "🎤 Personal Score spike: $score (innerMap=$innerMap)")
                            }
                        }
                    }
                }
            } else {
                if (verboseLogging) Log.v(TAG, "Executing: Default Base Model")
                val lastWindow = embeddingBuffer.takeLast(EMBEDDING_WINDOW_SIZE)
                // Default model: [1, 16, 96]
                val data = lastWindow.flatMap { it.toList() }.toFloatArray()
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(data),
                    longArrayOf(1, EMBEDDING_WINDOW_SIZE.toLong(), EMBEDDING_DIM.toLong())
                )
                inputTensor.use {
                    val clfInputName = clfSession?.inputNames?.iterator()?.next()
                    if (clfInputName != null) {
                        clfSession?.run(mapOf(clfInputName to it))?.use { res ->
                            score = (res.get(0).value as? Array<FloatArray>)?.get(0)?.get(0) ?: 0f
                        }
                    }
                }
            }

            if (verboseLogging && score > 0.001f) {
                Log.d(TAG, "🎤 Score spike: $score")
            }

            if (score >= detectionThreshold) {
                consecutiveDetections++
                if (verboseLogging) {
                    Log.v(TAG, "Consecutive hits: $consecutiveDetections/3 (Score: $score)")
                }

                if (consecutiveDetections >= requiredConsecutive) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime > detectionCooldownMs) {
                        lastDetectionTime = now
                        consecutiveDetections = 0
                        Log.d(TAG, "🔥 [Hey Toma] DETECTED! score=$score")
                        triggerHaptic()
                        onWakeWordDetected()
                        embeddingBuffer.clear()
                    } else {
                        consecutiveDetections = 0  // 쿨다운 중엔 카운터만 리셋
                        if (verboseLogging) Log.d(TAG, "Cooldown active, ignoring detection")
                    }
                }
            } else {
                consecutiveDetections = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classifier error: ${e.message}")
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {}
    }

    fun release() {
        melSession?.close()
        embSession?.close()
        clfSession?.close()
        ortEnv.close()
    }
}
