package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.ui.theme.*

@Composable
fun RecentHistoryScreen(
    items: List<RecentRecipeItem>,
    onBackClick: () -> Unit,
    onItemClick: (RecentRecipeItem) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFFF8F9FA)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                top = innerPadding.calculateTopPadding() + 20.dp,
                end = 24.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HistoryTopBar(onBackClick = onBackClick)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (items.isEmpty()) {
                item {
                    EmptyHistoryState()
                }
            } else {
                items(items, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        onClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTopBar(onBackClick: () -> Unit) {
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
            text = "최근 분석 전체보기",
            color = Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyHistoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFFFF3E8),
            modifier = Modifier.size(80.dp)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = TomaMainOrange.copy(alpha = 0.5f),
                modifier = Modifier.padding(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "아직 분석한 레시피가 없어요",
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "AI 레시피 검색을 통해\n새로운 요리를 시작해 보세요!",
            color = Color.Gray,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun HistoryItemCard(
    item: RecentRecipeItem,
    onClick: () -> Unit
) {
    val (icon, iconBgColor, badgeColor) = when (item.sourceType) {
        RecipeSourceType.TEXT -> Triple(Icons.AutoMirrored.Filled.MenuBook, Color(0xFFE8F1FF), Color(0xFF4DABF7))
        RecipeSourceType.YOUTUBE -> Triple(Icons.Default.PlayCircle, Color(0xFFFFF1E8), TomaMainOrange)
        RecipeSourceType.IMAGE -> Triple(Icons.Default.CameraAlt, Color(0xFFFFECEC), TomaMainRed)
        RecipeSourceType.WEB -> Triple(Icons.Default.Public, Color(0xFFE7F5FF), Color(0xFF228BE6))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [왼쪽 아이콘]
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = iconBgColor,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.padding(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.title,
                    color = Color.Black,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.timeText,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeColor.copy(alpha = 0.1f),
            ) {
                Text(
                    text = item.sourceType.name,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecentHistoryScreenPreview() {
    // 프리뷰용 가상 데이터
    val dummyItems = listOf(
        RecentRecipeItem(
            id = "1",
            title = "스팸 김치볶음밥 황금레시피",
            timeText = "방금 전",
            sourceType = RecipeSourceType.YOUTUBE
        ),
        RecentRecipeItem(
            id = "2",
            title = "간단한 계란말이",
            timeText = "2시간 전",
            sourceType = RecipeSourceType.TEXT
        ),
        RecentRecipeItem(
            id = "3",
            title = "엄마표 된장찌개",
            timeText = "어제",
            sourceType = RecipeSourceType.IMAGE
        ),
        RecentRecipeItem(
            id = "4",
            title = "초간단 백종원 라면",
            timeText = "3일 전",
            sourceType = RecipeSourceType.YOUTUBE
        )
    )

    RecentHistoryScreen(
        items = dummyItems,
        onBackClick = {},
        onItemClick = {}
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecentHistoryScreenEmptyPreview() {
    RecentHistoryScreen(
        items = emptyList(), // 데이터가 없을 때의 화면 프리뷰
        onBackClick = {},
        onItemClick = {}
    )
}