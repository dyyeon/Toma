package com.capstone.toma.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.capstone.toma.VoiceUiState

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
    onBackClick: () -> Unit = {}
) {
    val statusText = when (uiState) {
        VoiceUiState.Idle -> "READY"
        VoiceUiState.Listening -> "LISTENING"
        VoiceUiState.Processing -> "PROCESSING"
        VoiceUiState.Speaking -> "SPEAKING"
        VoiceUiState.Recovering -> "RECOVERING"
        VoiceUiState.Training -> "TRAINING"
        is VoiceUiState.Result -> "RESULT"
        is VoiceUiState.Error -> "ERROR"
    }

    val helperText = when (uiState) {
        VoiceUiState.Idle -> "레시피나 메뉴를 음성으로 요청해보세요"
        VoiceUiState.Listening -> "말씀하시는 내용을 듣고 있어요"
        VoiceUiState.Processing -> "음성을 분석하고 있어요"
        VoiceUiState.Speaking -> "답변을 들려드리고 있어요"
        VoiceUiState.Recovering -> "잠시 후 다시 시도할게요"
        VoiceUiState.Training -> "개인화 모델을 준비하고 있어요"
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

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier.padding(horizontal = 24.dp)
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
                    .height(260.dp),
                contentAlignment = Alignment.Center
            ) {
                VoiceMicButton(
                    isListening = uiState == VoiceUiState.Listening,
                    onClick = onMicClick
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                    visible = uiState == VoiceUiState.Listening,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ListeningEqualizer()
                }

                AnimatedVisibility(
                    visible = uiState is VoiceUiState.Result,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val result = uiState as? VoiceUiState.Result
                    if (result != null) {
                        ResultCard(text = result.text)
                    }
                }

                AnimatedVisibility(
                    visible = uiState is VoiceUiState.Error,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val error = uiState as? VoiceUiState.Error
                    if (error != null) {
                        ErrorCard(message = error.message)
                    }
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
                    SuggestionChip(
                        text = item,
                        onClick = { onSuggestionClick(item) }
                    )
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseRatio by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 600 else 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRatio"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "micScale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            isListening -> 0.25f
            pressed -> 0.15f
            else -> 0.1f
        },
        animationSpec = tween(300),
        label = "micGlowAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(240.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .drawBehind {
                val radius = (100.dp.toPx()) * pulseRatio
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to TomaMainOrange.copy(alpha = glowAlpha),
                        0.7f to TomaMainOrange.copy(alpha = glowAlpha * 0.5f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CircleShape,
            color = TomaMainOrange,
            shadowElevation = if (pressed) 4.dp else 12.dp,
            modifier = Modifier.size(130.dp)
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
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TAP TO SPEAK",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
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
private fun ListeningEqualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")

    val heights = List(5) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 400 + (index * 100),
                    easing = FastOutLinearInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(40.dp)
    ) {
        heights.forEach { heightRatio ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(32.dp * heightRatio.value)
                    .background(TomaMainOrange, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun ResultCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF20C997),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TomaPrimaryText,
                lineHeight = 22.sp
            )
        }
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

private val previewSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘",
    "빠른 요리 찾아줘",
    "쉬운 요리 추천해줘"
)

@Preview(name = "1. Idle 상태", showBackground = true, showSystemUi = true)
@Composable
private fun VoiceGuideIdlePreview() {
    VoiceGuideScreen(uiState = VoiceUiState.Idle, suggestions = previewSuggestions, onMicClick = {}, onSuggestionClick = {})
}

@Preview(name = "2. Listening 상태", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideListeningPreview() {
    VoiceGuideScreen(uiState = VoiceUiState.Listening, suggestions = previewSuggestions, onMicClick = {}, onSuggestionClick = {})
}

@Preview(name = "3. Result 결과", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideResultPreview() {
    VoiceGuideScreen(uiState = VoiceUiState.Result("김치볶음밥 레시피를 찾아드릴게요."), suggestions = previewSuggestions, onMicClick = {}, onSuggestionClick = {})
}

@Preview(name = "4. Error 에러", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun TomaVoiceGuideErrorPreview() {
    VoiceGuideScreen(uiState = VoiceUiState.Error("음성을 인식하지 못했어요. 다시 시도해 주세요."), suggestions = previewSuggestions, onMicClick = {}, onSuggestionClick = {})
}
