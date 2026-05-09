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
    object SpeakerEnrollment : TomaDestination("speaker_enrollment")
    object RecipeComplete : TomaDestination("recipe_complete/{keyword}?recipeData={recipeData}") {
        fun createRoute(keyword: String, recipeData: String? = null): String {
            val encodedKeyword = android.net.Uri.encode(keyword)
            return if (recipeData != null) {
                val encodedData = android.net.Uri.encode(recipeData)
                "recipe_complete/$encodedKeyword?recipeData=$encodedData"
            } else {
                "recipe_complete/$encodedKeyword"
            }
        }
    }
    object RecipeConfirm : TomaDestination("recipe_confirm/{keyword}/{sourceType}?recipeData={recipeData}") {
        fun createRoute(keyword: String, sourceType: com.capstone.toma.model.RecipeSourceType, recipeData: String? = null): String {
            val encodedKeyword = android.net.Uri.encode(keyword)
            val sourceTypeStr = sourceType.name
            return if (recipeData != null) {
                val encodedData = android.net.Uri.encode(recipeData)
                "recipe_confirm/$encodedKeyword/$sourceTypeStr?recipeData=$encodedData"
            } else {
                "recipe_confirm/$encodedKeyword/$sourceTypeStr"
            }
        }
    }
    object RecipeDetail : TomaDestination("recipe_detail/{keyword}/{sourceType}?recipeData={recipeData}") {
        fun createRoute(keyword: String, sourceType: com.capstone.toma.model.RecipeSourceType, recipeData: String? = null): String {
            val encodedKeyword = android.net.Uri.encode(keyword)
            val sourceTypeStr = sourceType.name
            return if (recipeData != null) {
                val encodedData = android.net.Uri.encode(recipeData)
                "recipe_detail/$encodedKeyword/$sourceTypeStr?recipeData=$encodedData"
            } else {
                "recipe_detail/$encodedKeyword/$sourceTypeStr"
            }
        }
    }
}
