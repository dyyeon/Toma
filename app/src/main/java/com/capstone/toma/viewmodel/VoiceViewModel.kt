package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.OpenAiManager
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.VoiceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val openAiManager = OpenAiManager()

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState: StateFlow<VoiceUiState> = _uiState

    private val _searchResult = MutableStateFlow<SearchResultData?>(null)
    val searchResult: StateFlow<SearchResultData?> = _searchResult

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: (() -> Unit)? = null

    fun onMicClick() {
        showError("마이크 입력은 아직 연결되지 않았습니다.")
    }

    fun onSuggestionClick(text: String) {
        _uiState.value = VoiceUiState.Processing

        viewModelScope.launch {
            openAiManager.processVoiceRequest(text) { result ->
                when (result) {
                    is VoiceRequestResult.Success -> {
                        _searchResult.value = SearchResultData(
                            userQuery = text,
                            requestType = result.requestType,
                            keyword = result.keyword,
                            responseMessage = result.responseMessage
                        )
                        _uiState.value = VoiceUiState.Result(result.responseMessage)
                    }
                    is VoiceRequestResult.Error -> {
                        showError(result.message)
                    }
                }
            }
        }
    }

    fun showError(message: String? = null) {
        _uiState.value = VoiceUiState.Error(
            message ?: "음성 기능 처리 중 문제가 발생했습니다."
        )
    }

    fun reset() {
        _uiState.value = VoiceUiState.Idle
        _searchResult.value = null
    }
}

data class SearchResultData(
    val userQuery: String,
    val requestType: String,
    val keyword: String,
    val responseMessage: String
)
