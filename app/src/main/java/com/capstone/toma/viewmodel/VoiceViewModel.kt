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
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

    // ─── SpeechRecognizer (로컬 STT) ───────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isManualFlow = false

    // Whisper용 — 레시피 검색 등 긴 문장 전용으로만 남김
    private val manualAudioFile = File(application.cacheDir, "manual_voice_search.m4a")
    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var recordingStartMs: Long = 0L
    private var peakAmplitude: Int = 0

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds = _recordingDurationSeconds.asStateFlow()

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
        val intent = Intent(application, TimerService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        initSpeechRecognizer(application)
    }

    // ─────────────────────────────────────────────
    // SpeechRecognizer 초기화
    // ─────────────────────────────────────────────

    private fun initSpeechRecognizer(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("VoiceViewModel", "SpeechRecognizer not available on this device")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("VoiceViewModel", "SpeechRecognizer: ready")
                viewModelScope.launch { _uiState.value = VoiceUiState.Listening }
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }

            override fun onBeginningOfSpeech() {
                Log.d("VoiceViewModel", "SpeechRecognizer: speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("VoiceViewModel", "SpeechRecognizer: end of speech")
                viewModelScope.launch { _uiState.value = VoiceUiState.Processing }
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "인식 실패"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 감지되지 않았어요."
                    SpeechRecognizer.ERROR_AUDIO -> "마이크 오류"
                    SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 타임아웃"
                    else -> "오류 ($error)"
                }
                Log.e("VoiceViewModel", "SpeechRecognizer error: $msg (code=$error)")
                viewModelScope.launch {
                    isManualFlow = false
                    _uiState.value = VoiceUiState.Error(msg)
                    delay(1500)
                    returnToIdle()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim().orEmpty()
                Log.d("VoiceViewModel", "SpeechRecognizer result: '$text'")

                viewModelScope.launch {
                    isManualFlow = false

                    if (text.length < 2 || isHallucinatedTranscript(text) || hasExcessiveRepetition(text)) {
                        Log.w("VoiceViewModel", "STT guard fail: '$text'")
                        _uiState.value = VoiceUiState.Error("다시 말씀해주세요.")
                        delay(1500)
                        returnToIdle()
                        return@launch
                    }

                    val intent = classifyManualIntent(text)
                    Log.d("VoiceViewModel", "classifyManualIntent('$text') → $intent")

                    returnToIdle()
                    _recognizedTextEvent.emit(text)
                    _intentEvent.emit(intent)
                    Log.d("VoiceViewModel", "intentEvent emitted: $intent")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                Log.d("VoiceViewModel", "Partial: '$partial'")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    // ─────────────────────────────────────────────
    // 상태 전환
    // ─────────────────────────────────────────────

    private fun returnToIdle() {
        Log.d("VoiceViewModel", "returnToIdle()")
        _uiState.value = VoiceUiState.Idle
        isManualFlow = false
        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.startCapture() }
    }

    fun startWakeWord() {
        Log.d("VoiceViewModel", "startWakeWord()")
        _uiState.value = VoiceUiState.Idle
        isManualFlow = false
        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) {
            realtimeManager.connect()
            audioStreamManager.startCapture()
        }
    }

    fun stopWakeWord() {
        Log.d("VoiceViewModel", "stopWakeWord()")
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
        speechRecognizer?.stopListening()
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.stopCapture() }
        if (isManualFlow) {
            isManualFlow = false
            _uiState.value = VoiceUiState.Idle
        }
    }

    fun onAppForeground() {
        Log.d("VoiceViewModel", "onAppForeground()")
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.startCapture() }
    }

    // ─────────────────────────────────────────────
    // 오디오 스트림 / 웨이크워드
    // ─────────────────────────────────────────────

    private fun observeAudioStream() {
        viewModelScope.launch(Dispatchers.IO) {
            for (pcmData in audioStreamManager.pcmChannel) {
                if (_uiState.value == VoiceUiState.Idle) wakeWordManager.processFrame(pcmData)
                if (_uiState.value == VoiceUiState.Listening && !isManualFlow) realtimeManager.sendAudio(pcmData)
            }
        }
    }

    private fun onWakeWordDetected() {
        viewModelScope.launch {
            if (_uiState.value != VoiceUiState.Idle) return@launch
            isManualFlow = false
            wakeWordManager.disarm()
            onStopTtsRequest?.invoke()
            _uiState.value = VoiceUiState.Listening
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            delay(8000)
            if (_uiState.value == VoiceUiState.Listening) returnToIdle()
        }
    }

    // ─────────────────────────────────────────────
    // AI 인텐트 처리 (웨이크워드 플로우)
    // ─────────────────────────────────────────────

    private fun handleAiIntent(jsonResponse: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val intent = TomaIntentParser.parse(jsonResponse)
                Log.d("VoiceViewModel", "Parsed TomaIntent = $intent")

                withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Processing }
                _intentEvent.emit(intent)

                withContext(Dispatchers.Main) {
                    when (intent) {
                        is TomaIntent.SET_TIMER -> triggerTimer(intent.durationMin)
                        TomaIntent.NEXT_STEP -> _uiState.value = VoiceUiState.Result("다음 단계로 넘어갑니다.")
                        TomaIntent.PREVIOUS_STEP -> _uiState.value = VoiceUiState.Result("이전 단계로 돌아갑니다.")
                        TomaIntent.REPEAT_STEP -> _uiState.value = VoiceUiState.Result("다시 읽어드릴게요.")
                        TomaIntent.RECOMMENDED_TIMER -> _uiState.value = VoiceUiState.Result("이 단계의 추천 타이머를 설정할게요.")
                        TomaIntent.CANCEL_TIMER -> cancelTimer()
                        is TomaIntent.RECIPE_SEARCH -> _uiState.value = VoiceUiState.Result("'${intent.keyword}' 레시피를 찾아볼게요.")
                        TomaIntent.CANCEL -> returnToIdle()
                        else -> _uiState.value = VoiceUiState.Error("명령을 이해하지 못했어요.")
                    }
                }

                val currentState = withContext(Dispatchers.Main) { _uiState.value }
                if (currentState !is VoiceUiState.Error && currentState !is VoiceUiState.Idle) {
                    withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Speaking }
                    delay(2000)
                    withContext(Dispatchers.Main) { returnToIdle() }
                }
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "handleAiIntent failed: ${e.message}", e)
                withContext(Dispatchers.Main) { _uiState.value = VoiceUiState.Error("오류가 발생했습니다.") }
                delay(2000)
                withContext(Dispatchers.Main) { returnToIdle() }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 수동 마이크 — SpeechRecognizer 사용 (핵심 변경)
    // ─────────────────────────────────────────────

    fun startListeningManually() {
        if (_uiState.value != VoiceUiState.Idle && _uiState.value !is VoiceUiState.Result) {
            Log.d("VoiceViewModel", "Manual listening ignored, uiState=${_uiState.value}")
            return
        }

        // SpeechRecognizer 없으면 Whisper 폴백
        if (speechRecognizer == null) {
            Log.w("VoiceViewModel", "SpeechRecognizer null, falling back to Whisper")
            startListeningWithWhisper()
            return
        }

        Log.d("VoiceViewModel", "startListeningManually via SpeechRecognizer")
        isManualFlow = true
        wakeWordManager.disarm()
        onStopTtsRequest?.invoke()
        audioStreamManager.stopCapture()

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 말 끝나고 500ms 침묵이면 자동 종료
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "SpeechRecognizer startListening failed: ${e.message}", e)
            isManualFlow = false
            startListeningWithWhisper()  // 실패 시 Whisper 폴백
        }
    }

    fun stopListeningManually() {
        if (!isManualFlow) return
        Log.d("VoiceViewModel", "stopListeningManually()")
        speechRecognizer?.stopListening()
        // onEndOfSpeech → onResults 콜백으로 이어짐
    }

    // ─────────────────────────────────────────────
    // Whisper 폴백 — 레시피 검색 등 긴 문장용
    // ─────────────────────────────────────────────

    private fun startListeningWithWhisper() {
        Log.d("VoiceViewModel", "startListeningWithWhisper()")
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
            recordingJob = viewModelScope.launch { runSilenceDetectionLoop(recorder) }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "Whisper MediaRecorder failed: ${e.message}", e)
            _uiState.value = VoiceUiState.Error("마이크를 시작할 수 없습니다.")
            returnToIdle()
        }
    }

    private suspend fun runSilenceDetectionLoop(recorder: MediaRecorder) {
        val pollIntervalMs = 80L
        val ampThreshold = 200
        val silenceTimeoutMs = 500L
        val maxRecordingMs = 8000L
        val startedAt = System.currentTimeMillis()
        var lastSpeechTime = startedAt
        var speechDetectedAt = 0L
        var firstPoll = true
        var lastTickSec = 0

        while (true) {
            delay(pollIntervalMs)
            val now = System.currentTimeMillis()
            val elapsed = now - startedAt

            val tickSec = (elapsed / 1000L).toInt()
            if (tickSec != lastTickSec) { lastTickSec = tickSec; _recordingDurationSeconds.value = tickSec }

            if (elapsed >= maxRecordingMs) { stopWhisperRecording(); return }

            val amplitude = try { recorder.maxAmplitude } catch (e: Exception) { return }
            if (firstPoll) { firstPoll = false; continue }
            if (amplitude > peakAmplitude) peakAmplitude = amplitude
            if (amplitude > ampThreshold) {
                lastSpeechTime = now
                if (speechDetectedAt == 0L) speechDetectedAt = now
            }

            val silenceCheckStart = if (speechDetectedAt > 0L) speechDetectedAt + 200L
            else startedAt + 300L

            if (now >= silenceCheckStart && (now - lastSpeechTime) > silenceTimeoutMs) {
                stopWhisperRecording()
                return
            }
        }
    }

    private fun stopWhisperRecording() {
        recordingJob?.cancel()
        val durationMs = System.currentTimeMillis() - recordingStartMs
        val capturedPeak = peakAmplitude

        try { mediaRecorder?.apply { stop(); release() } } catch (e: Exception) {
            Log.e("VoiceViewModel", "MediaRecorder stop failed: ${e.message}", e)
        }
        mediaRecorder = null

        if (durationMs < 500L || capturedPeak < 200) {
            viewModelScope.launch {
                isManualFlow = false
                _uiState.value = VoiceUiState.Error(
                    if (durationMs < 500L) "너무 짧아요. 다시 말씀해주세요." else "음성이 감지되지 않았어요."
                )
                delay(1500)
                returnToIdle()
            }
            return
        }

        _uiState.value = VoiceUiState.Processing
        viewModelScope.launch(Dispatchers.IO) {
            if (manualAudioFile.exists()) transcribeWhisperAudio(manualAudioFile)
            else withContext(Dispatchers.Main) {
                _uiState.value = VoiceUiState.Error("녹음 파일이 없습니다.")
                delay(1500)
                returnToIdle()
            }
        }
    }

    private fun transcribeWhisperAudio(audioFile: File) {
        openAiManager.transcribeAudio(audioFile) { recognizedText ->
            viewModelScope.launch {
                val text = recognizedText.orEmpty().trim()
                Log.d("VoiceViewModel", "Whisper STT: '$text'")
                if (text.length < 2 || isHallucinatedTranscript(text) || hasExcessiveRepetition(text)) {
                    isManualFlow = false
                    _uiState.value = VoiceUiState.Error("음성을 인식하지 못했어요.")
                    delay(1500)
                    returnToIdle()
                    return@launch
                }
                val intent = classifyManualIntent(text)
                isManualFlow = false
                returnToIdle()
                _recognizedTextEvent.emit(text)
                _intentEvent.emit(intent)
            }
        }
    }

    // ─────────────────────────────────────────────
    // 인텐트 분류
    // ─────────────────────────────────────────────

    private fun classifyManualIntent(text: String): TomaIntent {
        val t = text.trim().lowercase(Locale.ROOT)

        if (t.contains("다음") || t.contains("넘어가") || t.contains("next"))
            return TomaIntent.NEXT_STEP

        if (t.contains("이전") || t.contains("돌아가") || t.contains("뒤로") || t.contains("back"))
            return TomaIntent.PREVIOUS_STEP

        if (t.contains("다시") || t.contains("반복") || t.contains("한번 더") || t.contains("한 번 더"))
            return TomaIntent.REPEAT_STEP

        val timerStopKeywords = listOf("취소", "꺼줘", "꺼", "중지", "멈춰", "그만", "stop")
        if ((t.contains("타이머") || t.contains("알람")) && timerStopKeywords.any { t.contains(it) })
            return TomaIntent.CANCEL_TIMER

        val minuteMatch = "([0-9]+)\\s*분".toRegex().find(t)
        if (minuteMatch != null) {
            val min = minuteMatch.groupValues[1].toIntOrNull() ?: 1
            val timerTriggers = listOf("타이머", "알람", "맞춰", "설정", "해줘", "바꿔", "으로", "시작", "켜줘")
            if (timerTriggers.any { t.contains(it) }) return TomaIntent.SET_TIMER(min)
        }

        if (t.contains("타이머") || t.contains("알람")) return TomaIntent.RECOMMENDED_TIMER

        if (t.contains("취소") || t.contains("그만") || t.contains("멈춰") || t.contains("stop"))
            return TomaIntent.CANCEL

        if (t.contains("레시피") || t.contains("요리")) {
            val keyword = t.replace("레시피", "").replace("요리", "")
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
        return (words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0) > 3
    }

    // ─────────────────────────────────────────────
    // 오디오 캡처 제어
    // ─────────────────────────────────────────────

    fun pauseAudioCapture() {
        wakeWordManager.disarm()
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.stopCapture() }
    }

    fun resumeAudioCapture() {
        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) { audioStreamManager.startCapture() }
    }

    fun onMicClick() {
        when (_uiState.value) {
            VoiceUiState.Idle -> startListeningManually()
            VoiceUiState.Listening -> stopListeningManually()
            else -> {}
        }
    }

    // ─────────────────────────────────────────────
    // 타이머
    // ─────────────────────────────────────────────

    fun triggerTimer(minutes: Int) {
        viewModelScope.launch {
            val wasRunning = _isTimerRunning.value
            if (wasRunning) { cancelTimerSilently(); delay(200) }

            timerOriginalSeconds = minutes * 60
            timerStartedAtStep = currentStepIndex

            val intent = Intent(getApplication(), TimerService::class.java).apply {
                action = "START"; putExtra("minutes", minutes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                getApplication<Application>().startForegroundService(intent)
            else
                getApplication<Application>().startService(intent)

            _uiState.value = VoiceUiState.Result(
                if (wasRunning) "${minutes}분 타이머로 바꿨어요." else "${minutes}분 타이머를 시작합니다."
            )
            delay(2000)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    fun cancelTimer() {
        cancelTimerSilently()
        _uiState.value = VoiceUiState.Result("타이머를 취소했습니다.")
        viewModelScope.launch {
            delay(1500)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    private fun cancelTimerSilently() {
        getApplication<Application>().startService(
            Intent(getApplication(), TimerService::class.java).apply { action = "STOP" }
        )
        timerStartedAtStep = -1
        timerOriginalSeconds = 0
    }

    fun onStepChanged(newIndex: Int) {
        currentStepIndex = newIndex
        if (!_isTimerRunning.value || timerStartedAtStep < 0) return
        val remaining = _timerRemainingSeconds.value
        val stepDelta = newIndex - timerStartedAtStep
        val percentRemaining = if (timerOriginalSeconds > 0) remaining.toFloat() / timerOriginalSeconds else 0f

        when {
            stepDelta >= 2 -> {
                cancelTimerSilently()
                viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 종료했어요.") }
            }
            stepDelta == 1 && percentRemaining > 0.8f -> cancelTimerSilently()
            stepDelta == 1 && percentRemaining > 0.2f -> {
                cancelTimerSilently()
                viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 취소했어요.") }
            }
        }
    }

    fun showTimerManualGuidance() {
        viewModelScope.launch {
            _uiState.value = VoiceUiState.Result("이 단계에는 조리 시간이 없네요. \"3분 타이머 맞춰줘\"와 같이 직접 말씀해주세요!")
            delay(4000)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    // ─────────────────────────────────────────────
    // 생명주기
    // ─────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        onStopTtsRequest?.invoke()
        wakeWordManager.disarm()
        audioStreamManager.stopCapture()
        realtimeManager.disconnect()
        wakeWordManager.release()
        speechRecognizer?.destroy()
        speechRecognizer = null
        try { getApplication<Application>().unbindService(serviceConnection) } catch (_: Exception) {}
    }

    fun startEnrollmentRecording(context: Context) {}
    fun stopEnrollmentRecording() {}
    fun uploadEnrollmentWavs(context: Context, onComplete: () -> Unit, onError: (String) -> Unit) { onComplete() }
    fun startModelPolling(context: Context) {}
}