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
}
