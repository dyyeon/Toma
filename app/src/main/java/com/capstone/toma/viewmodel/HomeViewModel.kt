package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.storage.RecentHistoryStore
import com.capstone.toma.ui.screen.HomeUiState
import com.capstone.toma.ui.screen.RecentRecipeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val recentHistoryStore = RecentHistoryStore(application)

    private val _uiState = MutableStateFlow(
        HomeUiState(recentItems = recentHistoryStore.getRecentItems())
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateYoutubeLink(link: String) {
        _uiState.update { it.copy(youtubeLink = link) }
    }

    fun refreshRecentItems() {
        _uiState.update { it.copy(recentItems = recentHistoryStore.getRecentItems()) }
    }

    fun saveRecentRecipe(
        keyword: String,
        recipeDataJson: String?,
        sourceType: RecipeSourceType = RecipeSourceType.TEXT
    ) {
        _uiState.update {
            it.copy(
                recentItems = recentHistoryStore.saveRecentRecipe(
                    keyword = keyword,
                    recipeDataJson = recipeDataJson,
                    sourceType = sourceType
                )
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, errorDialogMessage = null, isAnalyzing = false) }
    }

    fun showError(message: String, isDialog: Boolean = false) {
        _uiState.update {
            if (isDialog) {
                it.copy(errorDialogMessage = message, isAnalyzing = false)
            } else {
                it.copy(errorMessage = message, isAnalyzing = false)
            }
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
