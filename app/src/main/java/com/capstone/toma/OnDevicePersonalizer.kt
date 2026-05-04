package com.capstone.toma

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class OnDevicePersonalizer(private val context: Context) {
    private val TAG = "WakeWord" // Use same tag for unified filtering
    private var weights: FloatArray? = null
    private var bias: Float = 0f
    private var means: FloatArray? = null
    private var stds: FloatArray? = null
    
    val positiveEmbeddings = mutableListOf<FloatArray>()
    val negativeEmbeddings = mutableListOf<FloatArray>()

    private val EMBEDDING_SIZE = 1536
    private val NEGATIVE_SAMPLES_FILE = "negative_samples.bin"

    init {
        loadNegativeSamplesFromAssets()
    }

    fun loadNegativeSamplesFromAssets(): Boolean {
        return try {
            val bytes = context.assets.open(NEGATIVE_SAMPLES_FILE).use { it.readBytes() }
            
            val expectedBytes = 300 * EMBEDDING_SIZE * 4
            Log.d(TAG, "negative_samples.bin size: ${bytes.size} bytes (expected: $expectedBytes)")
            
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
            Log.d(TAG, "✅ Negative samples loaded: ${negativeEmbeddings.size} samples (300 x 1536)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load negative samples: ${e.javaClass.simpleName}: ${e.message}")
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

            if (positiveEmbeddings.isEmpty()) {
                Log.e(TAG, "❌ No positive embeddings")
                return false
            }
            if (negativeEmbeddings.isEmpty()) {
                Log.e(TAG, "❌ No negative embeddings")
                return false
            }

            val inputDim = positiveEmbeddings[0].size
            Log.d(TAG, "Input dim: $inputDim")

            Log.d(TAG, "Checking for NaN/Inf/Mismatch...")
            positiveEmbeddings.forEachIndexed { i, arr ->
                if (arr.size != EMBEDDING_SIZE) {
                    Log.e(TAG, "❌ Pos[$i] dim mismatch: ${arr.size}")
                    return false
                }
                if (arr.any { it.isNaN() || it.isInfinite() }) {
                    Log.e(TAG, "❌ Pos[$i] contains NaN/Inf")
                    return false
                }
            }
            negativeEmbeddings.forEachIndexed { i, arr ->
                if (arr.size != EMBEDDING_SIZE) {
                    Log.e(TAG, "❌ Neg[$i] dim mismatch: ${arr.size}")
                    return false
                }
                if (arr.any { it.isNaN() || it.isInfinite() }) {
                    Log.e(TAG, "❌ Neg[$i] contains NaN/Inf")
                    return false
                }
            }

            // StandardScaler
            Log.d(TAG, "StandardScaler - Computing mean/std...")
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
                // Increased epsilon to 1e-2 to prevent weight explosion on low-variance features
                stdsArr[j] = kotlin.math.sqrt(sumSq / allSamples.size).toFloat().coerceAtLeast(1e-2f)
            }
            this.means = meansArr
            this.stds = stdsArr
            Log.d(TAG, "StandardScaler initialization done. Avg Std: ${stdsArr.average()}, Min Std: ${stdsArr.minOrNull()}")

            Log.d(TAG, "Initializing weights...")
            weights = FloatArray(EMBEDDING_SIZE) { 0f }
            bias = 0f

            val learningRate = 0.001f
            val iterations = 500
            val lambda = 0.1f // L2 regularization strength

            Log.d(TAG, "Starting training loop with L2 regularization...")
            for (iter in 0 until iterations) {
                var totalLoss = 0f

                // Positive updates
                for (pos in positiveEmbeddings) {
                    val pred = predict(pos)
                    val error = 1.0f - pred
                    
                    // Scale input for weight update
                    val scaledPos = FloatArray(EMBEDDING_SIZE) { (pos[it] - meansArr[it]) / stdsArr[it] }
                    updateWeightsWithL2(scaledPos, error, learningRate, lambda)
                    
                    totalLoss += -Math.log(pred.toDouble().coerceIn(1e-7, 1.0)).toFloat()
                }

                // Negative updates
                for (neg in negativeEmbeddings) {
                    val pred = predict(neg)
                    val error = 0.0f - pred
                    
                    // Scale input for weight update
                    val scaledNeg = FloatArray(EMBEDDING_SIZE) { (neg[it] - meansArr[it]) / stdsArr[it] }
                    updateWeightsWithL2(scaledNeg, error, learningRate, lambda)
                    
                    totalLoss += -Math.log((1.0 - pred).coerceIn(1e-7, 1.0)).toFloat()
                }

                if (iter % 100 == 0) {
                    val avgLoss = totalLoss / (positiveEmbeddings.size + negativeEmbeddings.size)
                    Log.v(TAG, "Iter $iter, Loss: $avgLoss")
                    if (avgLoss.isNaN()) {
                        Log.e(TAG, "❌ Loss is NaN at iter $iter")
                        return false
                    }
                }
            }

            Log.d(TAG, "✅ Training complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ train() exception: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun updateWeightsWithL2(xScaled: FloatArray, error: Float, lr: Float, lambda: Float) {
        val w = weights ?: return
        for (i in xScaled.indices) {
            val xi = xScaled[i]
            if (xi.isNaN() || xi.isInfinite()) continue

            // Gradient with L2 regularization
            w[i] = w[i] * (1 - lr * lambda) + lr * error * xi
            
            // Clip weights to prevent overflow
            if (w[i] > 10f) w[i] = 10f
            if (w[i] < -10f) w[i] = -10f
        }
        bias += lr * error
    }

    private fun updateWeights(xScaled: FloatArray, error: Float, lr: Float) {
        val w = weights ?: return
        for (i in xScaled.indices) {
            w[i] += lr * error * xScaled[i]
        }
        bias += lr * error
    }

    fun predict(embedding: FloatArray): Float {
        val w = weights ?: return 0f
        val m = means ?: return 0f
        val s = stds ?: return 0f

        // Check for invalid scaler values
        val hasZeroStd = s.any { it == 0f || it.isNaN() }
        val hasNanMean = m.any { it.isNaN() }
        if (hasZeroStd || hasNanMean) {
            Log.e(TAG, "❌ [OnDevicePersonalizer] Invalid scaler: zeroStd=$hasZeroStd, nanMean=$hasNanMean")
            return 0f
        }

        // Scale input and compute dot product
        var dot = bias
        for (i in w.indices) {
            val xi = embedding[i]
            // Safeguard against NaN/Inf in the pipeline embedding
            if (xi.isNaN() || xi.isInfinite()) continue 
            
            val xScaled = (xi - m[i]) / s[i]
            dot += w[i] * xScaled
        }

        val score = (1f / (1f + exp(-dot.toDouble()))).toFloat()
        
        // Detailed metrics for debugging saturation
        if (score > 0.8f) {
            Log.d(TAG, "🎯 [OnDevicePersonalizer] High Score: $score (dot: $dot, bias: $bias, w[0]: ${w[0]})")
            Log.v(TAG, "🎯 [OnDevicePersonalizer] Input Sample (first 5): ${embedding.take(5).joinToString()}")
        }
        return score
    }

    fun saveToFile(file: File) {
        try {
            val w = weights ?: return
            val m = means ?: return
            val s = stds ?: return
            
            // Save bias (1) + weights (1536) + means (1536) + stds (1536)
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
        try {
            val bytes = file.readBytes()
            val expectedSize = (1 + EMBEDDING_SIZE * 3) * 4
            if (bytes.size < expectedSize) {
                Log.e(TAG, "❌ Load failed: file size (${bytes.size}) < expected ($expectedSize)")
                return false
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bias = buffer.float
            
            val w = FloatArray(EMBEDDING_SIZE) { buffer.float }
            val m = FloatArray(EMBEDDING_SIZE) { buffer.float }
            val s = FloatArray(EMBEDDING_SIZE) { buffer.float }
            
            weights = w
            means = m
            stds = s
            
            Log.d(TAG, "✅ Weights and scaler loaded from ${file.absolutePath}")
            Log.d(TAG, "Loaded params: bias=$bias, w[0]=${w.getOrNull(0)}, m[0]=${m.getOrNull(0)}, s[0]=${s.getOrNull(0)}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Load failed: ${e.message}")
            return false
        }
    }
    
    fun clearPositiveSamples() {
        positiveEmbeddings.clear()
    }
}
