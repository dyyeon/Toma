package com.capstone.toma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.capstone.toma.ui.screen.TomaHomeScreen
import com.capstone.toma.ui.screen.VoiceGuideScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TomaApp()
        }
    }
}

@Composable
fun TomaApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "settings_main" // 👈 팀원들을 위해 시작 화면은 다시 "home"으로 돌려두었습니다!
    ) {
        // --- [기존: 팀원이 만든 홈 및 음성 가이드 화면] ---
        composable("home") {
            TomaHomeScreen(
                onMicClick = {
                    navController.navigate("voice_guide")
                }
            )
        }

        composable("voice_guide") {
            VoiceGuideScreen(
                uiState = VoiceUiState.Idle,
                suggestions = listOf(
                    "메뉴 추천해줘",
                    "재료로 요리 찾아줘",
                    "간단한 레시피 알려줘",
                    "빠른 요리 찾아줘",
                    "쉬운 요리 추천해줘"
                ),
                onMicClick = {
                },
                onSuggestionClick = { selected ->
                    // TODO: 선택한 추천 문장 처리
                }
            )
        }

        // --- 🌟 [추가: 호진님이 만든 설정 화면들] 🌟 ---
        composable("settings_main") {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onPushClick = { navController.navigate("push_setting") },
                onEmailClick = { navController.navigate("email_setting") },
                onCustomerCenterClick = { navController.navigate("customer_center") },
                onContactClick = { navController.navigate("contact_us") }
            )
        }

        composable("push_setting") { PushSettingScreen(onBackClick = { navController.popBackStack() }) }
        composable("email_setting") { EmailSettingScreen(onBackClick = { navController.popBackStack() }) }
        composable("customer_center") { CustomerCenterScreen(onBackClick = { navController.popBackStack() }) }
        composable("contact_us") { ContactUsScreen(onBackClick = { navController.popBackStack() }) }
    }
}