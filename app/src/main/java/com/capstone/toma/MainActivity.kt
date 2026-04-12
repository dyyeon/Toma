package com.capstone.toma

import android.Manifest // 💡 이거 임포트 확인!
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.capstone.toma.navigation.TomaNavHost
import com.capstone.toma.ui.theme.TomaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚨 앱 시작 시 마이크 권한이 있는지 확인하고, 없으면 팝업을 띄웁니다!
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

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

        // 임시로 만든 테스트 화면을 연결합니다.
        OpenAITestScreen()
    }
}