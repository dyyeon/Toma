package com.capstone.toma

// CHANGED: openWakeWord migration - Expanded states for better UX
sealed interface VoiceUiState {
    data object Idle : VoiceUiState
    data object Listening : VoiceUiState
    data object Processing : VoiceUiState
    data object Speaking : VoiceUiState      // AI가 응답 중인 상태
    data object Recovering : VoiceUiState    // 상태 초기화 중
    data object Training : VoiceUiState      // 개인화 모델 학습/다운로드 대기 중
    data class Result(val text: String) : VoiceUiState
    data class Error(val message: String) : VoiceUiState
}
