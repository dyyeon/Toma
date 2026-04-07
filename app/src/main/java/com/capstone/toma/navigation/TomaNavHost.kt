package com.capstone.toma.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.capstone.toma.ContactUsScreen
import com.capstone.toma.CustomerCenterScreen
import com.capstone.toma.EmailSettingScreen
import com.capstone.toma.PushSettingScreen
import com.capstone.toma.SettingsScreen
import com.capstone.toma.VoiceUiState
import com.capstone.toma.ui.screen.RecipeStorageScreen
import com.capstone.toma.ui.screen.TomaHomeScreen
import com.capstone.toma.ui.screen.VoiceGuideScreen

private val voiceSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘",
    "빠른 요리 찾아줘",
    "쉬운 요리 추천해줘"
)

@Composable
fun TomaNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = TomaDestination.Home.route
    ) {
        composable(TomaDestination.Home.route) {
            TomaHomeScreen(
                onMicClick = { navController.navigateSingleTop(TomaDestination.VoiceGuide.route) },
                onHomeClick = { navController.navigateSingleTop(TomaDestination.Home.route) },
                onStorageClick = { navController.navigateSingleTop(TomaDestination.RecipeStorage.route) },
                onSettingsClick = { navController.navigateSingleTop(TomaDestination.Settings.route) }
            )
        }

        composable(TomaDestination.RecipeStorage.route) {
            RecipeStorageScreen(
                onHomeClick = { navController.navigateSingleTop(TomaDestination.Home.route) },
                onStorageClick = { navController.navigateSingleTop(TomaDestination.RecipeStorage.route) },
                onSettingsClick = { navController.navigateSingleTop(TomaDestination.Settings.route) }
            )
        }

        composable(TomaDestination.VoiceGuide.route) {
            VoiceGuideScreen(
                uiState = VoiceUiState.Idle,
                suggestions = voiceSuggestions,
                onMicClick = {},
                onSuggestionClick = {}
            )
        }

        composable(TomaDestination.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onPushClick = { navController.navigate(TomaDestination.PushSetting.route) },
                onEmailClick = { navController.navigate(TomaDestination.EmailSetting.route) },
                onCustomerCenterClick = { navController.navigate(TomaDestination.CustomerCenter.route) },
                onContactClick = { navController.navigate(TomaDestination.ContactUs.route) }
            )
        }

        composable(TomaDestination.PushSetting.route) {
            PushSettingScreen(onBackClick = { navController.popBackStack() })
        }
        composable(TomaDestination.EmailSetting.route) {
            EmailSettingScreen(onBackClick = { navController.popBackStack() })
        }
        composable(TomaDestination.CustomerCenter.route) {
            CustomerCenterScreen(onBackClick = { navController.popBackStack() })
        }
        composable(TomaDestination.ContactUs.route) {
            ContactUsScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}
