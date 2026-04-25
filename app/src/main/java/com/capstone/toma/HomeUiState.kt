package com.capstone.toma

data class HomeUiState(
    val searchQuery: String = "",
    val youtubeLink: String = "",
    val linkPlaceholder: String = "레시피 링크(유튜브, 블로그 등)를 입력하세요",
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