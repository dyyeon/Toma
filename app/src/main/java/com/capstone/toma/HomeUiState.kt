package com.capstone.toma

data class HomeUiState(
    val searchQuery: String = "",
    val youtubeLink: String = "",
    val isAnalyzing: Boolean = false,
    val recentRecipes: List<RecentRecipeItem> = emptyList(),
    val errorMessage: String? = null
)

data class RecentRecipeItem(
    val id: String,
    val title: String,
    val source: RecipeSource,
    val timeText: String
)

enum class RecipeSource {
    TEXT, YOUTUBE, IMAGE
}