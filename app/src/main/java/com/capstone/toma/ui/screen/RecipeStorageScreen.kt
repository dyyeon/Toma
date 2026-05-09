package com.capstone.toma.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.model.StoredRecipe
import com.capstone.toma.ui.theme.TomaMainOrange
import com.capstone.toma.viewmodel.RecipeStorageViewModel
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults



private val StorageBg = Color(0xFFF8F9FA)
private val StorageCard = Color.White
private val StorageCardBorder = Color(0xFFF1F3F5)
private val StorageAccent = TomaMainOrange
private val StorageInk = Color(0xFF212529)
private val StorageMuted = Color(0xFF868E96)

@Composable
fun RecipeStorageScreen(
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val storageViewModel: RecipeStorageViewModel? = if (isPreview) null else viewModel()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val recipes by if (isPreview) {
        remember { mutableStateOf(previewRecipes) }
    } else {
        storageViewModel!!.recipes.collectAsState()
    }
    val latestRecipes by rememberUpdatedState(recipes)
    val latestStorageViewModel by rememberUpdatedState(storageViewModel)
    var query by rememberSaveable { mutableStateOf("") }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var imageTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val opened = recipes.firstOrNull { it.id == openedId }
    var showImagePickerDialog by rememberSaveable(openedId) { mutableStateOf(false) }
    val imagePickerLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val targetId = imageTargetId
            imageTargetId = null

            if (uri == null || targetId == null) return@rememberLauncherForActivityResult

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val targetRecipe = latestRecipes.firstOrNull { it.id == targetId }
                ?: return@rememberLauncherForActivityResult

            latestStorageViewModel?.saveRecipe(
                targetRecipe.copy(imageUri = uri.toString())
            )

            scope.launch {
                snackbar.showSnackbar("음식 사진을 저장했어요.")
            }
        }
    }
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
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.shadow(8.dp, CircleShape, ambientColor = StorageAccent, spotColor = StorageAccent)
                ) { Icon(Icons.Default.Add, contentDescription = "레시피 추가", modifier = Modifier.size(28.dp)) }
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
                onBackClick = onBackClick,
                onOpen = { openedId = it.id },
                onFavoriteToggle = { recipe -> storageViewModel?.toggleFavorite(recipe) }
            )
        } else {
            if (showImagePickerDialog) {
                ImagePickerDialog(
                    hasImage = !opened.imageUri.isNullOrBlank(),
                    onDismiss = { showImagePickerDialog = false },
                    onOpenGallery = {
                        showImagePickerDialog = false

                        if (isPreview) {
                            scope.launch {
                                snackbar.showSnackbar("미리보기에서는 사진을 추가할 수 없습니다.")
                            }
                        } else {
                            imageTargetId = opened.id
                            imagePickerLauncher?.launch(arrayOf("image/*"))
                        }
                    },
                    onResetToDefault = if (opened.imageUri.isNullOrBlank()) null else {
                        {
                            showImagePickerDialog = false
                            imageTargetId = null
                            latestStorageViewModel?.saveRecipe(
                                opened.copy(imageUri = null)
                            )
                            scope.launch {
                                snackbar.showSnackbar("기본 이미지로 되돌렸어요.")
                            }
                        }
                    }
                )
            }

            StorageDetail(
                inner = inner,
                recipe = opened,
                onBack = {
                    showImagePickerDialog = false
                    openedId = null
                },
                onFavoriteToggle = { storageViewModel?.toggleFavorite(opened) },
                onImagePick = { showImagePickerDialog = true }
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
    onBackClick: () -> Unit,
    onOpen: (StoredRecipe) -> Unit,
    onFavoriteToggle: (StoredRecipe) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StorageBg),
        contentPadding = PaddingValues(24.dp, inner.calculateTopPadding() + 16.dp, 24.dp, inner.calculateBottomPadding() + 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { StorageTopHeader(title = "저장소", onBackClick = onBackClick) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, StorageCardBorder),
                    shadowElevation = 2.dp
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("어떤 레시피를 찾으시나요?", color = StorageMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = StorageAccent) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = StorageInk,
                            unfocusedTextColor = StorageInk,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = StorageAccent
                        )
                    )
                }
                Text(
                    text = if (query.isBlank()) "총 ${totalCount}개의 레시피" else "${recipes.size}개 검색됨",
                    color = StorageMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        if (recipes.isEmpty()) {
            item { EmptySearchCard() }
        } else {
            items(recipes, key = { it.id }) { recipe ->
                RecipeCard(recipe, { onOpen(recipe) }, { onFavoriteToggle(recipe) })
            }
        }
    }
}

