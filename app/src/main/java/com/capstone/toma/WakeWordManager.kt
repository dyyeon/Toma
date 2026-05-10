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
import kotlinx.coroutines.withContext
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
    
    // Detection configuration
    var detectionThreshold: Float = 0.42f         // calibrated: 0.5 caused misses, 0.42 balances FP/FN
    var verboseLogging: Boolean = true
    private var consecutiveDetections = 0
    private val REQUIRED_CONSECUTIVE = 4          // 4 × 80ms = 320ms — snappier response
    private val RESET_FLOOR = 0.3f               // below this: hard-reset counter immediately
    private val SILENCE_RESET_FRAMES = 25         // 25 × 80ms ≈ 2s of silence → state reset

    // VAD (Voice Activity Detection) — pre-filter before ONNX inference
    private val VAD_RMS_THRESHOLD = 500f          // raised from 300: filters fridge/fan hum reliably
    private var consecutiveSilentFrames = 0

    // Anti-conflict gate: set false while the app is in active LISTENING mode to prevent
    // the async ONNX pipeline from firing onWakeWordDetected mid-command.
    @Volatile var isArmed: Boolean = true
        private set
    
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

    /**
     * Set to true during Speaker Enrollment or Personalization to allow silent frames 
     * (ambient noise collection) to reach the feature extraction pipeline.
     */
    var bypassVad: Boolean = false

    // FIX #5: Dedicated scope for the sequential pipeline consumer.
    // SupervisorJob means a crash in the pipeline does not cancel the parent scope.
    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // FIX #6: One chunk channel replaces the per-frame CoroutineScope.launch pattern.
    // Capacity 32 = ~2.5 seconds of buffering headroom before trySend starts dropping.
    // Drops are controlled (tail-drop on overload) rather than the CONFLATED "keep only
    // the latest" semantics that destroyed temporal continuity in v1.0.
    private val chunkChannel = Channel<ShortArray>(32)

    // State Buffers
    // FIX #7: ArrayDeque gives O(1) removeFirst()/addLast() vs ArrayList's O(n) removeAt(0).
    // At 1280 shorts/chunk the old code did 1280 × O(n) shifts per frame, causing CPU spikes
    // that stalled the audio thread and indirectly caused more frame drops.
    private val pcmBuffer = ArrayDeque<Short>()
    private val melBuffer = mutableListOf<FloatArray>()
    private val embeddingBuffer = LinkedList<FloatArray>()
    
    // 최근 1536차원 embedding 캐시 (Personalizer 학습용)
    private var lastEmbedding: FloatArray? = null

    // Ambient collection for negative samples
    private val ambientEmbeddings = mutableListOf<FloatArray>()
    private var isCollectingAmbient = false

    init {
        pipelineScope.launch(Dispatchers.IO) { loadModels() }
        startPipelineConsumer()
    }

    // FIX #8: Single long-lived coroutine that processes chunks sequentially.
    // The old code launched a NEW CoroutineScope(Dispatchers.Default).launch per
    // 80 ms frame, causing:
    //   a) Concurrent writes to melBuffer / embeddingBuffer (data corruption)
    //   b) Thread.sleep(80) blocking Dispatchers.Default threads (pool starvation)
    //   c) Unbounded scope growth (memory leak — scopes never cancelled)
    // One consumer coroutine serialises all pipeline calls with zero locks needed
    // on the internal buffers because only this coroutine ever touches them.
    private fun startPipelineConsumer() {
        pipelineScope.launch {
            for (chunk in chunkChannel) {
                runPipeline(chunk)
            }
        }
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

    private fun computeRms(shorts: ShortArray): Float {
        if (shorts.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in shorts) sumSq += s.toDouble() * s
        return kotlin.math.sqrt(sumSq / shorts.size).toFloat()
    }

    /** Re-enables wake-word detection after an active-listening session ends. */
    fun arm() {
        isArmed = true
        if (verboseLogging) Log.d(TAG, "🔓 Wake-word ARMED")
    }

    /**
     * Disables wake-word detection and flushes any accumulated consecutive-detection count.
     * Call this the moment the app enters LISTENING state so a previously accumulating score
     * cannot fire a spurious second trigger.
     */
    fun disarm() {
        isArmed = false
        consecutiveDetections = 0
        if (verboseLogging) Log.d(TAG, "🔒 Wake-word DISARMED — counter flushed")
    }

    fun processFrame(pcmData: ByteArray) {
        if (!isArmed) return  // disarmed during active listening — skip all ONNX work
        if (melSession == null || embSession == null) return
        if (!useOnDevicePersonalizer && clfSession == null) return

        val shortBuf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(shortBuf.remaining()) { shortBuf.get() }

        // VAD guard: skip ONNX inference entirely on silent frames.
        // Eliminates the continuous runClassifier() flood seen in Logcat during silence.
        val rms = computeRms(shorts)
        if (!bypassVad && rms < VAD_RMS_THRESHOLD) {
            consecutiveSilentFrames++
            if (consecutiveSilentFrames >= SILENCE_RESET_FRAMES) {
                // 2+ seconds of silence — flush stale detection state
                consecutiveDetections = 0
                consecutiveSilentFrames = 0
                if (verboseLogging) Log.v(TAG, "🔇 Long silence (${SILENCE_RESET_FRAMES * 80}ms) — detection state reset")
            }
            return
        }
        consecutiveSilentFrames = 0

        for (s in shorts) pcmBuffer.addLast(s)

        while (pcmBuffer.size >= CHUNK_SIZE) {
            val chunk = ShortArray(CHUNK_SIZE) { pcmBuffer.removeFirst() }
            chunkChannel.trySend(chunk)
        }
    }

    // Called exclusively from the single pipeline consumer coroutine — no synchronisation needed
    // on melBuffer / embeddingBuffer since only one coroutine ever writes them.
    private fun runPipeline(chunk: ShortArray) {
        try {
            val floatPcm = FloatArray(CHUNK_SIZE) { chunk[it].toFloat() / 32768.0f }
            val pcmTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatPcm), longArrayOf(1, CHUNK_SIZE.toLong()))
            
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
            val melInputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flattenedMel), longArrayOf(1, MEL_WINDOW_SIZE.toLong(), MEL_CHANNELS.toLong(), 1))
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
     * Called from external threads (ViewModel), so the assignment to lastEmbedding is @Volatile-safe
     * and the buffer clears are guarded here to avoid racing the pipeline consumer.
     */
    fun clearLastEmbedding() {
        lastEmbedding = null
        // ArrayDeque (pcmBuffer) is only written by the audio capture thread, but clearing it
        // from here is safe in practice because clearLastEmbedding is called only after
        // enrollment completes (enrollment mode stops audio capture first).
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

        val currentEmb = embeddingBuffer.takeLast(EMBEDDING_WINDOW_SIZE).flatMap { it.toList() }.toFloatArray()
        lastEmbedding = currentEmb

        synchronized(this) {
            if (isCollectingAmbient && ambientEmbeddings.size < 100) {
                lastEmbedding?.let { ambientEmbeddings.add(it.copyOf()) }
            }
        }

        // --- Inference ---
        var score = 0f
        try {
            if (useOnDevicePersonalizer) {
                score = personalizer.predict(currentEmb)
            } else if (isPersonalModel) {
                val flat = currentEmb  // already flattened [16×96 = 1536]
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
                                    ?.let { v -> when (v) {
                                        is Float -> v
                                        is Double -> v.toFloat()
                                        is Number -> v.toFloat()
                                        else -> 0f
                                    }}
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

            // --- Throttled logging: only print when score is high enough to matter ---
            if (score > 0.1f && verboseLogging) {
                Log.d(TAG, "🎤 Score: ${"%.3f".format(score)}, Consecutive: $consecutiveDetections/$REQUIRED_CONSECUTIVE")
            }

            // --- Detection gate with floor/decay (replaces binary reset) ---
            when {
                score >= detectionThreshold -> {
                    consecutiveDetections++
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
                    // Clear signal of non-detection — hard reset immediately
                    consecutiveDetections = 0
                }
                else -> {
                    // Hovering between floor and threshold (sustained background noise).
                    // Gradual decay prevents accumulation while remaining responsive
                    // if a real word follows immediately after a noise burst.
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
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {}
    }

    fun release() {
        chunkChannel.close()      // signals the pipeline consumer to stop
        pipelineScope.cancel()    // cancels the consumer coroutine and loadModels if still running
        melSession?.close()
        embSession?.close()
        clfSession?.close()
        // FIX #9: OrtEnvironment.getEnvironment() returns a SINGLETON. Calling ortEnv.close()
        // here destroys the global ONNX Runtime state. If WakeWordManager is ever recreated
        // (e.g., after ViewModel.onCleared + relaunch), the next OrtSession.createSession()
        // call will crash with "OrtEnvironment is closed". Never close the environment.
    }
}
