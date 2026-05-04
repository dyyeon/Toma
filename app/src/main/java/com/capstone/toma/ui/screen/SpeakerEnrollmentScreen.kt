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
    var isUploading by remember { mutableStateOf(false) }

    // VoiceViewModel에서 상태들을 collect
    val enrollmentCount by voiceViewModel.enrollmentCount.collectAsState()
    val enrollmentStatus by voiceViewModel.enrollmentStatus.collectAsState()

    val statusText = when (val status = enrollmentStatus) {
        VoiceViewModel.EnrollmentStatus.Idle -> "버튼을 눌러 녹음을 시작하세요"
        VoiceViewModel.EnrollmentStatus.Recording -> "🔴 지금 '헤이 토마'라고 말하세요"
        VoiceViewModel.EnrollmentStatus.Verifying -> "⏳ 확인 중..."
        is VoiceViewModel.EnrollmentStatus.Success -> "✅ 인식됨! (${status.count}/30)"
        VoiceViewModel.EnrollmentStatus.Failed -> "❌ 다시 말씀해주세요"
    }

    LaunchedEffect(enrollmentCount) {
        if (enrollmentCount >= 30) {
            isUploading = true
            voiceViewModel.uploadEnrollmentWavs(
                context = context,
                onComplete = {
                    isUploading = false
                    voiceViewModel.startModelPolling(context)
                    onEnrollmentComplete()
                },
                onError = { error ->
                    isUploading = false
                    // 에러 처리는 필요시 추가
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
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enrollmentStatus == VoiceViewModel.EnrollmentStatus.Failed) 
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 진행률 바
        LinearProgressIndicator(
            progress = { enrollmentCount / 30f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("$enrollmentCount / 30")
        Spacer(modifier = Modifier.height(24.dp))

        when {
            isUploading -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Firebase 업로드 중...")
            }
            enrollmentStatus == VoiceViewModel.EnrollmentStatus.Recording || 
            enrollmentStatus == VoiceViewModel.EnrollmentStatus.Verifying -> {
                Button(
                    onClick = {
                        voiceViewModel.stopEnrollmentRecording()
                    },
                    enabled = enrollmentStatus != VoiceViewModel.EnrollmentStatus.Verifying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("녹음 중지") }
            }
            else -> {
                Button(onClick = {
                    voiceViewModel.startEnrollmentRecording()
                }) { Text(if (enrollmentCount == 0) "녹음 시작" else "다음 샘플 녹음") }
            }
        }

        if (!isUploading && enrollmentStatus != VoiceViewModel.EnrollmentStatus.Recording && enrollmentStatus != VoiceViewModel.EnrollmentStatus.Verifying) {
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
