package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.capstone.toma.ui.theme.TomaBackground
import com.capstone.toma.ui.theme.TomaCard
import com.capstone.toma.ui.theme.TomaMainOrange
import com.capstone.toma.ui.theme.TomaPrimaryText
import com.capstone.toma.ui.theme.TomaSecondaryText
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
        containerColor = TomaBackground,
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
                start = 20.dp,
                top = innerPadding.calculateTopPadding() + 20.dp,
                end = 20.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
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
                SectionTitle("\uC7AC\uB8CC")
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
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "\uB4A4\uB85C\uAC00\uAE30",
                tint = TomaPrimaryText
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = title,
            color = TomaPrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
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
            .height(220.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        if (imageUrl.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = "\uC694\uB9AC \uC774\uBBF8\uC9C0",
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
                label = "\uBD84\uB958",
                value = category
            )
            InfoCard(
                modifier = Modifier.weight(1f),
                label = "\uC18C\uC694 \uC2DC\uAC04",
                value = timeText,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = TomaMainOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                modifier = Modifier.weight(1f),
                label = "\uB09C\uC774\uB3C4",
                value = difficulty,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = null,
                        tint = TomaMainOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
    icon: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier.height(88.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TomaCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                color = TomaSecondaryText,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.invoke()
                if (icon != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = value,
                    color = TomaPrimaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TomaPrimaryText,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun IngredientCard(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 2.dp
    ) {
        Text(
            text = text,
            color = TomaPrimaryText,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun ConfirmDecisionBar(
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "\uC704 \uB808\uC2DC\uD53C\uB85C \uC548\uB0B4\uB97C \uC2DC\uC791\uD560\uAE4C\uC694?",
                color = TomaPrimaryText,
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
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TomaSecondaryText)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("NO", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onConfirmClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TomaMainOrange,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("YES", fontWeight = FontWeight.Bold)
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
        .takeIf { it.isNotBlank() && it != "\uC5C6\uC74C" }

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
