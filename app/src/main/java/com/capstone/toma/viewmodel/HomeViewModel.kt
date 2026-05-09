package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.storage.RecentHistoryStore
import com.capstone.toma.ui.screen.HomeUiState
import com.capstone.toma.ui.screen.RecentRecipeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val recentHistoryStore = RecentHistoryStore(application)

    private val _uiState = MutableStateFlow(HomeUiState())

    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refreshRecentItems()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateYoutubeLink(link: String) {
        _uiState.update { it.copy(youtubeLink = link) }
    }

    fun refreshRecentItems() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = recentHistoryStore.getRecentItems()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(recentItems = items)
                }
            }
        }
    }

    fun saveRecentRecipe(
        keyword: String,
        recipeDataJson: String?,
        sourceType: RecipeSourceType = RecipeSourceType.TEXT
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = recentHistoryStore.saveRecentRecipe(
                keyword = keyword,
                recipeDataJson = recipeDataJson,
                sourceType = sourceType
            )
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(recentItems = items)
                }
            }
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                errorDialogMessage = null,
                isAnalyzing = false
            )
        }
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
        return current.recentItems.firstOrNull {
            it.id == current.selectedRecentItemId
        }
    }
}