@Composable
private fun StorageDetail(
    inner: PaddingValues,
    recipe: StoredRecipe,
    onBack: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onImagePick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StorageBg),
        contentPadding = PaddingValues(24.dp, inner.calculateTopPadding() + 16.dp, 24.dp, inner.calculateBottomPadding() + 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item { StorageTopHeader(title = "레시피 상세", onBackClick = onBack) }

        item { HeroCard(recipe, onFavoriteToggle, onImagePick) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = recipe.title,
                        color = StorageInk,
                        fontSize = 28.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${recipe.time}분", color = StorageAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("조리 시간", color = StorageMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricChip(icon = Icons.Default.Star, text = String.format("%.1f", recipe.rating), color = Color(0xFFFAB005))
                    MetricChip(icon = Icons.Default.RestaurantMenu, text = recipe.difficulty, color = Color(0xFF20C997))
                    MetricChip(icon = Icons.Default.LocalFireDepartment, text = "${recipe.calories} kcal", color = TomaMainOrange)
                }

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, StorageCardBorder)
                ) {
                    Text(
                        text = "\"${recipe.story}\"",
                        color = Color(0xFF495057),
                        lineHeight = 24.sp,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        item { SectionTitle("준비할 재료", "(${recipe.servings}인분)") }

        items(recipe.ingredients.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ingredient ->
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, StorageCardBorder),
                        shadowElevation = 1.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(StorageAccent, CircleShape))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(ingredient, color = StorageInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
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
private fun StorageTopHeader(title: String, onBackClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp).clickable { onBackClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, StorageCardBorder),
            shadowElevation = 2.dp
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "뒤로",
                modifier = Modifier.padding(12.dp),
                tint = StorageInk
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = StorageInk,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RecipeCard(recipe: StoredRecipe, onOpen: () -> Unit, onFavoriteToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = BorderStroke(1.dp, StorageCardBorder),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Column {
            RecipeArtwork(
                recipe = recipe,
                modifier = Modifier.fillMaxWidth().height(180.dp)
            )
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = StorageAccent.copy(alpha = 0.1f)) {
                        Text(recipe.category, color = StorageAccent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = StorageMuted, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${recipe.time}분 조리", color = StorageMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Text(
                        text = recipe.title,
                        color = StorageInk,
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(32.dp).padding(start = 8.dp)) {
                        Icon(
                            imageVector = if (recipe.favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "즐겨찾기",
                            tint = if (recipe.favorite) StorageAccent else Color.LightGray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(recipe: StoredRecipe, onFavoriteToggle: () -> Unit, onImagePick: () -> Unit) {
    val hasImage = !recipe.imageUri.isNullOrBlank()

    Surface(
        modifier = Modifier.fillMaxWidth().height(280.dp).clickable(onClick = onImagePick),
        shape = RoundedCornerShape(32.dp),
        shadowElevation = 8.dp,
        color = Color.Transparent
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            RecipeArtwork(recipe = recipe, modifier = Modifier.fillMaxSize())

            if (hasImage) {
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.5f)))))
            }

            Surface(
                color = StorageAccent,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            ) {
                Text(recipe.category, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }

            IconButton(
                onClick = onFavoriteToggle,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.White.copy(alpha = 0.9f), CircleShape)
            ) {
                Icon(if (recipe.favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "즐겨찾기", tint = StorageAccent)
            }

            Surface(
                color = Color.White.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).clickable(onClick = onImagePick)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = StorageAccent, modifier = Modifier.size(18.dp))
                    Text(if (hasImage) "이미지 변경" else "이미지 등록", color = StorageInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RecipeArtwork(recipe: StoredRecipe, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Brush.linearGradient(palette(recipe.category)))) {
        DefaultRecipeArtwork(category = recipe.category, title = recipe.title, modifier = Modifier.fillMaxSize())
        if (!recipe.imageUri.isNullOrBlank()) {
            AsyncImage(model = recipe.imageUri, contentDescription = "${recipe.title} 음식 사진", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DefaultRecipeArtwork(category: String, title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp).size(140.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp).size(80.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.08f)))
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(defaultImageMonogram(title, category), color = Color.White.copy(alpha = 0.95f), fontSize = 64.sp, fontWeight = FontWeight.Black)
            Text(defaultImageCaption(category), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ImagePickerDialog(hasImage: Boolean, onDismiss: () -> Unit, onOpenGallery: () -> Unit, onResetToDefault: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasImage) "레시피 이미지 변경" else "레시피 이미지 등록", color = StorageInk, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("갤러리에서 직접 찍은 음식 사진을 선택하여 나만의 레시피북을 꾸며보세요.", color = StorageMuted, lineHeight = 22.sp)
                if (hasImage && onResetToDefault != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF4F4),
                        modifier = Modifier.fillMaxWidth().clickable { onResetToDefault() }
                    ) {
                        Text("기본 이미지로 되돌리기", color = Color(0xFFE03131), fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onOpenGallery, colors = ButtonDefaults.buttonColors(containerColor = StorageAccent)) {
                Text("갤러리 열기", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", color = StorageMuted, fontWeight = FontWeight.Bold) }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun MetricChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, StorageCardBorder), shadowElevation = 1.dp) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(text, color = StorageInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Box(modifier = Modifier.size(width = 4.dp, height = 20.dp).background(StorageAccent, CircleShape).align(Alignment.CenterVertically))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, color = StorageInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, color = StorageMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StepCard(index: Int, step: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, StorageCardBorder),
        shadowElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(20.dp)) {
            Surface(shape = CircleShape, color = StorageAccent.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(index.toString(), color = StorageAccent, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(step, color = StorageInk, fontSize = 15.sp, lineHeight = 26.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EmptySearchCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        border = BorderStroke(1.dp, StorageCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Search, contentDescription = null, tint = StorageMuted.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
            Text("검색 결과가 없어요", color = StorageInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("다른 검색어를 입력해 보세요.", color = StorageMuted, fontSize = 14.sp)
        }
    }
}

private fun palette(category: String) = when (category) {
    "한식" -> listOf(Color(0xFF2C3E50), Color(0xFF000000))
    "파스타" -> listOf(Color(0xFF20C997), Color(0xFF0CA678))
    "음료" -> listOf(Color(0xFFFCC419), Color(0xFFF59F00))
    else -> listOf(Color(0xFFFFA94D), Color(0xFFF76707))
}

private fun defaultImageCaption(category: String) = when (category) {
    "한식" -> "따뜻한 집밥"
    "파스타" -> "특별한 한 끼"
    "음료" -> "여유로운 홈카페"
    "브런치" -> "주말의 브런치"
    else -> "나만의 레시피"
}

private fun defaultImageMonogram(title: String, category: String): String {
    return title.take(2).ifBlank { category.take(2).ifBlank { "T" } }
}

private val previewRecipes = listOf(
    StoredRecipe("kimchi", "김치볶음밥", "한식", "잘 익은 김치와 참기름으로 빠르게 완성하는 집밥 메뉴입니다.", 18, "쉬움", 2, 520, 4.8, true, listOf("밥 2공기", "김치 1컵", "대파 1/2대", "고추장 1큰술"), listOf("김치와 대파를 먼저 볶아 향을 올립니다.", "밥과 양념을 넣고 고르게 섞어 볶습니다.", "계란과 깨를 올려 마무리합니다."), RecipeSourceType.YOUTUBE, "2시간 전 저장"),
    StoredRecipe("pasta", "바질 크림 파스타", "파스타", "부드러운 크림과 바질 향이 어우러진 한 그릇 파스타입니다.", 25, "보통", 2, 670, 4.6, false, listOf("스파게티면 160g", "생크림 180ml", "바질 페스토 2큰술", "마늘 3쪽"), listOf("면을 알단테로 삶습니다.", "마늘과 크림, 바질 페스토로 소스를 만듭니다.", "면과 면수를 넣고 걸쭉하게 마무리합니다."), RecipeSourceType.TEXT, "어제 저장"),
    StoredRecipe("latte", "말차 라떼", "음료", "진한 말차와 우유의 균형이 좋은 홈카페 메뉴입니다.", 7, "쉬움", 1, 180, 4.7, true, listOf("말차 가루 2작은술", "우유 220ml", "꿀 1작은술", "얼음 적당량"), listOf("말차를 따뜻한 물에 먼저 풉니다.", "우유와 꿀을 섞어 컵에 담습니다.", "얼음 위로 말차를 부어 층을 만듭니다."), RecipeSourceType.IMAGE, "3일 전 저장"),
    StoredRecipe("toast", "버섯 브런치 토스트", "브런치", "버터에 볶은 버섯과 사워도우가 잘 어울리는 브런치입니다.", 15, "보통", 2, 410, 4.9, true, listOf("사워도우 2장", "버섯 믹스 150g", "버터 1큰술", "수란 2개"), listOf("빵을 노릇하게 굽습니다.", "버섯을 버터에 볶아 수분을 날립니다.", "빵 위에 버섯과 수란을 올려 마무리합니다."), RecipeSourceType.TEXT, "지난주 저장")
)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RecipeStorageScreenPreview() { RecipeStorageScreen() }