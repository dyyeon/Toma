package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.RecipeStorageViewModel
import org.json.JSONObject

@Composable
fun RecipeDetailScreen(
    keyword: String = "",
    recipeDataJson: String? = null,
    onBackClick: () -> Unit = {}
) {
    val storageViewModel: RecipeStorageViewModel = viewModel()
    
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let {
            try {
                JSONObject(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    val title = recipeData?.optString("title", keyword) ?: keyword
    val isFavorite by storageViewModel.isRecipeSaved(title).collectAsState(initial = false)

    val steps = remember(recipeData) {
        recipeData?.optJSONArray("steps")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: emptyList()
    }

    val ingredients = remember(recipeData) {
        recipeData?.optJSONArray("ingredients")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: emptyList()
    }

    val difficulty = recipeData?.optString("difficulty") ?: "보통"
    val timeStr = recipeData?.optString("time") ?: "20분"
    val imageUrl = recipeData?.optString("image_url")

    // 현재 단계를 관리 (0: 재료 확인, 1~N: 조리 단계)
    var currentStepIndex by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TomaBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 30.dp)
        ) {
            RecipeTopBar(
                onBackClick = onBackClick, 
                keyword = title,
                isFavorite = isFavorite,
                onFavoriteClick = {
                    storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            CookingImageSection(imageUrl)

            Spacer(modifier = Modifier.height(14.dp))

            val totalSteps = steps.size
            val progress = if (totalSteps > 0) (currentStepIndex.toFloat() / totalSteps.toFloat()) else 0f
            ProgressSection(current = currentStepIndex, total = totalSteps, progress = progress)

            Spacer(modifier = Modifier.height(20.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (currentStepIndex == 0) {
                    IngredientsSection(ingredients)
                } else {
                    CurrentStepSection(
                        stepNumber = currentStepIndex,
                        stepText = steps.getOrNull(currentStepIndex - 1) ?: "준비된 단계가 없습니다."
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            InfoCardRow(timeStr, difficulty)

            Spacer(modifier = Modifier.height(18.dp))

            AiSuggestionSection()

            Spacer(modifier = Modifier.height(20.dp))

            BottomControlSection(
                onPrevClick = { if (currentStepIndex > 0) currentStepIndex-- },
                onNextClick = { if (currentStepIndex < totalSteps) currentStepIndex++ }
            )
        }
    }
}

@Composable
private fun RecipeTopBar(
    onBackClick: () -> Unit, 
    keyword: String,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
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
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "즐겨찾기",
                tint = if (isFavorite) TomaMainOrange else Color(0xFF7D7D7D),
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFFFBE9E7)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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

            Surface(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart),
                shape = RoundedCornerShape(50),
                color = Color(0x99000000)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
}

@Composable
private fun IngredientsSection(ingredients: List<String>) {
    Column {
        Text(
            text = "🛒 준비할 재료",
            color = TomaMainOrange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (ingredients.isEmpty()) {
                    Text("재료 정보가 없습니다.", color = TomaSecondaryText)
                } else {
                    ingredients.forEach { ingredient ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("•", color = TomaMainOrange, modifier = Modifier.padding(end = 8.dp))
                            Text(text = ingredient, color = TomaPrimaryText, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(current: Int, total: Int, progress: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (current == 0) "재료 준비" else "$current / $total",
                color = TomaSecondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "${(progress * 100).toInt()}%",
                color = TomaMainOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50)),
            color = TomaMainOrange,
            trackColor = Color(0xFFE5E7EB)
        )
    }
}

@Composable
private fun CurrentStepSection(stepNumber: Int, stepText: String) {
    Column {
        Text(
            text = "STEP $stepNumber",
            color = TomaMainOrange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stepText,
            color = TomaPrimaryText,
            fontSize = 22.sp,
            lineHeight = 32.sp,
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
    Surface(
        modifier = modifier.clickable { },
        shape = RoundedCornerShape(50),
        color = Color(0xFFF3F4F6),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
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
}

@Composable
private fun BottomControlSection(
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit
) {
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
            onClick = onPrevClick,
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
                onClick = { /* 음성 인식 시작 */ },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성",
                        modifier = Modifier.size(28.dp)
                    )
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "말씀하세요", color = TomaMainOrange, fontSize = 11.sp)
        }

        CircleButton(
            containerColor = TomaLightOrange,
            iconTint = TomaMainOrange,
            size = 52.dp,
            onClick = onNextClick,
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
    onClick: () -> Unit = {},
    icon: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable { onClick() },
        color = containerColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides iconTint) {
                icon()
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecipeDetailScreenPreview() {
    RecipeDetailScreen()
}
