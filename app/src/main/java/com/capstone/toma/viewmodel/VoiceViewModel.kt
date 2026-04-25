package com.capstone.toma.viewmodel

import android.app.Application
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.capstone.toma.OpenAiManager
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.VoiceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val openAiManager = OpenAiManager()

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState: StateFlow<VoiceUiState> = _uiState

    // 검색 결과 데이터 (다음 화면으로 전달용)
    private val _searchResult = MutableStateFlow<SearchResultData?>(null)
    val searchResult: StateFlow<SearchResultData?> = _searchResult

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    // Vosk 제어를 위한 외부 콜백
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: (() -> Unit)? = null

    fun onMicClick() {
        _uiState.update { current ->
            when (current) {
                VoiceUiState.Idle -> {
                    startRecording()
                    VoiceUiState.Listening
                }
                VoiceUiState.Listening -> {
                    stopRecordingAndProcess()
                    VoiceUiState.Processing
                }
                VoiceUiState.Processing -> {
                    // Processing 중에는 클릭 무시
                    current
                }
                is VoiceUiState.Result -> VoiceUiState.Idle
                is VoiceUiState.Error -> {
                    startRecording()
                    VoiceUiState.Listening
                }
            }
        }
    }

    private fun startRecording() {
        try {
            // 확장자를 .m4a로 변경 (AAC 포맷에 더 적합)
            audioFile = File(
                getApplication<Application>().cacheDir,
                "voice_${System.currentTimeMillis()}.m4a"
            )

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 샘플링 레이트를 44100으로 설정하되 기기 호환성을 위해 체크
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            onRecordingStarted?.invoke() // Vosk 중지 요청
            Log.d("VoiceViewModel", "녹음 시작: ${audioFile?.absolutePath}")

        } catch (e: Exception) {
            Log.e("VoiceViewModel", "녹음 시작 실패: ${e.message}")
            showError("마이크를 사용할 수 없습니다.")
        }
    }

    private fun stopRecordingAndProcess() {
        try {
            mediaRecorder?.apply {
                // 너무 빨리 멈추면 RuntimeException이 발생할 수 있음
                try {
                    stop()
                } catch (e: RuntimeException) {
                    Log.e("VoiceViewModel", "녹음 데이터 부족: ${e.message}")
                }
                release()
            }
            mediaRecorder = null
            isRecording = false
            onRecordingStopped?.invoke() // Vosk 재개 요청

            Log.d("VoiceViewModel", "녹음 중지: ${audioFile?.absolutePath}")

            // STT 처리
            audioFile?.let { file ->
                processAudioFile(file)
            } ?: run {
                showError("오디오 파일을 찾을 수 없습니다.")
            }

        } catch (e: Exception) {
            Log.e("VoiceViewModel", "녹음 중지 실패: ${e.message}")
            showError("녹음을 저장할 수 없습니다.")
        }
    }

    private fun processAudioFile(file: File) {
        // STT 호출
        openAiManager.transcribeAudio(file) { transcribedText ->
            if (transcribedText != null && transcribedText.isNotBlank()) {
                Log.d("VoiceViewModel", "STT 결과: $transcribedText")

                // 인식된 텍스트를 잠시 보여줌
                _uiState.value = VoiceUiState.Result(transcribedText)

                // 바로 LLM 분석 시작
                analyzeVoiceRequest(transcribedText)

            } else {
                Log.e("VoiceViewModel", "STT 실패 또는 빈 결과")
                showError("음성을 인식하지 못했어요. 다시 시도해 주세요.")
            }

            // 임시 파일 삭제
            file.delete()
        }
    }

    private fun analyzeVoiceRequest(text: String) {
        // Processing 상태로 전환
        _uiState.value = VoiceUiState.Processing

        // LLM 분석 호출
        openAiManager.processVoiceRequest(text) { result ->
            when (result) {
                is VoiceRequestResult.Success -> {
                    Log.d("VoiceViewModel", "LLM 분석 성공 - ${result.requestType}: ${result.keyword}")

                    // 검색 결과 데이터 저장
                    _searchResult.value = SearchResultData(
                        userQuery = text,
                        requestType = result.requestType,
                        keyword = result.keyword,
                        responseMessage = result.responseMessage
                    )

                    // Result 상태로 전환 (응답 메시지 표시)
                    _uiState.value = VoiceUiState.Result(result.responseMessage)

                    // TODO: 여기서 검색 결과 화면으로 자동 이동하도록 이벤트 발생
                    // Navigation은 Activity/Composable에서 searchResult를 observe해서 처리
                }
                is VoiceRequestResult.Error -> {
                    Log.e("VoiceViewModel", "LLM 분석 실패: ${result.message}")
                    showError(result.message)
                }
            }
        }
    }

    fun onSuggestionClick(text: String) {
        // 제안 명령어 클릭 시 바로 LLM 분석
        _uiState.value = VoiceUiState.Processing
        analyzeVoiceRequest(text)
    }

    fun showError(message: String? = null) {
        _uiState.value = VoiceUiState.Error(
            message ?: "음성을 또렷하게 인식하지 못했어요. 조금 더 천천히 다시 말씀해 주세요."
        )
    }

    fun reset() {
        _uiState.value = VoiceUiState.Idle
        _searchResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        if (isRecording) {
            mediaRecorder?.apply {
                stop()
                release()
            }
        }
        mediaRecorder = null
        audioFile?.delete()
    }
}

/**
 * 검색 결과 데이터 (다음 화면으로 전달)
 */
data class SearchResultData(
    val userQuery: String,          // 사용자가 말한 원본 텍스트
    val requestType: String,        // "recipe_search", "menu_recommend" 등
    val keyword: String,            // 검색 키워드
    val responseMessage: String     // AI 응답 메시지
)