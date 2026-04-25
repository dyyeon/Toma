package com.capstone.toma

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels // 추가
import com.capstone.toma.navigation.TomaNavHost
import com.capstone.toma.ui.theme.TomaTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.capstone.toma.viewmodel.VoiceViewModel // ViewModel 위치 확인

class MainActivity : ComponentActivity() {

    private lateinit var voskManager: VoskManager

    // ✅ 1. ViewModel을 Activity 레벨에서 선언 (UI와 공유하기 위함)
    private val voiceViewModel: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 팀원들의 스플래시 화면 유지
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // 권한 체크
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        /* ✅ Vosk 기능 잠시 비활성화 (개발 편의를 위함)
        voskManager = VoskManager(this) {
            runOnUiThread {
                Log.d("MainActivity", "🚨 [호출어 감지] ViewModel 상태를 업데이트합니다.")
                voiceViewModel.onMicClick()
                Toast.makeText(this, "토마야 감지됨!", Toast.LENGTH_SHORT).show()
            }
        }

        voiceViewModel.onRecordingStarted = {
            Log.d("MainActivity", "🔇 수동 녹음 시작 - Vosk 중지")
            voskManager.stopListening()
        }
        voiceViewModel.onRecordingStopped = {
            Log.d("MainActivity", "🎤 수동 녹음 종료 - Vosk 재개")
            voskManager.startListening()
        }

        voskManager.initModel()
        */

        // 4. 메인 화면 띄우기
        setContent {
            TomaTheme {
                // TomaNavHost 내부에서 동일한 VoiceViewModel을 사용할 수 있게 됩니다.
                TomaNavHost()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        voskManager.stopListening()
    }

    override fun onRestart() {
        super.onRestart()
        if (!voskManager.isListening()) {
            voskManager.startListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voskManager.release()
    }
}