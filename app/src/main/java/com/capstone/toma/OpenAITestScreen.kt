package com.capstone.toma

import android.Manifest
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

    // 상태 유지를 위해 remember 사용
    val openAiManager = remember { OpenAiManager() }
    val audioRecorder = remember { AudioRecorder(context) }

    var statusText by remember { mutableStateOf("마이크 권한을 허용하고 버튼을 눌러주세요.") }
    var isRecording by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("") }

    // 마이크 권한 요청 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            statusText = "버튼을 눌러 명령하세요\n(예: \"다음 단계 알려줘\")"
        } else {
            statusText = "마이크 권한이 필요합니다."
        }
    }

    // 화면이 처음 켜질 때 마이크 권한 요청
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🍅 토마 AI 임시 테스트", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(30.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (!isRecording) {
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
                                }
                            } else {
                                statusText = "❌ 음성 인식 실패 (Logcat 확인)"
                            }
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.size(width = 200.dp, height = 50.dp)
        ) {
            Text(if (isRecording) "녹음 중지" else "음성 명령 시작")
        }
    }
}