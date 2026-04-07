package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.ui.component.TomaActionMenuButton
import com.capstone.toma.ui.component.TomaActionMenuItem
import com.capstone.toma.ui.theme.TomaMainRed
import com.capstone.toma.ui.theme.TomaPrimaryText
import com.capstone.toma.ui.theme.TomaSecondaryText
import kotlinx.coroutines.launch

private val StorageBg = Color(0xFFFFFBF7)
private val StorageCard = Color.White
private val StorageChip = Color(0xFFFFEFE3)
private val StorageAccent = TomaMainRed
private val StorageInk = TomaPrimaryText
private val StorageMuted = TomaSecondaryText

private data class StoredRecipe(
    val id: String,
    val title: String,
    val category: String,
    val story: String,
    val time: Int,
    val difficulty: String,
    val servings: Int,
    val calories: Int,
    val rating: Double,
    val favorite: Boolean,
    val ingredients: List<String>,
    val steps: List<String>
)

@Composable
fun RecipeStorageScreen(
    onHomeClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var recipes by remember { mutableStateOf(sampleRecipes) }
    var query by rememberSaveable { mutableStateOf("") }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val opened = recipes.firstOrNull { it.id == openedId }
    val filtered = remember(recipes, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) recipes else recipes.filter {
            listOf(it.title, it.category, it.story).any { text -> text.contains(keyword, true) } ||
                it.ingredients.any { ingredient -> ingredient.contains(keyword, true) }
        }
    }
    Scaffold(
        containerColor = StorageBg,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (opened == null) {
                FloatingActionButton(
                    onClick = { scope.launch { snackbar.showSnackbar("추가 화면은 다음 단계에서 연결하면 됩니다.") } },
                    containerColor = StorageAccent,
                    contentColor = Color.White
                ) { Icon(Icons.Default.Add, contentDescription = "레시피 추가") }
            }
        }
    ) { inner ->
        if (opened == null) {
            StorageList(
                inner = inner,
                recipes = filtered,
                totalCount = recipes.size,
                query = query,
                onQueryChange = { query = it },
                onHomeClick = onHomeClick,
                onStorageClick = onStorageClick,
                onSettingsClick = onSettingsClick,
                onOpen = { openedId = it.id },
                onFavoriteToggle = { recipe -> recipes = recipes.toggleFavorite(recipe.id) }
            )
        } else {
            StorageDetail(
                inner = inner,
                recipe = opened,
                onBack = { openedId = null },
                onHomeClick = onHomeClick,
                onStorageClick = onStorageClick,
                onSettingsClick = onSettingsClick,
                onFavoriteToggle = { recipes = recipes.toggleFavorite(opened.id) }
            )
        }
    }
}

@Composable
private fun StorageList(
    inner: PaddingValues,
    recipes: List<StoredRecipe>,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onHomeClick: () -> Unit,
    onStorageClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onOpen: (StoredRecipe) -> Unit,
    onFavoriteToggle: (StoredRecipe) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StorageBg),
        contentPadding = PaddingValues(20.dp, inner.calculateTopPadding() + 12.dp, 20.dp, inner.calculateBottomPadding() + 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { StorageHeader(onHomeClick, onStorageClick, onSettingsClick) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("키워드를 입력하세요.", color = StorageMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = StorageMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = StorageInk,
                        unfocusedTextColor = StorageInk,
                        focusedContainerColor = StorageCard,
                        unfocusedContainerColor = StorageCard,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = StorageAccent
                    )
                )
                Text(if (query.isBlank()) "${totalCount}개의 저장 레시피" else "${recipes.size} / ${totalCount}개 검색됨", color = StorageMuted)
            }
        }
        if (recipes.isEmpty()) item { EmptySearchCard() } else items(recipes, key = { it.id }) { recipe ->
            RecipeCard(recipe, { onOpen(recipe) }, { onFavoriteToggle(recipe) })
        }
    }
}

@Composable
private fun StorageDetail(
    inner: PaddingValues,
    recipe: StoredRecipe,
    onBack: () -> Unit,
    onHomeClick: () -> Unit,
    onStorageClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StorageBg),
        contentPadding = PaddingValues(20.dp, inner.calculateTopPadding() + 12.dp, 20.dp, inner.calculateBottomPadding() + 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.background(StorageCard, CircleShape)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "뒤로", tint = StorageInk)
            }
            Spacer(Modifier.width(10.dp))
            Text("레시피 상세", color = StorageInk, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            StorageMenu(onHomeClick, onStorageClick, onSettingsClick)
        }
        }
        item { HeroCard(recipe, onFavoriteToggle) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(recipe.title, color = StorageInk, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${recipe.time}m", color = StorageAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("TOTAL TIME", color = StorageMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricChip(String.format("%.1f", recipe.rating), true)
                    MetricChip(recipe.difficulty, false)
                    MetricChip("${recipe.calories} kcal", false)
                }
                Text("\"${recipe.story}\"", color = StorageMuted, lineHeight = 26.sp)
            }
        }
        item { SectionTitle("재료", "(${recipe.servings}인분)") }
        items(recipe.ingredients.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ingredient ->
                    Surface(color = StorageCard, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f)) {
                        Text(ingredient, color = StorageInk, modifier = Modifier.padding(16.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { SectionTitle("조리 순서") }
        itemsIndexed(recipe.steps) { index, step -> StepCard(index + 1, step) }
    }
}

@Composable
private fun StorageHeader(onHomeClick: () -> Unit, onStorageClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("내 레시피", color = StorageInk, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        StorageMenu(onHomeClick, onStorageClick, onSettingsClick)
    }
}

@Composable
private fun StorageMenu(onHomeClick: () -> Unit, onStorageClick: () -> Unit, onSettingsClick: () -> Unit) {
    TomaActionMenuButton(
        items = listOf(
            TomaActionMenuItem("홈", onHomeClick),
            TomaActionMenuItem("저장소", onStorageClick),
            TomaActionMenuItem("설정", onSettingsClick)
        ),
        containerColor = StorageCard,
        iconTint = StorageInk
    )
}

@Composable
private fun RecipeCard(recipe: StoredRecipe, onOpen: () -> Unit, onFavoriteToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(212.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(palette(recipe.category)))
        )
        Text("${recipe.time}분 조리 · ${recipe.category}", color = StorageAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(recipe.title, color = StorageInk, fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(24.dp)) {
                Icon(if (recipe.favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "즐겨찾기", tint = StorageAccent, modifier = Modifier.size(18.dp))
            }
        }
        Text("${recipe.difficulty} · ${recipe.servings}인분", color = StorageMuted)
    }
}

