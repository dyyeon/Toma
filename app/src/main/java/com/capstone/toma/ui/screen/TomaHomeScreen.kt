package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import coil.compose.SubcomposeAsyncImage
import org.json.JSONObject
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.ui.component.LoadingSection
import com.capstone.toma.ui.component.TomaDrawerItem
import com.capstone.toma.ui.component.TomaDrawerSheet
import com.capstone.toma.ui.component.TomaTopAppBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaMainRed = Color(0xFFE03131)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaCardBorder = Color(0xFFF1F3F5)
private val TomaPrimaryText = Color(0xFF212529)
private val TomaSecondaryText = Color(0xFF868E96)

data class RecentRecipeItem(
    val id: String,
    val title: String,
    val timeText: String,
    val sourceType: RecipeSourceType,
    val recipeDataJson: String? = null
)

data class HomeUiState(
    val searchQuery: String = "",
    val youtubeLink: String = "",
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
    onYoutubeLinkChange: (String) -> Unit,
    onYoutubeSubmit: () -> Unit,
    onPhotoScanClick: () -> Unit = {},
    onRecentItemClick: (String) -> Unit = {},
    onRecentMoreClick: () -> Unit = {},
    onHomeClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onPrivacyPolicyClick: () -> Unit = {},
    onSpeakerEnrollmentClick: () -> Unit = {},
    onErrorDismiss: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    uiState.errorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            title = { Text(text = "알림", fontWeight = FontWeight.Bold, color = TomaPrimaryText) },
            text = { Text(text = message, color = TomaSecondaryText) },
            confirmButton = {
                TextButton(onClick = onErrorDismiss) {
                    Text("확인", color = TomaMainOrange, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White
        )
    }

    val drawerItems = listOf(
        TomaDrawerItem(
            label = "저장소",
            subtitle = "저장한 레시피를 관리합니다",
            icon = Icons.Default.BookmarkBorder,
            onClick = { scope.launch { drawerState.close(); onStorageClick() } }
        ),
        TomaDrawerItem(
            label = "설정",
            subtitle = "앱 설정과 지원 메뉴를 확인합니다",
            icon = Icons.Default.Settings,
            onClick = { scope.launch { drawerState.close(); onSettingsClick() } }
        ),
        TomaDrawerItem(
            label = "개인정보 처리방침",
            subtitle = "TOMA 서비스 이용 약관 및 방침",
            icon = Icons.Default.PrivacyTip,
            onClick = { scope.launch { drawerState.close(); onPrivacyPolicyClick() } }
        )
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { TomaDrawerSheet(items = drawerItems) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TomaBackground)
        ) {
            TomaTopAppBar(
                onMenuClick = { scope.launch { drawerState.open() } }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(12.dp))

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
                        youtubeLink = uiState.youtubeLink,
                        onYoutubeLinkChange = onYoutubeLinkChange,
                        onYoutubeSubmit = onYoutubeSubmit,
                        onPhotoScanClick = onPhotoScanClick,
                        enabled = !uiState.isAnalyzing
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                if (!uiState.isAnalyzing) {
                    RecentAnalysisSection(
                        items = uiState.recentItems,
                        onItemClick = onRecentItemClick,
                        onMoreClick = onRecentMoreClick
                    )
                }

                Spacer(modifier = Modifier.height(80.dp))
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
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, TomaCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = TomaMainOrange.copy(alpha = 0.1f)
            ) {
                Text(
                    text = "AI 레시피 검색",
                    color = TomaMainOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "어떤 요리를\n만들어볼까요?",
                color = TomaPrimaryText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "재료나 메뉴명을 검색하거나 말씀해주세요.",
                color = TomaSecondaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF1F3F5))
                        .padding(horizontal = 16.dp),
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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { onSearchSubmit() },
                                onDone = { onSearchSubmit() }
                            ),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "예: 남은 참치캔 요리",
                                        color = Color(0xFFADB5BD),
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        )
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "검색",
                            tint = Color(0xFFADB5BD),
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(enabled = enabled) { onSearchSubmit() }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = enabled) { onMicClick() },
                    shape = RoundedCornerShape(16.dp),
                    color = if (enabled) TomaMainOrange else Color(0xFFD9D9D9),
                    shadowElevation = if (enabled) 4.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "음성 검색",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImportSection(
    youtubeLink: String,
    onYoutubeLinkChange: (String) -> Unit,
    onYoutubeSubmit: () -> Unit,
    onPhotoScanClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = "외부 레시피 가져오기")

        Spacer(modifier = Modifier.height(16.dp))

        YoutubeImportCard(
            linkText = youtubeLink,
            onLinkChange = onYoutubeLinkChange,
            onSubmit = onYoutubeSubmit,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(16.dp))

        PhotoImportCard(
            onClick = onPhotoScanClick,
            enabled = enabled
        )
    }
}


