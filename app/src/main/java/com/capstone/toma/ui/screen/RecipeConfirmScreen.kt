package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.capstone.toma.ui.theme.*
import org.json.JSONObject

@Composable
fun RecipeConfirmScreen(
    keyword: String = "",
    recipeDataJson: String? = null,
    onBackClick: () -> Unit = {},
    onConfirmClick: () -> Unit = {},
    onRejectClick: () -> Unit = {}
) {
    val recipe = remember(keyword, recipeDataJson) {
        parseConfirmRecipe(keyword, recipeDataJson)
    }

    Scaffold(
        containerColor = Color(0xFFF8F9FA),
        bottomBar = {
            ConfirmDecisionBar(
                onConfirmClick = onConfirmClick,
                onRejectClick = onRejectClick
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                top = innerPadding.calculateTopPadding() + 20.dp,
                end = 24.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                ConfirmTopBar(
                    title = recipe.title,
                    onBackClick = onBackClick
                )
            }

            item {
                ConfirmImageSection(recipe.imageUrl)
            }

            item {
                InfoCardRow(
                    category = recipe.category,
                    timeText = recipe.timeText,
                    difficulty = recipe.difficulty
                )
            }

            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(TomaMainOrange)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "준비할 재료",
                            color = Color.Black,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            items(recipe.ingredients.chunked(2)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { ingredient ->
                        IngredientCard(
                            text = ingredient,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmTopBar(
    title: String,
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clickable { onBackClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
            shadowElevation = 2.dp
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "뒤로가기",
                modifier = Modifier.padding(12.dp),
                tint = Color.Black
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            color = Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConfirmImageSection(imageUrl: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = TomaMainOrange, spotColor = TomaMainOrange),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        if (imageUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFFFFF3E8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.RestaurantMenu,
                    contentDescription = null,
                    tint = TomaMainOrange.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = "요리 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun InfoCardRow(
    category: String,
    timeText: String,
    difficulty: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                modifier = Modifier.weight(1f),
                label = "카테고리",
                value = category,
                icon = Icons.Default.RestaurantMenu,
                iconColor = Color(0xFF20C997)
            )
            InfoCard(
                modifier = Modifier.weight(1f),
                label = "소요 시간",
                value = timeText,
                icon = Icons.Default.Schedule,
                iconColor = Color(0xFF4DABF7)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                modifier = Modifier.weight(1f),
                label = "난이도",
                value = difficulty,
                icon = Icons.Default.Thermostat,
                iconColor = Color(0xFFFAB005)
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color
) {
    Surface(
        modifier = modifier.height(90.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = iconColor)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun IngredientCard(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE9ECEF))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                color = Color(0xFF495057),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun ConfirmDecisionBar(
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 24.dp,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "이 레시피로 요리를 시작할까요?",
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRejectClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                    border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("아니오", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onConfirmClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = TomaMainOrange, spotColor = TomaMainOrange),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TomaMainOrange,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("시작하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


private data class ConfirmRecipeUiData(
    val title: String,
    val category: String,
    val ingredients: List<String>,
    val difficulty: String,
    val timeText: String,
    val imageUrl: String?
)

private fun parseConfirmRecipe(keyword: String, recipeDataJson: String?): ConfirmRecipeUiData {
    val json = recipeDataJson?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }

    val ingredients = parseStringArray(json, "ingredients")
    val title = json?.optString("title").orEmpty()
        .ifBlank { keyword }
    val category = json?.optString("category").orEmpty()
    val difficulty = json?.optString("difficulty").orEmpty()
    val timeText = json?.optString("time").orEmpty()
    val imageUrl = json?.optString("image_url").orEmpty()
        .takeIf { it.isNotBlank() && it != "없음" }

    return ConfirmRecipeUiData(
        title = title,
        category = category,
        ingredients = ingredients,
        difficulty = difficulty,
        timeText = timeText,
        imageUrl = imageUrl
    )
}

private fun parseStringArray(json: JSONObject?, key: String): List<String> {
    val array = json?.optJSONArray(key) ?: return emptyList()
    return List(array.length()) { index -> array.optString(index) }
        .filter { it.isNotBlank() }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecipeConfirmScreenPreview() {
    val mockRecipeJson = """
        {
            "title": "스팸 듬뿍 김치볶음밥",
            "category": "한식",
            "difficulty": "쉬움",
            "time": "15분",
            "image_url": "https://images.unsplash.com/photo-1512621776951-a57141f2eefd",
            "ingredients": [
                "신김치 1컵",
                "밥 1공기",
                "스팸 1캔",
                "참기름 1큰술",
                "계란 1개",
                "통깨 약간"
            ]
        }
    """.trimIndent()

    RecipeConfirmScreen(
        keyword = "김치볶음밥",
        recipeDataJson = mockRecipeJson,
        onBackClick = {},
        onConfirmClick = {},
        onRejectClick = {}
    )
}