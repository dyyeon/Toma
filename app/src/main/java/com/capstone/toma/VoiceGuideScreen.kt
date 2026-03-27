package com.capstone.toma

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val VoiceBrand = Color(0xFFEE8C2B)
private val VoiceTextPrimary = Color(0xFF222222)
private val VoiceTextSecondary = Color(0xFF7A7A7A)
private val VoiceSurface = Color(0xFFF7F7F7)

@Composable
fun TomaVoiceGuideScreen(
    uiState: VoiceUiState,
    suggestions: List<String>,
    onMicClick: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when (uiState) {
        VoiceUiState.Idle -> "READY"
        VoiceUiState.Listening -> "LISTENING"
        VoiceUiState.Processing -> "PROCESSING"
        is VoiceUiState.Result -> "RESULT"
        is VoiceUiState.Error -> "ERROR"
    }

    val helperText = when (uiState) {
        VoiceUiState.Idle -> "레시피나 메뉴를 음성으로 요청해보세요"
        VoiceUiState.Listening -> "말씀하시는 내용을 듣고 있어요"
        VoiceUiState.Processing -> "음성을 분석하고 있어요"
        is VoiceUiState.Result -> "인식된 요청을 확인해보세요"
        is VoiceUiState.Error -> "다시 한 번 말씀해 주세요"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VoiceSurface)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        VoiceTopBar()

        Spacer(modifier = Modifier.height(34.dp))

        Text(
            text = "무엇을 도와드릴까요?",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = VoiceTextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "음성으로 레시피 검색이나 메뉴 추천을 요청해보세요.",
            fontSize = 15.sp,
            color = VoiceTextSecondary
        )

        Spacer(modifier = Modifier.height(28.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            VoiceMicButton(
                isListening = uiState == VoiceUiState.Listening,
                onClick = onMicClick
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        StatusBadge(statusText = statusText)

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = helperText,
            fontSize = 14.sp,
            color = VoiceTextSecondary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        AnimatedVisibility(visible = uiState == VoiceUiState.Listening) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ListeningBarRow()
            }
        }

        AnimatedVisibility(visible = uiState is VoiceUiState.Result) {
            val result = uiState as? VoiceUiState.Result
            if (result != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, VoiceBrand.copy(alpha = 0.18f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = result.text,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 15.sp,
                        color = VoiceTextPrimary
                    )
                }
            }
        }

        AnimatedVisibility(visible = uiState is VoiceUiState.Error) {
            val error = uiState as? VoiceUiState.Error
            if (error != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFFF4F4),
                    border = BorderStroke(1.dp, Color(0x33D32F2F)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = error.message,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp,
                        color = Color(0xFFC62828)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "추천 명령어",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = VoiceTextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        suggestions.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { item ->
                    SuggestionChip(
                        text = item,
                        onClick = { onSuggestionClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun VoiceTopBar() {
    Box(
        modifier = Modifier.fillMaxWidth().height(40.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .align(Alignment.CenterStart)
                .background(VoiceBrand.copy(alpha = 0.16f), CircleShape)
        )

        Text(
            text = "To-ma",
            modifier = Modifier.align(Alignment.Center),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = VoiceTextPrimary
        )

        IconButton(
            onClick = {},
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = VoiceTextPrimary
            )
        }
    }
}

@Composable
private fun VoiceMicButton(
    isListening: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val ringColor = if (isListening) {
        VoiceBrand.copy(alpha = 0.18f)
    } else {
        VoiceBrand.copy(alpha = 0.10f)
    }

    val fillColor = VoiceBrand
    val iconColor = Color.White

    val glowSize by animateDpAsState(
        targetValue = when {
            pressed -> 186.dp
            isListening -> 196.dp
            else -> 188.dp
        },
        label = "glowSize"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            pressed -> 0.10f
            isListening -> 0.22f
            else -> 0.12f
        },
        label = "glowAlpha"
    )

    val animatedElevation by animateDpAsState(
        targetValue = when {
            pressed -> 8.dp
            isListening -> 22.dp
            else -> 14.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "micShadowElevation"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "micScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(210.dp)
    ) {
        // 1. 글로우 레이어
        Box(
            modifier = Modifier
                .size(glowSize)
                .background(
                    VoiceBrand.copy(alpha = glowAlpha),
                    CircleShape
                )
        )

        // 2. 실제 마이크 버튼
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CircleShape,
            color = fillColor,
            border = BorderStroke(3.dp, ringColor),
            modifier = Modifier
                .size(176.dp)
                .graphicsLayer {
                    shadowElevation = animatedElevation.toPx()
                    shape = CircleShape
                    clip = false
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = iconColor,
                    modifier = Modifier.size(60.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(statusText: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = VoiceBrand.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, VoiceBrand.copy(alpha = 0.22f)),
    ) {
        Text(
            text = statusText,
            color = VoiceBrand,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ListeningBarRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(20.dp, 30.dp, 16.dp, 34.dp, 22.dp).forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(barHeight)
                    .background(VoiceBrand, RoundedCornerShape(999.dp))
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = VoiceBrand.copy(alpha = 0.22f)
        ),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = VoiceBrand.copy(alpha = 0.10f),
            labelColor = VoiceTextPrimary
        ),
        modifier = modifier
    )
}

private val previewSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘",
    "빠른 요리 찾아줘",
    "쉬운 요리 추천해줘"
)

@Preview(name = "Idle", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideIdlePreview() {
    TomaVoiceGuideScreen(
        uiState = VoiceUiState.Idle,
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Listening", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideListeningPreview() {
    TomaVoiceGuideScreen(
        uiState = VoiceUiState.Listening,
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Processing", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideProcessingPreview() {
    TomaVoiceGuideScreen(
        uiState = VoiceUiState.Processing,
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Result", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideResultPreview() {
    TomaVoiceGuideScreen(
        uiState = VoiceUiState.Result("김치볶음밥 레시피를 찾아드릴게요."),
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideErrorPreview() {
    TomaVoiceGuideScreen(
        uiState = VoiceUiState.Error("음성을 인식하지 못했어요. 다시 시도해 주세요."),
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}