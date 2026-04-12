package com.capstone.toma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.capstone.toma.navigation.TomaNavHost
import com.capstone.toma.ui.theme.TomaTheme

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
    TomaTheme {
        // 기존 네비게이션은 잠깐 주석(//) 처리해 둡니다.
        // TomaNavHost()

        // 임시로 만든 테스트 화면을 연결합니다. (테스트 끝나면 이거 지우고 위 주석 풀면 끝!)
        OpenAITestScreen()
    }
}