package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.capstone.toma.R
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.model.normalizeRecipeCategory
import com.capstone.toma.ui.component.LoadingSection
import com.capstone.toma.ui.component.TomaDrawerItem
import com.capstone.toma.ui.component.TomaDrawerSheet
import com.capstone.toma.ui.component.TomaTopAppBar
import com.capstone.toma.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

data class RecentRecipeItem(
    val id: String,
    val title: String,
    val timeText: String,
    val sourceType: RecipeSourceType,
    val recipeDataJson: String? = null
)

data class HomeUiState(
    val searchQuery: String = "",
    val recipeLink: String = "",
    val isAnalyzing: Boolean = false,
    val recentItems: List<RecentRecipeItem> = emptyList(),
    val selectedRecentItemId: String? = null,
    val errorMessage: String? = null,
    val errorDialogMessage: String? = null
)

@Composable
fun TomaHomeScreen(
    uiState: HomeUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onMicClick: () -> Unit,
    onLinkChange: (String) -> Unit,
    onLinkSubmit: () -> Unit,
    onPhotoScanClick: () -> Unit = {},
    onRecentItemClick: (String) -> Unit = {},
    onRecentMoreClick: () -> Unit = {},
    onHomeClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSpeakerEnrollmentClick: () -> Unit = {},
    onPrivacyPolicyClick: () -> Unit = {},
    onErrorDismiss: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    uiState.errorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            title = { Text(text = "알림", fontWeight = FontWeight.Bold) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = onErrorDismiss) {
                    Text("확인", color = TomaMainOrange)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White
        )
    }

    val drawerItems = listOf(
        TomaDrawerItem(
            label = "저장소",
            subtitle = "저장한 레시피를 관리합니다",
            icon = Icons.Default.BookmarkBorder,
            onClick = {
                scope.launch {
                    drawerState.close()
                    onStorageClick()
                }
            }
        ),
        TomaDrawerItem(
            label = "설정",
            subtitle = "앱 설정과 지원 메뉴를 확인합니다",
            icon = Icons.Default.Settings,
            onClick = {
                scope.launch {
                    drawerState.close()
                    onSettingsClick()
                }
            }
        ),
        TomaDrawerItem(
            label = "화자 등록",
            subtitle = "나의 목소리를 등록합니다",
            icon = Icons.Default.RecordVoiceOver,
            onClick = {
                scope.launch {
                    drawerState.close()
                    onSpeakerEnrollmentClick()
                }
            }
        ),
        TomaDrawerItem(
            label = "개인정보 처리방침",
            subtitle = "TOMA 서비스 이용 약관 및 방침",
            icon = Icons.Default.PrivacyTip,
            onClick = {
                scope.launch {
                    drawerState.close()
                    onPrivacyPolicyClick()
                }
            }
        )
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TomaDrawerSheet(items = drawerItems)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TomaBackground)
                .padding(bottom = 24.dp)
        ) {
            TomaTopAppBar(
                onMenuClick = {
                    scope.launch { drawerState.open() }
                }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                AIRecipeSearchCard(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearchSubmit = onSearchSubmit,
                    onMicClick = onMicClick,
                    enabled = !uiState.isAnalyzing,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (uiState.isAnalyzing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingSection()
                    }
                } else {
                    ImportSection(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        linkText = uiState.recipeLink,
                        onLinkChange = onLinkChange,
                        onLinkSubmit = onLinkSubmit,
                        onPhotoScanClick = onPhotoScanClick,
                        enabled = !uiState.isAnalyzing
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (!uiState.isAnalyzing) {
                    RecentAnalysisSection(
                        items = uiState.recentItems,
                        onItemClick = onRecentItemClick,
                        onMoreClick = onRecentMoreClick
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                uiState.errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(20.dp))
                    ErrorMessageCard(
                        message = message,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun AIRecipeSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onMicClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Text(
                text = "AI 레시피 검색",
                color = TomaMainOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "어떤 요리를 만들어볼까요?",
                color = TomaPrimaryText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "재료나 메뉴명을 말하면 AI가 최적의 레시피를 제안해드립니다.",
                color = TomaSecondaryText,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TomaBackground)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            enabled = enabled,
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = TomaPrimaryText,
                                fontSize = 15.sp
                            ),
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { onSearchSubmit() },
                                onDone = { onSearchSubmit() }
                            ),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "검색어를 입력하세요",
                                        color = TomaSecondaryText,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "검색",
                            tint = TomaSecondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (enabled) TomaMainOrange else Color(0xFFD9D9D9)
                        )
                        .clickable(enabled = enabled) { onMicClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성 검색",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ImportSection(
    linkText: String,
    onLinkChange: (String) -> Unit,
    onLinkSubmit: () -> Unit,
    onPhotoScanClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = "레시피 가져오기")

        Spacer(modifier = Modifier.height(14.dp))

        LinkImportCard(
            linkText = linkText,
            onLinkChange = onLinkChange,
            onSubmit = onLinkSubmit,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(14.dp))

        PhotoImportCard(
            onClick = onPhotoScanClick,
            enabled = enabled
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TomaPrimaryText,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun LinkImportCard(
    linkText: String,
    onLinkChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TomaLightOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "레시피 링크",
                        tint = TomaMainOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "레시피 링크 분석",
                        color = TomaPrimaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "YouTube · 블로그 · 웹페이지 레시피",
                        color = TomaSecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TomaBackground)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = linkText,
                        onValueChange = onLinkChange,
                        enabled = enabled,
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = TomaPrimaryText,
                            fontSize = 14.sp
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onSubmit() }
                        ),
                        decorationBox = { innerTextField ->
                            if (linkText.isEmpty()) {
                                Text(
                                    text = "레시피 링크를 붙여넣으세요",
                                    color = TomaSecondaryText,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (enabled) TomaMainOrange else Color(0xFFD9D9D9)
                        )
                        .clickable(enabled = enabled) { onSubmit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallMade,
                        contentDescription = "레시피 링크 전송",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PhotoImportCard(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFFF3E8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "이미지 업로드",
                    tint = TomaMainOrange,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "이미지 업로드",
                    color = TomaPrimaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "레시피 이미지를 바로 추출하세요",
                    color = TomaSecondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "이미지 업로드 이동",
                tint = Color(0xFFD0D3DB)
            )
        }
    }
}

@Composable
fun RecentAnalysisSection(
    items: List<RecentRecipeItem>,
    onItemClick: (String) -> Unit,
    onMoreClick: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "최근 분석 항목",
                color = TomaPrimaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            TextButton(onClick = onMoreClick) {
                Text(
                    text = "전체 보기",
                    color = TomaMainOrange,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty()) {
            Text(
                text = "아직 분석한 레시피가 없습니다.",
                color = TomaSecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.take(2).forEach { item ->
                    RecentAnalysisCard(
                        item = item,
                        modifier = Modifier.weight(1f),
                        onClick = { onItemClick(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun RecentAnalysisCard(
    item: RecentRecipeItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val badgeText = when (item.sourceType) {
        RecipeSourceType.TEXT -> "채팅"
        RecipeSourceType.YOUTUBE -> "유튜브"
        RecipeSourceType.WEB -> "웹"
        RecipeSourceType.IMAGE -> "이미지"
    }

    val badgeBgColor = when (item.sourceType) {
        RecipeSourceType.YOUTUBE -> Color(0xFFE53935)
        RecipeSourceType.WEB -> Color(0xFF43A047)
        RecipeSourceType.TEXT -> Color(0xFF757575)
        RecipeSourceType.IMAGE -> TomaMainOrange
    }

    val tempColor = when (item.sourceType) {
        RecipeSourceType.YOUTUBE -> Color(0xFFFFEBEE)
        RecipeSourceType.WEB -> Color(0xFFE8F5E9)
        RecipeSourceType.TEXT -> Color(0xFFF5F5F5)
        RecipeSourceType.IMAGE -> Color(0xFFFFF3E8)
    }
    
    val fallbackImageRes = remember(item) {
        when (extractRecentCategory(item.recipeDataJson, item.title)) {
            "한식" -> R.drawable.recent_korean_rice
            "일식" -> R.drawable.recent_japanese_fish
            "디저트" -> R.drawable.recent_dessert_cookie
            "양식" -> R.drawable.recent_western_pasta
            else -> R.drawable.ic_tomato
        }
    }
    
    val imageUrl = remember(item.recipeDataJson) {
        if (item.recipeDataJson.isNullOrBlank()) null
        else {
            runCatching {
                JSONObject(item.recipeDataJson)
                    .optString("image_url")
                    .trim()
                    .takeIf { it.startsWith("http") }
            }.getOrNull()
        }
    }

    Surface(
        modifier = modifier
            .clickable { onClick() }
            .shadow(4.dp, RoundedCornerShape(20.dp)),
        color = Color.White,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(tempColor)
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Image(
                        painter = painterResource(id = fallbackImageRes),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, end = 10.dp)
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBgColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = item.title,
                    color = TomaPrimaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = item.timeText,
                    color = TomaSecondaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "레시피 자세히 보기",
                    color = TomaMainOrange,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun extractRecentCategory(recipeDataJson: String?, title: String): String {
    if (recipeDataJson.isNullOrBlank()) {
        return normalizeRecipeCategory(rawCategory = null, title = title)
    }

    return runCatching {
        val json = JSONObject(recipeDataJson)
        val recipeTitle = json.optString("title").ifBlank { title }
        normalizeRecipeCategory(
            rawCategory = json.optString("category"),
            title = recipeTitle
        )
    }.getOrElse {
        normalizeRecipeCategory(rawCategory = null, title = title)
    }
}

@Composable
fun ErrorMessageCard(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFFF4F4)
    ) {
        Text(
            text = message,
            color = Color(0xFFC62828),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTomaHomeScreen() {
    TomaHomeScreen(
        uiState = HomeUiState(
            searchQuery = "",
            recipeLink = "",
            isAnalyzing = false,
            recentItems = listOf(
                RecentRecipeItem(
                    id = "1",
                    title = "김치볶음밥",
                    timeText = "방금 분석",
                    sourceType = RecipeSourceType.YOUTUBE
                )
            )
        ),
        onSearchQueryChange = {},
        onSearchSubmit = {},
        onMicClick = {},
        onLinkChange = {},
        onLinkSubmit = {},
        onPhotoScanClick = {},
        onRecentItemClick = {},
        onRecentMoreClick = {},
        onStorageClick = {},
        onSettingsClick = {},
        onSpeakerEnrollmentClick = {},
        onPrivacyPolicyClick = {}
    )
}
