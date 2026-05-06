package com.capstone.toma.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.UserManager
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.VoiceViewModel

@Composable
fun SpeakerEnrollmentScreen(
    voiceViewModel: VoiceViewModel,
    onEnrollmentComplete: () -> Unit
) {
    val context = LocalContext.current
    var isUploading by remember { mutableStateOf(false) }

    // Collect states from ViewModel
    val enrollmentCount by voiceViewModel.enrollmentCount.collectAsState()
    val enrollmentStatus by voiceViewModel.enrollmentStatus.collectAsState()

    // Bixby-style sequential UI: calm, focused, single-state
    val mainText = when (enrollmentStatus) {
        VoiceViewModel.EnrollmentStatus.Idle -> "\"헤이 토마\" 라고\n말해주세요"
        VoiceViewModel.EnrollmentStatus.CollectingAmbient -> "잠시 조용히 해주세요...\n(주변 소음 수집 중 5초)"
        VoiceViewModel.EnrollmentStatus.Recording -> "🔴 듣고 있어요..."
        VoiceViewModel.EnrollmentStatus.Verifying -> "⏳ 확인 중..."
        is VoiceViewModel.EnrollmentStatus.Success -> "✅ 인식됨! ($enrollmentCount/10)"
        VoiceViewModel.EnrollmentStatus.Failed -> "다시 말씀해주세요"
    }

    LaunchedEffect(enrollmentCount) {
        if (enrollmentCount >= 10) {
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
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 1. Central Status Text
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = mainText,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "StatusTransition"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 40.sp
                    ),
                    textAlign = TextAlign.Center,
                    color = TomaPrimaryText
                )
            }
        }

        // 2. Dot Progress Bar (●●●○○○○○○○)
        DotProgressBar(
            count = enrollmentCount,
            total = 10,
            modifier = Modifier.padding(vertical = 40.dp)
        )

        // 3. Primary Action Button
        Box(
            modifier = Modifier
                .height(140.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isUploading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TomaMainOrange)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("개인화 작업 완료 중...", fontSize = 14.sp, color = TomaSecondaryText)
                }
            } else {
                // Button is only enabled in Idle, Success (ready for next), or Failed (retry) states
                val isButtonActive = enrollmentStatus == VoiceViewModel.EnrollmentStatus.Idle ||
                                     enrollmentStatus is VoiceViewModel.EnrollmentStatus.Success ||
                                     enrollmentStatus == VoiceViewModel.EnrollmentStatus.Failed

                Button(
                    onClick = { voiceViewModel.startEnrollmentRecording(context) },
                    enabled = isButtonActive,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TomaMainOrange,
                        contentColor = Color.White,
                        disabledContainerColor = TomaMainOrange.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth(0.7f),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (enrollmentCount == 0) "녹음 시작" else "다음 샘플 녹음",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. Skip / Secondary Action
        if (enrollmentStatus == VoiceViewModel.EnrollmentStatus.Idle && !isUploading) {
            TextButton(
                onClick = {
                    UserManager.setSkipped(context)
                    onEnrollmentComplete()
                }
            ) {
                Text(
                    "나중에 할게요", 
                    color = TomaSecondaryText,
                    fontSize = 14.sp
                )
            }
        } else {
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun DotProgressBar(count: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..total) {
            val isActive = i <= count
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (isActive) TomaMainOrange else Color.LightGray.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            )
        }
    }
}
