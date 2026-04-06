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
        startDestination = "settings_main" // 👈 설정창 테스트를 위해 임시로 지정
    ) {
        // --- [팀원이 만든 홈 화면들] ---
        composable("home") {
            TomaHomeScreen(
                onMicClick = { navController.navigate("voice_guide") }
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
                onMicClick = {},
                onSuggestionClick = { selected ->
                    // TODO: 선택한 추천 문장 처리
                }
            )
        }

        // --- 🌟 [호진님이 만든 설정 화면들] 🌟 ---

        // 1. 설정 메인 화면
        composable("settings_main") {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onPushClick = { navController.navigate("push_setting") },
                onEmailClick = { navController.navigate("email_setting") },
                onCustomerCenterClick = { navController.navigate("customer_center") },
                onContactClick = { navController.navigate("contact_us") }
            )
        }

        // 2. 푸시 알림 설정 화면
        composable("push_setting") {
            PushSettingScreen(onBackClick = { navController.popBackStack() })
        }

        // 3. 이메일 수신 설정 화면
        composable("email_setting") {
            EmailSettingScreen(onBackClick = { navController.popBackStack() })
        }

        // 4. 고객센터(FAQ) 화면
        composable("customer_center") {
            CustomerCenterScreen(onBackClick = { navController.popBackStack() })
        }

        // 5. 문의하기 화면
        composable("contact_us") {
            ContactUsScreen(onBackClick = { navController.popBackStack() })
        }
    }
}