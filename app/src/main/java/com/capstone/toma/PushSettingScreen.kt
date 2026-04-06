package com.capstone.toma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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

    var isMasterEnabled by remember { mutableStateOf(true) }
    var isCommentEnabled by remember { mutableStateOf(true) }
    var isLikeEnabled by remember { mutableStateOf(true) }
    var isRecipeRecommendEnabled by remember { mutableStateOf(true) }
    var isIngredientAlertEnabled by remember { mutableStateOf(true) }
    var isEventEnabled by remember { mutableStateOf(false) }
    var isNoticeEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize().background(TomaSettingsBg).padding(top = 48.dp, start = 20.dp, end = 20.dp).verticalScroll(rememberScrollState())
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("푸시 알림 설정", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
            PushSwitchItem("앱 푸시 알림 전체 허용", "모든 알림을 한 번에 켜고 끌 수 있습니다.", isMasterEnabled, { isMasterEnabled = it }, TomaPointOrange)
        }
        Spacer(modifier = Modifier.height(32.dp))

        if (isMasterEnabled) {
            Text("커뮤니티 알림", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
                PushSwitchItem("내 게시글 댓글 알림", null, isCommentEnabled, { isCommentEnabled = it }, TomaPointOrange)
                Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                PushSwitchItem("내 레시피 좋아요 알림", null, isLikeEnabled, { isLikeEnabled = it }, TomaPointOrange)
            }
            Spacer(modifier = Modifier.height(32.dp))

            Text("맞춤 정보 알림", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
                PushSwitchItem("오늘의 맞춤 레시피 추천", null, isRecipeRecommendEnabled, { isRecipeRecommendEnabled = it }, TomaPointOrange)
                Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                PushSwitchItem("관심 식재료 알림", null, isIngredientAlertEnabled, { isIngredientAlertEnabled = it }, TomaPointOrange)
            }
            Spacer(modifier = Modifier.height(32.dp))

            Text("이벤트 및 공지", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
                PushSwitchItem("이벤트 및 프로모션 알림", "광고성 정보 수신 동의", isEventEnabled, { isEventEnabled = it }, TomaPointOrange)
                Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                PushSwitchItem("중요 공지사항 알림", null, isNoticeEnabled, { isNoticeEnabled = it }, TomaPointOrange)
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun PushSwitchItem(title: String, subtitle: String? = null, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit, activeColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, color = Color(0xFF8E8E93), fontSize = 12.sp)
            }
        }
        Switch(checked = isChecked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = activeColor))
    }
}