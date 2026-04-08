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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.ui.component.LoadingSection
import com.capstone.toma.ui.component.TomaActionMenuItem
import com.capstone.toma.ui.component.TomaTopAppBar
import com.capstone.toma.ui.theme.TomaLightRed
import com.capstone.toma.ui.theme.TomaMainOrange
import com.capstone.toma.ui.theme.TomaMainRed
import com.capstone.toma.ui.theme.TomaPrimaryText
import com.capstone.toma.ui.theme.TomaSecondaryText
import com.capstone.toma.ui.theme.TomaSurface

enum class RecipeSourceType {
    TEXT, YOUTUBE, IMAGE
}

data class RecentRecipeItem(
    val id: String,
    val title: String,
    val timeText: String,
    val sourceType: RecipeSourceType
)

data class HomeUiState(
    val searchQuery: String = "",
    val youtubeLink: String = "",
    val isAnalyzing: Boolean = false,
    val recentItems: List<RecentRecipeItem> = emptyList(),
    val selectedRecentItemId: String? = null,
    val errorMessage: String? = null
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
    onSettingsClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaSurface)
            .padding(top = 24.dp, bottom = 24.dp)
    ) {
        TomaTopAppBar(
            menuItems = listOf(
                TomaActionMenuItem("홈", onHomeClick),
                TomaActionMenuItem("저장소", onStorageClick),
                TomaActionMenuItem("설정", onSettingsClick)
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            AIRecipeSearchCard(
                query = uiState.searchQuery,
                onQueryChange = onSearchQueryChange,
                onSearchSubmit = onSearchSubmit,
                onMicClick = onMicClick,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            ImportSection(
                modifier = Modifier.padding(horizontal = 24.dp),
                youtubeLink = uiState.youtubeLink,
                onYoutubeLinkChange = onYoutubeLinkChange,
                onYoutubeSubmit = onYoutubeSubmit,
                onPhotoScanClick = onPhotoScanClick
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.isAnalyzing) {
                LoadingSection()
            } else {
                RecentAnalysisSection(
                    items = uiState.recentItems,
                    onItemClick = onRecentItemClick,
                    onMoreClick = onRecentMoreClick
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            uiState.selectedRecentItemId?.let { selectedId ->
                val selectedItem = uiState.recentItems.firstOrNull { it.id == selectedId }

                if (selectedItem != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    SelectedRecentItemCard(
                        title = selectedItem.title,
                        sourceType = selectedItem.sourceType,
                        timeText = selectedItem.timeText,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

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

@Composable
fun AIRecipeSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onMicClick: () -> Unit,
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

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    placeholder = {
                        Text(
                            text = "검색어를 입력하세요",
                            color = TomaSecondaryText,
                            fontSize = 15.sp
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "검색",
                            tint = TomaSecondaryText,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onSearchSubmit() }
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearchSubmit() },
                        onDone = { onSearchSubmit() }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TomaSurface,
                        unfocusedContainerColor = TomaSurface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = TomaMainOrange,
                        focusedTextColor = TomaPrimaryText,
                        unfocusedTextColor = TomaPrimaryText
                    )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onMicClick,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TomaMainOrange
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성 검색",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "레시피 가져오기",
            color = TomaSecondaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(14.dp))

        YoutubeImportCard(
            linkText = youtubeLink,
            onLinkChange = onYoutubeLinkChange,
            onSubmit = onYoutubeSubmit
        )

        Spacer(modifier = Modifier.height(14.dp))

        PhotoImportCard(
            onClick = onPhotoScanClick
        )
    }
}

@Composable
fun YoutubeImportCard(
    linkText: String,
    onLinkChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 4.dp
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(TomaLightRed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartDisplay,
                    contentDescription = "유튜브",
                    tint = TomaMainRed,
                    modifier = Modifier.size(24.dp)
                )
            }

            OutlinedTextField(
                value = linkText,
                onValueChange = onLinkChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "유튜브 링크를 붙여넣으세요",
                        color = TomaSecondaryText,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit() }
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = TomaMainOrange,
                    focusedTextColor = TomaPrimaryText,
                    unfocusedTextColor = TomaPrimaryText
                )
            )

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(4.dp)
                    .clickable { onSubmit() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = "유튜브 링크 전송",
                    tint = Color(0xD20A0F23),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun PhotoImportCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                    contentDescription = "사진 스캔",
                    tint = TomaMainOrange,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "이미지 업로드",
                    color = TomaPrimaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "레시피를 바로 추출하세요",
                    color = TomaSecondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "사진 스캔 이동",
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
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    RecentAnalysisCard(
                        item = item,
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
    onClick: () -> Unit
) {
    val badgeText = when (item.sourceType) {
        RecipeSourceType.TEXT -> "TEXT"
        RecipeSourceType.YOUTUBE -> "YOUTUBE"
        RecipeSourceType.IMAGE -> "IMAGE"
    }

    val badgeBgColor = when (item.sourceType) {
        RecipeSourceType.TEXT -> TomaMainOrange
        RecipeSourceType.YOUTUBE -> TomaMainOrange
        RecipeSourceType.IMAGE -> TomaMainRed
    }

    val tempColor = when (item.sourceType) {
        RecipeSourceType.TEXT -> Color(0xFFE8F1FF)
        RecipeSourceType.YOUTUBE -> Color(0xFFFFF1E8)
        RecipeSourceType.IMAGE -> Color(0xFFFFECEC)
    }

    Column(
        modifier = Modifier
            .size(width = 200.dp, height = 240.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.5f)
                .background(tempColor)
        ) {
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.title,
                color = TomaPrimaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = item.timeText,
                color = TomaSecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SelectedRecentItemCard(
    title: String,
    sourceType: RecipeSourceType,
    timeText: String,
    modifier: Modifier = Modifier
) {
    val sourceLabel = when (sourceType) {
        RecipeSourceType.TEXT -> "TEXT"
        RecipeSourceType.YOUTUBE -> "YOUTUBE"
        RecipeSourceType.IMAGE -> "IMAGE"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "선택된 최근 항목",
                color = TomaMainOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                color = TomaPrimaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$sourceLabel · $timeText",
                color = TomaSecondaryText,
                fontSize = 14.sp
            )
        }
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
            youtubeLink = "",
            isAnalyzing = false,
            recentItems = listOf(
                RecentRecipeItem(
                    id = "1",
                    title = "김치볶음밥",
                    timeText = "2시간 전 분석",
                    sourceType = RecipeSourceType.YOUTUBE
                ),
                RecentRecipeItem(
                    id = "2",
                    title = "계란말이",
                    timeText = "어제 분석",
                    sourceType = RecipeSourceType.IMAGE
                )
            )
        ),
        onSearchQueryChange = {},
        onSearchSubmit = {},
        onMicClick = {},
        onYoutubeLinkChange = {},
        onYoutubeSubmit = {},
        onPhotoScanClick = {},
        onRecentItemClick = {},
        onRecentMoreClick = {},
        onHomeClick = {},
        onStorageClick = {},
        onSettingsClick = {}
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTomaHomeScreenLoading() {
    TomaHomeScreen(
        uiState = HomeUiState(
            isAnalyzing = true
        ),
        onSearchQueryChange = {},
        onSearchSubmit = {},
        onMicClick = {},
        onYoutubeLinkChange = {},
        onYoutubeSubmit = {},
        onPhotoScanClick = {},
        onRecentItemClick = {},
        onRecentMoreClick = {},
        onHomeClick = {},
        onStorageClick = {},
        onSettingsClick = {}
    )
}