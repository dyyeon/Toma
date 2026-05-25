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

    // ─── Sensitivity knobs ────────────────────────────────────────────────────
    // detectionThreshold: minimum classifier score to count as a candidate hit.
    //   • Higher → fewer false positives, but you must say the word more clearly.
    //   • Lower  → more sensitive, but unrelated speech may trigger it.
    //   Tuning guide:
    //     Still firing on normal speech? Raise toward 0.80f.
    //     Missing clear "Hey Toma" utterances? Lower toward 0.60f.
    var detectionThreshold: Float = 0.70f

    // REQUIRED_CONSECUTIVE: how many consecutive frames must exceed the threshold
    // before a detection fires.  One "frame" = one classifier output (~80 ms of audio).
    //   3 frames × 80 ms = 240 ms — rejects one-shot spikes while still responding
    //   to a clearly spoken "헤이 토마" (~500–600 ms total utterance).
    //   Tuning guide:
    //     Still getting spike-triggered detections? Raise to 4–5.
    //     Real utterances sometimes missed? Lower to 2.
    var requiredConsecutive: Int = 3
    // ─────────────────────────────────────────────────────────────────────────

    var verboseLogging: Boolean = true
    private var consecutiveDetections = 0
    private val RESET_FLOOR = 0.20f
    private val SILENCE_RESET_FRAMES = 50

    // ─── Signal-quality gates ─────────────────────────────────────────────────
    // VAD_RMS_THRESHOLD: frames below this RMS are discarded before the pipeline.
    // Higher = fewer false positives from low-level ambient noise (fans, HVAC).
    // Raise toward 400 for very quiet rooms; lower toward 200 for noisy venues.
    var VAD_RMS_THRESHOLD = 300f

    // MIN_VAD_PASSES_BEFORE_SCORING: consecutive VAD-passing frames required before
    // the classifier pipeline opens.  Rejects one-shot transients (clicks, breaths).
    // 3 frames ≈ 240 ms of sustained signal above VAD_RMS_THRESHOLD.
    private val MIN_VAD_PASSES_BEFORE_SCORING = 3
    private var consecutiveVadPasses = 0

    // Rolling RMS history across all frames (silent frames included so brief bursts
    // don't count as speech while the rolling average is still near zero).
    private val RMS_WINDOW_SIZE = 6
    private val rmsHistory = FloatArray(RMS_WINDOW_SIZE) { 0f }
    private var rmsHistoryIdx = 0
    @Volatile private var recentAvgRms = 0f

    // MIN_SCORING_RMS: rolling-average gate applied after VAD.  Must be lower than
    // VAD_RMS_THRESHOLD so real sustained speech passes; set equal here so that only
    // audio that has held above 300 for the whole window counts.
    private val MIN_SCORING_RMS = 100f
    // ─────────────────────────────────────────────────────────────────────────

    private var consecutiveSilentFrames = 0

    private var firstDetectionTime = 0L
    private val DETECTION_WINDOW_MS = 1000L

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
    // detectionCooldownMs: minimum gap between two consecutive detections.
    // 3 000 ms prevents double-fire on the same utterance and on TTS audio bleed
    // while still letting the user re-trigger soon after an early-cancelled session.
    var detectionCooldownMs = 3000L

    var bypassVad: Boolean = false

    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val chunkChannel = Channel<ShortArray>(64)

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

            Log.d(TAG, "✅ 3-Stage ONNX Pipeline initialized (threshold=$detectionThreshold, consecutive=$requiredConsecutive, vad=$VAD_RMS_THRESHOLD, window=${DETECTION_WINDOW_MS}ms)")
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
        consecutiveDetections = 0
        firstDetectionTime = 0L
        consecutiveSilentFrames = 0
        if (verboseLogging) Log.d(TAG, "🔓 Wake-word ARMED (threshold=$detectionThreshold, vad=$VAD_RMS_THRESHOLD, window=${DETECTION_WINDOW_MS}ms)")
    }

    fun disarm() {
        isArmed = false
        consecutiveDetections = 0
        firstDetectionTime = 0L
        if (verboseLogging) Log.d(TAG, "🔒 Wake-word DISARMED — counter flushed")
    }

    fun processFrame(pcmData: ByteArray) {
        if (!isArmed) return
        if (melSession == null || embSession == null) {
            if (verboseLogging) Log.w(TAG, "processFrame skipped: models not loaded yet")
            return
        }
        if (!useOnDevicePersonalizer && clfSession == null) {
            if (verboseLogging) Log.w(TAG, "processFrame skipped: classifier not loaded yet")
            return
        }

        val shortBuf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(shortBuf.remaining()) { shortBuf.get() }

        val rms = computeRms(shorts)

        // Keep a rolling RMS average across all frames (including silent ones).
        rmsHistory[rmsHistoryIdx % RMS_WINDOW_SIZE] = rms
        rmsHistoryIdx++
        recentAvgRms = rmsHistory.average().toFloat()

        if (!bypassVad && rms < VAD_RMS_THRESHOLD) {
            consecutiveSilentFrames++
            consecutiveVadPasses = 0
            if (verboseLogging && consecutiveSilentFrames % 10 == 0) {
                Log.v(TAG, "VAD skip: rms=${"%.1f".format(rms)} (silent ${consecutiveSilentFrames} frames, avgRms=${"%.1f".format(recentAvgRms)})")
            }
            if (consecutiveSilentFrames >= SILENCE_RESET_FRAMES) {
                consecutiveDetections = 0
                firstDetectionTime = 0L
                consecutiveSilentFrames = 0
                if (verboseLogging) Log.v(TAG, "🔇 Long silence — detection state reset")
            }
            return
        }

        consecutiveSilentFrames = 0
        consecutiveVadPasses++

        // Require at least MIN_VAD_PASSES_BEFORE_SCORING consecutive VAD-passing
        // frames before opening the classifier pipeline.  This rejects transients
        // (a single loud click or breath) that pass VAD for only one frame.
        if (!bypassVad && consecutiveVadPasses < MIN_VAD_PASSES_BEFORE_SCORING) {
            if (verboseLogging) Log.d(TAG, "🔕 VAD warmup: pass ${consecutiveVadPasses}/$MIN_VAD_PASSES_BEFORE_SCORING, rms=${"%.1f".format(rms)}")
            return
        }

        if (verboseLogging) Log.d(TAG, "VAD pass: rms=${"%.1f".format(rms)} vadPasses=$consecutiveVadPasses avgRms=${"%.1f".format(recentAvgRms)}")

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
        firstDetectionTime = 0L
        consecutiveSilentFrames = 0
        consecutiveVadPasses = 0
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
                if (verboseLogging) Log.d(TAG, "⚙️ Scoring path: on-device personalizer")
                score = personalizer.predict(currentEmb)

            } else if (isPersonalModel) {
                if (verboseLogging) Log.d(TAG, "⚙️ Scoring path: personal ONNX model")
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(currentEmb),
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
                if (verboseLogging) Log.d(TAG, "⚙️ Scoring path: default ONNX model")
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(currentEmb.copyOf()),
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

            Log.d(TAG, "Score: $score consecutive: $consecutiveDetections")
            if (verboseLogging && score > 0.05f) {
                Log.d(TAG, "🎤 Score=${"%.3f".format(score)} consecutive=$consecutiveDetections/$requiredConsecutive")
            }

            when {
                score >= detectionThreshold && recentAvgRms < MIN_SCORING_RMS -> {
                    // Score crossed the threshold but the rolling average energy is too
                    // low — this is a brief transient that happened to score high, not
                    // a real wake word utterance.  Reset rather than accumulate.
                    Log.d(TAG, "🔕 Score=${"%.3f".format(score)} gated: avgRms=${"%.1f".format(recentAvgRms)} < MIN ${"%.1f".format(MIN_SCORING_RMS)} — weak signal, skipping")
                    consecutiveDetections = 0
                    firstDetectionTime = 0L
                }

                score >= detectionThreshold -> {
                    val now = System.currentTimeMillis()

                    if (consecutiveDetections == 0) {
                        firstDetectionTime = now
                    } else if (now - firstDetectionTime > DETECTION_WINDOW_MS) {
                        Log.d(TAG, "⏱️ Detection window expired (${now - firstDetectionTime}ms) — reset and restart")
                        consecutiveDetections = 0
                        firstDetectionTime = now
                    }

                    consecutiveDetections++
                    Log.d(TAG, "✅ threshold passed: score=${"%.3f".format(score)}, consecutive=$consecutiveDetections/$requiredConsecutive")

                    if (consecutiveDetections >= requiredConsecutive) {
                        if (now - lastDetectionTime > detectionCooldownMs) {
                            lastDetectionTime = now
                            consecutiveDetections = 0
                            firstDetectionTime = 0L
                            Log.d(TAG, "🔥 [Hey Toma] DETECTED! score=${"%.3f".format(score)}")
                            triggerHaptic()
                            onWakeWordDetected()
                            embeddingBuffer.clear()
                        } else {
                            consecutiveDetections = 0
                            firstDetectionTime = 0L
                            if (verboseLogging) Log.d(TAG, "⏱️ Cooldown active — ignoring detection")
                        }
                    }
                }

                score < RESET_FLOOR -> {
                    if (consecutiveDetections > 0) {
                        consecutiveDetections = 0
                        firstDetectionTime = 0L
                    }
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
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (_: Exception) {}
    }

    fun release() {
        chunkChannel.close()
        pipelineScope.cancel()
        melSession?.close()
        embSession?.close()
        clfSession?.close()
    }
}