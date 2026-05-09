package com.capstone.toma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PushSettingScreen(onBackClick: () -> Unit = {}) {
    val TomaPointOrange = Color(0xFFEE8C2B)
    val TomaSettingsBg = Color(0xFFFFFBFA)
    val TomaItemGroupBg = Color(0xFFF7F2F0)
    val TomaSecondaryText = Color(0xFF8E8E93)

    // 마스터 스위치 (전체 알림)
    var isMasterEnabled by remember { mutableStateOf(true) }

    // 개별 설정 스위치들
    var isCommentEnabled by remember { mutableStateOf(true) }
    var isLikeEnabled by remember { mutableStateOf(true) }
    var isRecipeRecommendEnabled by remember { mutableStateOf(true) }
    var isIngredientAlertEnabled by remember { mutableStateOf(true) }
    var isEventEnabled by remember { mutableStateOf(false) }
    var isNoticeEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaSettingsBg)
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
            .verticalScroll(rememberScrollState()) // 스크롤 가능하도록 추가!
    ) {
        // 상단 바
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("푸시 알림 설정", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- 1. 기본 설정 (마스터 스위치) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(1.dp, RoundedCornerShape(16.dp))
                .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                .padding(vertical = 8.dp)
        ) {
            PushSwitchItem(
                title = "앱 푸시 알림 전체 허용",
                subtitle = "모든 알림을 한 번에 켜고 끌 수 있습니다.",
                isChecked = isMasterEnabled,
                onCheckedChange = { isMasterEnabled = it },
                activeColor = TomaPointOrange
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 전체 알림이 켜져 있을 때만 아래 세부 항목들이 보이도록 (혹은 조작 가능하도록) 처리
        if (isMasterEnabled) {

            // --- 2. 커뮤니티 알림 ---
            Text("커뮤니티 알림", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(16.dp))
                    .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp)
            ) {
                PushSwitchItem(title = "내 게시글 댓글 알림", isChecked = isCommentEnabled, onCheckedChange = { isCommentEnabled = it }, activeColor = TomaPointOrange)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                PushSwitchItem(title = "내 레시피 좋아요 알림", isChecked = isLikeEnabled, onCheckedChange = { isLikeEnabled = it }, activeColor = TomaPointOrange)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- 3. 서비스 및 맞춤 알림 ---
            Text("맞춤 정보 알림", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(16.dp))
                    .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp)
            ) {
                PushSwitchItem(title = "오늘의 맞춤 레시피 추천", isChecked = isRecipeRecommendEnabled, onCheckedChange = { isRecipeRecommendEnabled = it }, activeColor = TomaPointOrange)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                PushSwitchItem(title = "관심 식재료 알림", isChecked = isIngredientAlertEnabled, onCheckedChange = { isIngredientAlertEnabled = it }, activeColor = TomaPointOrange)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- 4. 혜택 및 중요 알림 ---
            Text("이벤트 및 공지", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(16.dp))
                    .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp)
            ) {
                PushSwitchItem(title = "이벤트 및 프로모션 알림", subtitle = "광고성 정보 수신 동의", isChecked = isEventEnabled, onCheckedChange = { isEventEnabled = it }, activeColor = TomaPointOrange)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                PushSwitchItem(title = "중요 공지사항 알림", isChecked = isNoticeEnabled, onCheckedChange = { isNoticeEnabled = it }, activeColor = TomaPointOrange)
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// 스위치가 들어가는 Row 컴포넌트 (코드 재사용성을 위해 분리)
@Composable
fun PushSwitchItem(
    title: String,
    subtitle: String? = null,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, color = Color(0xFF8E8E93), fontSize = 12.sp)
            }
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = activeColor
            )
        )
    }
}