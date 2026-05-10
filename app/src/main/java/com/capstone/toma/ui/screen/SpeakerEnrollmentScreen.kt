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
import com.capstone.toma.ui.component.TomaTopAppBar
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.VoiceViewModel

@Composable
fun SpeakerEnrollmentScreen(
    voiceViewModel: VoiceViewModel,
    onEnrollmentComplete: () -> Unit,
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var isUploading by remember { mutableStateOf(false) }

    // Collect states from ViewModel
    val enrollmentCount by voiceViewModel.enrollmentCount.collectAsState()
    val enrollmentStatus by voiceViewModel.enrollmentStatus.collectAsState()

    // Reset audio session on entering to ensure clean capture
    DisposableEffect(Unit) {
        voiceViewModel.stopWakeWord()
        voiceViewModel.startWakeWord()
        onDispose {
            voiceViewModel.stopEnrollmentRecording()
        }
    }

    // Bixby-style sequential UI: calm, focused, single-state
    val mainText = when {
        isUploading -> "개인화 정보를\n안전하게 저장 중입니다"
        enrollmentCount >= 10 -> "모든 녹음 완료!\n분석을 시작합니다"
        enrollmentStatus == VoiceViewModel.EnrollmentStatus.Idle -> {
            if (enrollmentCount == 0) "\"헤이 토마\" 라고\n말해볼까요?"
            else "좋아요! 다음에도\n\"헤이 토마\"라고 해주세요"
        }
        enrollmentStatus == VoiceViewModel.EnrollmentStatus.CollectingAmbient -> "잠시 조용히 해주세요...\n주변 소음을 파악하고 있어요"
        enrollmentStatus == VoiceViewModel.EnrollmentStatus.Recording -> "🔴 듣고 있어요...\n지금 말씀해주세요!"
        enrollmentStatus == VoiceViewModel.EnrollmentStatus.Verifying -> "⏳ 목소리 데이터를\n확인하고 있어요"
        enrollmentStatus is VoiceViewModel.EnrollmentStatus.Success -> "✅ 아주 잘 들려요!\n($enrollmentCount/10)"
        enrollmentStatus == VoiceViewModel.EnrollmentStatus.Failed -> "잘 들리지 않았어요\n조금 더 크게 말씀해주세요"
        else -> ""
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
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TomaTopAppBar(
            showBackButton = true,
            onBackClick = onBackClick
        )

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
                    CircularProgressIndicator(
                        color = TomaMainOrange,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "개인화 작업 완료 중...",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TomaSecondaryText
                    )
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
