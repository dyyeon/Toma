package com.capstone.toma.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.OpenAiManager
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.ui.screen.AiChatUiState
import com.capstone.toma.ui.screen.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed class ChatNavigationEvent {
    data class ToConfirm(
        val keyword: String,
        val sourceType: com.capstone.toma.model.RecipeSourceType,
        val recipeData: String?
    ) : ChatNavigationEvent()

    data class ToDetail(
        val keyword: String,
        val sourceType: com.capstone.toma.model.RecipeSourceType,
        val recipeData: String?
    ) : ChatNavigationEvent()
}

class ChatViewModel : ViewModel() {
    private val openAiManager = OpenAiManager()

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<ChatNavigationEvent?>(null)
    val navigationEvent: StateFlow<ChatNavigationEvent?> = _navigationEvent.asStateFlow()

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    private val timeFormat = SimpleDateFormat("a h:mm", Locale.KOREAN)
    private var lastAnalyzedRecipeData: String? = null

    fun resetChat() {
        _uiState.value = AiChatUiState()
        lastAnalyzedRecipeData = null
        clearNavigationEvent()
        clearErrorEvent()
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    fun clearErrorEvent() {
        _errorEvent.value = null
        _uiState.update { it.copy(errorDialogMessage = null, isTyping = false) }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(text: String? = null) {
        if (_uiState.value.isTyping) return

        val messageText = text ?: _uiState.value.inputText
        if (messageText.isBlank()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = messageText,
            isUser = true,
            timestamp = getCurrentTime()
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isTyping = true
            )
        }

        processAiResponse(messageText)
    }

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

        processAiResponse(hiddenPrompt ?: displayText)
    }

    fun startLinkAnalysis(
        userDisplay: String,
        initialAiText: String,
        fixedSourceType: com.capstone.toma.model.RecipeSourceType? = null,
        onAnalyze: suspend (updateStatus: (String) -> Unit) -> VoiceRequestResult
    ) {
        if (_uiState.value.isTyping) return

        val userMessageId = UUID.randomUUID().toString()
        val aiMessageId = UUID.randomUUID().toString()

        val userMessage = ChatMessage(
            id = userMessageId,
            text = userDisplay,
            isUser = true,
            timestamp = getCurrentTime()
        )
        val aiMessage = ChatMessage(
            id = aiMessageId,
            text = initialAiText,
            isUser = false,
            timestamp = getCurrentTime()
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + aiMessage,
                isTyping = true
            )
        }

        viewModelScope.launch {
            val result = onAnalyze { status ->
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { message ->
                            if (message.id == aiMessageId) {
                                message.copy(text = status)
                            } else {
                                message
                            }
                        }
                    )
                }
            }

            when (result) {
                is VoiceRequestResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                if (message.id == aiMessageId) {
                                    message.copy(text = result.responseMessage)
                                } else {
                                    message
                                }
                            },
                            isTyping = false
                        )
                    }
                    handleNavigation(result, fixedSourceType)
                }
                is VoiceRequestResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                if (message.id == aiMessageId) {
                                    message.copy(text = "분석 중 문제가 발생했습니다.")
                                } else {
                                    message
                                }
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

        _uiState.update { it.copy(messages = it.messages + userMessage + aiMessage) }
    }

    private fun processAiResponse(userText: String) {
        val history = _uiState.value.messages.map { it.text to it.isUser }

        viewModelScope.launch(Dispatchers.IO) {
            openAiManager.processChatRequest(userText, history) { result ->
                viewModelScope.launch {
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
                            handleNavigation(result)
                        }
                        is VoiceRequestResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isTyping = false,
                                    errorDialogMessage = result.message
                                )
                            }
                            _errorEvent.value = result.message
                        }
                    }
                }
            }
        }
    }

    private fun handleNavigation(
        result: VoiceRequestResult.Success,
        fixedSourceType: com.capstone.toma.model.RecipeSourceType? = null
    ) {
        val latestRecipeData = result.recipeData?.takeIf { it.isNotBlank() }
        if (latestRecipeData != null) {
            lastAnalyzedRecipeData = latestRecipeData
        }

        val effectiveRecipeData = latestRecipeData ?: lastAnalyzedRecipeData
        val hasRecipeContext = result.keyword.isNotBlank() || !effectiveRecipeData.isNullOrBlank()

        if (hasRecipeContext && (
                result.requestType == "recipe_search" ||
                    result.requestType == "recipe_navigation"
                )
        ) {
            val keyword = result.keyword
            val sourceType = fixedSourceType ?: if (keyword.startsWith("http")) {
                if (keyword.contains("youtube.com") || keyword.contains("youtu.be")) {
                    com.capstone.toma.model.RecipeSourceType.YOUTUBE
                } else {
                    com.capstone.toma.model.RecipeSourceType.WEB
                }
            } else {
                com.capstone.toma.model.RecipeSourceType.TEXT
            }

            _navigationEvent.value = ChatNavigationEvent.ToConfirm(
                keyword = keyword,
                sourceType = sourceType,
                recipeData = effectiveRecipeData
            )
        }
    }

    private fun getCurrentTime(): String = timeFormat.format(Date())
}
