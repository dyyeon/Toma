package com.capstone.toma.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.OnDevicePersonalizer
import com.capstone.toma.UserManager
import com.capstone.toma.WakeWordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeakerEnrollmentViewModel(application: Application) : AndroidViewModel(application) {

    enum class Step { Intro, Recording, Complete }

    private val _step = MutableStateFlow(Step.Intro)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _sampleIndex = MutableStateFlow(0)
    val sampleIndex: StateFlow<Int> = _sampleIndex.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _recordingHint = MutableStateFlow("버튼을 눌러 녹음을 시작하세요")
    val recordingHint: StateFlow<String> = _recordingHint.asStateFlow()

    private val _isPersonalizing = MutableStateFlow(false)
    val isPersonalizing: StateFlow<Boolean> = _isPersonalizing.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var monitorJob: Job? = null
    private var wakeWordManager: WakeWordManager? = null

    private var recordingStartedAtMs: Long = 0L
    private var speechStartedAtMs: Long = 0L
    private var lastVoiceDetectedAtMs: Long = 0L
    private var maxAmplitudeSeen: Int = 0
    private var speechDetected: Boolean = false

    private val enrollmentDir: File by lazy {
        File(getApplication<Application>().filesDir, "enrollment").apply { mkdirs() }
    }

    fun setWakeWordManager(wm: WakeWordManager) {
        wakeWordManager = wm
    }

    fun sampleFile(oneBasedIndex: Int): File =
        File(enrollmentDir, "sample_$oneBasedIndex.m4a")

    fun goToRecording() {
        _step.value = Step.Recording
        _recordingHint.value = "버튼을 눌러 녹음을 시작하세요"
    }

    fun skipEnrollment() {
        runCatching { stopRecorderQuietly() }
        UserManager.setSpeakerEnrolled(getApplication(), false)
        UserManager.setSkipped(getApplication())
    }

    fun consumeError() {
        _errorMessage.value = null
    }

    fun startRecordingSample() {
        if (_isRecording.value) return
        if (_sampleIndex.value >= TOTAL_SAMPLES) return

        val targetFile = sampleFile(_sampleIndex.value + 1)
        if (targetFile.exists()) targetFile.delete()

        resetRecordingMetrics()

        try {
            val ctx = getApplication<Application>()
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(targetFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            _isRecording.value = true
            _recordingHint.value = "'헤이 토마'를 또렷하게 말해주세요. 말이 끝나면 자동으로 종료돼요."

            startAmplitudeMonitoring()

            Log.d(TAG, "Recording sample ${_sampleIndex.value + 1}/$TOTAL_SAMPLES → ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder start failed", e)
            _errorMessage.value = "마이크를 시작할 수 없습니다. 권한을 확인해주세요."
            stopRecorderQuietly()
            _isRecording.value = false
            _recordingHint.value = "버튼을 눌러 다시 시도하세요"
        }
    }

    private fun startAmplitudeMonitoring() {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (isActive && _isRecording.value) {
                delay(150)

                val amplitude = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val now = SystemClock.elapsedRealtime()

                if (amplitude > maxAmplitudeSeen) maxAmplitudeSeen = amplitude

                if (amplitude >= SPEECH_AMPLITUDE_THRESHOLD) {
                    if (!speechDetected) {
                        speechDetected = true
                        speechStartedAtMs = now
                    }
                    lastVoiceDetectedAtMs = now
                    _recordingHint.value = "좋아요! 한 번만 또렷하게 말해주세요."
                } else {
                    if (!speechDetected) {
                        _recordingHint.value = "목소리를 기다리고 있어요."
                    } else {
                        val silenceDuration = now - lastVoiceDetectedAtMs
                        if (silenceDuration >= AUTO_STOP_SILENCE_MS) {
                            stopRecordingSample(autoStopped = true)
                            break
                        } else {
                            _recordingHint.value = "거의 끝났어요. 잠시 후 자동으로 종료돼요."
                        }
                    }
                }

                val totalDuration = now - recordingStartedAtMs
                if (totalDuration >= MAX_RECORDING_MS) {
                    stopRecordingSample(autoStopped = true)
                    break
                }
            }
        }
    }

    fun stopRecordingSample(autoStopped: Boolean = false) {
        if (!_isRecording.value) return

        val current = _sampleIndex.value
        val targetFile = sampleFile(current + 1)
        val totalDuration = SystemClock.elapsedRealtime() - recordingStartedAtMs
        val speechDuration =
            if (speechDetected && speechStartedAtMs > 0L && lastVoiceDetectedAtMs >= speechStartedAtMs)
                lastVoiceDetectedAtMs - speechStartedAtMs
            else 0L

        monitorJob?.cancel()
        monitorJob = null

        var stopSucceeded = true
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            stopSucceeded = false
            Log.w(TAG, "MediaRecorder stop failed: ${e.message}")
        }
        mediaRecorder = null
        _isRecording.value = false

        val fileLooksValid = targetFile.exists() && targetFile.length() > MIN_VALID_FILE_BYTES

        if (!stopSucceeded || !fileLooksValid || totalDuration < MIN_TOTAL_RECORDING_MS) {
            targetFile.delete()
            _errorMessage.value = "녹음이 너무 짧아요. '헤이 토마'를 끝까지 다시 말해주세요."
            _recordingHint.value = "버튼을 눌러 다시 녹음하세요"
            return
        }

        if (!speechDetected || maxAmplitudeSeen < SPEECH_AMPLITUDE_THRESHOLD || speechDuration < MIN_SPEECH_DURATION_MS) {
            targetFile.delete()
            _errorMessage.value = "목소리가 잘 들리지 않았어요. 조금 더 또렷하게 다시 말해주세요."
            _recordingHint.value = "버튼을 눌러 다시 녹음하세요"
            return
        }

        Log.d(TAG, "Sample accepted: idx=${current + 1}, total=${totalDuration}ms, speech=${speechDuration}ms, maxAmp=$maxAmplitudeSeen, autoStopped=$autoStopped")

        val nextIndex = current + 1
        _sampleIndex.value = nextIndex

        if (nextIndex >= TOTAL_SAMPLES) {
            _recordingHint.value = "등록이 완료되었습니다."
            _isPersonalizing.value = true  // set before step change to avoid race
            _step.value = Step.Complete
            viewModelScope.launch { runPersonalizationBestEffort() }
        } else {
            _recordingHint.value = "좋아요! 다시 한 번 '헤이 토마'를 말해주세요."
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before enrollment flow starts
    private suspend fun runPersonalizationBestEffort() {
        val ctx = getApplication<Application>()
        val wm = wakeWordManager
        try {
            withContext(Dispatchers.IO) {
                val files = (1..TOTAL_SAMPLES).map { sampleFile(it) }
                if (!files.all { it.exists() && it.length() > 0L }) {
                    Log.w(TAG, "Personalization skipped: not all sample files present")
                    return@withContext
                }

                if (wm == null) {
                    Log.w(TAG, "WakeWordManager not injected — skipping embedding extraction")
                    return@withContext
                }

                val personalizer = OnDevicePersonalizer(ctx)

                wm.arm()
                wm.bypassVad = true

                // ── Collect positive embeddings from enrollment recordings ──
                for (i in 1..TOTAL_SAMPLES) {
                    wm.clearLastEmbedding()
                    val pcm = decodeMp4ToPcm(sampleFile(i))
                    if (pcm.isEmpty()) {
                        Log.w(TAG, "Sample $i: empty PCM after decode — skipping")
                        continue
                    }
                    Log.d(TAG, "Sample $i: ${pcm.size} samples decoded, feeding pipeline…")
                    feedPcmToWakeWord(wm, pcm)
                    delay(500)
                    val emb = wm.getLastEmbedding()
                    if (emb != null) {
                        personalizer.addPositiveSample(emb)
                        Log.d(TAG, "Sample $i: embedding captured (dim=${emb.size})")
                    } else {
                        Log.w(TAG, "Sample $i: pipeline produced no embedding")
                    }
                }

                // ── Collect negative embeddings from real room audio ──
                wm.clearLastEmbedding()
                wm.startAmbientCollection()

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val audioRecord = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, CHUNK_SIZE * 2)
                    )
                }.getOrNull()

                val silenceSamples = SILENCE_SECONDS * SAMPLE_RATE
                if (audioRecord != null && audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.startRecording()
                    val chunk = ByteArray(CHUNK_SIZE * 2)
                    var fed = 0
                    while (fed + CHUNK_SIZE <= silenceSamples) {
                        val read = audioRecord.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                        if (read > 0) wm.processFrame(chunk)
                        fed += CHUNK_SIZE
                    }
                    audioRecord.stop()
                    audioRecord.release()
                } else {
                    // Fallback: feed zero audio if AudioRecord unavailable
                    val silenceChunk = ByteArray(CHUNK_SIZE * 2)
                    var fed = 0
                    while (fed + CHUNK_SIZE <= silenceSamples) {
                        wm.processFrame(silenceChunk)
                        fed += CHUNK_SIZE
                        delay(80)
                    }
                }

                delay(500)
                val negatives = wm.stopAmbientCollection()
                personalizer.setNegativeSamples(negatives)

                wm.bypassVad = false
                wm.clearLastEmbedding()
                wm.disarm()

                Log.d(TAG, "Training: ${personalizer.positiveEmbeddings.size} pos, ${negatives.size} neg")

                val trained = runCatching { personalizer.train() }.getOrElse {
                    Log.w(TAG, "personalizer.train() threw: ${it.message}"); false
                }

                if (trained) {
                    val weightsFile = File(ctx.filesDir, "personal_weights.bin")
                    runCatching { personalizer.saveToFile(weightsFile) }.onFailure {
                        Log.w(TAG, "personalizer.saveToFile() failed: ${it.message}")
                    }
                    wm.loadPersonalWeights(personalizer)
                    Log.d(TAG, "On-device personalization weights saved and activated")
                } else {
                    Log.d(TAG, "train() returned false — proceeding without personal weights")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Personalization failed (non-blocking): ${e.message}", e)
        } finally {
            _isPersonalizing.value = false
            UserManager.setSpeakerEnrolled(ctx, true)
        }
    }

    private suspend fun feedPcmToWakeWord(wm: WakeWordManager, pcm: ShortArray) {
        var offset = 0
        while (offset + CHUNK_SIZE <= pcm.size) {
            val bytes = ByteArray(CHUNK_SIZE * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (j in 0 until CHUNK_SIZE) bb.putShort(pcm[offset + j])
            wm.processFrame(bytes)
            offset += CHUNK_SIZE
            delay(100)
        }
    }

    private fun decodeMp4ToPcm(file: File): ShortArray {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)

            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; fmt = f; break
                }
            }
            if (trackIdx < 0 || fmt == null) {
                Log.e(TAG, "No audio track found in ${file.name}"); return ShortArray(0)
            }

            extractor.selectTrack(trackIdx)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val raw = ArrayList<Short>(65536)
            var channels = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false

            while (!outDone) {
                if (!inDone) {
                    val idx = codec.dequeueInputBuffer(10_000L)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        channels = runCatching { codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val sb = outBuf.asShortBuffer()
                        while (sb.hasRemaining()) raw.add(sb.get())
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }

            codec.stop()
            codec.release()

            // Downmix stereo → mono if decoder returned multi-channel output
            if (channels <= 1) {
                ShortArray(raw.size) { raw[it] }
            } else {
                ShortArray(raw.size / channels) { i ->
                    var sum = 0L
                    for (c in 0 until channels) sum += raw[i * channels + c]
                    (sum / channels).toShort()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeMp4ToPcm failed for ${file.name}: ${e.message}", e)
            ShortArray(0)
        } finally {
            extractor.release()
        }
    }

    private fun resetRecordingMetrics() {
        recordingStartedAtMs = 0L
        speechStartedAtMs = 0L
        lastVoiceDetectedAtMs = 0L
        maxAmplitudeSeen = 0
        speechDetected = false
    }

    private fun stopRecorderQuietly() {
        monitorJob?.cancel()
        monitorJob = null
        runCatching { mediaRecorder?.apply { stop(); release() } }
        mediaRecorder = null
        _isRecording.value = false
        resetRecordingMetrics()
    }

    override fun onCleared() {
        super.onCleared()
        stopRecorderQuietly()
    }

    companion object {
        const val TOTAL_SAMPLES = 5
        const val TARGET_SENTENCE = "헤이 토마"
        const val CHUNK_SIZE = 1280

        private const val SAMPLE_RATE = 16000
        private const val SILENCE_SECONDS = 3
        private const val TAG = "SpeakerEnrollVM"

        private const val SPEECH_AMPLITUDE_THRESHOLD = 1500
        private const val AUTO_STOP_SILENCE_MS = 1200L
        private const val MIN_TOTAL_RECORDING_MS = 1500L
        private const val MIN_SPEECH_DURATION_MS = 700L
        private const val MAX_RECORDING_MS = 5000L
        private const val MIN_VALID_FILE_BYTES = 1200L
    }
}
