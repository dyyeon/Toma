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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

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

    private val _intentEvent = MutableSharedFlow<TomaIntent>()
    val intentEvent = _intentEvent.asSharedFlow()

    private val _recognizedTextEvent = MutableSharedFlow<String>()
    val recognizedTextEvent = _recognizedTextEvent.asSharedFlow()

    private val _voiceAnnouncement = MutableSharedFlow<String>()
    val voiceAnnouncement = _voiceAnnouncement.asSharedFlow()

    private val openAiManager = OpenAiManager()
    private val audioStreamManager = AudioStreamManager(application)
    private val onDevicePersonalizer = OnDevicePersonalizer(application)
    private val wakeWordManager = WakeWordManager(application, onDevicePersonalizer) {
        onWakeWordDetected()
    }
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    private val realtimeManager = OpenAiRealtimeManager(
        apiKey = BuildConfig.OPENAI_API_KEY,
        onResult = { jsonResponse -> handleAiIntent(jsonResponse) },
        onError = { error ->
            viewModelScope.launch {
                Log.e("VoiceViewModel", "Realtime error: $error")
                _uiState.value = VoiceUiState.Error(error)
                delay(3000)
                if (_uiState.value is VoiceUiState.Error) {
                    returnToIdle()
                }
            }
        },
        onSessionReady = {
            if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                viewModelScope.launch {
                    Log.d("VoiceViewModel", "Realtime session ready")
                    _uiState.value = VoiceUiState.Result("토마 준비됐어요!")
                    delay(1500)
                    _uiState.value = VoiceUiState.Listening
                }
            }
        }
    )

    private var mediaRecorder: MediaRecorder? = null
    private val manualAudioFile = File(application.cacheDir, "manual_voice_search.m4a")
    private var isManualFlow = false

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds = _recordingDurationSeconds.asStateFlow()
    private var recordingJob: Job? = null

    private var recordingStartMs: Long = 0L
    private var peakAmplitude: Int = 0

    private val hallucinationKeywords = listOf(
        "자막", "구독", "좋아요", "감사합니다", "시청해주셔서",
        "알림설정", "영상", "채널", "댓글", "좋아요와 구독",
        "subscribe", "like and subscribe", "thank you",
        "thanks for watching", "please like", "turn on",
        "notification", "comment below"
    )

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
            Log.d("VoiceViewModel", "TimerService connected")
            viewModelScope.launch {
                srv.remainingSeconds.collect { _timerRemainingSeconds.value = it }
            }
            viewModelScope.launch {
                srv.isTimerRunning.collect { _isTimerRunning.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w("VoiceViewModel", "TimerService disconnected")
            timerService = null
            _isTimerRunning.value = false
        }
    }

    var onStopTtsRequest: (() -> Unit)? = null

    init {
        wakeWordManager.verboseLogging = true
        observeAudioStream()
        val intent = Intent(application, TimerService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun returnToIdle() {
        Log.d("VoiceViewModel", "returnToIdle()")
        _uiState.value = VoiceUiState.Idle
        isManualFlow = false
        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.startCapture()
        }
    }

    fun startWakeWord() {
        Log.d("VoiceViewModel", "Starting WakeWord sensing")
        _uiState.value = VoiceUiState.Idle
        isManualFlow = false
        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) {
            realtimeManager.connect()
            audioStreamManager.startCapture()
        }
    }

    fun stopWakeWord() {
        Log.d("VoiceViewModel", "Stopping WakeWord sensing")
        wakeWordManager.disarm()
        _uiState.value = VoiceUiState.Idle
        isManualFlow = false
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
            realtimeManager.disconnect()
        }
    }

    fun onAppBackground() {
        Log.d("VoiceViewModel", "onAppBackground()")
        onStopTtsRequest?.invoke()
        wakeWordManager.disarm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
        }
        if (isManualFlow && mediaRecorder != null) {
            recordingJob?.cancel()
            try { mediaRecorder?.apply { stop(); release() } } catch (_: Exception) {}
            mediaRecorder = null
            isManualFlow = false
            _uiState.value = VoiceUiState.Idle
        }
    }

    fun onAppForeground() {
        Log.d("VoiceViewModel", "onAppForeground()")
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.startCapture()
        }
    }

    private fun observeAudioStream() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("VoiceViewModel", "observeAudioStream started")
            for (pcmData in audioStreamManager.pcmChannel) {
                if (_uiState.value == VoiceUiState.Idle) {
                    wakeWordManager.processFrame(pcmData)
                }
                if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                    realtimeManager.sendAudio(pcmData)
                }
            }
            Log.w("VoiceViewModel", "observeAudioStream ended")
        }
    }

    private fun onWakeWordDetected() {
        viewModelScope.launch {
            Log.d("VoiceViewModel", "onWakeWordDetected() uiState=${_uiState.value}")
            if (_uiState.value != VoiceUiState.Idle) return@launch

            isManualFlow = false
            wakeWordManager.disarm()
            onStopTtsRequest?.invoke()
            _uiState.value = VoiceUiState.Listening
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

            delay(8000)
            if (_uiState.value == VoiceUiState.Listening) {
                Log.d("VoiceViewModel", "Wake-word session timed out")
                returnToIdle()
            }
        }
    }

    private fun handleAiIntent(jsonResponse: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                Log.d("VoiceViewModel", "handleAiIntent raw = $jsonResponse")
                val intent = TomaIntentParser.parse(jsonResponse)
                Log.d("VoiceViewModel", "Parsed TomaIntent = $intent")

                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Processing
                }

                _intentEvent.emit(intent)
                Log.d("VoiceViewModel", "intentEvent emitted: $intent")

                withContext(Dispatchers.Main) {
                    when (intent) {
                        is TomaIntent.SET_TIMER -> {
                            Log.d("VoiceViewModel", "SET_TIMER: ${intent.durationMin}min")
                            triggerTimer(intent.durationMin)
                        }
                        TomaIntent.NEXT_STEP ->
                            _uiState.value = VoiceUiState.Result("다음 단계로 넘어갑니다.")
                        TomaIntent.PREVIOUS_STEP ->
                            _uiState.value = VoiceUiState.Result("이전 단계로 돌아갑니다.")
                        TomaIntent.REPEAT_STEP ->
                            _uiState.value = VoiceUiState.Result("다시 읽어드릴게요.")
                        TomaIntent.RECOMMENDED_TIMER ->
                            _uiState.value = VoiceUiState.Result("이 단계의 추천 타이머를 설정할게요.")
                        // ✅ CANCEL_TIMER 추가 — 웨이크워드 플로우에서도 타이머 취소 처리
                        TomaIntent.CANCEL_TIMER -> {
                            Log.d("VoiceViewModel", "CANCEL_TIMER via wake-word flow")
                            cancelTimer()
                        }
                        is TomaIntent.RECIPE_SEARCH ->
                            _uiState.value = VoiceUiState.Result("'${intent.keyword}' 레시피를 찾아볼게요.")
                        TomaIntent.CANCEL -> returnToIdle()
                        else -> {
                            Log.w("VoiceViewModel", "Unknown intent: $intent")
                            _uiState.value = VoiceUiState.Error("명령을 이해하지 못했어요.")
                        }
                    }
                }

                val currentState = withContext(Dispatchers.Main) { _uiState.value }
                if (currentState !is VoiceUiState.Error && currentState !is VoiceUiState.Idle) {
                    withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Speaking }
                    delay(3000)
                    withContext(Dispatchers.Main) { returnToIdle() }
                }
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "handleAiIntent failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Error("오류가 발생했습니다.")
                }
                delay(3000)
                withContext(Dispatchers.Main) { returnToIdle() }
            }
        }
    }

    fun startListeningManually() {
        if (_uiState.value != VoiceUiState.Idle && _uiState.value !is VoiceUiState.Result) {
            Log.d("VoiceViewModel", "Manual listening ignored, uiState=${_uiState.value}")
            return
        }

        Log.d("VoiceViewModel", "Starting manual recording flow")
        isManualFlow = true
        wakeWordManager.disarm()
        onStopTtsRequest?.invoke()
        audioStreamManager.stopCapture()

        try {
            if (manualAudioFile.exists()) manualAudioFile.delete()

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
            recordingJob = viewModelScope.launch {
                runSilenceDetectionLoop(recorder)
            }

            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "Failed to start MediaRecorder: ${e.message}", e)
            _uiState.value = VoiceUiState.Error("마이크를 시작할 수 없습니다.")
            returnToIdle()
        }
    }

    private suspend fun runSilenceDetectionLoop(recorder: MediaRecorder) {
        val pollIntervalMs = 100L
        val ampThreshold = 200
        val silenceTimeoutMs = 600L   // ✅ 900 → 600ms
        val maxRecordingMs = 8000L
        val minBeforeSilenceCheck = 400L  // ✅ 600 → 400ms

        val startedAt = System.currentTimeMillis()
        var lastSpeechTime = startedAt
        var speechFrameCount = 0
        var firstPoll = true
        var lastTickSec = 0

        while (true) {
            delay(pollIntervalMs)

            val now = System.currentTimeMillis()
            val elapsed = now - startedAt

            val tickSec = (elapsed / 1000L).toInt()
            if (tickSec != lastTickSec) {
                lastTickSec = tickSec
                _recordingDurationSeconds.value = tickSec
            }

            if (elapsed >= maxRecordingMs) {
                Log.d("VoiceViewModel", "Auto-stop: max duration (8s)")
                stopListeningManually()
                return
            }

            val amplitude = try {
                recorder.maxAmplitude
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "maxAmplitude failed: ${e.message}", e)
                return
            }

            if (firstPoll) { firstPoll = false; continue }

            if (amplitude > peakAmplitude) peakAmplitude = amplitude

            if (amplitude > ampThreshold) {
                lastSpeechTime = now
                speechFrameCount++
                if (speechFrameCount == 3) {
                    Log.d("VoiceViewModel", "Speech confirmed (3 frames) — watchdog active")
                }
            }

            if (elapsed >= minBeforeSilenceCheck) {
                val silenceDuration = now - lastSpeechTime
                if (silenceDuration > silenceTimeoutMs) {
                    Log.d(
                        "VoiceViewModel",
                        "Auto-stop: silence=${silenceDuration}ms, speechFrames=$speechFrameCount, peak=$peakAmplitude"
                    )
                    stopListeningManually()
                    return
                }
            }
        }
    }

    fun stopListeningManually() {
        if (!isManualFlow || mediaRecorder == null) {
            Log.d("VoiceViewModel", "stopListeningManually ignored, isManualFlow=$isManualFlow")
            return
        }

        Log.d("VoiceViewModel", "Stopping manual recording flow")
        recordingJob?.cancel()

        val durationMs = System.currentTimeMillis() - recordingStartMs
        val capturedPeak = peakAmplitude

        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "MediaRecorder stop failed: ${e.message}", e)
        }
        mediaRecorder = null

        Log.d(
            "VoiceViewModel",
            "file exists=${manualAudioFile.exists()}, size=${if (manualAudioFile.exists()) manualAudioFile.length() else -1}, duration=${durationMs}ms, peak=$capturedPeak"
        )

        if (durationMs < 700L) {
            Log.d("VoiceViewModel", "Guard 1 fail: duration ${durationMs}ms < 700ms")
            rejectRecording("너무 짧아요. 다시 말씀해주세요.")
            return
        }

        if (capturedPeak < 200) {
            Log.d("VoiceViewModel", "Guard 2 fail: peak $capturedPeak < 200")
            rejectRecording("음성이 감지되지 않았어요.")
            return
        }

        _uiState.value = VoiceUiState.Processing

        viewModelScope.launch(Dispatchers.IO) {
            if (manualAudioFile.exists()) {
                transcribeManualAudio(manualAudioFile)
            } else {
                withContext(Dispatchers.Main) {
                    Log.e("VoiceViewModel", "Manual audio file missing after recording")
                    _uiState.value = VoiceUiState.Error("녹음 파일이 생성되지 않았습니다.")
                    delay(2000)
                    returnToIdle()
                }
            }
        }
    }

    private fun rejectRecording(message: String) {
        viewModelScope.launch {
            Log.w("VoiceViewModel", "Recording rejected: $message")
            _uiState.value = VoiceUiState.Error(message)
            delay(2000)
            _uiState.value = VoiceUiState.Idle
            isManualFlow = false
            returnToIdle()
        }
    }

    private fun transcribeManualAudio(audioFile: File) {
        Log.d("VoiceViewModel", "transcribeManualAudio start: size=${audioFile.length()}")

        openAiManager.transcribeAudio(audioFile) { recognizedText ->
            viewModelScope.launch {
                val text = recognizedText.orEmpty().trim()
                Log.d("VoiceViewModel", "STT result: '$text'")

                if (text.length < 2) {
                    Log.d("VoiceViewModel", "Guard 4 fail: length ${text.length}")
                    rejectRecording("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    return@launch
                }

                if (isHallucinatedTranscript(text)) {
                    Log.d("VoiceViewModel", "Guard 3 fail: hallucinated '$text'")
                    rejectRecording("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    return@launch
                }

                if (hasExcessiveRepetition(text)) {
                    Log.d("VoiceViewModel", "Guard 5 fail: repetition '$text'")
                    rejectRecording("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    return@launch
                }

                val intent = classifyManualIntent(text)
                Log.d("VoiceViewModel", "classifyManualIntent('$text') → $intent")

                _uiState.value = VoiceUiState.Idle
                isManualFlow = false
                returnToIdle()

                _recognizedTextEvent.emit(text)
                _intentEvent.emit(intent)
                Log.d("VoiceViewModel", "intentEvent emitted: $intent")
            }
        }
    }

    private fun classifyManualIntent(text: String): TomaIntent {
        val t = text.trim().lowercase(Locale.ROOT)

        // 1. 다음 단계
        if (t.contains("다음") || t.contains("넘어가") || t.contains("next")) {
            return TomaIntent.NEXT_STEP
        }

        // 2. 이전 단계
        if (t.contains("이전") || t.contains("돌아가") || t.contains("뒤로") || t.contains("back")) {
            return TomaIntent.PREVIOUS_STEP
        }

        // 3. 반복
        if (t.contains("다시") || t.contains("반복") || t.contains("한번 더") || t.contains("한 번 더")) {
            return TomaIntent.REPEAT_STEP
        }

        // 4. ✅ CANCEL_TIMER — 타이머 + 중지 키워드 (숫자 없음), SET_TIMER보다 먼저 체크
        val timerStopKeywords = listOf("취소", "꺼줘", "꺼", "중지", "멈춰", "그만", "stop")
        if ((t.contains("타이머") || t.contains("알람")) &&
            timerStopKeywords.any { t.contains(it) }) {
            return TomaIntent.CANCEL_TIMER
        }

        // 5. ✅ SET_TIMER — 숫자 + 타이머 설정 키워드
        val minuteMatch = "([0-9]+)\\s*분".toRegex().find(t)
        if (minuteMatch != null) {
            val min = minuteMatch.groupValues[1].toIntOrNull() ?: 1
            val timerTriggers = listOf(
                "타이머", "알람", "맞춰", "설정", "해줘", "바꿔", "으로", "시작", "켜줘"
            )
            if (timerTriggers.any { t.contains(it) }) {
                return TomaIntent.SET_TIMER(min)
            }
        }

        // 6. RECOMMENDED_TIMER — 숫자 없이 타이머만
        if (t.contains("타이머") || t.contains("알람")) {
            return TomaIntent.RECOMMENDED_TIMER
        }

        // 7. 일반 CANCEL — 음성 세션만 종료
        if (t.contains("취소") || t.contains("그만") || t.contains("멈춰") || t.contains("stop")) {
            return TomaIntent.CANCEL
        }

        // 8. 레시피 검색
        if (t.contains("레시피") || t.contains("요리")) {
            val keyword = t
                .replace("레시피", "").replace("요리", "")
                .replace("찾아", "").replace("줘", "").trim()
            if (keyword.isNotBlank()) return TomaIntent.RECIPE_SEARCH(keyword)
        }

        return TomaIntent.UNKNOWN
    }

    private fun isHallucinatedTranscript(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return hallucinationKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun hasExcessiveRepetition(text: String): Boolean {
        val words = text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 4) return false
        val maxCount = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return maxCount > 3
    }

    fun pauseAudioCapture() {
        Log.d("VoiceViewModel", "pauseAudioCapture()")
        wakeWordManager.disarm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
        }
    }

    fun resumeAudioCapture() {
        Log.d("VoiceViewModel", "resumeAudioCapture()")
        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.startCapture()
        }
    }

    fun onMicClick() {
        when (_uiState.value) {
            VoiceUiState.Idle -> startListeningManually()
            VoiceUiState.Listening -> stopListeningManually()
            else -> Log.d("VoiceViewModel", "onMicClick ignored, uiState=${_uiState.value}")
        }
    }

    fun triggerTimer(minutes: Int) {
        viewModelScope.launch {
            Log.d("VoiceViewModel", "triggerTimer($minutes), stepIndex=$currentStepIndex")
            val wasRunning = _isTimerRunning.value
            if (wasRunning) {
                cancelTimerSilently()
                delay(300)
            }

            timerOriginalSeconds = minutes * 60
            timerStartedAtStep = currentStepIndex

            val intent = Intent(getApplication(), TimerService::class.java).apply {
                action = "START"
                putExtra("minutes", minutes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }

            // ✅ 교체 시 "바꿨어요", 신규 시 "시작합니다"
            val msg = if (wasRunning) "${minutes}분 타이머로 바꿨어요."
            else "${minutes}분 타이머를 시작합니다."
            _uiState.value = VoiceUiState.Result(msg)

            delay(3000)
            if (_uiState.value is VoiceUiState.Result) {
                _uiState.value = VoiceUiState.Idle
            }
        }
    }

    fun cancelTimer() {
        Log.d("VoiceViewModel", "cancelTimer()")
        cancelTimerSilently()
        _uiState.value = VoiceUiState.Result("타이머를 취소했습니다.")
        viewModelScope.launch {
            delay(2000)
            if (_uiState.value is VoiceUiState.Result) {
                _uiState.value = VoiceUiState.Idle
            }
        }
    }

    private fun cancelTimerSilently() {
        Log.d("VoiceViewModel", "cancelTimerSilently()")
        getApplication<Application>().startService(
            Intent(getApplication(), TimerService::class.java).apply { action = "STOP" }
        )
        timerStartedAtStep = -1
        timerOriginalSeconds = 0
    }

    fun onStepChanged(newIndex: Int) {
        Log.d("VoiceViewModel", "onStepChanged($newIndex)")
        currentStepIndex = newIndex
        if (!_isTimerRunning.value || timerStartedAtStep < 0) return

        val remaining = _timerRemainingSeconds.value
        val stepDelta = newIndex - timerStartedAtStep
        val percentRemaining = if (timerOriginalSeconds > 0) {
            remaining.toFloat() / timerOriginalSeconds
        } else 0f

        Log.d("VoiceViewModel", "Timer step sync: delta=$stepDelta, percent=$percentRemaining")

        if (stepDelta >= 2) {
            cancelTimerSilently()
            viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 종료했어요.") }
        } else if (stepDelta == 1 && percentRemaining > 0.8f) {
            cancelTimerSilently()
        } else if (stepDelta == 1 && percentRemaining > 0.2f) {
            cancelTimerSilently()
            viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 취소했어요.") }
        }
    }

    fun showTimerManualGuidance() {
        viewModelScope.launch {
            _uiState.value = VoiceUiState.Result(
                "이 단계에는 조리 시간이 없네요. \"3분 타이머 맞춰줘\"와 같이 직접 말씀해주세요!"
            )
            delay(4000)
            if (_uiState.value is VoiceUiState.Result) {
                _uiState.value = VoiceUiState.Idle
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("VoiceViewModel", "onCleared()")
        onStopTtsRequest?.invoke()
        wakeWordManager.disarm()
        audioStreamManager.stopCapture()
        realtimeManager.disconnect()
        wakeWordManager.release()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w("VoiceViewModel", "unbindService failed: ${e.message}")
        }
    }

    fun startEnrollmentRecording(context: Context) {}
    fun stopEnrollmentRecording() {}
    fun uploadEnrollmentWavs(context: Context, onComplete: () -> Unit, onError: (String) -> Unit) {
        onComplete()
    }
    fun startModelPolling(context: Context) {}
}