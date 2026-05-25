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

    val wakeWordManager = WakeWordManager(application, onDevicePersonalizer) {
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
                delay(1500)
                forceResetToIdle("realtime_error")
            }
        },
        onSessionReady = {
            if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                viewModelScope.launch {
                    _uiState.value = VoiceUiState.Result("토마 준비됐어요!")
                    delay(1000)
                    if (_uiState.value is VoiceUiState.Result) {
                        _uiState.value = VoiceUiState.Listening
                    }
                }
            }
        }
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private var isManualFlow = false

    val manualAudioFile = File(application.cacheDir, "manual_voice_search.m4a")
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

    private val _isTimerVisible = MutableStateFlow(false)
    val isTimerVisible: StateFlow<Boolean> = _isTimerVisible.asStateFlow()

    private val _recommendedTimerMinutes = MutableStateFlow<Int?>(null)
    val recommendedTimerMinutes: StateFlow<Int?> = _recommendedTimerMinutes.asStateFlow()

    private var timerOriginalSeconds = 0
    private var timerStartedAtStep = -1
    private var currentStepIndex = -1

    var onStopTtsRequest: (() -> Unit)? = null

    @Volatile
    var isTtsSpeaking: Boolean = false

    @Volatile
    var isManualListening: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            val srv = binder.getService()
            timerService = srv
            Log.d("VoiceViewModel", "TimerService connected")

            viewModelScope.launch {
                srv.remainingSeconds.collect { remaining ->
                    _timerRemainingSeconds.value = remaining
                    if (remaining <= 0 && !_isTimerRunning.value) {
                        Log.d("VoiceViewModel", "Timer finished -> hide timer")
                        timerStartedAtStep = -1
                        timerOriginalSeconds = 0
                        _isTimerVisible.value = false
                    }
                }
            }

            viewModelScope.launch {
                srv.isTimerRunning.collect { running ->
                    _isTimerRunning.value = running
                    if (!running && _timerRemainingSeconds.value <= 0) {
                        timerStartedAtStep = -1
                        timerOriginalSeconds = 0
                        _isTimerVisible.value = false
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            timerService = null
            _isTimerRunning.value = false
            _isTimerVisible.value = false
        }
    }

    init {
        wakeWordManager.verboseLogging = true
        observeAudioStream()

        val intent = Intent(application, TimerService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        initSpeechRecognizer(application)
    }

    private fun initSpeechRecognizer(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("VoiceViewModel", "SpeechRecognizer not available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("VoiceViewModel", "SpeechRecognizer: ready")
                viewModelScope.launch {
                    _uiState.value = VoiceUiState.Listening
                }
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }

            override fun onBeginningOfSpeech() {
                Log.d("VoiceViewModel", "SpeechRecognizer: speech started")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                Log.d("VoiceViewModel", "SpeechRecognizer: end of speech")
                viewModelScope.launch {
                    _uiState.value = VoiceUiState.Processing
                }
            }

            override fun onError(error: Int) {
                Log.e("VoiceViewModel", "SpeechRecognizer error=$error")
                viewModelScope.launch {
                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            forceResetToIdle("recognizer_busy")
                        }

                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            _uiState.value = VoiceUiState.Error("다시 말씀해주세요.")
                            delay(1000)
                            forceResetToIdle("speech_timeout_or_no_match")
                        }

                        else -> {
                            _uiState.value = VoiceUiState.Error("음성 인식 오류가 발생했어요.")
                            delay(1000)
                            forceResetToIdle("speech_error_$error")
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim().orEmpty()
                Log.d("VoiceViewModel", "SpeechRecognizer result='$text'")

                viewModelScope.launch {
                    if (text.length < 2 || isHallucinatedTranscript(text) || hasExcessiveRepetition(text)) {
                        _uiState.value = VoiceUiState.Error("다시 말씀해주세요.")
                        delay(1000)
                        forceResetToIdle("speech_guard_fail")
                        return@launch
                    }

                    val intent = classifyManualIntent(text)
                    _recognizedTextEvent.emit(text)
                    _intentEvent.emit(intent)
                    forceResetToIdle("speech_result_done")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun observeAudioStream() {
        viewModelScope.launch(Dispatchers.IO) {
            for (pcmData in audioStreamManager.pcmChannel) {
                if (_uiState.value == VoiceUiState.Idle && !isManualListening && !isTtsSpeaking) {
                    wakeWordManager.processFrame(pcmData)
                }

                if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                    realtimeManager.sendAudio(pcmData)
                }
            }
        }
    }

    private fun onWakeWordDetected() {
        viewModelScope.launch {
            Log.d(
                "VoiceViewModel",
                "onWakeWordDetected uiState=${_uiState.value}, isTtsSpeaking=$isTtsSpeaking, isManualListening=$isManualListening"
            )

            if (_uiState.value != VoiceUiState.Idle) return@launch
            if (isManualListening) return@launch

            wakeWordManager.disarm()
            onStopTtsRequest?.invoke()

            isManualFlow = false
            isManualListening = false
            isTtsSpeaking = false

            _uiState.value = VoiceUiState.Listening
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)

            delay(8000)
            if (_uiState.value == VoiceUiState.Listening && !isManualFlow) {
                forceResetToIdle("wakeword_timeout")
            }
        }
    }

    private fun handleAiIntent(jsonResponse: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val intent = TomaIntentParser.parse(jsonResponse)
                Log.d("VoiceViewModel", "Parsed intent=$intent")

                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Processing
                }

                _intentEvent.emit(intent)

                withContext(Dispatchers.Main) {
                    when (intent) {
                        is TomaIntent.SET_TIMER -> triggerTimer(intent.durationMin)
                        TomaIntent.NEXT_STEP -> _uiState.value = VoiceUiState.Result("다음 단계로 넘어갑니다.")
                        TomaIntent.PREVIOUS_STEP -> _uiState.value = VoiceUiState.Result("이전 단계로 돌아갑니다.")
                        TomaIntent.REPEAT_STEP -> _uiState.value = VoiceUiState.Result("다시 읽어드릴게요.")
                        TomaIntent.RECOMMENDED_TIMER -> _uiState.value = VoiceUiState.Result("추천 타이머를 확인할게요.")
                        TomaIntent.CANCEL_TIMER -> cancelTimer()
                        is TomaIntent.RECIPE_SEARCH -> _uiState.value = VoiceUiState.Result("'${intent.keyword}' 레시피를 찾아볼게요.")
                        TomaIntent.CANCEL -> forceResetToIdle("cancel_intent")
                        else -> _uiState.value = VoiceUiState.Error("명령을 이해하지 못했어요.")
                    }
                }

                delay(1200)
                if (_uiState.value !is VoiceUiState.Listening) {
                    withContext(Dispatchers.Main) {
                        forceResetToIdle("ai_intent_done")
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "handleAiIntent failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Error("오류가 발생했습니다.")
                }
                delay(1200)
                withContext(Dispatchers.Main) {
                    forceResetToIdle("ai_exception")
                }
            }
        }
    }

    fun startWakeWord() {
        Log.d("VoiceViewModel", "startWakeWord()")
        _uiState.value = VoiceUiState.Idle
        isManualFlow = false
        isManualListening = false

        if (!isTtsSpeaking) {
            wakeWordManager.arm()
            viewModelScope.launch(Dispatchers.IO) {
                realtimeManager.connect()
                audioStreamManager.startCapture()
            }
        }
    }

    fun stopWakeWord() {
        Log.d("VoiceViewModel", "stopWakeWord()")
        wakeWordManager.disarm()
        isManualFlow = false
        isManualListening = false
        _uiState.value = VoiceUiState.Idle

        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
            realtimeManager.disconnect()
        }
    }

    fun onAppBackground() {
        Log.d("VoiceViewModel", "onAppBackground()")
        onStopTtsRequest?.invoke()
        wakeWordManager.disarm()
        speechRecognizer?.cancel()

        isManualFlow = false
        isManualListening = false
        _uiState.value = VoiceUiState.Idle

        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
        }
    }

    fun onAppForeground() {
        Log.d("VoiceViewModel", "onAppForeground()")
        if (!isTtsSpeaking && !isManualListening) {
            wakeWordManager.arm()
            viewModelScope.launch(Dispatchers.IO) {
                audioStreamManager.startCapture()
            }
        }
    }

    fun pauseAudioCapture() {
        Log.d("WakeWord", "pauseAudioCapture()")
        wakeWordManager.disarm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
        }
    }

    fun resumeAudioCapture() {
        Log.d(
            "WakeWord",
            "resumeAudioCapture() isTtsSpeaking=$isTtsSpeaking, isManualListening=$isManualListening, uiState=${_uiState.value}"
        )

        if (isTtsSpeaking) {
            Log.d("WakeWord", "⛔ Re-arm blocked: TTS still speaking")
            return
        }

        if (isManualListening) {
            Log.d("WakeWord", "⛔ Re-arm blocked: manual listening active")
            return
        }

        if (_uiState.value == VoiceUiState.Listening && isManualFlow) {
            Log.d("WakeWord", "⛔ Re-arm blocked: manual flow currently listening")
            return
        }

        wakeWordManager.arm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.startCapture()
        }
    }

    fun startListeningManually() {
        Log.d(
            "Mic",
            "startListeningManually() uiState=${_uiState.value}, isTtsSpeaking=$isTtsSpeaking, isManualListening=$isManualListening"
        )

        onStopTtsRequest?.invoke()
        isTtsSpeaking = false

        speechRecognizer?.cancel()
        stopWhisperRecordingSilently()

        wakeWordManager.disarm()
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
        }

        isManualFlow = true
        isManualListening = true
        _uiState.value = VoiceUiState.Listening

        if (speechRecognizer == null) {
            startListeningWithWhisper()
            return
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "SpeechRecognizer start failed", e)
            startListeningWithWhisper()
        }
    }

    fun stopListeningManually() {
        Log.d("Mic", "stopListeningManually()")
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        stopWhisperRecordingSilently()
        viewModelScope.launch {
            forceResetToIdle("manual_stop")
        }
    }

    private fun startListeningWithWhisper() {
        Log.d("VoiceViewModel", "startListeningWithWhisper()")

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
            recordingStartMs = System.currentTimeMillis()
            peakAmplitude = 0
            _recordingDurationSeconds.value = 0
            _uiState.value = VoiceUiState.Listening

            recordingJob?.cancel()
            recordingJob = viewModelScope.launch {
                runSilenceDetectionLoop(recorder)
            }
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "Whisper recorder failed", e)
            viewModelScope.launch {
                _uiState.value = VoiceUiState.Error("마이크를 시작할 수 없습니다.")
                delay(1000)
                forceResetToIdle("whisper_start_fail")
            }
        }
    }

    private suspend fun runSilenceDetectionLoop(recorder: MediaRecorder) {
        val pollIntervalMs = 80L
        val ampThreshold = 200
        val silenceTimeoutMs = 600L
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
            if (tickSec != lastTickSec) {
                lastTickSec = tickSec
                _recordingDurationSeconds.value = tickSec
            }

            if (elapsed >= maxRecordingMs) {
                stopWhisperRecording()
                return
            }

            val amplitude = try {
                recorder.maxAmplitude
            } catch (e: Exception) {
                return
            }

            if (firstPoll) {
                firstPoll = false
                continue
            }

            if (amplitude > peakAmplitude) peakAmplitude = amplitude

            if (amplitude > ampThreshold) {
                lastSpeechTime = now
                if (speechDetectedAt == 0L) speechDetectedAt = now
            }

            val silenceCheckStart =
                if (speechDetectedAt > 0L) speechDetectedAt + 200L else startedAt + 300L

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

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null

        if (durationMs < 500L || capturedPeak < 200) {
            viewModelScope.launch {
                _uiState.value = VoiceUiState.Error("다시 말씀해주세요.")
                delay(1000)
                forceResetToIdle("whisper_too_short")
            }
            return
        }

        _uiState.value = VoiceUiState.Processing

        viewModelScope.launch(Dispatchers.IO) {
            if (manualAudioFile.exists()) {
                transcribeWhisperAudio(manualAudioFile)
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Error("녹음 파일이 없습니다.")
                    delay(1000)
                    forceResetToIdle("whisper_file_missing")
                }
            }
        }
    }

    private fun stopWhisperRecordingSilently() {
        recordingJob?.cancel()
        recordingJob = null

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null
    }

    private fun transcribeWhisperAudio(audioFile: File) {
        openAiManager.transcribeAudio(audioFile) { recognizedText ->
            viewModelScope.launch {
                val text = recognizedText.orEmpty().trim()
                Log.d("VoiceViewModel", "Whisper result='$text'")

                if (text.length < 2 || isHallucinatedTranscript(text) || hasExcessiveRepetition(text)) {
                    _uiState.value = VoiceUiState.Error("음성을 인식하지 못했어요.")
                    delay(1000)
                    forceResetToIdle("whisper_guard_fail")
                    return@launch
                }

                val intent = classifyManualIntent(text)
                _recognizedTextEvent.emit(text)
                _intentEvent.emit(intent)
                forceResetToIdle("whisper_done")
            }
        }
    }

    private fun classifyManualIntent(text: String): TomaIntent {
        val t = text.trim().lowercase(Locale.ROOT)

        if (t.contains("다음") || t.contains("넘어가") || t.contains("next"))
            return TomaIntent.NEXT_STEP

        if (t.contains("이전") || t.contains("돌아가") || t.contains("뒤로") || t.contains("back"))
            return TomaIntent.PREVIOUS_STEP

        if (t.contains("다시") || t.contains("반복") || t.contains("한번 더") || t.contains("한 번 더"))
            return TomaIntent.REPEAT_STEP

        val hasTimerWord = t.contains("타이머") || t.contains("알람")
        val cancelWords = listOf("취소", "꺼줘", "꺼", "중지", "멈춰", "그만", "stop")
        val hasCancelWord = cancelWords.any { t.contains(it) }

        if (hasTimerWord && hasCancelWord) {
            return TomaIntent.CANCEL_TIMER
        }

        // 숫자 파싱 없이, 타이머 관련 명령은 전부 추천 타이머로만 처리
        if (hasTimerWord) {
            return TomaIntent.RECOMMENDED_TIMER
        }

        if (t.contains("취소") || t.contains("그만") || t.contains("멈춰") || t.contains("stop"))
            return TomaIntent.CANCEL

        if (t.contains("레시피") || t.contains("요리")) {
            val keyword = t.replace("레시피", "")
                .replace("요리", "")
                .replace("찾아", "")
                .replace("줘", "")
                .trim()
            if (keyword.isNotBlank()) return TomaIntent.RECIPE_SEARCH(keyword)
        }

        return TomaIntent.UNKNOWN
    }

    private fun isHallucinatedTranscript(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return hallucinationKeywords.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun hasExcessiveRepetition(text: String): Boolean {
        val words =
            text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 4) return false
        return (words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0) > 3
    }

    private fun forceResetToIdle(reason: String) {
        Log.d(
            "VoiceViewModel",
            "forceResetToIdle($reason) | uiState=${_uiState.value}, isTtsSpeaking=$isTtsSpeaking, isManualListening=$isManualListening"
        )

        isManualFlow = false
        isManualListening = false
        _uiState.value = VoiceUiState.Idle

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        stopWhisperRecordingSilently()

        if (!isTtsSpeaking) {
            wakeWordManager.arm()
            viewModelScope.launch(Dispatchers.IO) {
                audioStreamManager.startCapture()
            }
        }
    }

    fun onMicClick() {
        Log.d(
            "Mic",
            "onMicClick uiState=${_uiState.value}, isTtsSpeaking=$isTtsSpeaking, isManualListening=$isManualListening"
        )
        startListeningManually()
    }

    fun setRecommendedTimerForCurrentStep(minutes: Int?) {
        _recommendedTimerMinutes.value = minutes
        Log.d("VoiceViewModel", "recommendedTimer=$minutes")
    }

    fun toggleRecommendedTimer() {
        val minutes = _recommendedTimerMinutes.value
        if (minutes == null || minutes <= 0) {
            Log.d("VoiceViewModel", "No recommended timer for current step")
            return
        }

        val sameStepRunning = _isTimerRunning.value && timerStartedAtStep == currentStepIndex
        if (sameStepRunning) {
            cancelTimerSilently()
            _isTimerVisible.value = false
            return
        }

        triggerTimer(minutes)
        _isTimerVisible.value = true
    }

    fun triggerTimer(minutes: Int) {
        viewModelScope.launch {
            val wasRunning = _isTimerRunning.value
            if (wasRunning) {
                cancelTimerSilently()
                delay(200)
            }

            timerOriginalSeconds = minutes * 60
            _timerRemainingSeconds.value = timerOriginalSeconds
            timerStartedAtStep = currentStepIndex
            _isTimerVisible.value = true

            val intent = Intent(getApplication(), TimerService::class.java).apply {
                action = "START"
                putExtra("minutes", minutes)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }

            _uiState.value = VoiceUiState.Result(
                if (wasRunning) "${minutes}분 타이머로 바꿨어요." else "${minutes}분 타이머를 시작합니다."
            )

            delay(1000)
            if (_uiState.value is VoiceUiState.Result) {
                forceResetToIdle("timer_trigger_done")
            }
        }
    }

    fun adjustTimer(deltaSeconds: Int) {
        val current = _timerRemainingSeconds.value
        val next = (current + deltaSeconds).coerceAtLeast(0)
        if (next == current) return

        _timerRemainingSeconds.value = next
        
        if (_isTimerRunning.value) {
            val intent = Intent(getApplication(), TimerService::class.java).apply {
                action = "START"
                putExtra("minutes", next / 60)
                putExtra("seconds", next % 60)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        }
    }

    fun toggleTimerState() {
        if (_isTimerRunning.value) {
            cancelTimerSilently()
        } else {
            val mins = _timerRemainingSeconds.value / 60
            if (mins > 0) triggerTimer(mins)
        }
    }

    fun hideTimerCard() {
        cancelTimerSilently()
        _isTimerVisible.value = false
    }

    fun showTimerForStep(minutes: Int) {
        _timerRemainingSeconds.value = minutes * 60
        timerOriginalSeconds = minutes * 60
        timerStartedAtStep = currentStepIndex
        _isTimerVisible.value = true
    }

    fun cancelTimer() {
        cancelTimerSilently()
        _isTimerVisible.value = false
        _uiState.value = VoiceUiState.Result("타이머를 취소했습니다.")

        viewModelScope.launch {
            delay(1000)
            if (_uiState.value is VoiceUiState.Result) {
                forceResetToIdle("timer_cancel_done")
            }
        }
    }

    private fun cancelTimerSilently() {
        getApplication<Application>().startService(
            Intent(getApplication(), TimerService::class.java).apply { action = "STOP" }
        )
        timerStartedAtStep = -1
        timerOriginalSeconds = 0
        _isTimerVisible.value = false
    }

    fun onStepChanged(newIndex: Int) {
        currentStepIndex = newIndex

        if (!_isTimerRunning.value || timerStartedAtStep < 0) {
            _isTimerVisible.value = false
            return
        }

        if (timerStartedAtStep != currentStepIndex) {
            cancelTimerSilently()
            viewModelScope.launch {
                _voiceAnnouncement.emit("이전 단계 타이머를 종료했어요.")
            }
        }
    }

    fun showTimerManualGuidance() {
        viewModelScope.launch {
            _uiState.value = VoiceUiState.Result("이 단계에는 조리 시간이 없네요.")
            delay(1500)
            if (_uiState.value is VoiceUiState.Result) {
                forceResetToIdle("timer_manual_guidance_done")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        onStopTtsRequest?.invoke()
        wakeWordManager.disarm()
        audioStreamManager.stopCapture()
        realtimeManager.disconnect()
        wakeWordManager.release()

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {
        }
    }

}
