package com.capstone.toma.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * VoiceViewModel — Handles Wake Word flow (Realtime) and Voice Search flow (Whisper STT).
 * UPDATED: Removed Firebase model polling. Local assets are used for the ONNX model.
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    // Enrollment Status (FLOW DISABLED but state kept for compatibility)
    sealed interface EnrollmentStatus {
        data object Idle : EnrollmentStatus
        data object CollectingAmbient : EnrollmentStatus
        data object Recording : EnrollmentStatus
        data object Verifying : EnrollmentStatus
        data class Success(val count: Int) : EnrollmentStatus
        data object Failed : EnrollmentStatus
    }

    private val _enrollmentStatus = MutableStateFlow<EnrollmentStatus>(EnrollmentStatus.Idle)
    val enrollmentStatus = _enrollmentStatus.asStateFlow()

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _intentEvent = kotlinx.coroutines.flow.MutableSharedFlow<TomaIntent>()
    val intentEvent = _intentEvent.asSharedFlow()

    private val _recognizedTextEvent = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val recognizedTextEvent = _recognizedTextEvent.asSharedFlow()

    // Shared Flow for TTS announcements (timer cancellations, etc.)
    private val _voiceAnnouncement = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val voiceAnnouncement = _voiceAnnouncement.asSharedFlow()

    private val openAiManager = OpenAiManager()
    private val audioStreamManager = AudioStreamManager(application)
    private val onDevicePersonalizer = OnDevicePersonalizer(application)
    private val wakeWordManager = WakeWordManager(application, onDevicePersonalizer) {
        onWakeWordDetected()
    }
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    // Realtime API used ONLY for Wake Word / Intent mode
    private val realtimeManager = OpenAiRealtimeManager(
        apiKey = BuildConfig.OPENAI_API_KEY,
        onResult = { jsonResponse -> handleAiIntent(jsonResponse) },
        onError = { error ->
            viewModelScope.launch {
                _uiState.value = VoiceUiState.Error(error)
                delay(3000)
                if (_uiState.value is VoiceUiState.Error) returnToIdle()
            }
        },
        onSessionReady = {
            if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                viewModelScope.launch {
                    _uiState.value = VoiceUiState.Result("토마 준비됐어요!")
                    delay(1500)
                    _uiState.value = VoiceUiState.Listening
                }
            }
        }
    )

    // --- Manual Recording Flow (MediaRecorder) ---
    private var mediaRecorder: MediaRecorder? = null
    private val manualAudioFile = File(application.cacheDir, "manual_voice_search.m4a")
    private var isManualFlow = false

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds = _recordingDurationSeconds.asStateFlow()
    private var recordingJob: kotlinx.coroutines.Job? = null

    // --- Anti-hallucination guards: tracked per-recording ---
    private var recordingStartMs: Long = 0L
    private var peakAmplitude: Int = 0

    // Keywords that Whisper hallucinates from silence/background noise.
    // Match is case-insensitive and applied to the trimmed transcription.
    private val hallucinationKeywords = listOf(
        // Korean (YouTube-style auto-caption artifacts)
        "자막", "구독", "좋아요", "감사합니다", "시청해주셔서",
        "알림설정", "영상", "채널", "댓글", "좋아요와 구독",
        // English
        "subscribe", "like and subscribe", "thank you",
        "thanks for watching", "please like", "turn on",
        "notification", "comment below"
    )

    // --- Timer Logic ---
    private var timerService: TimerService? = null
    private val _timerRemainingSeconds = MutableStateFlow(0)
    val timerRemainingSeconds: StateFlow<Int> = _timerRemainingSeconds.asStateFlow()
    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private var timerOriginalSeconds = 0
    private var timerStartedAtStep = -1
    private var currentStepIndex = -1

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            val srv = binder.getService()
            timerService = srv
            viewModelScope.launch { srv.remainingSeconds.collect { _timerRemainingSeconds.value = it } }
            viewModelScope.launch { srv.isTimerRunning.collect { _isTimerRunning.value = it } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            timerService = null
            _isTimerRunning.value = false
        }
    }

    var onStopTtsRequest: (() -> Unit)? = null

    init {
        wakeWordManager.verboseLogging = true
        observeAudioStream()

        // Timer service binding
        val intent = Intent(application, TimerService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Note: WakeWordManager automatically loads the bundled model from assets in its own init.
    }

    private fun returnToIdle() {
        _uiState.value = VoiceUiState.Idle
        wakeWordManager.arm()
    }

    fun startWakeWord() {
        Log.d("VoiceViewModel", "Starting WakeWord sensing")
        viewModelScope.launch(Dispatchers.IO) {
            realtimeManager.connect()
            audioStreamManager.startCapture()
        }
    }

    fun stopWakeWord() {
        Log.d("VoiceViewModel", "Stopping WakeWord sensing")
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
            realtimeManager.disconnect()
            withContext(Dispatchers.Main) { returnToIdle() }
        }
    }

    private fun observeAudioStream() {
        viewModelScope.launch(Dispatchers.IO) {
            for (pcmData in audioStreamManager.pcmChannel) {
                // Background wake-word sensing
                if (_uiState.value == VoiceUiState.Idle) {
                    wakeWordManager.processFrame(pcmData)
                }

                // Realtime streaming ONLY if not in manual flow
                if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                    realtimeManager.sendAudio(pcmData)
                }
            }
        }
    }

    private fun onWakeWordDetected() {
        viewModelScope.launch {
            if (_uiState.value != VoiceUiState.Idle) return@launch
            isManualFlow = false
            wakeWordManager.disarm()
            onStopTtsRequest?.invoke()
            resumeAudioCapture()
            _uiState.value = VoiceUiState.Listening
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

            delay(8000)
            if (_uiState.value == VoiceUiState.Listening) returnToIdle()
        }
    }

    private fun handleAiIntent(jsonResponse: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val intent = TomaIntentParser.parse(jsonResponse)
                withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Processing }
                _intentEvent.emit(intent)

                withContext(Dispatchers.Main) {
                    when (intent) {
                        is TomaIntent.SET_TIMER -> triggerTimer(intent.durationMin)
                        TomaIntent.NEXT_STEP -> _uiState.value = VoiceUiState.Result("다음 단계로 넘어갑니다.")
                        TomaIntent.PREVIOUS_STEP -> _uiState.value = VoiceUiState.Result("이전 단계로 돌아갑니다.")
                        TomaIntent.REPEAT_STEP -> _uiState.value = VoiceUiState.Result("다시 읽어드릴게요.")
                        is TomaIntent.RECIPE_SEARCH -> _uiState.value = VoiceUiState.Result("'${intent.keyword}' 레시피를 찾아볼게요.")
                        TomaIntent.CANCEL -> returnToIdle()
                        else -> _uiState.value = VoiceUiState.Error("명령을 이해하지 못했어요.")
                    }
                }

                val currentState = withContext(Dispatchers.Main) { _uiState.value }
                if (currentState !is VoiceUiState.Error && currentState !is VoiceUiState.Idle) {
                    withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Speaking }
                    delay(3000)
                    withContext(Dispatchers.Main) { returnToIdle() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Error("오류가 발생했습니다.") }
                delay(3000)
                withContext(Dispatchers.Main) { returnToIdle() }
            }
        }
    }

    fun startListeningManually() {
        if (_uiState.value != VoiceUiState.Idle && _uiState.value !is VoiceUiState.Result) return

        Log.d("VoiceViewModel", "Starting manual recording flow (MediaRecorder)")
        isManualFlow = true
        onStopTtsRequest?.invoke()

        audioStreamManager.stopCapture()

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(getApplication())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(manualAudioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            _uiState.value = VoiceUiState.Listening
            _recordingDurationSeconds.value = 0
            recordingStartMs = System.currentTimeMillis()
            peakAmplitude = 0

            recordingJob?.cancel()
            recordingJob = viewModelScope.launch { runSilenceDetectionLoop(recorder) }

            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "Failed to start MediaRecorder: ${e.message}")
            _uiState.value = VoiceUiState.Error("마이크를 시작할 수 없습니다.")
            resumeAudioCapture()
        }
    }

    /**
     * Polls MediaRecorder.maxAmplitude every 100ms and auto-stops the recording when:
     *   • The user has spoken at least once (amplitude > AMP_THRESHOLD), AND
     *   • Silence has persisted for SILENCE_TIMEOUT_MS (1500ms)
     *
     * A hard cap of MAX_RECORDING_MS (10s) is enforced regardless of speech state.
     * The silence timer never starts until the first real speech, so users have
     * unlimited time to begin speaking after tapping the mic.
     */
    private suspend fun runSilenceDetectionLoop(recorder: MediaRecorder) {
        val pollIntervalMs = 100L
        val ampThreshold = 800
        val silenceTimeoutMs = 1500L
        val maxRecordingMs = 10_000L

        val startedAt = System.currentTimeMillis()
        var lastSpeechTime = startedAt
        var hasSpokenAtLeastOnce = false
        var firstPoll = true // discard the first maxAmplitude() reading — it's always 0
        var lastTickSec = 0

        while (true) {
            delay(pollIntervalMs)

            val now = System.currentTimeMillis()
            val elapsed = now - startedAt

            // Update UI timer once per second
            val tickSec = (elapsed / 1000L).toInt()
            if (tickSec != lastTickSec) {
                lastTickSec = tickSec
                _recordingDurationSeconds.value = tickSec
            }

            // Hard cap on total recording length
            if (elapsed >= maxRecordingMs) {
                Log.d("VoiceViewModel", "Auto-stop: hit max duration (${maxRecordingMs}ms)")
                stopListeningManually()
                return
            }

            val amplitude = try {
                recorder.maxAmplitude
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "maxAmplitude read failed: ${e.message}")
                return
            }

            if (firstPoll) {
                firstPoll = false
                continue
            }

            if (amplitude > peakAmplitude) peakAmplitude = amplitude

            if (amplitude > ampThreshold) {
                lastSpeechTime = now
                if (!hasSpokenAtLeastOnce) {
                    hasSpokenAtLeastOnce = true
                    Log.d("VoiceViewModel", "Speech detected — silence watchdog armed")
                }
            } else if (hasSpokenAtLeastOnce && (now - lastSpeechTime) > silenceTimeoutMs) {
                Log.d("VoiceViewModel", "Auto-stop: silence for ${now - lastSpeechTime}ms")
                stopListeningManually()
                return
            }
        }
    }

    fun stopListeningManually() {
        if (!isManualFlow || mediaRecorder == null) return

        Log.d("VoiceViewModel", "Stopping manual recording flow")
        recordingJob?.cancel()

        // Snapshot pre-stop metrics for Guards 1 & 2 (read before release()).
        val durationMs = System.currentTimeMillis() - recordingStartMs
        val capturedPeak = peakAmplitude

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "MediaRecorder stop failed: ${e.message}")
        }
        mediaRecorder = null

        // ── Guard 1: minimum duration (< 2s) — hard block, never reach Whisper.
        if (durationMs < 2000L) {
            Log.d("VoiceViewModel", "Guard 1 fail: duration ${durationMs}ms < 2000ms")
            rejectRecording("너무 짧아요. 다시 말씀해주세요.")
            return
        }

        // ── Guard 2: peak amplitude (< 1500) — user never actually spoke.
        if (capturedPeak < 1500) {
            Log.d("VoiceViewModel", "Guard 2 fail: peak amplitude $capturedPeak < 1500")
            rejectRecording("음성이 감지되지 않았어요.")
            return
        }

        _uiState.value = VoiceUiState.Processing

        viewModelScope.launch(Dispatchers.IO) {
            if (manualAudioFile.exists()) {
                transcribeManualAudio(manualAudioFile)
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Error("녹음 파일이 생성되지 않았습니다.")
                    delay(2000)
                    returnToIdle()
                    resumeAudioCapture()
                }
            }
        }
    }

    /** Shared rejection path for Guards 1 & 2 — skips Whisper entirely. */
    private fun rejectRecording(message: String) {
        viewModelScope.launch {
            _uiState.value = VoiceUiState.Error(message)
            delay(2000)
            _uiState.value = VoiceUiState.Idle
            isManualFlow = false
            resumeAudioCapture()
        }
    }

    private fun transcribeManualAudio(audioFile: File) {
        openAiManager.transcribeAudio(audioFile) { recognizedText ->
            viewModelScope.launch {
                val text = recognizedText.orEmpty().trim()

                // ── Guard 4: minimum text length (< 2 chars after trim).
                if (text.length < 2) {
                    Log.d("VoiceViewModel", "Guard 4 fail: text length ${text.length} < 2 ('$text')")
                    rejectRecording("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    return@launch
                }

                // ── Guard 3: hallucination keyword filter (case-insensitive).
                if (isHallucinatedTranscript(text)) {
                    Log.d("VoiceViewModel", "Guard 3 fail: hallucinated text rejected ('$text')")
                    rejectRecording("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    return@launch
                }

                // ── Guard 5: repetition pattern (same word > 3 times).
                if (hasExcessiveRepetition(text)) {
                    Log.d("VoiceViewModel", "Guard 5 fail: excessive repetition ('$text')")
                    rejectRecording("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    return@launch
                }

                // All 5 guards passed — navigate directly to chat.
                _uiState.value = VoiceUiState.Idle
                isManualFlow = false
                resumeAudioCapture()
                _recognizedTextEvent.emit(text)
            }
        }
    }

    private fun isHallucinatedTranscript(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return hallucinationKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun hasExcessiveRepetition(text: String): Boolean {
        val words = text.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size < 4) return false
        val maxCount = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return maxCount > 3
    }

    fun pauseAudioCapture() {
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.stopCapture() }
    }

    fun resumeAudioCapture() {
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.startCapture() }
    }

    fun onMicClick() {
        if (_uiState.value == VoiceUiState.Idle) startListeningManually()
        else if (_uiState.value == VoiceUiState.Listening) stopListeningManually()
    }

    fun triggerTimer(minutes: Int) {
        viewModelScope.launch {
            if (_isTimerRunning.value) { cancelTimerSilently(); _voiceAnnouncement.emit("이전 타이머를 취소하고 새 타이머를 시작할게요."); delay(300) }
            timerOriginalSeconds = minutes * 60
            timerStartedAtStep = currentStepIndex
            val intent = Intent(getApplication(), TimerService::class.java).apply { action = "START"; putExtra("minutes", minutes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getApplication<Application>().startForegroundService(intent) else getApplication<Application>().startService(intent)
            _uiState.value = VoiceUiState.Result("${minutes}분 타이머를 시작합니다."); delay(3000)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    fun cancelTimer() { cancelTimerSilently(); _uiState.value = VoiceUiState.Result("타이머를 취소했습니다."); viewModelScope.launch { delay(2000); if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle } }

    private fun cancelTimerSilently() {
        getApplication<Application>().startService(Intent(getApplication(), TimerService::class.java).apply { action = "STOP" })
        timerStartedAtStep = -1
        timerOriginalSeconds = 0
    }

    fun onStepChanged(newIndex: Int) {
        currentStepIndex = newIndex
        if (!_isTimerRunning.value || timerStartedAtStep < 0) return
        val remaining = _timerRemainingSeconds.value
        val stepDelta = newIndex - timerStartedAtStep
        val percentRemaining = if (timerOriginalSeconds > 0) remaining.toFloat() / timerOriginalSeconds else 0f
        if (stepDelta >= 2) { cancelTimerSilently(); viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 종료했어요.") } }
        else if (stepDelta == 1 && percentRemaining > 0.8f) cancelTimerSilently()
        else if (stepDelta == 1 && percentRemaining > 0.2f) { cancelTimerSilently(); viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 취소했어요.") } }
    }

    fun showTimerManualGuidance() { viewModelScope.launch { _uiState.value = VoiceUiState.Result("이 단계에는 조리 시간이 없네요. \"3분 타이머 맞춰줘\"와 같이 직접 말씀해주세요!"); delay(4000); if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle } }

    override fun onCleared() {
        super.onCleared()
        audioStreamManager.stopCapture()
        realtimeManager.disconnect()
        wakeWordManager.release()
        try { getApplication<Application>().unbindService(serviceConnection) } catch (_: Exception) {}
    }

    // LEGACY STUBS for compatibility with other files (not used in current flow)
    fun startEnrollmentRecording(context: Context) {}
    fun stopEnrollmentRecording() {}
    fun uploadEnrollmentWavs(context: Context, onComplete: () -> Unit, onError: (String) -> Unit) { onComplete() }
    fun startModelPolling(context: Context) {}
}
