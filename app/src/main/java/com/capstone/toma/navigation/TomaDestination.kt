package com.capstone.toma.navigation

sealed class TomaDestination(val route: String) {
    object Home : TomaDestination("home")
    object RecipeStorage : TomaDestination("recipe_storage")
    object VoiceGuide : TomaDestination("voice_guide")
    object Settings : TomaDestination("settings_main")
    object PushSetting : TomaDestination("push_setting")
    object EmailSetting : TomaDestination("email_setting")
    object CustomerCenter : TomaDestination("customer_center")
    object ContactUs : TomaDestination("contact_us")
    object PrivacyPolicy : TomaDestination("privacy_policy")
    object Chat : TomaDestination("ai_chat")
    object RecentHistory : TomaDestination("recent_history")
    object RecipeConfirm : TomaDestination("recipe_confirm/{keyword}?recipeData={recipeData}") {
        fun createRoute(keyword: String, recipeData: String? = null): String {
            val encodedKeyword = android.net.Uri.encode(keyword)
            return if (recipeData != null) {
                val encodedData = android.net.Uri.encode(recipeData)
                "recipe_confirm/$encodedKeyword?recipeData=$encodedData"
            } else {
                "recipe_confirm/$encodedKeyword"
            }
        }
    }
    object RecipeDetail : TomaDestination("recipe_detail/{keyword}?recipeData={recipeData}") {
        fun createRoute(keyword: String, recipeData: String? = null): String {
            val encodedKeyword = android.net.Uri.encode(keyword)
            return if (recipeData != null) {
                val encodedData = android.net.Uri.encode(recipeData)
                "recipe_detail/$encodedKeyword?recipeData=$encodedData"
            } else {
                "recipe_detail/$encodedKeyword"
            }
        }
    }
}
