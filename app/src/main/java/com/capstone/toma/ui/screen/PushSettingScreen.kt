package com.capstone.toma.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaCardBorder = Color(0xFFF1F3F5)

@Composable
fun PushSettingScreen(onBackClick: () -> Unit = {}) {
    var isMasterEnabled by remember { mutableStateOf(true) }
    var isCommentEnabled by remember { mutableStateOf(true) }
    var isLikeEnabled by remember { mutableStateOf(true) }
    var isRecipeRecommendEnabled by remember { mutableStateOf(true) }
    var isIngredientAlertEnabled by remember { mutableStateOf(true) }
    var isEventEnabled by remember { mutableStateOf(false) }
    var isNoticeEnabled by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = TomaBackground,
        topBar = {
            SettingsTopBar(onBackClick = onBackClick)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            SettingsCard {
                PushSwitchItem(
                    title = "앱 푸시 알림 전체 허용",
                    subtitle = "모든 알림을 한 번에 켜고 끌 수 있습니다.",
                    isChecked = isMasterEnabled,
                    onCheckedChange = { isMasterEnabled = it },
                    icon = Icons.Default.NotificationsActive,
                    iconColor = TomaMainOrange
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(
                visible = isMasterEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    SectionHeader(title = "커뮤니티 알림", icon = Icons.Default.People)
                    SettingsCard {
                        PushSwitchItem(
                            title = "내 게시글 댓글 알림",
                            isChecked = isCommentEnabled,
                            onCheckedChange = { isCommentEnabled = it }
                        )
                        CustomDivider()
                        PushSwitchItem(
                            title = "내 레시피 좋아요 알림",
                            isChecked = isLikeEnabled,
                            onCheckedChange = { isLikeEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    SectionHeader(title = "맞춤 정보 알림", icon = Icons.Default.Tune)
                    SettingsCard {
                        PushSwitchItem(
                            title = "오늘의 맞춤 레시피 추천",
                            isChecked = isRecipeRecommendEnabled,
                            onCheckedChange = { isRecipeRecommendEnabled = it }
                        )
                        CustomDivider()
                        PushSwitchItem(
                            title = "관심 식재료 알림",
                            isChecked = isIngredientAlertEnabled,
                            onCheckedChange = { isIngredientAlertEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    SectionHeader(title = "이벤트 및 공지", icon = Icons.Default.Campaign)
                    SettingsCard {
                        PushSwitchItem(
                            title = "이벤트 및 프로모션 알림",
                            subtitle = "광고성 정보 수신 동의",
                            isChecked = isEventEnabled,
                            onCheckedChange = { isEventEnabled = it }
                        )
                        CustomDivider()
                        PushSwitchItem(
                            title = "중요 공지사항 알림",
                            isChecked = isNoticeEnabled,
                            onCheckedChange = { isNoticeEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .statusBarsPadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(44.dp)
                .clickable { onBackClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, TomaCardBorder),
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
            text = "알림 설정",
            color = Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TomaMainOrange,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = Color.Gray,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
private fun PushSwitchItem(
    title: String,
    subtitle: String? = null,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconColor: Color = Color.Gray
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(vertical = 12.dp, horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Surface(
                    shape = CircleShape,
                    color = iconColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }

            Column {
                Text(
                    text = title,
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = if (icon != null) FontWeight.Bold else FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.85f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = TomaMainOrange,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE5E5EA),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun CustomDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        thickness = 1.dp,
        color = TomaCardBorder
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PushSettingScreenPreview() {
    PushSettingScreen()
}