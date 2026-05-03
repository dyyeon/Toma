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
    private val onWakeWordDetected: () -> Unit
) {
    private val TAG = "WakeWord"
    
    // Configuration
    var detectionThreshold: Float = 0.5f
    var verboseLogging: Boolean = true
    
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

    // State Buffers
    private val pcmBuffer = mutableListOf<Short>()
    private val melBuffer = mutableListOf<FloatArray>()
    private val embeddingBuffer = LinkedList<FloatArray>()

    init {
        loadModels()
    }

    private fun loadModels() {
        try {
            val modelFiles = listOf("melspectrogram.onnx", "embedding_model.onnx", "hey_toma.onnx")
            val sessions = mutableListOf<OrtSession>()

            for (fileName in modelFiles) {
                val file = java.io.File(context.filesDir, fileName)
                // Always copy from assets to internal storage to ensure accessibility and handle potential .data files
                context.assets.open(fileName).use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Also check for .data file (some ONNX models have external data)
                val dataFileName = "$fileName.data"
                try {
                    context.assets.open(dataFileName).use { input ->
                        java.io.File(context.filesDir, dataFileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copied external data for $fileName")
                } catch (e: Exception) {
                    // Not all models have .data files, so it's okay if this fails
                }

                sessions.add(ortEnv.createSession(file.absolutePath))
            }

            melSession = sessions[0]
            embSession = sessions[1]
            clfSession = sessions[2]
            
            Log.d(TAG, "✅ 3-Stage ONNX Models loaded successfully from internal storage")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model load failed: ${e.message}")
        }
    }

    fun processFrame(pcmData: ByteArray) {
        if (melSession == null || embSession == null || clfSession == null) return

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
                val melOutput = melSession?.run(Collections.singletonMap("input", pcmTensor))
                melOutput?.use {
                    val melValue = it[0].value as Array<Array<Array<FloatArray>>>
                    val frames = melValue[0][0] // Shape: [1, 1, T, 32] -> frames is [T, 32]
                    
                    if (verboseLogging) Log.d(TAG, "[Shape Check] Mel Input: [1, 1280], Output T: ${frames.size}")

                    // Process all mel frames from this chunk and update buffer
                    for (frame in frames) {
                        melBuffer.add(frame)
                        if (melBuffer.size > MEL_WINDOW_SIZE) {
                            melBuffer.removeAt(0)
                        }
                    }

                    // Stride 8: Call embedding/classifier once per 80ms chunk (approx. 12.5Hz)
                    if (melBuffer.size == MEL_WINDOW_SIZE) {
                        runEmbedding()
                    }
                }
            }
        } catch (e: Exception) {
            if (verboseLogging) Log.e(TAG, "Pipeline error: ${e.message}")
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
                val embOutput = embSession?.run(Collections.singletonMap("input", melInputTensor))
                embOutput?.use {
                    val embValue = it[0].value as Array<Array<Array<FloatArray>>>
                    val embedding = embValue[0][0][0] // [96]
                    
                    embeddingBuffer.add(embedding)
                    if (embeddingBuffer.size > EMBEDDING_WINDOW_SIZE) {
                        embeddingBuffer.removeFirst()
                    }

                    if (embeddingBuffer.size == EMBEDDING_WINDOW_SIZE) {
                        runClassifier()
                    }
                }
            }
        } catch (e: Exception) {
            if (verboseLogging) Log.e(TAG, "Embedding error: ${e.message}")
        }
    }

    private fun runClassifier() {
        try {
            val flattenedEmb = FloatArray(EMBEDDING_WINDOW_SIZE * EMBEDDING_DIM)
            for (i in 0 until EMBEDDING_WINDOW_SIZE) {
                System.arraycopy(embeddingBuffer[i], 0, flattenedEmb, i * EMBEDDING_DIM, EMBEDDING_DIM)
            }
            
            val clfInputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flattenedEmb), longArrayOf(1, EMBEDDING_WINDOW_SIZE.toLong(), EMBEDDING_DIM.toLong()))
            
            clfInputTensor.use {
                val clfOutput = clfSession?.run(Collections.singletonMap("input", clfInputTensor))
                clfOutput?.use {
                    val scoreData = it[0].value as Array<FloatArray>
                    val score = scoreData[0][0]
                    
                    if (verboseLogging) Log.v(TAG, "Current WakeWord Score: $score")
                    
                    if (score >= detectionThreshold) {
                        Log.d(TAG, "🔥 [Hey Toma] DETECTED! Score: $score")
                        triggerHaptic()
                        onWakeWordDetected()
                        embeddingBuffer.clear()
                    }
                }
            }
        } catch (e: Exception) {
            if (verboseLogging) Log.e(TAG, "Classifier error: ${e.message}")
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
