package com.capstone.toma.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.capstone.toma.ui.component.TomaTopAppBar
import com.capstone.toma.VoiceUiState
import com.capstone.toma.viewmodel.VoiceViewModel

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaPrimaryText = Color(0xFF212529)
private val TomaSecondaryText = Color(0xFF868E96)
private val TomaCardBorder = Color(0xFFF1F3F5)

@Composable
fun VoiceGuideScreen(
    uiState: VoiceUiState,
    suggestions: List<String>,
    onMicClick: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onBackClick: () -> Unit = {},
    voiceViewModel: VoiceViewModel
) {
    val durationSeconds by voiceViewModel.recordingDurationSeconds.collectAsState()
    val isListening = uiState == VoiceUiState.Listening

    val statusText = when (uiState) {
        VoiceUiState.Idle -> "READY"
        VoiceUiState.Listening -> "RECORDING"
        VoiceUiState.Processing -> "ANALYZING"
        VoiceUiState.Speaking -> "SPEAKING"
        VoiceUiState.Training -> "TRAINING"
        VoiceUiState.Recovering -> "RECOVERING"
        is VoiceUiState.Result -> "RESULT"
        is VoiceUiState.Error -> "ERROR"
    }

    val helperText = when (uiState) {
        VoiceUiState.Idle -> "레시피나 메뉴를 음성으로 요청해보세요"
        VoiceUiState.Listening -> "녹음 중 • 탭하여 종료"
        VoiceUiState.Processing -> "음성을 열심히 분석하고 있어요"
        VoiceUiState.Speaking -> "TOMA가 답변을 말하고 있어요"
        VoiceUiState.Training -> "목소리를 학습하고 있어요"
        VoiceUiState.Recovering -> "잠시 후 다시 시도해주세요"
        is VoiceUiState.Result -> "인식된 요청을 확인해보세요"
        is VoiceUiState.Error -> "다시 한 번 말씀해 주세요"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaBackground)
            .padding(bottom = 24.dp)
    ) {
        TomaTopAppBar(showBackButton = true, onBackClick = onBackClick)

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = "무엇을 도와드릴까요?",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TomaPrimaryText,
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "음성으로 레시피 검색이나\n메뉴 추천을 편리하게 요청해보세요.",
                fontSize = 15.sp,
                color = TomaSecondaryText,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(56.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                VoiceMicButton(
                    isListening = isListening,
                    onClick = onMicClick
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Timer shown only while recording.
                AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val mm = (durationSeconds / 60).coerceAtLeast(0)
                    val ss = (durationSeconds % 60).coerceAtLeast(0)
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", mm, ss),
                        color = TomaMainOrange,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                StatusBadge(
                    statusText = statusText,
                    isError = uiState is VoiceUiState.Error
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = helperText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TomaSecondaryText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = uiState is VoiceUiState.Error,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val error = uiState as? VoiceUiState.Error
                    if (error != null) ErrorCard(message = error.message)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "💡 이런 명령어는 어떠세요?",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TomaPrimaryText
            )

            Spacer(modifier = Modifier.height(16.dp))

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                suggestions.forEach { item ->
                    SuggestionChip(text = item, onClick = { onSuggestionClick(item) })
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun VoiceMicButton(
    isListening: Boolean,
    onClick: () -> Unit
) {
    // Single source of animation — only active while listening.
    // Idle: static button. Listening: breathing scale 1.0 ↔ 1.07, 900ms, EaseInOut.
    val transition = rememberInfiniteTransition(label = "micPulse")
    val animatedScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                1.0f at 0 using FastOutSlowInEasing
                1.07f at 450 using FastOutSlowInEasing
                1.0f at 900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )

    // Apply scale only while listening; otherwise static at 1.0.
    val finalScale = if (isListening) animatedScale else 1.0f

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = TomaMainOrange, // always orange — no red/stop color swap
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(140.dp)
            .graphicsLayer {
                scaleX = finalScale
                scaleY = finalScale
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop" else "Mic",
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

@Composable
private fun StatusBadge(statusText: String, isError: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isError) Color(0xFFFFF4F4) else TomaMainOrange.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, if (isError) Color(0xFFFFE3E3) else TomaMainOrange.copy(alpha = 0.2f))
    ) {
        Text(
            text = statusText,
            color = if (isError) Color(0xFFE03131) else TomaMainOrange,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFFF4F4),
        border = BorderStroke(1.dp, Color(0xFFFFE3E3)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFE03131),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFC62828),
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.White,
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 1.dp
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = TomaPrimaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
