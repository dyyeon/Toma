package com.capstone.toma.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.OpenAiManager
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.ui.screen.AiChatUiState
import com.capstone.toma.ui.screen.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatViewModel : ViewModel() {
    private val openAiManager = OpenAiManager()

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<Pair<String, String?>?>(null)
    val navigationEvent: StateFlow<Pair<String, String?>?> = _navigationEvent.asStateFlow()

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    private val timeFormat = SimpleDateFormat("a h:mm", Locale.KOREAN)

    // 분석된 레시피 데이터를 임시 보관 (화면 이동 시 전달용)
    private var lastAnalyzedRecipeData: String? = null

    /**
     * 새로운 분석이나 대화를 시작할 때 기존 내역을 초기화합니다.
     */
    fun resetChat() {
        _uiState.update { AiChatUiState() }
        lastAnalyzedRecipeData = null
        clearNavigationEvent()
        clearErrorEvent()
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    fun clearErrorEvent() {
        _errorEvent.value = null
        _uiState.update { 
            it.copy(
                errorDialogMessage = null,
                isTyping = false 
            ) 
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(text: String? = null) {
        if (_uiState.value.isTyping) return // [추가] 분석 중 중복 전송 방지
        val messageText = text ?: _uiState.value.inputText
        if (messageText.isBlank()) return
        sendCustomMessage(messageText)
    }

    /**
     * 사용자에게 보여지는 텍스트와 실제 AI에게 전달하는 텍스트를 다르게 설정할 수 있습니다.
     */
    fun sendCustomMessage(displayText: String, hiddenPrompt: String? = null) {
        if (displayText.isBlank()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = displayText,
            isUser = true,
            timestamp = getCurrentTime()
        )

        _uiState.update { 
            it.copy(
                messages = it.messages + userMessage,
                inputText = if (hiddenPrompt == null) "" else it.inputText,
                isTyping = true
            )
        }

        // 실제 AI에게는 hiddenPrompt가 있으면 그걸 보내고, 없으면 displayText를 보냄
        processAiResponse(hiddenPrompt ?: displayText)
    }

    /**
     * 분석 단계별로 메시지를 업데이트하며 최종 결과를 받아옵니다.
     */
    fun startLinkAnalysis(userDisplay: String, initialAiText: String, onAnalyze: suspend (updateStatus: (String) -> Unit) -> VoiceRequestResult) {
        if (_uiState.value.isTyping) return // [추가] 중복 분석 시작 방지
        val userMsgId = UUID.randomUUID().toString()
        val aiMsgId = UUID.randomUUID().toString()

        val userMessage = ChatMessage(id = userMsgId, text = userDisplay, isUser = true, timestamp = getCurrentTime())
        val aiPendingMessage = ChatMessage(id = aiMsgId, text = initialAiText, isUser = false, timestamp = getCurrentTime())

        _uiState.update { 
            it.copy(
                messages = it.messages + userMessage + aiPendingMessage,
                isTyping = true
            )
        }

        viewModelScope.launch {
            val result = onAnalyze { status ->
                // 중간 상태 업데이트
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { 
                            if (it.id == aiMsgId) it.copy(text = status) else it 
                        }
                    )
                }
            }

            // 최종 결과 반영
            when (result) {
                is VoiceRequestResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { 
                                if (it.id == aiMsgId) it.copy(text = result.responseMessage) else it 
                            },
                            isTyping = false
                        )
                    }
                    if (result.requestType == "recipe_search") {
                        lastAnalyzedRecipeData = result.recipeData
                    }

                    if (result.requestType == "recipe_navigation") {
                        val data = result.recipeData ?: lastAnalyzedRecipeData
                        _navigationEvent.value = result.keyword to data
                    }
                }
                is VoiceRequestResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { 
                                if (it.id == aiMsgId) it.copy(text = "죄송해요. 분석 중에 문제가 발생했어요. 😢") else it 
                            },
                            isTyping = false,
                            errorDialogMessage = result.message
                        )
                    }
                    _errorEvent.value = result.message
                }
            }
        }
    }

    fun addInitialMessages(userText: String, aiResponse: String) {
        if (_uiState.value.messages.isNotEmpty()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = userText,
            isUser = true,
            timestamp = getCurrentTime()
        )
        val aiMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = aiResponse,
            isUser = false,
            timestamp = getCurrentTime()
        )

        _uiState.update { 
            it.copy(messages = it.messages + userMessage + aiMessage)
        }
    }

    private fun processAiResponse(userText: String) {
        // 대화 내역 추출 (text와 isUser 정보만 추출)
        val history = _uiState.value.messages.map { it.text to it.isUser }

        viewModelScope.launch {
            openAiManager.processChatRequest(userText, history) { result ->
                when (result) {
                    is VoiceRequestResult.Success -> {
                        val aiMessage = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = result.responseMessage,
                            isUser = false,
                            timestamp = getCurrentTime()
                        )
                        _uiState.update { 
                            it.copy(
                                messages = it.messages + aiMessage,
                                isTyping = false
                            )
                        }
                        
                        if (result.requestType == "recipe_search") {
                            // 분석 완료 상태. 데이터 캐싱 후 사용자 응답 대기
                            lastAnalyzedRecipeData = result.recipeData
                        }

                        if (result.requestType == "recipe_navigation") {
                            // 사용자가 시작 의사를 밝힘. 상세 화면으로 이동
                            val data = result.recipeData ?: lastAnalyzedRecipeData
                            _navigationEvent.value = result.keyword to data
                        }
                    }
                    is VoiceRequestResult.Error -> {
                        _uiState.update { 
                            it.copy(
                                isTyping = false,
                                errorDialogMessage = "네트워크 연결이 원활하지 않아요. 다시 시도해 주세요."
                            ) 
                        }
                        _errorEvent.value = result.message
                    }
                }
            }
        }
    }

    private fun getCurrentTime(): String = timeFormat.format(Date())
}
