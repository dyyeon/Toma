package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.model.StoredRecipe
import com.capstone.toma.storage.RecipeStorageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecipeStorageViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val repository = RecipeStorageRepository.getInstance(application)

    val recipes: StateFlow<List<StoredRecipe>> = repository.observeRecipes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            runCatching {
                repository.ensureSeeded()
            }
        }
    }

    fun toggleFavorite(recipe: StoredRecipe) {
        viewModelScope.launch {
            runCatching {
                repository.updateFavorite(
                    recipeId = recipe.id,
                    isFavorite = !recipe.favorite
                )
            }
        }
    }

    fun saveRecipe(recipe: StoredRecipe) {
        viewModelScope.launch {
            runCatching {
                repository.saveRecipe(recipe)
            }
        }
    }
}
