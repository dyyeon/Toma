package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.capstone.toma.ui.theme.*


import androidx.compose.runtime.remember
import org.json.JSONObject

@Composable
fun RecipeDetailScreen(
    keyword: String = "",
    recipeDataJson: String? = null,
    onBackClick: () -> Unit = {}
) {
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let {
            try {
                JSONObject(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    val steps = recipeData?.optJSONArray("steps")?.let { array ->
        List(array.length()) { array.getString(it) }
    } ?: emptyList()

    val difficulty = recipeData?.optString("difficulty") ?: "보통"
    val time = recipeData?.optString("time") ?: "20분"
    val imageUrl = recipeData?.optString("image_url")

    // 사용하지 않는 ingredients 변수 제거 또는 로그 추가 (추후 재료 화면 구현 시 활용 가능)
    // val ingredients = ...

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TomaBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 30.dp)
        ) {
            RecipeTopBar(onBackClick, keyword)

            Spacer(modifier = Modifier.height(16.dp))

            CookingImageSection(imageUrl)

            Spacer(modifier = Modifier.height(14.dp))

            ProgressSection()

            Spacer(modifier = Modifier.height(20.dp))

            CurrentStepSection(steps.getOrNull(0) ?: "준비된 단계가 없습니다.")

            Spacer(modifier = Modifier.height(18.dp))

            InfoCardRow(time, difficulty)

            Spacer(modifier = Modifier.height(18.dp))

            AiSuggestionSection()

            Spacer(modifier = Modifier.weight(1f))

            BottomControlSection()
        }
    }
}

@Composable
private fun RecipeTopBar(onBackClick: () -> Unit, keyword: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "닫기",
                tint = Color(0xFF7D7D7D),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = if (keyword.isNotBlank()) keyword else "To-ma",
            color = if (keyword.isNotBlank()) Color.Black else TomaMainOrange,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFBE9E7))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "주의",
                tint = Color(0xFFE46A5D),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "고온 주의",
                color = Color(0xFFE46A5D),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CookingImageSection(imageUrl: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box {
            AsyncImage(
                model = imageUrl ?: "https://images.unsplash.com/photo-1512621776951-a57141f2eefd",
                contentDescription = "조리 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "LIVE FEED",
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun ProgressSection() {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "3 / 8",
                color = TomaSecondaryText,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "▌▌",
                color = Color(0xFFB6BDC9),
                fontSize = 10.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "45%",
                color = TomaMainOrange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LinearProgressIndicator(
            progress = { 0.45f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = TomaMainOrange,
            trackColor = Color(0xFFE5E7EB)
        )
    }
}

@Composable
private fun CurrentStepSection(stepText: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "✕ 진행 중인 단계",
                color = TomaMainOrange,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stepText,
            color = TomaPrimaryText,
            fontSize = 24.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InfoCardRow(time: String, difficulty: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoCard(
            modifier = Modifier.weight(1f),
            label = "소요 시간",
            value = time,
            icon = {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = TomaMainOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        InfoCard(
            modifier = Modifier.weight(1f),
            label = "난이도",
            value = difficulty,
            icon = {
                Icon(
                    imageVector = Icons.Default.Thermostat,
                    contentDescription = null,
                    tint = Color(0xFFFF6B57),
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: @Composable () -> Unit
) {
    Card(
        modifier = modifier.height(86.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TomaCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(TomaMainOrange)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    color = TomaSecondaryText,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = value,
                        color = TomaPrimaryText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AiSuggestionSection() {
    Column {
        Text(
            text = "AI 제안",
            color = TomaSecondaryText,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestionChip(
                modifier = Modifier.weight(1f),
                text = "왜 생크림인가요?",
                icon = {
                    Icon(
                        imageVector = Icons.Default.QuestionMark,
                        contentDescription = null,
                        tint = TomaMainOrange,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )

            SuggestionChip(
                modifier = Modifier.weight(1f),
                text = "타이머 설정",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color(0xFFFF6B57),
                        modifier = Modifier.size(14.dp)
                    )
                }
            )

            SuggestionChip(
                modifier = Modifier.weight(1f),
                text = "읽어주기",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = TomaBlue,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    modifier: Modifier = Modifier,
    text: String,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFF3F4F6))
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = TomaPrimaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomControlSection() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CircleButton(
            containerColor = Color(0xFFF1F3F5),
            iconTint = TomaPrimaryText,
            size = 52.dp,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "이전",
                    modifier = Modifier.size(22.dp)
                )
            }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircleButton(
                containerColor = TomaMainOrange,
                iconTint = Color.White,
                size = 64.dp,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성",
                        modifier = Modifier.size(28.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "듣고 있어요",
                color = TomaMainOrange,
                fontSize = 11.sp
            )
        }

        CircleButton(
            containerColor = TomaLightOrange,
            iconTint = TomaMainOrange,
            size = 52.dp,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "다음",
                    modifier = Modifier.size(22.dp)
                )
            }
        )
    }
}

@Composable
private fun CircleButton(
    containerColor: Color,
    iconTint: Color,
    size: androidx.compose.ui.unit.Dp,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides iconTint
        ) {
            icon()
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecipeDetailScreenPreview() {
    RecipeDetailScreen()
}
