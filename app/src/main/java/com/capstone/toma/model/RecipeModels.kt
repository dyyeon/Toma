package com.capstone.toma.model

import org.json.JSONArray
import org.json.JSONObject

enum class RecipeSourceType {
    TEXT, YOUTUBE, WEB, IMAGE
}

data class RecentRecipeRecord(
    val id: String,
    val title: String,
    val timeText: String,
    val sourceType: RecipeSourceType,
    val recipeDataJson: String?
)

data class StoredRecipe(
    val id: String,
    val title: String,
    val category: String,
    val story: String,
    val time: Int,
    val difficulty: String,
    val servings: Int,
    val calories: Int,
    val rating: Double,
    val favorite: Boolean,
    val ingredients: List<String>,
    val steps: List<String>,
    val sourceType: RecipeSourceType,
    val timeText: String,
    val imageUri: String? = null
)

fun StoredRecipe.toRecipeDataJson(): String {
    return JSONObject().apply {
        put("title", title)
        put("category", category)
        put("story", story)
        put("time", time)
        put("difficulty", difficulty)
        put("servings", servings)
        put("calories", calories)
        put("rating", rating)
        put("source_type", sourceType.name)
        put("ingredients", JSONArray(ingredients))
        put("steps", JSONArray(steps))
        imageUri?.takeIf { it.isNotBlank() }?.let { put("image_url", it) }
    }.toString()
}
