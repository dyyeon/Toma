package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.OpenAiManager
import com.capstone.toma.PublicRecipeManager
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.WebPageManager
import com.capstone.toma.storage.ChatMessageEntity
import com.capstone.toma.storage.ChatRepository
import com.capstone.toma.storage.ChatSessionEntity
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.capstone.toma.ui.screen.AiChatUiState
import com.capstone.toma.ui.screen.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LastRecipeContext(
    val keyword: String,
    val sourceType: com.capstone.toma.model.RecipeSourceType,
    val recipeData: String
)

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

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val openAiManager = OpenAiManager()
    private val chatRepository = ChatRepository.getInstance(app)

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<ChatNavigationEvent?>(null)
    val navigationEvent: StateFlow<ChatNavigationEvent?> = _navigationEvent.asStateFlow()

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    private val _recipeContexts = MutableStateFlow<Map<String, LastRecipeContext>>(emptyMap())
    val recipeContextsByMessageId: StateFlow<Map<String, LastRecipeContext>> = _recipeContexts.asStateFlow()

    private val timeFormat = SimpleDateFormat("a h:mm", Locale.KOREAN)
    private var lastAnalyzedRecipeData: String? = null
    private var sessionSourceType: com.capstone.toma.model.RecipeSourceType? = null

    private var currentSessionId: String? = null
    private var messageOrderIndex = 0

    // Incremented on resetChat() to invalidate in-flight OkHttp callbacks
    private var requestGeneration = 0
    // Job for ongoing startLinkAnalysis coroutine; cancelled on resetChat()
    private var linkAnalysisJob: Job? = null

    fun observeSessions(): Flow<List<ChatSessionEntity>> = chatRepository.observeSessions()

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val entities = withContext(Dispatchers.IO) {
                chatRepository.getMessages(sessionId)
            }

            val messages = entities.map { entity ->
                ChatMessage(
                    id = entity.id,
                    text = entity.text,
                    isUser = entity.isUser,
                    timestamp = entity.timestamp,
                    imageUri = entity.imageUri
                )
            }

            // 레시피 컨텍스트가 저장된 메시지로부터 _recipeContexts 복원
            val restoredContexts = entities
                .mapNotNull { entity ->
                    val json = entity.recipeContextJson ?: return@mapNotNull null
                    runCatching {
                        val j = JSONObject(json)
                        entity.id to LastRecipeContext(
                            keyword = j.getString("keyword"),
                            sourceType = runCatching {
                                com.capstone.toma.model.RecipeSourceType.valueOf(j.getString("sourceType"))
                            }.getOrDefault(com.capstone.toma.model.RecipeSourceType.TEXT),
                            recipeData = j.getString("recipeData")
                        )
                    }.getOrNull()
                }
                .toMap()

            currentSessionId = sessionId
            messageOrderIndex = messages.size
            lastAnalyzedRecipeData = null
            sessionSourceType = null
            _recipeContexts.value = restoredContexts
            clearNavigationEvent()
            clearErrorEvent()
            _uiState.value = AiChatUiState(messages = messages)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.deleteSession(sessionId)
        }
        if (currentSessionId == sessionId) {
            currentSessionId = null
            messageOrderIndex = 0
        }
    }

    private fun ensureSessionId(firstMessageText: String): String {
        return currentSessionId ?: run {
            val id = UUID.randomUUID().toString()
            val title = firstMessageText.trim().take(30).ifBlank { "새 대화" }
            currentSessionId = id
            messageOrderIndex = 0
            viewModelScope.launch(Dispatchers.IO) {
                chatRepository.createSession(id, title, System.currentTimeMillis())
            }
            id
        }
    }

    private fun persistMessage(message: ChatMessage) {
        val sessionId = currentSessionId ?: return
        val order = messageOrderIndex++
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.saveMessage(
                ChatMessageEntity(
                    id = message.id,
                    sessionId = sessionId,
                    text = message.text,
                    isUser = message.isUser,
                    timestamp = message.timestamp,
                    imageUri = message.imageUri,
                    orderIndex = order
                )
            )
            chatRepository.touchSession(sessionId)
        }
    }

    fun resetChat() {
        requestGeneration++
        linkAnalysisJob?.cancel()
        linkAnalysisJob = null
        currentSessionId = null
        messageOrderIndex = 0
        _uiState.value = AiChatUiState()
        lastAnalyzedRecipeData = null
        sessionSourceType = null
        _recipeContexts.value = emptyMap()
        clearNavigationEvent()
        clearErrorEvent()
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    fun clearErrorEvent() {
        _errorEvent.value = null
        _uiState.update { it.copy(errorDialogMessage = null, isTyping = false, isSpecificAnalysis = false) }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage(text: String? = null, imageUri: String? = null) {
        if (_uiState.value.isTyping) return

        val messageText = text ?: _uiState.value.inputText
        if (messageText.isBlank() && imageUri == null) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = messageText.ifBlank { "사진 분석 요청" },
            isUser = true,
            timestamp = getCurrentTime(),
            imageUri = imageUri
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isTyping = true,
                quickActions = null
            )
        }

        ensureSessionId(userMessage.text)
        persistMessage(userMessage)

        if (imageUri == null) {
            processAiResponse(messageText)
        }
    }

    fun sendCustomMessage(displayText: String, hiddenPrompt: String? = null) {
        if (displayText.isBlank()) return
        if (_uiState.value.isTyping) return

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
                isTyping = true,
                quickActions = null
            )
        }

        ensureSessionId(userMessage.text)
        persistMessage(userMessage)

        val target = hiddenPrompt ?: displayText
        processAiResponse(target)
    }

    fun startLinkAnalysis(
        userDisplay: String,
        initialAiText: String,
        fixedSourceType: com.capstone.toma.model.RecipeSourceType? = null,
        onAnalyze: suspend (updateStatus: (String) -> Unit) -> VoiceRequestResult
    ) {
        if (_uiState.value.isTyping) return
        if (fixedSourceType != null && sessionSourceType == null) {
            sessionSourceType = fixedSourceType
        }

        val userMessageId = UUID.randomUUID().toString()
        val aiMessageId = UUID.randomUUID().toString()
        val userTimestamp = getCurrentTime()
        val aiTimestamp = getCurrentTime()

        val userMessage = ChatMessage(
            id = userMessageId,
            text = userDisplay,
            isUser = true,
            timestamp = userTimestamp
        )
        val aiMessage = ChatMessage(
            id = aiMessageId,
            text = initialAiText,
            isUser = false,
            timestamp = aiTimestamp
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + aiMessage,
                isTyping = true,
                isSpecificAnalysis = true,
                quickActions = null
            )
        }

        ensureSessionId(userMessage.text)
        persistMessage(userMessage)

        linkAnalysisJob?.cancel()
        linkAnalysisJob = viewModelScope.launch {
            val result = onAnalyze { status ->
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { message ->
                            if (message.id == aiMessageId) message.copy(text = status) else message
                        }
                    )
                }
            }

            when (result) {
                is VoiceRequestResult.Success -> {
                    val isInsufficient = result.requestType == "insufficient_content"
                    val isMultiRecipe = result.requestType == "multi_recipe"
                    val chips = when {
                        isMultiRecipe -> result.keyword
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { dish -> dish to "$dish 레시피를 만들어주세요" }
                            .takeIf { it.isNotEmpty() }
                        isInsufficient -> listOfNotNull(
                            if (result.keyword.isNotBlank())
                                ("네, 만들어주세요" to "${result.keyword} 레시피를 만들어주세요")
                            else null
                        ).takeIf { it.isNotEmpty() }
                        else -> null
                    }

                    if (isInsufficient || isMultiRecipe) sessionSourceType = null

                    val displayMessage = if (isInsufficient && result.keyword.isBlank()) {
                        "이 페이지 본문을 충분히 읽지 못했어요. 음식 이름을 직접 알려주시면 일반적인 레시피로 정리해드릴게요."
                    } else {
                        result.responseMessage
                    }

                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                if (message.id == aiMessageId) message.copy(text = displayMessage)
                                else message
                            },
                            isTyping = false,
                            isSpecificAnalysis = false,
                            quickActions = chips
                        )
                    }
                    persistMessage(ChatMessage(id = aiMessageId, text = displayMessage, isUser = false, timestamp = aiTimestamp))
                    handleNavigation(result, aiMessageId, fixedSourceType)
                }
                is VoiceRequestResult.Error -> {
                    val errorText = "분석 중 문제가 발생했습니다."
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { message ->
                                if (message.id == aiMessageId) message.copy(text = errorText) else message
                            },
                            isTyping = false,
                            isSpecificAnalysis = false,
                            errorDialogMessage = result.message
                        )
                    }
                    persistMessage(ChatMessage(id = aiMessageId, text = errorText, isUser = false, timestamp = aiTimestamp))
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
        ensureSessionId(userMessage.text)
        persistMessage(userMessage)
        persistMessage(aiMessage)
    }

    private fun processAiResponse(userText: String) {
        val correctedText = com.capstone.toma.DishNameHelper.correctDishNameInText(userText)
        val history = _uiState.value.messages.map { it.text to it.isUser }
        val gen = ++requestGeneration

        viewModelScope.launch(Dispatchers.IO) {
            openAiManager.processChatRequest(correctedText, history) { result ->
                viewModelScope.launch {
                    if (gen != requestGeneration) return@launch
                    when (result) {
                        is VoiceRequestResult.Success -> {
                            val enrichedResult = if (
                                result.requestType == "recipe_search" &&
                                result.keyword.isNotBlank() &&
                                result.recipeData != null
                            ) {
                                val recipeJson = JSONObject(result.recipeData)
                                val currentImageUrl = recipeJson.optString("image_url")
                                if (currentImageUrl.isBlank() || currentImageUrl == "없음") {
                                    val recipeTitle = recipeJson.optString("title", "")
                                    val searchKeyword = com.capstone.toma.DishNameHelper.canonicalSearchKeyword(
                                        gptKeyword = result.keyword,
                                        recipeTitle = recipeTitle
                                    )
                                    val imageUrl = withContext(Dispatchers.IO) {
                                        val publicRecipe = PublicRecipeManager().searchRecipe(searchKeyword)
                                        val publicImageUrl = if (
                                            publicRecipe != null &&
                                            com.capstone.toma.DishNameHelper.isImageConsistentWithRecipe(
                                                publicRecipe.name, searchKeyword
                                            )
                                        ) {
                                            publicRecipe.mainImageUrl.takeIf { it.isNotBlank() }
                                        } else null
                                        publicImageUrl ?: WebPageManager().searchFoodImage(searchKeyword)
                                    }
                                    if (imageUrl != null) {
                                        recipeJson.put("image_url", imageUrl)
                                        result.copy(recipeData = recipeJson.toString())
                                    } else result
                                } else result
                            } else result

                            val aiMessageId = UUID.randomUUID().toString()
                            val aiTimestamp = getCurrentTime()

                            val recipeImageUrl = enrichedResult.recipeData?.let {
                                try { JSONObject(it).optString("image_url") } catch (_: Exception) { null }
                            }?.takeIf { it.isNotBlank() && it != "없음" }

                            val aiMessage = ChatMessage(
                                id = aiMessageId,
                                text = enrichedResult.responseMessage,
                                isUser = false,
                                timestamp = aiTimestamp,
                                imageUri = recipeImageUrl
                            )

                            _uiState.update {
                                it.copy(
                                    messages = it.messages + aiMessage,
                                    isTyping = false
                                )
                            }
                            persistMessage(aiMessage)
                            handleNavigation(enrichedResult, aiMessageId)
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
        aiMessageId: String,
        fixedSourceType: com.capstone.toma.model.RecipeSourceType? = null
    ) {
        val latestRecipeData = result.recipeData?.takeIf { it.isNotBlank() }
        if (latestRecipeData != null) {
            lastAnalyzedRecipeData = latestRecipeData
        }

        val effectiveRecipeData = latestRecipeData ?: lastAnalyzedRecipeData
        val hasRecipeContext = result.keyword.isNotBlank() || !effectiveRecipeData.isNullOrBlank()

        if (result.requestType == "not_recipe" || result.requestType == "multi_recipe") return

        if (hasRecipeContext && (
                result.requestType == "recipe_search" ||
                    result.requestType == "recipe_navigation"
                )
        ) {
            val keyword = result.keyword
            val sourceType = fixedSourceType
                ?: sessionSourceType
                ?: if (keyword.startsWith("http")) {
                    if (keyword.contains("youtube.com/watch") ||
                        keyword.contains("youtube.com/shorts/") ||
                        keyword.contains("youtu.be/") ||
                        keyword.contains("youtube.com/embed/")) {
                        com.capstone.toma.model.RecipeSourceType.YOUTUBE
                    } else {
                        com.capstone.toma.model.RecipeSourceType.WEB
                    }
                } else {
                    com.capstone.toma.model.RecipeSourceType.TEXT
                }

            val context = LastRecipeContext(
                keyword = keyword,
                sourceType = sourceType,
                recipeData = effectiveRecipeData ?: ""
            )
            _recipeContexts.update { it + (aiMessageId to context) }
            persistRecipeContext(aiMessageId, context)
            _navigationEvent.value = ChatNavigationEvent.ToConfirm(
                keyword = keyword,
                sourceType = sourceType,
                recipeData = effectiveRecipeData
            )
        }
    }

    fun reopenRecipe(messageId: String) {
        val ctx = _recipeContexts.value[messageId] ?: return
        _navigationEvent.value = ChatNavigationEvent.ToConfirm(
            keyword = ctx.keyword,
            sourceType = ctx.sourceType,
            recipeData = ctx.recipeData
        )
    }

    fun dismissQuickActions() {
        _uiState.update { it.copy(quickActions = null) }
    }

    private fun persistRecipeContext(messageId: String, context: LastRecipeContext) {
        val contextJson = org.json.JSONObject().apply {
            put("keyword", context.keyword)
            put("sourceType", context.sourceType.name)
            put("recipeData", context.recipeData)
        }.toString()
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.saveMessageRecipeContext(messageId, contextJson)
        }
    }

    private fun getCurrentTime(): String = timeFormat.format(Date())
}
