package com.capstone.toma.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.ui.screen.HomeUiState
import com.capstone.toma.ui.screen.RecentRecipeItem
import com.capstone.toma.ui.screen.RecipeSourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            recentItems = listOf(
                RecentRecipeItem(
                    id = "kimchi",
                    title = "김치볶음밥",
                    timeText = "2시간 전 분석",
                    sourceType = RecipeSourceType.YOUTUBE
                ),
                RecentRecipeItem(
                    id = "egg",
                    title = "계란말이",
                    timeText = "어제 분석",
                    sourceType = RecipeSourceType.IMAGE
                )
            )
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateYoutubeLink(link: String) {
        _uiState.update { it.copy(youtubeLink = link) }
    }

    fun submitSearch() {
        if (_uiState.value.isAnalyzing) return // [추가] 분석 중 중복 클릭 방지
        val query = _uiState.value.searchQuery.trim()

        if (query.isBlank()) {
            showError("검색어를 입력해주세요.")
            return
        }

        clearError()

        addRecentRecipe(
            RecentRecipeItem(
                id = "text_${System.currentTimeMillis()}",
                title = query,
                timeText = "방금 검색",
                sourceType = RecipeSourceType.TEXT
            )
        )

        _uiState.update { it.copy(searchQuery = "") }
    }

    fun submitYoutube() {
        if (_uiState.value.isAnalyzing) return // [추가] 분석 중 중복 클릭 방지
        val link = _uiState.value.youtubeLink.trim()

        if (link.isBlank()) {
            showError("유튜브 링크를 입력해주세요.")
            return
        }

        if (!isValidYoutubeUrl(link)) {
            showError("유효한 유튜브 링크를 입력해주세요.")
            return
        }

        clearError()

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }

            delay(1800)

            addRecentRecipe(
                RecentRecipeItem(
                    id = "youtube_${System.currentTimeMillis()}",
                    title = extractYoutubeTitle(link),
                    timeText = "방금 분석",
                    sourceType = RecipeSourceType.YOUTUBE
                )
            )

            _uiState.update {
                it.copy(
                    youtubeLink = "",
                    isAnalyzing = false
                )
            }
        }
    }

    fun onImageSelected(uriString: String) {
        if (_uiState.value.isAnalyzing) return // [추가] 분석 중 중복 클릭 방지
        clearError()

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }

            delay(1200)

            addRecentRecipe(
                RecentRecipeItem(
                    id = "image_${System.currentTimeMillis()}",
                    title = "업로드한 이미지 레시피",
                    timeText = "방금 분석",
                    sourceType = RecipeSourceType.IMAGE
                )
            )

            _uiState.update { it.copy(isAnalyzing = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, errorDialogMessage = null, isAnalyzing = false) }
    }

    fun showError(message: String, isDialog: Boolean = false) {
        _uiState.update { 
            if (isDialog) it.copy(errorDialogMessage = message, isAnalyzing = false)
            else it.copy(errorMessage = message, isAnalyzing = false)
        }
    }

    private fun addRecentRecipe(item: RecentRecipeItem) {
        _uiState.update {
            it.copy(recentItems = listOf(item) + it.recentItems)
        }
    }

    private fun isValidYoutubeUrl(url: String): Boolean {
        return url.contains("youtube.com/watch") ||
                url.contains("youtu.be/") ||
                url.contains("youtube.com/shorts/")
    }

    private fun extractYoutubeTitle(link: String): String {
        return when {
            link.contains("youtu.be/") -> "Short-form 유튜브 레시피"
            link.contains("shorts") -> "유튜브 쇼츠 레시피"
            else -> "유튜브 레시피"
        }
    }

    fun selectRecentItem(itemId: String) {
        _uiState.update { it.copy(selectedRecentItemId = itemId) }
    }

    fun clearSelectedRecentItem() {
        _uiState.update { it.copy(selectedRecentItemId = null) }
    }

    fun getSelectedRecentItem(): RecentRecipeItem? {
        val current = _uiState.value
        return current.recentItems.firstOrNull { it.id == current.selectedRecentItemId }
    }
}