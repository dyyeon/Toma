package com.capstone.toma

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class OnDevicePersonalizer(private val context: Context) {
    private val TAG = "WakeWord" 
    private var weights: FloatArray? = null
    private var bias: Float = 0f
    private var means: FloatArray? = null
    private var stds: FloatArray? = null
    
    val positiveEmbeddings = mutableListOf<FloatArray>()
    val negativeEmbeddings = mutableListOf<FloatArray>()

    private val EMBEDDING_SIZE = 1536

    // init {
    //     // REMOVED: Loading negative_samples.bin from Python pipeline causes feature space mismatch.
    //     // We now collect negative samples directly on device.
    // }

    fun setNegativeSamples(samples: List<FloatArray>) {
        negativeEmbeddings.clear()
        negativeEmbeddings.addAll(samples)
        Log.d("OnDevice", "✅ Set ${negativeEmbeddings.size} Android-native negative samples")
    }

    /**
     * Legacy method. No longer called by default to avoid pipeline mismatch.
     */
    fun loadNegativeSamplesFromAssets(): Boolean {
        return try {
            val bytes = context.assets.open("negative_samples.bin").use { it.readBytes() }
            val floatBuffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            
            negativeEmbeddings.clear()
            for (i in 0 until 300) {
                val embedding = FloatArray(EMBEDDING_SIZE)
                for (j in 0 until EMBEDDING_SIZE) {
                    embedding[j] = floatBuffer.get()
                }
                negativeEmbeddings.add(embedding)
            }
            Log.d(TAG, "✅ Legacy negative samples loaded: ${negativeEmbeddings.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load legacy negative samples: ${e.message}")
            false
        }
    }

    fun addPositiveSample(embedding: FloatArray) {
        if (embedding.size == EMBEDDING_SIZE) {
            positiveEmbeddings.add(embedding)
            Log.d(TAG, "➕ Added positive sample. Total: ${positiveEmbeddings.size}")
        } else {
            Log.e(TAG, "❌ Dimension mismatch: Received ${embedding.size}, expected $EMBEDDING_SIZE")
        }
    }

    fun train(): Boolean {
        return try {
            Log.d(TAG, "train() called - positives: ${positiveEmbeddings.size}, negatives: ${negativeEmbeddings.size}")

            if (positiveEmbeddings.isEmpty() || negativeEmbeddings.isEmpty()) {
                Log.e(TAG, "❌ Insufficient data for training")
                return false
            }

            // StandardScaler
            val allSamples = positiveEmbeddings + negativeEmbeddings
            val meansArr = FloatArray(EMBEDDING_SIZE)
            val stdsArr = FloatArray(EMBEDDING_SIZE)
            
            for (j in 0 until EMBEDDING_SIZE) {
                var sum = 0f
                for (sample in allSamples) sum += sample[j]
                meansArr[j] = sum / allSamples.size
                
                var sumSq = 0f
                for (sample in allSamples) {
                    val diff = sample[j] - meansArr[j]
                    sumSq += diff * diff
                }
                stdsArr[j] = kotlin.math.sqrt(sumSq / allSamples.size).toFloat().coerceAtLeast(1e-2f)
            }
            this.means = meansArr
            this.stds = stdsArr

            weights = FloatArray(EMBEDDING_SIZE) { 0f }
            bias = 0f

            val learningRate = 0.005f
            val iterations = 1000
            val lambda = 0.05f 

            for (iter in 0 until iterations) {
                for (pos in positiveEmbeddings) {
                    val pred = predict(pos)
                    val error = 1.0f - pred
                    val scaledPos = FloatArray(EMBEDDING_SIZE) { (pos[it] - meansArr[it]) / stdsArr[it] }
                    updateWeightsWithL2(scaledPos, error, learningRate, lambda)
                }

                for (neg in negativeEmbeddings) {
                    val pred = predict(neg)
                    val error = 0.0f - pred
                    val scaledNeg = FloatArray(EMBEDDING_SIZE) { (neg[it] - meansArr[it]) / stdsArr[it] }
                    updateWeightsWithL2(scaledNeg, error, learningRate, lambda)
                }
            }

            Log.d(TAG, "✅ On-device training complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ train() failed: ${e.message}")
            false
        }
    }

    private fun updateWeightsWithL2(xScaled: FloatArray, error: Float, lr: Float, lambda: Float) {
        val w = weights ?: return
        for (i in xScaled.indices) {
            w[i] = w[i] * (1 - lr * lambda) + lr * error * xScaled[i]
            if (w[i] > 10f) w[i] = 10f
            if (w[i] < -10f) w[i] = -10f
        }
        bias += lr * error
    }

    fun predict(embedding: FloatArray): Float {
        val w = weights ?: return 0f
        val m = means ?: return 0f
        val s = stds ?: return 0f

        // Single pass: compute dot product, weight L2-norm, and scaled-input norm together.
        var dot = bias.toDouble()
        var wNormSq = 0.0
        var scaledNormSq = 0.0
        for (i in w.indices) {
            val xScaled = (embedding[i] - m[i]) / s[i]
            dot += w[i] * xScaled
            wNormSq += w[i].toDouble() * w[i]
            scaledNormSq += xScaled.toDouble() * xScaled
        }

        // ── Saturation fix ──────────────────────────────────────────────────
        // With EMBEDDING_SIZE=1536 and weights clamped to ±10, the raw logit
        // can reach ±15 360.  sigmoid(x) collapses to exactly 0.0 or 1.0 in
        // float32 for |x| > ~88, so threshold tuning becomes ineffective.
        //
        // Dividing by ‖w‖ normalises the logit to a scale independent of
        // weight magnitude and dimension count.  For a random (out-of-
        // distribution) input, the normalised logit is O(1), giving meaningful
        // sigmoid values; for a strongly aligned input (the actual wake word)
        // it is still large, preserving detection.
        //
        // Tune NOTE: after this change the effective score range is compressed.
        // If real "Hey Toma" utterances start to miss, lower detectionThreshold
        // (e.g. 0.60).  If false positives remain, raise it (e.g. 0.85).
        // ────────────────────────────────────────────────────────────────────
        val wNorm = kotlin.math.sqrt(wNormSq).toFloat().coerceAtLeast(1e-6f)
        val normalizedLogit = (dot / wNorm).toFloat()
        val score = (1f / (1f + exp(-normalizedLogit.toDouble()))).toFloat()

        Log.d(TAG,
            "predict | rawLogit=${"%.1f".format(dot)} " +
            "wNorm=${"%.2f".format(wNorm)} " +
            "normLogit=${"%.4f".format(normalizedLogit)} " +
            "scaledInputNorm=${"%.2f".format(kotlin.math.sqrt(scaledNormSq))} " +
            "score=${"%.4f".format(score)}"
        )

        return score
    }

    fun saveToFile(file: File) {
        try {
            val w = weights ?: return
            val m = means ?: return
            val s = stds ?: return
            val buffer = ByteBuffer.allocate((1 + EMBEDDING_SIZE * 3) * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(bias)
            for (v in w) buffer.putFloat(v)
            for (v in m) buffer.putFloat(v)
            for (v in s) buffer.putFloat(v)
            file.writeBytes(buffer.array())
            Log.d(TAG, "✅ Weights and scaler saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Save failed: ${e.message}")
        }
    }

    fun loadFromFile(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val bytes = file.readBytes()
            val expectedSize = (1 + EMBEDDING_SIZE * 3) * 4
            if (bytes.size < expectedSize) return false
            
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bias = buffer.float
            weights = FloatArray(EMBEDDING_SIZE) { buffer.float }
            means = FloatArray(EMBEDDING_SIZE) { buffer.float }
            stds = FloatArray(EMBEDDING_SIZE) { buffer.float }
            
            Log.d(TAG, "✅ Weights and scaler loaded from ${file.absolutePath}")
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun clearPositiveSamples() {
        positiveEmbeddings.clear()
    }
}
