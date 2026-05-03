package com.capstone.toma.ui.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.ui.component.TomaTopAppBar
import com.capstone.toma.ui.theme.*
import com.capstone.toma.VoiceUiState
@Composable
fun VoiceGuideScreen(
    uiState: VoiceUiState,
    suggestions: List<String>,
    onMicClick: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onBackClick: () -> Unit = {}
) {
    val statusText = when (uiState) {
        VoiceUiState.Idle -> "READY"
        VoiceUiState.Listening -> "LISTENING"
        VoiceUiState.Processing -> "PROCESSING"
        VoiceUiState.Speaking -> "SPEAKING"
        VoiceUiState.Recovering -> "RECOVERING"
        is VoiceUiState.Result -> "RESULT"
        is VoiceUiState.Error -> "ERROR"
    }

    val helperText = when (uiState) {
        VoiceUiState.Idle -> "레시피나 메뉴를 음성으로 요청해보세요"
        VoiceUiState.Listening -> "말씀하시는 내용을 듣고 있어요"
        VoiceUiState.Processing -> "음성을 분석하고 있어요"
        VoiceUiState.Speaking -> "답변을 들려드리고 있어요"
        VoiceUiState.Recovering -> "잠시 후 다시 시도할게요"
        is VoiceUiState.Result -> "인식된 요청을 확인해보세요"
        is VoiceUiState.Error -> "다시 한 번 말씀해 주세요"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaBackground)
            .padding(bottom = 24.dp)
    ) {
        TomaTopAppBar(
            title = "음성 안내",
            showBackButton = true,
            onBackClick = onBackClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = "무엇을 도와드릴까요?",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = TomaPrimaryText
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "음성으로 레시피 검색이나 메뉴 추천을 요청해보세요.",
                fontSize = 15.sp,
                color = TomaSecondaryText
            )

            Spacer(modifier = Modifier.height(56.dp)) // Increased spacing above

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                VoiceMicButton(
                    isListening = uiState == VoiceUiState.Listening,
                    onClick = onMicClick
                )
            }

            Spacer(modifier = Modifier.height(48.dp)) // Increased spacing below

            StatusBadge(
                statusText = statusText
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = helperText,
                fontSize = 14.sp,
                color = TomaSecondaryText,
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
                        border = BorderStroke(1.dp, TomaMainOrange.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = result.text,
                            modifier = Modifier.padding(13.dp),
                            fontSize = 15.sp,
                            color = TomaPrimaryText
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
                color = TomaPrimaryText
            )

            Spacer(modifier = Modifier.height(12.dp))

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                suggestions.forEach { item ->
                    SuggestionChip(
                        text = item,
                        onClick = { onSuggestionClick(item) }
                    )
                }
            }
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

    val animatedScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(),
        label = "micScale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            isListening -> 0.85f // Increased for better visibility
            pressed -> 0.65f
            else -> 0.50f
        },
        animationSpec = spring(),
        label = "micGlowAlpha"
    )

    val glowRadius by animateDpAsState(
        targetValue = when {
            isListening -> 180.dp // Increased radius
            pressed -> 140.dp
            else -> 155.dp
        },
        animationSpec = spring(),
        label = "micGlowRadius"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(176.dp)
            .drawBehind {
                val buttonRadius = 88.dp.toPx()
                val currentGlowRadius = glowRadius.toPx()

                if (currentGlowRadius > buttonRadius) {
                    val stop = (buttonRadius / currentGlowRadius).coerceIn(0f, 0.95f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to TomaMainOrange.copy(alpha = glowAlpha),
                            stop to TomaMainOrange.copy(alpha = glowAlpha),
                            stop + (1f - stop) * 0.4f to TomaMainOrange.copy(alpha = glowAlpha * 0.3f),
                            1f to Color.Transparent,
                            center = center,
                            radius = currentGlowRadius
                        ),
                        radius = currentGlowRadius,
                        center = center
                    )
                }
            }
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CircleShape,
            color = TomaMainOrange.copy(alpha = 0.8f),
            modifier = Modifier
                .size(182.dp)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    clip = false
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap to say To-ma",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    statusText: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = statusText,
        color = TomaMainOrange.copy(alpha = 0.8f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = modifier
    )
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
                    .background(TomaMainOrange, RoundedCornerShape(999.dp))
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
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 1.dp,
            color = TomaMainOrange.copy(alpha = 0.22f)
        ),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = TomaMainOrange.copy(alpha = 0.10f),
            labelColor = TomaPrimaryText
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

@Preview(name = "Idle", showBackground = true, showSystemUi = true)
@Composable
private fun VoiceGuideIdlePreview() {
    VoiceGuideScreen(
        uiState = VoiceUiState.Idle,
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Listening", showBackground = true, widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
private fun TomaVoiceGuideListeningPreview() {
    VoiceGuideScreen(
        uiState = VoiceUiState.Listening,
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Processing", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideProcessingPreview() {
    VoiceGuideScreen(
        uiState = VoiceUiState.Processing,
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Result", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideResultPreview() {
    VoiceGuideScreen(
        uiState = VoiceUiState.Result("김치볶음밥 레시피를 찾아드릴게요."),
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}

@Preview(name = "Error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideErrorPreview() {
    VoiceGuideScreen(
        uiState = VoiceUiState.Error("음성을 인식하지 못했어요. 다시 시도해 주세요."),
        suggestions = previewSuggestions,
        onMicClick = {},
        onSuggestionClick = {}
    )
}
