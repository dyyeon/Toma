package com.capstone.toma.viewmodel

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CHANGED: openWakeWord migration - Integrated State Machine & Realtime API
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    // Enrollment Status
    sealed interface EnrollmentStatus {
        data object Idle : EnrollmentStatus
        data object CollectingAmbient : EnrollmentStatus // NEW: Step 1 of enrollment
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

    // Announcements spoken by the screen's TTS engine (e.g. timer replacement notices)
    private val _voiceAnnouncement = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val voiceAnnouncement = _voiceAnnouncement.asSharedFlow()

    private val openAiManager = OpenAiManager()

    private val audioStreamManager = AudioStreamManager(application)
    private val onDevicePersonalizer = OnDevicePersonalizer(application)
    private val wakeWordManager = WakeWordManager(application, onDevicePersonalizer) {
        onWakeWordDetected()
    }
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    
    // API Key from BuildConfig (set in build.gradle.kts)
    private val realtimeManager = OpenAiRealtimeManager(
        apiKey = BuildConfig.OPENAI_API_KEY,
        onResult = { jsonResponse -> handleAiIntent(jsonResponse) },
        onError = { error ->
            viewModelScope.launch {
                _uiState.value = VoiceUiState.Error(error)
                // Error 상태에서도 3초 후 Idle로 복귀하여 다시 웨이크워드 대기
                delay(3000)
                if (_uiState.value is VoiceUiState.Error) {
                    _uiState.value = VoiceUiState.Idle
                }
            }
        }
    )

    // 등록용 wav 파일 저장 목록 (Thread-safe)
    private val enrollmentWavFiles = java.util.Collections.synchronizedList(mutableListOf<File>())
    private val _enrollmentCount = MutableStateFlow(0)
    val enrollmentCount: StateFlow<Int> = _enrollmentCount.asStateFlow()
    private val enrollmentBuffer = ByteArrayOutputStream()
    private val ENROLLMENT_CHUNK_BYTES = 64000 // 2 seconds at 16kHz mono 16-bit
    private val MAX_ENROLLMENT_SAMPLES = 10 // Reduced for on-device personalization

    // --- Timer Logic (Foreground Service) ---
    private var timerService: TimerService? = null
    private val _timerRemainingSeconds = MutableStateFlow(0)
    val timerRemainingSeconds: StateFlow<Int> = _timerRemainingSeconds.asStateFlow()
    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    // Smart-timer context: track original duration and which step started it
    private var timerOriginalSeconds = 0
    private var timerStartedAtStep = -1
    private var currentStepIndex = -1

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            val srv = binder.getService()
            timerService = srv
            viewModelScope.launch {
                srv.remainingSeconds.collect { _timerRemainingSeconds.value = it }
            }
            viewModelScope.launch {
                srv.isTimerRunning.collect { _isTimerRunning.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            timerService = null
            _isTimerRunning.value = false
        }
    }

    // Callback for RecipeDetailScreen to stop TTS
    var onStopTtsRequest: (() -> Unit)? = null

    init {
        wakeWordManager.verboseLogging = true
        observeAudioStream()
        
        // Bind to TimerService
        val intent = Intent(application, TimerService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
            withContext(Dispatchers.Main) {
                _uiState.value = VoiceUiState.Idle
            }
        }
    }

    private fun observeAudioStream() {
        viewModelScope.launch(Dispatchers.IO) {
            for (pcmData in audioStreamManager.pcmChannel) {
                // 1. Always process for WakeWord if in IDLE
                if (_uiState.value == VoiceUiState.Idle) {
                    wakeWordManager.processFrame(pcmData)
                }
                
                // 2. Stream to OpenAI if in LISTENING
                if (_uiState.value == VoiceUiState.Listening) {
                    realtimeManager.sendAudio(pcmData)
                }
            }
        }
    }

    private fun onWakeWordDetected() {
        viewModelScope.launch {
            if (_uiState.value == VoiceUiState.Idle) {
                // IMPORTANT: Stop any ongoing TTS when wake-word is detected
                onStopTtsRequest?.invoke()

                _uiState.value = VoiceUiState.Listening
                // Play earcon to notify user
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

                // Auto-timeout if no speech detected for 5 seconds
                delay(5000)
                if (_uiState.value == VoiceUiState.Listening) {
                    _uiState.value = VoiceUiState.Idle
                }
            }
        }
    }

    private fun handleAiIntent(jsonResponse: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val intent = TomaIntentParser.parse(jsonResponse)

                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Processing
                }

                Log.d("VoiceViewModel", "Intent Parsed: $intent")
                _intentEvent.emit(intent)

                withContext(Dispatchers.Main) {
                    when (intent) {
                        is TomaIntent.SET_TIMER -> {
                            if (!isNotificationPermissionGranted()) {
                                _uiState.value = VoiceUiState.Error("알림 권한이 없어 타이머 알람을 받을 수 없어요.")
                            } else {
                                triggerTimer(intent.durationMin)
                            }
                        }
                        TomaIntent.RECOMMENDED_TIMER -> {
                            _uiState.value = VoiceUiState.Result("추천 시간으로 타이머를 맞춰드릴게요.")
                        }
                        TomaIntent.NEXT_STEP -> {
                            _uiState.value = VoiceUiState.Result("다음 단계로 넘어갑니다.")
                        }
                        TomaIntent.PREVIOUS_STEP -> {
                            _uiState.value = VoiceUiState.Result("이전 단계로 돌아갑니다.")
                        }
                        TomaIntent.REPEAT_STEP -> {
                            _uiState.value = VoiceUiState.Result("다시 읽어드릴게요.")
                        }
                        is TomaIntent.RECIPE_SEARCH -> {
                            _uiState.value = VoiceUiState.Result("'${intent.keyword}' 레시피를 찾아볼게요.")
                        }
                        TomaIntent.CANCEL -> {
                            _uiState.value = VoiceUiState.Idle
                        }
                        else -> {
                            _uiState.value = VoiceUiState.Error("명령을 이해하지 못했어요.")
                        }
                    }
                }

                // Transition to SPEAKING then back to IDLE after a short delay
                val currentState = withContext(Dispatchers.Main) { _uiState.value }
                if (currentState !is VoiceUiState.Error && currentState !is VoiceUiState.Idle) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = VoiceUiState.Speaking
                    }
                    delay(3000)
                    withContext(Dispatchers.Main) {
                        _uiState.value = VoiceUiState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceViewModel", "Intent parsing error: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Error("오류가 발생했습니다.")
                }
            }
        }
    }


    private fun normalizeRecognizedText(text: String): String {
        return text.trim()
    }

    fun transcribeAudioAndProcess(audioFile: File) {
        openAiManager.transcribeAudio(audioFile) { recognizedText ->
            viewModelScope.launch(Dispatchers.Default) {
                val text = normalizeRecognizedText(recognizedText.orEmpty())
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Result(text)
                }
                delay(900)
                _recognizedTextEvent.emit(text)
            }
        }
    }

    fun startListeningManually() {
        if (_uiState.value != VoiceUiState.Idle) return
        
        // Stop any ongoing TTS when mic button is tapped
        onStopTtsRequest?.invoke()

        viewModelScope.launch(Dispatchers.IO) {
            realtimeManager.connect()
            audioStreamManager.startCapture()
            withContext(Dispatchers.Main) {
                _uiState.value = VoiceUiState.Listening
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }
        }
    }

    fun stopListeningManually() {
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
            realtimeManager.disconnect()
            withContext(Dispatchers.Main) {
                _uiState.value = VoiceUiState.Idle
            }
        }
    }

    /**
     * Pauses PCM capture so TTS can use the speaker without Samsung's hardware AEC
     * suppressing the output. Audio focus is released so TTS can claim it freely.
     * Call [resumeAudioCapture] once TTS finishes (via UtteranceProgressListener).
     */
    fun pauseAudioCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.stopCapture()
        }
    }

    /**
     * Restarts PCM capture and re-requests audio focus after TTS has finished speaking.
     */
    fun resumeAudioCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            audioStreamManager.startCapture()
        }
    }

    fun onMicClick() {
        // Manual trigger (optional, keeps current UI working)
        if (_uiState.value == VoiceUiState.Idle) {
            onWakeWordDetected()
        } else {
            _uiState.value = VoiceUiState.Idle
        }
    }

    fun onSuggestionClick(text: String) {
        _uiState.value = VoiceUiState.Result("'$text' 명령을 준비했어요.")
    }

    fun startEnrollmentRecording(context: Context) {
        if (_enrollmentStatus.value != EnrollmentStatus.Idle && _enrollmentStatus.value !is EnrollmentStatus.Success && _enrollmentStatus.value != EnrollmentStatus.Failed) return

        wakeWordManager.bypassVad = true
        viewModelScope.launch {
            // Step 1: Collect ambient noise if starting a new session
            if (_enrollmentCount.value == 0) {
                _enrollmentStatus.value = EnrollmentStatus.CollectingAmbient
                wakeWordManager.startAmbientCollection()
                delay(5000) // Collect for 5 seconds
                val negatives = wakeWordManager.stopAmbientCollection()
                onDevicePersonalizer.setNegativeSamples(negatives)
                Log.d("OnDevice", "✅ Collected ${negatives.size} native ambient samples")
            }

            // Step 2: Proceed to record positive samples
            _enrollmentStatus.value = EnrollmentStatus.Recording
            
            // AudioStreamManager에 enrollment 모드 시작
            audioStreamManager.startEnrollmentMode { wavBytes ->
                synchronized(enrollmentBuffer) {
                    if (_enrollmentCount.value < MAX_ENROLLMENT_SAMPLES) {
                        enrollmentBuffer.write(wavBytes)
                        
                        if (enrollmentBuffer.size() >= ENROLLMENT_CHUNK_BYTES) {
                            Log.d("Enrollment", "Processing frame for enrollment... (Buffer size: ${enrollmentBuffer.size()} bytes)")
                            val pcmBytes = enrollmentBuffer.toByteArray().take(ENROLLMENT_CHUNK_BYTES).toByteArray()
                            enrollmentBuffer.reset()
                            
                            // Stop recording immediately after capturing enough audio
                            audioStreamManager.stopEnrollmentMode()

                            viewModelScope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    _enrollmentStatus.value = EnrollmentStatus.Verifying
                                }

                                val tempFile = File(context.cacheDir, "enroll_verify_temp.wav")
                                saveAsWav(pcmBytes, tempFile)

                                    // FIX #10: Amplitude guard — reject silent or barely-audible
                                // recordings on-device before spending an API round-trip.
                                // RMS threshold ~800 / 32768 ≈ 2.4 % of full scale.
                                // Recordings below this are noise or an accidental tap, not speech.
                                val isLoudEnough = isPcmLoudEnough(pcmBytes)
                                val isValid = isLoudEnough && verifyWithWhisper(tempFile)
                                if (!isLoudEnough) Log.d("Enrollment", "❌ 거부됨: 음량이 너무 낮음 (RMS < 800)")

                                withContext(Dispatchers.Main) {
                                    if (isValid) {
                                        val embedding = wakeWordManager.getLastEmbedding()
                                        if (embedding != null) {
                                            onDevicePersonalizer.addPositiveSample(embedding)
                                        }

                                        val count = _enrollmentCount.value + 1
                                        val file = File(context.filesDir, "enrollment_$count.wav")
                                        tempFile.copyTo(file, overwrite = true)
                                        synchronized(enrollmentWavFiles) { enrollmentWavFiles.add(file) }
                                        
                                        _enrollmentCount.value = count
                                        _enrollmentStatus.value = EnrollmentStatus.Success(count)
                                        
                                        delay(1500) // Show success
                                        
                                        if (count >= MAX_ENROLLMENT_SAMPLES) {
                                            // Final training
                                            Log.d("Enrollment", "🎓 Starting on-device training...")
                                            if (onDevicePersonalizer.train()) {
                                                val weightsFile = File(context.filesDir, "personal_weights.bin")
                                                onDevicePersonalizer.saveToFile(weightsFile)
                                                wakeWordManager.loadPersonalWeights(onDevicePersonalizer)
                                                wakeWordManager.clearLastEmbedding()
                                                wakeWordManager.bypassVad = false
                                                _enrollmentStatus.value = EnrollmentStatus.Idle
                                            } else {
                                                wakeWordManager.bypassVad = false
                                                _enrollmentStatus.value = EnrollmentStatus.Failed
                                            }
                                        } else {
                                            _enrollmentStatus.value = EnrollmentStatus.Idle
                                        }
                                    } else {
                                        wakeWordManager.bypassVad = false
                                        _enrollmentStatus.value = EnrollmentStatus.Failed
                                        delay(1000)
                                        _enrollmentStatus.value = EnrollmentStatus.Idle
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun verifyWithWhisper(wavFile: File): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "ko")
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return false
            val text = JSONObject(body).getString("text").lowercase()

            Log.d("Enrollment", "Whisper: \"$text\"")

            // "헤이 토마" 다양한 표기 허용
            val keywords = listOf("토마", "toma", "thoma", "토 마")
            val isValid = keywords.any { text.contains(it) }

            if (!isValid) Log.d("Enrollment", "❌ 거부됨: \"$text\"")
            isValid

        } catch (e: Exception) {
            // FIX #11: Previously returned true on any exception to "avoid rejecting due to
            // network issues." This had the opposite effect: bad recordings (silent, wrong word,
            // far-field) were silently accepted whenever the device was offline, building a
            // corrupted voice profile that caused the personalised model to fail.
            // Return false and surface the error — the UI already handles EnrollmentStatus.Failed
            // with a retry prompt, which is safer than silently accepting bad data.
            Log.e("VoiceViewModel", "Whisper verification error: ${e.message}")
            false
        }
    }

    // Calculates RMS amplitude of 16-bit little-endian PCM bytes.
    // Returns true if the signal is loud enough to be recognisable speech.
    private fun isPcmLoudEnough(pcmBytes: ByteArray, minRms: Float = 800f): Boolean {
        var sumSq = 0.0
        var i = 0
        while (i < pcmBytes.size - 1) {
            val sample = ((pcmBytes[i].toInt() and 0xFF) or (pcmBytes[i + 1].toInt() shl 8)).toShort().toFloat()
            sumSq += sample * sample
            i += 2
        }
        val count = pcmBytes.size / 2
        val rms = if (count > 0) kotlin.math.sqrt(sumSq / count).toFloat() else 0f
        Log.d("Enrollment", "RMS amplitude: ${"%.1f".format(rms)} (threshold: $minRms)")
        return rms >= minRms
    }

    private fun saveAsWav(pcmBytes: ByteArray, file: File, sampleRate: Int = 16000) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = (numChannels * bitsPerSample / 8).toShort()
        val dataSize = pcmBytes.size
        val chunkSize = 36 + dataSize

        val outputStream = file.outputStream()
        val buffer = java.io.ByteArrayOutputStream()

        fun writeInt(value: Int) {
            buffer.write(value and 0xFF)
            buffer.write((value shr 8) and 0xFF)
            buffer.write((value shr 16) and 0xFF)
            buffer.write((value shr 24) and 0xFF)
        }
        fun writeShort(value: Short) {
            buffer.write(value.toInt() and 0xFF)
            buffer.write((value.toInt() shr 8) and 0xFF)
        }
        fun writeString(s: String) = buffer.write(s.toByteArray(Charsets.US_ASCII))

        // RIFF 헤더
        writeString("RIFF")
        writeInt(chunkSize)
        writeString("WAVE")

        // fmt 청크
        writeString("fmt ")
        writeInt(16)                          // Subchunk1Size
        writeShort(1)                         // AudioFormat (PCM)
        writeShort(numChannels.toShort())     // NumChannels
        writeInt(sampleRate)                  // SampleRate
        writeInt(byteRate)                    // ByteRate
        writeShort(blockAlign)                // BlockAlign
        writeShort(bitsPerSample.toShort())   // BitsPerSample

        // data 청크
        writeString("data")
        writeInt(dataSize)

        // PCM 데이터
        buffer.write(pcmBytes)

        outputStream.write(buffer.toByteArray())
        outputStream.close()
    }

    fun stopEnrollmentRecording() {
        wakeWordManager.bypassVad = false
        audioStreamManager.stopEnrollmentMode()
        enrollmentBuffer.reset()
    }

    fun uploadEnrollmentWavs(context: Context, onComplete: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val userId = UserManager.getUserId(context)
                val storage = FirebaseStorage.getInstance()

                // 로컬 리스트를 복사하여 작업 (Thread-safety)
                val filesToUpload = synchronized(enrollmentWavFiles) { enrollmentWavFiles.toList() }

                if (filesToUpload.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("업로드할 파일이 없습니다.") }
                    return@launch
                }

                val timestamp = System.currentTimeMillis()
                filesToUpload.forEachIndexed { index, file ->
                    val fileName = "me_${timestamp}_${String.format(Locale.US, "%03d", index + 1)}.wav"
                    val storageRef = storage.reference
                        .child("users/$userId/recordings/$fileName")

                    storageRef.putFile(Uri.fromFile(file)).await()
                    Log.d("Enrollment", "✅ 업로드 성공 (${index + 1}/$MAX_ENROLLMENT_SAMPLES): $fileName")
                }

                Log.d("Enrollment", "✅ 모든 파일 업로드 완료")

                // 재학습 트리거 파일 업로드 (빈 파일)
                val triggerRef = storage.reference.child("users/$userId/retrain_trigger")
                triggerRef.putBytes(ByteArray(0)).await()
                Log.d("Enrollment", "✅ 재학습 트리거 업로드")
                
                // 모든 업로드가 성공한 후에만 완료 플래그 설정
                UserManager.setHasUploaded(context, true)
                UserManager.setEnrolled(context, true)
                
                synchronized(enrollmentWavFiles) { enrollmentWavFiles.clear() }
                _enrollmentCount.value = 0

                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e("Enrollment", "❌ 업로드 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = VoiceUiState.Error("업로드 실패: ${e.message}")
                    onError(e.message ?: "알 수 없는 오류가 발생했습니다.")
                }
            }
        }
    }

    private var pollingJob: kotlinx.coroutines.Job? = null

    fun startModelPolling(context: android.content.Context) {
        val userId = UserManager.getUserId(context)
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            val storageRef = FirebaseStorage.getInstance()
                .reference
                .child("users/$userId/models/hey_toma_personal.onnx")

            val localFile = File(context.filesDir, "hey_toma_personal.onnx")

            Log.d("VoiceViewModel", "모델 폴링 시작: users/$userId/models/hey_toma_personal.onnx")
            _uiState.value = VoiceUiState.Training

            while (this.isActive) {
                try {
                    storageRef.getFile(localFile).await()
                    // 여기까지 왔으면 다운로드 성공
                    Log.d("VoiceViewModel", "✅ 개인 모델 다운로드 완료")
                    wakeWordManager.loadPersonalModel(localFile.absolutePath)
                    UserManager.setEnrolled(context, true)
                    _uiState.value = VoiceUiState.Idle
                    break // 폴링 중단
                } catch (e: Exception) {
                    Log.d("VoiceViewModel", "모델 아직 없음, 대기 중... (${e.message})")
                    delay(10000) // 10초 대기 후 재시도
                }
            }
        }
    }

    /** Returns true when the app is allowed to post notifications (required for timer alarms). */
    private fun isNotificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** --- Timer Logic (Foreground Service Integration) --- */

    fun triggerTimer(minutes: Int) {
        viewModelScope.launch {
            if (_isTimerRunning.value) {
                cancelTimerSilently()
                _voiceAnnouncement.emit("이전 타이머를 취소하고 새 타이머를 시작할게요.")
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

            _uiState.value = VoiceUiState.Result("${minutes}분 타이머를 시작합니다.")
            delay(3000)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    fun cancelTimer() {
        cancelTimerSilently()
        _uiState.value = VoiceUiState.Result("타이머를 취소했습니다.")
        viewModelScope.launch {
            delay(2000)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    /** Stops the foreground service and resets tracking state without a UI message. */
    private fun cancelTimerSilently() {
        val stopIntent = Intent(getApplication(), TimerService::class.java).apply { action = "STOP" }
        getApplication<Application>().startService(stopIntent)
        timerStartedAtStep = -1
        timerOriginalSeconds = 0
    }

    /**
     * Called by RecipeDetailScreen whenever the displayed step index changes.
     * Applies smart-cancel rules so stale timers don't outlive their cooking phase.
     */
    fun onStepChanged(newIndex: Int) {
        currentStepIndex = newIndex
        if (!_isTimerRunning.value || timerStartedAtStep < 0) return

        val remaining = _timerRemainingSeconds.value
        val stepDelta = newIndex - timerStartedAtStep
        val percentRemaining =
            if (timerOriginalSeconds > 0) remaining.toFloat() / timerOriginalSeconds else 0f

        when {
            // Jumped 2+ steps → cooking phase is completely over
            stepDelta >= 2 -> {
                cancelTimerSilently()
                viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 종료했어요.") }
            }
            // Moved 1 step forward but barely used the timer (>80% left) → skipped early, cancel silently
            stepDelta == 1 && percentRemaining > 0.8f -> cancelTimerSilently()
            // Moved 1 step forward and timer is nearly done (<20% left) → let it finish
            stepDelta == 1 && percentRemaining < 0.2f -> { /* no-op */ }
            // Moved 1 step forward in the middle range → cancel and notify
            stepDelta == 1 -> {
                cancelTimerSilently()
                viewModelScope.launch { _voiceAnnouncement.emit("이전 단계 타이머를 취소했어요.") }
            }
            // Going backward → leave timer running
        }
    }

    /** Shows a message when the user requests a timer but the recipe context doesn't specify a time. */
    fun showTimerManualGuidance() {
        viewModelScope.launch {
            _uiState.value = VoiceUiState.Result("이 단계에는 조리 시간이 없네요. \"3분 타이머 맞춰줘\"와 같이 직접 말씀해주세요!")
            delay(4000)
            if (_uiState.value is VoiceUiState.Result) _uiState.value = VoiceUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        audioStreamManager.stopCapture()
        realtimeManager.disconnect()
        wakeWordManager.release()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {}
    }
}
