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
                VoiceUiState.Processing -> {
                    VoiceUiState.Result("말씀하신 내용을 바탕으로 레시피를 준비해드릴게요.")
                }
                is VoiceUiState.Result -> VoiceUiState.Idle
                is VoiceUiState.Error -> VoiceUiState.Listening
            }
        }
    }

    fun onSuggestionClick(text: String) {
        _uiState.value = VoiceUiState.Result(
            buildSuggestionResponse(text)
        )
    }

    fun showError() {
        _uiState.value = VoiceUiState.Error(
            "음성을 또렷하게 인식하지 못했어요. 조금 더 천천히 다시 말씀해 주세요."
        )
    }

    fun reset() {
        _uiState.value = VoiceUiState.Idle
    }

    private fun buildSuggestionResponse(command: String): String {
        return when (command) {
            "메뉴 추천해줘" -> "지금 바로 만들기 좋은 메뉴를 추천해드릴게요."
            "재료로 요리 찾아줘" -> "가지고 있는 재료를 기준으로 만들 수 있는 요리를 찾아드릴게요."
            "간단한 레시피 알려줘" -> "쉽고 간단하게 따라 할 수 있는 레시피를 보여드릴게요."
            "빠른 요리 찾아줘" -> "짧은 시간 안에 만들 수 있는 요리를 우선으로 찾아드릴게요."
            "쉬운 요리 추천해줘" -> "초보자도 부담 없이 만들 수 있는 쉬운 요리를 추천해드릴게요."
            else -> "\"$command\" 요청을 바탕으로 적절한 요리를 찾아드릴게요."
        }
    }
}