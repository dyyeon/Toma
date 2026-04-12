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

        // ✅ 2. VoskManager 초기화 및 콜백 연결
        voskManager = VoskManager(this) {
            runOnUiThread {
                Log.d("MainActivity", "🚨 [호출어 감지] ViewModel 상태를 업데이트합니다.")

                // 💡 "토마야"라고 부르면 ViewModel의 버튼 클릭 함수를 강제로 실행!
                // 이렇게 하면 화면이 Idle -> Listening 상태로 바뀝니다.
                voiceViewModel.onMicClick()

                Toast.makeText(this, "토마야 감지됨!", Toast.LENGTH_SHORT).show()
            }
        }

        // ✅ 3. Vosk 모델 시작
        voskManager.initModel()

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