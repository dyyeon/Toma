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

    fun updateRecipeLink(link: String) {
        _uiState.update { it.copy(recipeLink = link) }
    }

    fun submitLink() {
        if (_uiState.value.isAnalyzing) return

        val link = _uiState.value.recipeLink.trim()

        if (link.isBlank()) {
            showError("링크를 입력해주세요.")
            return
        }

        if (!isValidUrl(link)) {
            showError("유효한 링크를 입력해주세요.")
            return
        }

        clearError()

        val sourceType = when {
            link.contains("youtube.com") || link.contains("youtu.be") -> RecipeSourceType.YOUTUBE
            link.contains("naver.com") || link.contains("tistory.com") -> RecipeSourceType.WEB
            else -> RecipeSourceType.WEB
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }

            delay(1500)

            addRecentRecipe(
                RecentRecipeItem(
                    id = "link_${System.currentTimeMillis()}",
                    title = extractLinkTitle(sourceType),
                    timeText = "방금 분석",
                    sourceType = sourceType
                )
            )

            _uiState.update {
                it.copy(
                    recipeLink = "",
                    isAnalyzing = false
                )
            }
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null, errorDialogMessage = null, isAnalyzing = false)
        }
    }

    fun showError(message: String, isDialog: Boolean = false) {
        _uiState.update {
            if (isDialog) it.copy(errorDialogMessage = message)
            else it.copy(errorMessage = message)
        }
    }

    private fun addRecentRecipe(item: RecentRecipeItem) {
        _uiState.update {
            it.copy(recentItems = listOf(item) + it.recentItems)
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun extractLinkTitle(type: RecipeSourceType): String {
        return when (type) {
            RecipeSourceType.YOUTUBE -> "유튜브 레시피"
            RecipeSourceType.WEB -> "웹 레시피"
            else -> "레시피"
        }
    }
}