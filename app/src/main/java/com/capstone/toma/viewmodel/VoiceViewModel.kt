package com.capstone.toma.viewmodel

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
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

/**
 * CHANGED: openWakeWord migration - Integrated State Machine & Realtime API
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _intentEvent = kotlinx.coroutines.flow.MutableSharedFlow<TomaIntent>()
    val intentEvent = _intentEvent.asSharedFlow()

    private val audioStreamManager = AudioStreamManager()
    private val wakeWordManager = WakeWordManager(application) {
        onWakeWordDetected()
    }
    private val timerManager = TimerManager(application)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    
    // API Key from BuildConfig (set in build.gradle.kts)
    private val realtimeManager = OpenAiRealtimeManager(
        apiKey = BuildConfig.OPENAI_API_KEY,
        onResult = { jsonResponse -> handleAiIntent(jsonResponse) },
        onError = { error -> _uiState.value = VoiceUiState.Error(error) }
    )

    // 등록용 wav 파일 저장 목록 (Thread-safe)
    private val enrollmentWavFiles = java.util.Collections.synchronizedList(mutableListOf<File>())
    private val _enrollmentCount = MutableStateFlow(0)
    val enrollmentCount: StateFlow<Int> = _enrollmentCount.asStateFlow()
    private val enrollmentBuffer = ByteArrayOutputStream()
    private val ENROLLMENT_CHUNK_BYTES = 64000 // 2 seconds at 16kHz mono 16-bit

    init {
        wakeWordManager.verboseLogging = true
        audioStreamManager.startCapture()
        realtimeManager.connect()
        observeAudioStream()
    }

    private fun observeAudioStream() {
        viewModelScope.launch {
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
        if (_uiState.value == VoiceUiState.Idle) {
            _uiState.value = VoiceUiState.Listening
            // Play earcon to notify user
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            
            // Auto-timeout if no speech detected for 5 seconds
            viewModelScope.launch {
                delay(5000)
                if (_uiState.value == VoiceUiState.Listening) {
                    _uiState.value = VoiceUiState.Idle
                }
            }
        }
    }

    private fun handleAiIntent(jsonResponse: String) {
        val intent = TomaIntentParser.parse(jsonResponse)
        _uiState.value = VoiceUiState.Processing
        
        Log.d("VoiceViewModel", "Intent Parsed: $intent")

        viewModelScope.launch {
            _intentEvent.emit(intent)
        }

        when (intent) {
            is TomaIntent.SET_TIMER -> {
                timerManager.setTimer(intent.durationMin)
                _uiState.value = VoiceUiState.Result("${intent.durationMin}분 타이머를 맞췄어요.")
            }
            TomaIntent.NEXT_STEP -> {
                _uiState.value = VoiceUiState.Result("다음 단계로 넘어갑니다.")
                // TODO: Link with RecipeDetail screen action
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
                return
            }
            else -> {
                _uiState.value = VoiceUiState.Error("명령을 이해하지 못했어요.")
            }
        }

        // Transition to SPEAKING then back to IDLE after a short delay
        viewModelScope.launch {
            _uiState.value = VoiceUiState.Speaking
            delay(3000)
            _uiState.value = VoiceUiState.Idle
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

    fun startEnrollmentRecording() {
        // AudioStreamManager에 enrollment 모드 시작
        audioStreamManager.startEnrollmentMode { wavBytes ->
            synchronized(enrollmentBuffer) {
                if (_enrollmentCount.value < 30) {
                    enrollmentBuffer.write(wavBytes)
                    
                    while (enrollmentBuffer.size() >= ENROLLMENT_CHUNK_BYTES) {
                        val allBytes = enrollmentBuffer.toByteArray()
                        val pcmBytes = allBytes.take(ENROLLMENT_CHUNK_BYTES).toByteArray()
                        
                        // 남은 데이터 보관
                        enrollmentBuffer.reset()
                        if (allBytes.size > ENROLLMENT_CHUNK_BYTES) {
                            enrollmentBuffer.write(allBytes, ENROLLMENT_CHUNK_BYTES, allBytes.size - ENROLLMENT_CHUNK_BYTES)
                        }
                        
                        val context = getApplication<Application>().applicationContext
                        val file = File(context.filesDir, "enrollment_${_enrollmentCount.value + 1}.wav")
                        saveAsWav(pcmBytes, file)
                        enrollmentWavFiles.add(file)
                        _enrollmentCount.value += 1
                        
                        // 30개가 다 차면 자동으로 중단
                        if (_enrollmentCount.value >= 30) {
                            stopEnrollmentRecording()
                            break
                        }
                    }
                }
            }
        }
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

                filesToUpload.forEachIndexed { index, file ->
                    val fileName = "me_${String.format(Locale.US, "%03d", index + 1)}.wav"
                    val storageRef = storage.reference
                        .child("users/$userId/recordings/$fileName")

                    storageRef.putFile(Uri.fromFile(file)).await()
                    Log.d("Enrollment", "✅ 업로드 성공 (${index + 1}/30): $fileName")
                }

                Log.d("Enrollment", "✅ 모든 파일 업로드 완료")
                
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

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        audioStreamManager.stopCapture()
        realtimeManager.disconnect()
        wakeWordManager.release()
    }
}
