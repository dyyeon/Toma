package com.capstone.toma.model

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