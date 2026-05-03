package com.capstone.toma.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.capstone.toma.UserManager
import com.capstone.toma.viewmodel.VoiceViewModel

@Composable
fun SpeakerEnrollmentScreen(
    voiceViewModel: VoiceViewModel,
    onEnrollmentComplete: () -> Unit
) {
    val context = LocalContext.current
    var recordCount by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("버튼을 눌러 녹음을 시작하세요") }

    // VoiceViewModel에서 enrollmentCount 상태를 collect
    val enrollmentCount by voiceViewModel.enrollmentCount.collectAsState()

    LaunchedEffect(enrollmentCount) {
        recordCount = enrollmentCount
        if (enrollmentCount >= 30) {
            isRecording = false
            isUploading = true
            statusText = "Firebase 업로드 중..."
            voiceViewModel.uploadEnrollmentWavs(
                context = context,
                onComplete = {
                    isUploading = false
                    voiceViewModel.startModelPolling(context)
                    onEnrollmentComplete()
                },
                onError = { error ->
                    isUploading = false
                    statusText = "업로드 실패: $error\n잠시 후 다시 시도됩니다."
                    // 에러 시 count를 리셋하여 다시 시도하거나, 
                    // 혹은 UI에서 재시도 버튼을 노출하도록 할 수 있음.
                }
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("화자 등록", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(statusText)
        Spacer(modifier = Modifier.height(16.dp))

        // 진행률 바
        LinearProgressIndicator(
            progress = { recordCount / 30f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("$recordCount / 30")
        Spacer(modifier = Modifier.height(24.dp))

        when {
            isUploading -> CircularProgressIndicator()
            isRecording -> {
                Button(
                    onClick = {
                        isRecording = false
                        voiceViewModel.stopEnrollmentRecording()
                        statusText = "녹음 중지됨"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("녹음 중지") }
                Text("🔴 지금 '헤이 토마'라고 말하세요")
            }
            else -> {
                Button(onClick = {
                    isRecording = true
                    statusText = "'헤이 토마'라고 말하세요"
                    voiceViewModel.startEnrollmentRecording()
                }) { Text("녹음 시작") }
            }
        }

        if (!isUploading && !isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    UserManager.setSkipped(context)  // NOT setEnrolled
                    onEnrollmentComplete()
                }
            ) {
                Text(
                    text = "나중에 하기",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
