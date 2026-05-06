package com.capstone.toma.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.model.RecentRecipeRecord
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.model.StoredRecipe
import com.capstone.toma.model.normalizeRecipeCategory
import com.capstone.toma.storage.RecipeStorageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

    init {
        viewModelScope.launch {
            runCatching { repository.ensureSeeded() }
        }
    }

    fun isRecipeSaved(title: String): Flow<Boolean> {
        val recipeId = generateStableId(title)
        return repository.isRecipeSaved(recipeId)
    }

    fun saveRecentRecipe(title: String, recipeJson: String?) {
        if (recipeJson.isNullOrBlank()) return

        viewModelScope.launch {
            runCatching {
                val json = JSONObject(recipeJson)
                val recipeId = generateStableId(json.optString("title", title))
                val existing = recipes.value.firstOrNull { it.id == recipeId }
                val normalizedTitle = json.optString("title", title)
                val normalizedCategory = normalizeRecipeCategory(
                    rawCategory = json.optString("category", existing?.category ?: "기타"),
                    title = normalizedTitle
                )
                val parsedTime = parseTimeValue(json.opt("time"))
                val savedRecipe = StoredRecipe(
                    id = recipeId,
                    title = normalizedTitle,
                    category = normalizedCategory,
                    story = json.optString("story", existing?.story ?: ""),
                    time = if (parsedTime > 0) parsedTime else existing?.time ?: 0,
                    difficulty = json.optString("difficulty", existing?.difficulty ?: "\uBCF4\uD1B5"),
                    servings = parseServingsValue(json.opt("servings"), existing?.servings ?: 1),
                    calories = json.optInt("calories", existing?.calories ?: 0),
                    rating = json.optDouble("rating", existing?.rating ?: 0.0),
                    favorite = existing?.favorite ?: false,
                    ingredients = parseJsonArray(json, "ingredients").ifEmpty { existing?.ingredients ?: emptyList() },
                    steps = parseJsonArray(json, "steps").ifEmpty { existing?.steps ?: emptyList() },
                    sourceType = existing?.sourceType ?: inferSourceType(json),
                    timeText = "\uBC29\uAE08 \uC800\uC7A5",
                    imageUri = json.optString("image_url").takeIf { it.isNotBlank() } ?: existing?.imageUri
                )

                repository.saveRecipe(savedRecipe)
                repository.saveRecentRecipe(
                    RecentRecipeRecord(
                        id = recipeId,
                        title = savedRecipe.title,
                        timeText = "\uBC29\uAE08 \uC800\uC7A5",
                        sourceType = savedRecipe.sourceType,
                        recipeDataJson = recipeJson
                    )
                )
            }
        }
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
                        val normalizedTitle = json.optString("title", title)
                        val recipe = StoredRecipe(
                            id = recipeId,
                            title = normalizedTitle,
                            category = normalizeRecipeCategory(
                                rawCategory = json.optString("category", "기타"),
                                title = normalizedTitle
                            ),
                            story = json.optString("story", ""),
                            time = parseTimeValue(json.opt("time")),
                            difficulty = json.optString("difficulty", "\uBCF4\uD1B5"),
                            servings = parseServingsValue(json.opt("servings"), 1),
                            calories = json.optInt("calories", 0),
                            rating = json.optDouble("rating", 0.0),
                            favorite = true,
                            ingredients = parseJsonArray(json, "ingredients"),
                            steps = parseJsonArray(json, "steps"),
                            sourceType = inferSourceType(json),
                            timeText = "\uBC29\uAE08 \uBD84\uC11D",
                            imageUri = json.optString("image_url").takeIf { it.isNotBlank() }
                        )
                        repository.saveRecipe(recipe)
                    }
                }
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

    fun deleteRecipe(recipeId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteRecipe(recipeId)
            }
        }
    }

    private fun generateStableId(title: String): String {
        return "recipe_${title.hashCode()}"
    }

    private fun parseJsonArray(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.optString(it) }
            .filter { it.isNotBlank() }
    }

    private fun inferSourceType(json: JSONObject): RecipeSourceType {
        return if (json.optString("image_url").isNotBlank()) {
            RecipeSourceType.IMAGE
        } else {
            RecipeSourceType.TEXT
        }
    }

    private fun parseTimeValue(raw: Any?): Int {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun parseServingsValue(raw: Any?, fallback: Int): Int {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: fallback
            else -> fallback
        }
    }
}