@Composable
private fun HeroCard(recipe: StoredRecipe, onFavoriteToggle: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(palette(recipe.category)))
    ) {
        Surface(color = StorageAccent, shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
            Text(recipe.category, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        IconButton(onClick = onFavoriteToggle, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.White.copy(alpha = 0.92f), CircleShape)) {
            Icon(if (recipe.favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "즐겨찾기", tint = StorageAccent)
        }
    }
}

@Composable
private fun MetricChip(text: String, star: Boolean) {
    Surface(color = StorageChip, shape = RoundedCornerShape(18.dp)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (star) Icon(Icons.Default.Star, contentDescription = null, tint = StorageAccent, modifier = Modifier.size(15.dp))
            Text(text, color = StorageInk, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, color = StorageInk, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, color = StorageAccent, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun StepCard(index: Int, step: String) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = StorageCard)) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(index.toString().padStart(2, '0'), color = StorageAccent, fontWeight = FontWeight.Bold)
            Text(step, color = StorageInk, lineHeight = 24.sp)
        }
    }
}

@Composable
private fun EmptySearchCard() {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = StorageCard)) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("검색 결과가 없습니다.", color = StorageInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("검색어를 바꾸거나 전체 목록으로 돌아가 다시 확인해보세요.", color = StorageMuted)
        }
    }
}

private fun List<StoredRecipe>.toggleFavorite(id: String) = map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
private fun palette(category: String) = when (category) {
    "한식" -> listOf(Color(0xFF131518), Color(0xFF453126))
    "파스타" -> listOf(Color(0xFF74C8B7), Color(0xFF3B938B))
    "음료" -> listOf(Color(0xFFC2CC97), Color(0xFF98A761))
    else -> listOf(Color(0xFFF2DDBE), Color(0xFFD39D6E))
}

private val sampleRecipes = listOf(
    StoredRecipe("kimchi", "김치볶음밥", "한식", "잘 익은 김치와 참기름으로 빠르게 완성하는 집밥 메뉴입니다.", 18, "쉬움", 2, 520, 4.8, true, listOf("밥 2공기", "김치 1컵", "대파 1/2대", "고추장 1큰술"), listOf("김치와 대파를 먼저 볶아 향을 올립니다.", "밥과 양념을 넣고 고르게 섞어 볶습니다.", "계란과 깨를 올려 마무리합니다.")),
    StoredRecipe("pasta", "바질 크림 파스타", "파스타", "부드러운 크림과 바질 향이 어우러진 한 그릇 파스타입니다.", 25, "보통", 2, 670, 4.6, false, listOf("스파게티면 160g", "생크림 180ml", "바질 페스토 2큰술", "마늘 3쪽"), listOf("면을 알단테로 삶습니다.", "마늘과 크림, 바질 페스토로 소스를 만듭니다.", "면과 면수를 넣고 걸쭉하게 마무리합니다.")),
    StoredRecipe("latte", "말차 라떼", "음료", "진한 말차와 우유의 균형이 좋은 홈카페 메뉴입니다.", 7, "쉬움", 1, 180, 4.7, true, listOf("말차 가루 2작은술", "우유 220ml", "꿀 1작은술", "얼음 적당량"), listOf("말차를 따뜻한 물에 먼저 풉니다.", "우유와 꿀을 섞어 컵에 담습니다.", "얼음 위로 말차를 부어 층을 만듭니다.")),
    StoredRecipe("toast", "버섯 브런치 토스트", "브런치", "버터에 볶은 버섯과 사워도우가 잘 어울리는 브런치입니다.", 15, "보통", 2, 410, 4.9, true, listOf("사워도우 2장", "버섯 믹스 150g", "버터 1큰술", "수란 2개"), listOf("빵을 노릇하게 굽습니다.", "버섯을 버터에 볶아 수분을 날립니다.", "빵 위에 버섯과 수란을 올려 마무리합니다."))
)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RecipeStorageScreenPreview() { RecipeStorageScreen() }
