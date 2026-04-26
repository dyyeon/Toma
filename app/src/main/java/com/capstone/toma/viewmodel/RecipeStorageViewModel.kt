package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.model.StoredRecipe
import com.capstone.toma.storage.RecipeStorageRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    fun isRecipeSaved(title: String): Flow<Boolean> {
        val recipeId = generateStableId(title)
        return repository.isRecipeSaved(recipeId)
    }

    private fun generateStableId(title: String): String {
        return "recipe_${title.hashCode()}"
    }

    fun toggleFavorite(title: String, recipeJson: String?, isCurrentFavorite: Boolean) {
        viewModelScope.launch {
            val recipeId = generateStableId(title)
            if (isCurrentFavorite) {
                repository.deleteRecipe(recipeId)
            } else {
                recipeJson?.let { jsonStr ->
                    runCatching {
                        val json = JSONObject(jsonStr)
                        val recipe = StoredRecipe(
                            id = recipeId,
                            title = json.optString("title", title),
                            category = json.optString("category", "기타"),
                            story = json.optString("story", ""),
                            time = json.optInt("time", 0),
                            difficulty = json.optString("difficulty", "보통"),
                            servings = json.optInt("servings", 1),
                            calories = json.optInt("calories", 0),
                            rating = json.optDouble("rating", 0.0),
                            favorite = true,
                            ingredients = parseJsonArray(json, "ingredients"),
                            steps = parseJsonArray(json, "steps"),
                            sourceType = RecipeSourceType.TEXT, // 기본값
                            timeText = "방금 분석됨"
                        )
                        repository.saveRecipe(recipe)
                    }
                }
            }
        }
    }

    private fun parseJsonArray(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.optString(it) }
    }

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
