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
import java.nio.FloatBuffer
import java.util.*

/**
 * CHANGED: openWakeWord migration - ONNX based "Hey Toma" detection
 */
class WakeWordManager(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private val TAG = "WakeWord"
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    // openWakeWord config: 16kHz, 16-bit PCM.
    // The model expects a specific chunk size (e.g., 1280 samples for 80ms)
    private val THRESHOLD = 0.5f

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            // Assume 'hey_toma.onnx' is placed in assets
            val modelBytes = context.assets.open("hey_toma.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes)
            Log.d(TAG, "✅ openWakeWord model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model load failed: ${e.message}")
        }
    }

    /**
     * Process a PCM 16-bit byte array frame
     */
    fun processFrame(pcmData: ByteArray) {
        if (ortSession == null) return

        try {
            // Convert ByteArray (PCM 16-bit) to FloatArray (-1.0 to 1.0)
            val shortBuffer = java.nio.ByteBuffer.wrap(pcmData)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            
            val floatArray = FloatArray(shortBuffer.remaining())
            for (i in floatArray.indices) {
                floatArray[i] = shortBuffer.get() / 32768f
            }

            val score = predict(floatArray)
            if (score > THRESHOLD) {
                Log.d(TAG, "🚨 [Hey Toma] Detected! Score: $score")
                triggerHaptic()
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
        }
    }

    private fun predict(data: FloatArray): Float {
        val inputName = ortSession?.inputNames?.iterator()?.next() ?: return 0f
        
        // Shape: [1, samples]
        val shape = longArrayOf(1, data.size.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(data), shape)
        
        ortSession?.use { session ->
            val result = session.run(Collections.singletonMap(inputName, tensor))
            val output = result[0].value
            
            // Handle common ONNX output shapes for openWakeWord
            return when (output) {
                is Array<*> -> (output[0] as FloatArray)[0]
                is FloatArray -> output[0]
                else -> 0f
            }
        } ?: return 0f
    }

    private fun triggerHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(100)
        }
    }

    fun release() {
        ortSession?.close()
        ortEnv.close()
    }
}
