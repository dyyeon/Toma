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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*

class WakeWordManager(
    private val context: Context,
    private val personalizer: OnDevicePersonalizer,
    private val onWakeWordDetected: () -> Unit
) {
    private val TAG = "WakeWord"

    var detectionThreshold: Float = 0.35f
    var verboseLogging: Boolean = true
    private var consecutiveDetections = 0
    private val REQUIRED_CONSECUTIVE = 4
    private val RESET_FLOOR = 0.3f
    private val SILENCE_RESET_FRAMES = 25

    private val VAD_RMS_THRESHOLD = 120f
    private var consecutiveSilentFrames = 0

    @Volatile
    var isArmed: Boolean = true
        private set

    private val SAMPLE_RATE = 16000
    private val CHUNK_SIZE = 1280
    private val MEL_WINDOW_SIZE = 76
    private val EMBEDDING_WINDOW_SIZE = 16
    private val MEL_CHANNELS = 32
    private val EMBEDDING_DIM = 96

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var clfSession: OrtSession? = null
    private var isPersonalModel = false

    var useOnDevicePersonalizer = false
        private set
    private var lastDetectionTime = 0L
    private val detectionCooldownMs = 3000L

    var bypassVad: Boolean = false

    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val chunkChannel = Channel<ShortArray>(32)

    private val pcmBuffer = ArrayDeque<Short>()
    private val melBuffer = mutableListOf<FloatArray>()
    private val embeddingBuffer = LinkedList<FloatArray>()

    private var lastEmbedding: FloatArray? = null

    private val ambientEmbeddings = mutableListOf<FloatArray>()
    private var isCollectingAmbient = false

    init {
        pipelineScope.launch(Dispatchers.IO) { loadModels() }
        startPipelineConsumer()
    }

    private fun startPipelineConsumer() {
        pipelineScope.launch {
            for (chunk in chunkChannel) {
                runPipeline(chunk)
            }
        }
    }

    private fun loadModels() {
        try {
            melSession = ortEnv.createSession(context.assets.open("melspectrogram.onnx").readBytes())
            embSession = ortEnv.createSession(context.assets.open("embedding_model.onnx").readBytes())
            Log.d(TAG, "✅ Base models (Mel, Embedding) loaded from assets")

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

    private fun computeRms(shorts: ShortArray): Float {
        if (shorts.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in shorts) sumSq += s.toDouble() * s
        return kotlin.math.sqrt(sumSq / shorts.size).toFloat()
    }

    fun arm() {
        isArmed = true
        if (verboseLogging) Log.d(TAG, "🔓 Wake-word ARMED")
    }

    fun disarm() {
        isArmed = false
        consecutiveDetections = 0
        if (verboseLogging) Log.d(TAG, "🔒 Wake-word DISARMED — counter flushed")
    }

    fun processFrame(pcmData: ByteArray) {
        if (!isArmed) {
            if (verboseLogging) Log.d(TAG, "processFrame skipped: isArmed=false")
            return
        }
        if (melSession == null || embSession == null) return
        if (!useOnDevicePersonalizer && clfSession == null) return

        val shortBuf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(shortBuf.remaining()) { shortBuf.get() }

        val rms = computeRms(shorts)
        if (verboseLogging) {
            Log.d(TAG, "RMS=${"%.1f".format(rms)}")
        }

        if (!bypassVad && rms < VAD_RMS_THRESHOLD) {
            if (verboseLogging) {
                Log.v(TAG, "VAD skip: rms=${"%.1f".format(rms)} < threshold=$VAD_RMS_THRESHOLD")
            }
            consecutiveSilentFrames++
            if (consecutiveSilentFrames >= SILENCE_RESET_FRAMES) {
                consecutiveDetections = 0
                consecutiveSilentFrames = 0
                if (verboseLogging) {
                    Log.v(TAG, "🔇 Long silence (${SILENCE_RESET_FRAMES * 80}ms) — detection state reset")
                }
            }
            return
        }
        consecutiveSilentFrames = 0

        for (s in shorts) pcmBuffer.addLast(s)

        while (pcmBuffer.size >= CHUNK_SIZE) {
            val chunk = ShortArray(CHUNK_SIZE) { pcmBuffer.removeFirst() }
            val result = chunkChannel.trySend(chunk)
            if (result.isFailure && verboseLogging) {
                Log.w(TAG, "⚠️ chunk dropped: pipeline overloaded")
            }
        }
    }

    private fun runPipeline(chunk: ShortArray) {
        try {
            val floatPcm = FloatArray(CHUNK_SIZE) { chunk[it].toFloat() / 32768.0f }
            val pcmTensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(floatPcm),
                longArrayOf(1, CHUNK_SIZE.toLong())
            )

            pcmTensor.use {
                val melInputName = melSession?.inputNames?.iterator()?.next() ?: "input"
                val melOutput = melSession?.run(Collections.singletonMap(melInputName, pcmTensor))
                melOutput?.use {
                    @Suppress("UNCHECKED_CAST")
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
            val melInputTensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(flattenedMel),
                longArrayOf(1, MEL_WINDOW_SIZE.toLong(), MEL_CHANNELS.toLong(), 1)
            )
            melInputTensor.use {
                val embInputName = embSession?.inputNames?.iterator()?.next() ?: "input"
                val embOutput = embSession?.run(Collections.singletonMap(embInputName, melInputTensor))
                embOutput?.use {
                    @Suppress("UNCHECKED_CAST")
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
        this.useOnDevicePersonalizer = true
        Log.d(TAG, "✅ On-device personal model weights activated")
    }

    fun getLastEmbedding(): FloatArray? = lastEmbedding

    fun clearLastEmbedding() {
        lastEmbedding = null
        pcmBuffer.clear()
        melBuffer.clear()
        embeddingBuffer.clear()
        consecutiveDetections = 0
        consecutiveSilentFrames = 0
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

        val currentEmb = embeddingBuffer.takeLast(EMBEDDING_WINDOW_SIZE)
            .flatMap { it.toList() }
            .toFloatArray()
        lastEmbedding = currentEmb

        synchronized(this) {
            if (isCollectingAmbient && ambientEmbeddings.size < 100) {
                lastEmbedding?.let { ambientEmbeddings.add(it.copyOf()) }
            }
        }

        var score = 0f
        try {
            if (useOnDevicePersonalizer) {
                score = personalizer.predict(currentEmb)
            } else if (isPersonalModel) {
                val flat = currentEmb
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
                            val onnxMap = when (rawValue) {
                                is List<*> -> rawValue.firstOrNull()
                                else -> rawValue
                            }
                            val innerMap = when (onnxMap) {
                                is ai.onnxruntime.OnnxMap -> onnxMap.value
                                is Map<*, *> -> onnxMap
                                else -> null
                            }
                            score = innerMap?.let { map ->
                                (map[1L] ?: map[1] ?: map.entries.find { it.key.toString() == "1" }?.value)
                                    ?.let { v ->
                                        when (v) {
                                            is Float -> v
                                            is Double -> v.toFloat()
                                            is Number -> v.toFloat()
                                            else -> 0f
                                        }
                                    }
                            } ?: 0f
                        }
                    }
                }
            } else {
                val data = currentEmb.copyOf()
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(data),
                    longArrayOf(1, EMBEDDING_WINDOW_SIZE.toLong(), EMBEDDING_DIM.toLong())
                )
                inputTensor.use {
                    val clfInputName = clfSession?.inputNames?.iterator()?.next()
                    if (clfInputName != null) {
                        clfSession?.run(mapOf(clfInputName to it))?.use { res ->
                            @Suppress("UNCHECKED_CAST")
                            score = (res.get(0).value as? Array<FloatArray>)?.get(0)?.get(0) ?: 0f
                        }
                    }
                }
            }

            if (verboseLogging) {
                Log.d(
                    TAG,
                    "🎤 Score: ${"%.3f".format(score)}, Consecutive: $consecutiveDetections/$REQUIRED_CONSECUTIVE"
                )
            }

            when {
                score >= detectionThreshold -> {
                    consecutiveDetections++
                    if (verboseLogging) {
                        Log.d(
                            TAG,
                            "threshold passed: score=${"%.3f".format(score)}, consecutive=$consecutiveDetections"
                        )
                    }
                    if (consecutiveDetections >= REQUIRED_CONSECUTIVE) {
                        val now = System.currentTimeMillis()
                        if (now - lastDetectionTime > detectionCooldownMs) {
                            lastDetectionTime = now
                            consecutiveDetections = 0
                            Log.d(TAG, "🔥 [Hey Toma] DETECTED! score=${"%.3f".format(score)}")
                            triggerHaptic()
                            onWakeWordDetected()
                            embeddingBuffer.clear()
                        } else {
                            consecutiveDetections = 0
                            if (verboseLogging) Log.d(TAG, "⏱️ Cooldown active — ignoring detection")
                        }
                    }
                }
                score < RESET_FLOOR -> {
                    consecutiveDetections = 0
                }
                else -> {
                    if (consecutiveDetections > 0) consecutiveDetections--
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classifier error: ${e.message}")
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
        }
    }

    fun release() {
        chunkChannel.close()
        pipelineScope.cancel()
        melSession?.close()
        embSession?.close()
        clfSession?.close()
    }
}