package com.capstone.toma.viewmodel

import androidx.lifecycle.ViewModel
import com.capstone.toma.VoiceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class VoiceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState: StateFlow<VoiceUiState> = _uiState

    fun onMicClick() {
        _uiState.update { current ->
            when (current) {
                VoiceUiState.Idle -> VoiceUiState.Listening
                VoiceUiState.Listening -> VoiceUiState.Processing
                VoiceUiState.Processing -> VoiceUiState.Result("김치볶음밥 레시피를 찾아드릴게요.")
                is VoiceUiState.Result -> VoiceUiState.Idle
                is VoiceUiState.Error -> VoiceUiState.Listening
            }
        }
    }

    fun onSuggestionClick(text: String) {
        _uiState.value = VoiceUiState.Result(text)
    }

    fun showError() {
        _uiState.value = VoiceUiState.Error("음성을 인식하지 못했어요. 다시 시도해 주세요.")
    }

    fun reset() {
        _uiState.value = VoiceUiState.Idle
    }
}