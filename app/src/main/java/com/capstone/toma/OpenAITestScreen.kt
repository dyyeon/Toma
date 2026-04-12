package com.capstone.toma

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OpenAITestScreen() {
    val context = LocalContext.current

    // 1. 매니저 클래스들 준비
    val openAiManager = remember { OpenAiManager() }
    val audioRecorder = remember { AudioRecorder(context) }

    // 상태 변수들
    var statusText by remember { mutableStateOf("보스크 모델 로딩 중...") }
    var isRecording by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("") }

    // 2. VoskManager 설정 (호출어 감지 시 동작 정의)
    val voskManager = remember {
        VoskManager(
            context = context,
            onWakeWordDetected = {
                // 🚨 "토마야"가 들렸을 때 실행되는 구간
                if (!isRecording) {
                    Log.d("TomaTest", "호출어 감지! 자동으로 녹음을 시작합니다.")
                    audioRecorder.startRecording()
                    isRecording = true
                    statusText = "🎤 '토마야' 감지! 듣고 있어요...\n(말을 마치면 중지 버튼을 누르세요)"
                    recognizedText = ""
                }
            }
        )
    }

    // 마이크 권한 요청 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            statusText = "준비 완료! '토마야'라고 부르거나\n버튼을 눌러 명령하세요."
            voskManager.initModel() // 권한 허용 시 보스크 초기화
        } else {
            statusText = "마이크 권한이 거부되었습니다."
        }
    }

    // 화면이 켜지자마자 권한 요청
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🍅 토마 AI 음성 제어 테스트", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(30.dp))

        // 상태 표시창
        Surface(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // 수동 제어 버튼
        Button(
            onClick = {
                if (!isRecording) {
                    // 수동 녹음 시작 시 보스크 잠시 중지 (마이크 경합 방지)
                    voskManager.stopListening()
                    audioRecorder.startRecording()
                    isRecording = true
                    statusText = "🎤 듣고 있어요...\n(말을 마치면 다시 누르세요)"
                    recognizedText = ""
                } else {
                    isRecording = false
                    statusText = "⏳ 서버 분석 중..."
                    val file = audioRecorder.stopRecording()

                    if (file != null) {
                        openAiManager.transcribeAudio(file) { text ->
                            if (text != null) {
                                recognizedText = text
                                statusText = "인식: $text\n의도 파악 중..."

                                openAiManager.analyzeIntent(text) { intent ->
                                    statusText = "인식된 말: $recognizedText\n\n🎯 최종 의도: $intent"

                                    // 분석이 끝나면 다시 보스크 호출어 감지 시작!
                                    voskManager.startListening()
                                }
                            } else {
                                statusText = "❌ 음성 인식 실패\n다시 '토마야'라고 불러보세요."
                                voskManager.startListening()
                            }
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.size(width = 220.dp, height = 55.dp)
        ) {
            Text(if (isRecording) "말하기 완료 (중지)" else "음성 명령 시작")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Tip: '토마야'라고 부르면 자동으로 시작됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}