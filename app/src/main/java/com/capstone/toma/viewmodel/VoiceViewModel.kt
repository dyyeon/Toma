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

            recordingJob?.cancel()
            recordingJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _recordingDurationSeconds.value += 1
                    if (_recordingDurationSeconds.value >= 30) {
                        stopListeningManually()
                        break
                    }
                }
            }

            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "Failed to start MediaRecorder: ${e.message}")
            _uiState.value = VoiceUiState.Error("마이크를 시작할 수 없습니다.")
            resumeAudioCapture()
        }
    }

    fun stopListeningManually() {
        if (!isManualFlow || mediaRecorder == null) return

        Log.d("VoiceViewModel", "Stopping manual recording flow")
        recordingJob?.cancel()

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceViewModel", "MediaRecorder stop failed: ${e.message}")
        }
        mediaRecorder = null
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

    private fun transcribeManualAudio(audioFile: File) {
        openAiManager.transcribeAudio(audioFile) { recognizedText ->
            viewModelScope.launch {
                val text = recognizedText.orEmpty().trim()
                if (text.isBlank()) {
                    _uiState.value = VoiceUiState.Error("음성을 인식하지 못했어요. 다시 시도해주세요.")
                    delay(2000)
                    _uiState.value = VoiceUiState.Idle
                    resumeAudioCapture()
                    return@launch
                }
                _uiState.value = VoiceUiState.Result(text)
                delay(1200)
                _recognizedTextEvent.emit(text)
                isManualFlow = false
                _uiState.value = VoiceUiState.Idle
                resumeAudioCapture()
            }
        }
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
