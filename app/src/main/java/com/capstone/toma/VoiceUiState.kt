package com.capstone.toma

sealed interface VoiceUiState {
    data object Idle : VoiceUiState
    data object Listening : VoiceUiState
    data object Processing : VoiceUiState
    data class Result(val text: String) : VoiceUiState
    data class Error(val message: String) : VoiceUiState
}