@Composable
fun YoutubeImportCard(
    linkText: String,
    onLinkChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val icons = listOf(Icons.Filled.SmartDisplay, Icons.Default.Description, Icons.Default.Description)
    var iconIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            iconIndex = (iconIndex + 1) % icons.size
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFF1E8),
                modifier = Modifier.size(52.dp)
            ) {
                androidx.compose.animation.Crossfade(targetState = icons[iconIndex], label = "IconAnimation") { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = "유튜브",
                        tint = TomaMainOrange,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF8F9FA))
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    decorationBox = { innerTextField ->
                        if (linkText.isEmpty()) {
                            Text(
                                text = "유튜브,블로그 링크 붙여넣기",
                                color = Color(0xFFADB5BD),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Surface(
                shape = CircleShape,
                color = TomaMainOrange,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(enabled = enabled) { onSubmit() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = "유튜브 링크 전송",
                    tint = Color.White,
                    modifier = Modifier.padding(10.dp)
                )
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
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFECEC),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "사진 스캔",
                    tint = TomaMainRed,
                    modifier = Modifier.padding(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "이미지 스캔하기",
                    color = TomaPrimaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "메뉴판이나 요리 사진으로 추출",
                    color = TomaSecondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "사진 스캔 이동",
                tint = Color(0xFFCED4DA),
                modifier = Modifier.size(24.dp)
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
            SectionTitle(title = "최근 분석 항목")

            TextButton(onClick = onMoreClick, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = "전체 보기",
                    color = TomaSecondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                border = BorderStroke(1.dp, TomaCardBorder)
            ) {
                Text(
                    text = "아직 분석한 레시피가 없습니다.",
                    color = TomaSecondaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(20.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items.take(2), key = { it.id }) { item ->
                    RecentAnalysisCard(
                        item = item,
                        onClick = { onItemClick(item.id) }
                    )
                }
            }
        }
    }
}

private fun extractImageUrl(recipeDataJson: String?): String? {
    if (recipeDataJson.isNullOrBlank()) return null
    return runCatching {
        JSONObject(recipeDataJson).optString("image_url").takeIf { it.isNotBlank() && it != "없음" }
    }.getOrNull()
}

@Composable
fun RecentAnalysisCard(
    item: RecentRecipeItem,
    onClick: () -> Unit
) {
    val (icon, bgColor, iconColor, badgeText) = when (item.sourceType) {
        RecipeSourceType.TEXT -> listOf(Icons.AutoMirrored.Filled.MenuBook, Color(0xFFE8F1FF), Color(0xFF4DABF7), "AI")
        RecipeSourceType.YOUTUBE -> listOf(Icons.Filled.SmartDisplay, Color(0xFFFFF1E8), TomaMainOrange, "YOUTUBE")
        RecipeSourceType.IMAGE -> listOf(Icons.Default.CameraAlt, Color(0xFFFFECEC), TomaMainRed, "IMAGE")
        RecipeSourceType.WEB -> listOf(Icons.Default.Public, Color(0xFFE7F5FF), Color(0xFF228BE6), "WEB")
        RecipeSourceType.SAVED -> listOf(Icons.Default.Bookmark, Color(0xFFEBFBEE), Color(0xFF2F9E44), "SAVED")
    }

    val thumbnailUrl = extractImageUrl(item.recipeDataJson)

    Surface(
        modifier = Modifier
            .width(165.dp)
            .height(240.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailUrl != null) {
                    SubcomposeAsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = {
                            // URL 로드 실패 시 아이콘 placeholder
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(bgColor as Color),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                                    contentDescription = null,
                                    tint = (iconColor as Color).copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                    )
                    // 썸네일 위에 배지 오버레이
                    Surface(
                        shape = RoundedCornerShape(bottomStart = 12.dp),
                        color = (iconColor as Color).copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = badgeText as String,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    // 썸네일 없을 때 아이콘 배경
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bgColor as Color),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                            contentDescription = null,
                            tint = (iconColor as Color).copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 12.dp),
                            color = iconColor,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = badgeText as String,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = item.title,
                    color = TomaPrimaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.timeText,
                    color = TomaSecondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(CircleShape)
                .background(TomaMainOrange)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = TomaPrimaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTomaHomeScreen() {
    TomaHomeScreen(
        uiState = HomeUiState(
            searchQuery = "",
            youtubeLink = "",
            isAnalyzing = false,
            recentItems = listOf(
                RecentRecipeItem(
                    id = "1",
                    title = "백종원 김치볶음밥",
                    timeText = "2시간 전",
                    sourceType = RecipeSourceType.YOUTUBE
                ),
                RecentRecipeItem(
                    id = "2",
                    title = "스팸 계란말이",
                    timeText = "어제",
                    sourceType = RecipeSourceType.IMAGE
                ),
                RecentRecipeItem(
                    id = "3",
                    title = "간단 파스타",
                    timeText = "3일 전",
                    sourceType = RecipeSourceType.TEXT
                )
            )
        ),
        onSearchQueryChange = {},
        onSearchSubmit = {},
        onMicClick = {},
        onYoutubeLinkChange = {},
        onYoutubeSubmit = {},
        onPhotoScanClick = {}
    )
}
