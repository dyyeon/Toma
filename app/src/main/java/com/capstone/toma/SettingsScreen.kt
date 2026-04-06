package com.capstone.toma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TomaPointOrange = Color(0xFFEE8C2B)
private val TomaSettingsBg = Color(0xFFFFFBFA)
private val TomaItemGroupBg = Color(0xFFF7F2F0)
private val TomaSecondaryText = Color(0xFF8E8E93)

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onPushClick: () -> Unit = {},
    onEmailClick: () -> Unit = {},
    onCustomerCenterClick: () -> Unit = {},
    onContactClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().background(TomaSettingsBg)
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.Black) }
            Text("설정", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text("TOMA", color = TomaPointOrange, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        // 알림 섹션
        Text("알림", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
            SettingsItemRow(icon = Icons.Default.NotificationsNone, title = "푸시 알림 설정", onClick = onPushClick)
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            SettingsItemRow(icon = Icons.Default.MailOutline, title = "이메일 수신 설정", onClick = onEmailClick)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 지원 섹션 (문의하기 포함)
        Text("지원", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
            SettingsItemRow(icon = Icons.Default.HelpOutline, title = "고객센터", onClick = onCustomerCenterClick)
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            SettingsItemRow(icon = Icons.Default.HelpOutline, title = "문의하기", onClick = onContactClick)
        }
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun SettingsItemRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = TomaPointOrange)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = TomaSecondaryText, modifier = Modifier.size(24.dp))
    }
}