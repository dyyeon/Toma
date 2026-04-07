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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.ui.component.TomaActionMenuItem
import com.capstone.toma.ui.component.TomaTopAppBar
import com.capstone.toma.ui.theme.*

@Composable
fun TomaHomeScreen(
    onMicClick: () -> Unit,
    onHomeClick: () -> Unit = {},
    onStorageClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaSurface)
            .padding(vertical = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TomaTopAppBar(
            menuItems = listOf(
                TomaActionMenuItem("홈", onHomeClick),
                TomaActionMenuItem("저장소", onStorageClick),
                TomaActionMenuItem("설정", onSettingsClick)
            )
        )
        Spacer(modifier = Modifier.height(32.dp))
        MicButton(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClick = onMicClick
        )
        Spacer(modifier = Modifier.height(32.dp))
        LinkInputSection()
        Spacer(modifier = Modifier.height(24.dp))
        ScanOptionsSection()
        Spacer(modifier = Modifier.height(32.dp))
        QuickAnalysisSection()
        Spacer(modifier = Modifier.height(32.dp))
        RecentAnalysisSection()
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun MicButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(90.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "음성 입력",
            modifier = Modifier.size(36.dp),
            tint = TomaMainRed
        )
    }
}

@Composable
fun LinkInputSection() {
    var linkText by remember { mutableStateOf("") }

    TextField(
        value = linkText,
        onValueChange = { linkText = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(56.dp)
            .shadow(
                4.dp,
                RoundedCornerShape(28.dp),
                spotColor = TomaPrimaryText.copy(alpha = 0.05f)
            ),
        placeholder = {
            Text(
                text = "유튜브 링크를 붙여넣으세요...",
                color = TomaSecondaryText,
                fontSize = 16.sp
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = "Link Icon",
                tint = TomaSecondaryText,
                modifier = Modifier.size(24.dp)
            )
        },
        trailingIcon = {
            IconButton(
                onClick = { },
                modifier = Modifier
                    .size(40.dp)
                    .background(TomaIosLinkBlue, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Submit",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        colors = TextFieldDefaults.colors(
            focusedTextColor = TomaPrimaryText,
            unfocusedTextColor = TomaPrimaryText,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = TomaIosLinkBlue
        ),
        shape = RoundedCornerShape(28.dp),
        singleLine = true
    )
}

@Composable
fun ScanOptionsSection() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScanOptionCard(
            modifier = Modifier.weight(1f),
            iconVector = Icons.Default.PhotoCamera,
            iconBackgroundColor = TomaLightRed,
            iconTintColor = TomaMainRed,
            text = "사진 스캔"
        )

        ScanOptionCard(
            modifier = Modifier.weight(1f),
            iconVector = Icons.Default.Description,
            iconBackgroundColor = TomaQuickAnalysisBlue,
            iconTintColor = Color(0xFF1E88E5),
            text = "PDF 스캔"
        )
    }
}

@Composable
fun ScanOptionCard(
    modifier: Modifier = Modifier,
    iconVector: ImageVector,
    iconBackgroundColor: Color,
    iconTintColor: Color,
    text: String
) {
    Column(
        modifier = modifier
            .height(140.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = TomaPrimaryText.copy(0.05f))
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(iconBackgroundColor, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = text,
                tint = iconTintColor,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = text,
            color = TomaPrimaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

data class QuickAnalysisItem(
    val icon: ImageVector,
    val iconColor: Color,
    val iconBgColor: Color,
    val text: String
)

@Composable
fun QuickAnalysisSection() {
    val items = listOf(
        QuickAnalysisItem(Icons.Default.LocalFireDepartment, TomaMainRed, TomaLightRed, "틱톡 트렌드"),
        QuickAnalysisItem(Icons.Default.Article, Color(0xFF1E88E5), TomaQuickAnalysisBlue, "스크린샷"),
        QuickAnalysisItem(Icons.Default.Book, Color(0xFF43A047), TomaQuickAnalysisGreen, "레시피 요약")
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "빠른 분석",
            color = TomaPrimaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items) { item ->
                QuickAnalysisChip(item = item)
            }
        }
    }
}

@Composable
fun QuickAnalysisChip(item: QuickAnalysisItem) {
    Surface(
        onClick = { },
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.shadow(2.dp, RoundedCornerShape(20.dp), spotColor = TomaPrimaryText.copy(0.03f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(item.iconBgColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.text,
                    tint = item.iconColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = item.text,
                color = TomaPrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

data class RecentAnalysisItem(
    val title: String,
    val timeText: String,
    val badgeText: String,
    val badgeBgColor: Color,
    val tempColor: Color
)

@Composable
fun RecentAnalysisSection() {
    val items = listOf(
        RecentAnalysisItem("Miso Glazed Salmon", "2시간 전 분석", "YOUTUBE", TomaIosLinkBlue, Color(0xFF8D6E63)),
        RecentAnalysisItem("Roasted Root Salad", "어제 분석", "IMAGE", TomaMainRed, Color(0xFF43A047))
    )

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

            TextButton(onClick = { }) {
                Text(
                    text = "전체 보기",
                    color = TomaIosLinkBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items) { item ->
                RecentAnalysisCard(item = item)
            }
        }
    }
}

@Composable
fun RecentAnalysisCard(item: RecentAnalysisItem) {
    Column(
        modifier = Modifier
            .size(width = 200.dp, height = 240.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = TomaPrimaryText.copy(0.05f))
            .background(Color.White, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.5f)
                .background(item.tempColor)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, end = 10.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(8.dp))
                    .background(item.badgeBgColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.badgeText,
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTomaHomeScreen() {
    TomaHomeScreen(
        onMicClick = {},
        onHomeClick = {},
        onStorageClick = {},
        onSettingsClick = {}
    )
}
