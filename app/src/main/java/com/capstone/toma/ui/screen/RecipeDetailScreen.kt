package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onBackClick: () -> Unit = {},
    onFinish: (String, String?) -> Unit = { _, _ -> }
) {
    val storageViewModel: RecipeStorageViewModel = viewModel()
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val isFavorite by storageViewModel.isRecipeSaved(title).collectAsState(initial = false)

    RecipeDetailContent(
        keyword = keyword,
        recipeDataJson = recipeDataJson,
        isFavorite = isFavorite,
        onBackClick = onBackClick,
        onFavoriteClick = { storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite) },
        onFinish = onFinish
    )
}

@Composable
fun RecipeDetailContent(
    keyword: String,
    recipeDataJson: String?,
    isFavorite: Boolean,
    onBackClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onFinish: (String, String?) -> Unit
) {
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val steps = remember(recipeData) {
        recipeData?.optJSONArray("steps")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
    }
    val ingredients = remember(recipeData) {
        recipeData?.optJSONArray("ingredients")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
    }
    val imageUrl = recipeData?.optString("image_url")
    val difficulty = recipeData?.optString("difficulty") ?: "보통"
    val timeStr = recipeData?.optString("time") ?: "20분"

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val totalSteps = steps.size

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8F9FA)) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                RecipeTopBar(
                    onBackClick = onBackClick,
                    keyword = title,
                    isFavorite = isFavorite,
                    onFavoriteClick = onFavoriteClick
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 이미지 섹션 추가
                RecipeImageSection(imageUrl = imageUrl)

                Spacer(modifier = Modifier.height(24.dp))

                val progress = if (totalSteps > 0) (currentStepIndex.toFloat() / totalSteps.toFloat()) else 0f
                ProgressSection(current = currentStepIndex, total = totalSteps, progress = progress)

                Spacer(modifier = Modifier.height(32.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    if (currentStepIndex == 0) {
                        IngredientsSection(ingredients)
                    } else {
                        CurrentStepSection(
                            stepNumber = currentStepIndex,
                            stepText = steps.getOrNull(currentStepIndex - 1) ?: ""
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                InfoCardRow(timeStr, difficulty)

                Spacer(modifier = Modifier.height(24.dp))

                AiSuggestionSection()

                Spacer(modifier = Modifier.height(32.dp))

                BottomControlSection(
                    onPrevClick = { if (currentStepIndex > 0) currentStepIndex-- },
                    onNextClick = {
                        if (currentStepIndex < totalSteps) {
                            currentStepIndex++
                        } else {
                            onFinish(keyword, recipeDataJson)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(40.dp))
            }
            
            // 우측 하단 스캔 버튼
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                    shadowElevation = 2.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan",
                        modifier = Modifier.padding(10.dp),
                        tint = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipeTopBar(onBackClick: () -> Unit, keyword: String, isFavorite: Boolean, onFavoriteClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(40.dp).clickable { onBackClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEEEEEE))
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(10.dp), tint = Color.Black)
        }
        Text(
            text = titleCase(keyword),
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "Favorite",
                tint = if (isFavorite) TomaMainOrange else Color.Black,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 고온 주의 배지
        Surface(
            color = Color(0xFFFFECEC),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = Color(0xFFFA5252),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("고온 주의", color = Color(0xFFFA5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecipeImageSection(imageUrl: String?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFE9ECEF)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (imageUrl.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Restaurant, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(48.dp))
                }
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // LIVE FEED 배지
            Surface(
                modifier = Modifier.padding(16.dp),
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("LIVE FEED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(current: Int, total: Int, progress: Float) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (current == 0) "재료 준비" else "단계 $current / $total",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(progress * 100).toInt()}%",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TomaMainOrange
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE9ECEF), CircleShape)) {
            Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(
                brush = Brush.horizontalGradient(listOf(TomaMainOrange, Color(0xFFFFB347))),
                shape = CircleShape
            ))
        }
    }
}

@Composable
private fun IngredientsSection(ingredients: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = TomaMainOrange, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("준비할 재료", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TomaMainOrange)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
            shadowElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ingredients.forEach { ingredient ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(TomaMainOrange, CircleShape))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(ingredient, fontSize = 18.sp, color = Color(0xFF495057), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentStepSection(stepNumber: Int, stepText: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stepNumber.toString(),
            modifier = Modifier.align(Alignment.TopStart).offset(y = (-20).dp).alpha(0.05f),
            fontSize = 160.sp, fontWeight = FontWeight.Black, color = Color.Black
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        ) {
            Text("How to Cook", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TomaMainOrange, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stepText,
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF212529),
                lineHeight = 38.sp
            )
        }
    }
}

@Composable
private fun InfoCardRow(time: String, difficulty: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        InfoCardDetail(
            modifier = Modifier.weight(1f),
            label = "소요 시간",
            value = time,
            icon = Icons.Default.AccessTime,
            color = TomaMainOrange
        )
        InfoCardDetail(
            modifier = Modifier.weight(1f),
            label = "난이도",
            value = difficulty,
            icon = Icons.Default.Thermostat,
            color = TomaMainOrange
        )
    }
}

@Composable
private fun InfoCardDetail(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Surface(
        modifier = modifier.height(90.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
        shadowElevation = 2.dp
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(color))
            Column(modifier = Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun AiSuggestionSection() {
    Column {
        Text("AI 제안", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val suggestions = listOf(
                Triple("왜 생크림인가...", Icons.Default.QuestionMark, Color(0xFFFFB347)),
                Triple("타이머 설정", Icons.Default.Timer, Color(0xFFFF4E22)),
                Triple("읽어주기", Icons.Default.Hearing, Color(0xFF4dabf7))
            )
            suggestions.forEach { (text, icon, color) ->
                Surface(
                    modifier = Modifier.weight(1f).clickable { },
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomControlSection(onPrevClick: () -> Unit, onNextClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 이전 버튼
            IconButton(onClick = onPrevClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.Black)
            }

            // 큰 주황색 마이크 버튼
            Surface(
                modifier = Modifier.size(80.dp).clickable { },
                shape = CircleShape,
                color = TomaMainOrange,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(36.dp), tint = Color.White)
                }
            }

            // 다음 버튼
            Surface(
                modifier = Modifier.size(44.dp).clickable { onNextClick() },
                shape = CircleShape,
                color = Color(0xFFFFF3E8)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(12.dp), tint = TomaMainOrange)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("말씀하세요", color = TomaMainOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun titleCase(str: String) = str.lowercase().replaceFirstChar { it.uppercase() }

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecipeDetailScreenPreview() {
    RecipeDetailContent(
        keyword = "간장계란밥",
        recipeDataJson = null,
        isFavorite = true,
        onBackClick = {},
        onFavoriteClick = {},
        onFinish = { _, _ -> }
    )
}
