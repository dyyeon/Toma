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
        startDestination = "home"
    ) {
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
    }
}