package com.capstone.toma.ui.screen

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
import com.capstone.toma.ui.theme.*

// --- 1. 색상 및 스타일 정의 ---
// 호진님이 요청하신 포인트 오렌지 색상 (#EE8C2B)
val TomaPointOrange = Color(0xFFEE8C2B)
// 전체 화면 배경색 (따뜻한 off-white)
val TomaSettingsBg = Color(0xFFFFFBFA)
// 리스트 카드 배경색 (연한 베이지/그레이)
val TomaItemGroupBg = Color(0xFFF7F2F0)

// --- 2. 메인 설정 화면 Composable ---
// 나중에 내비게이션으로 호출할 때 이 함수를 사용하면 됩니다.
@Composable
fun SettingsScreen(
    // 나중에 뒤로가기 처리를 위해 콜백 함수를 받아둡니다. (기본값은 빈 함수)
    onBackClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaSettingsBg)
            // 상단 상태바 고려 및 시원한 여백 (top 48dp, 좌우 20dp)
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
            .verticalScroll(rememberScrollState()) // 스크롤 가능하게 설정
    ) {
        // --- 3. 상단 헤더 바 (뒤로가기, 타이틀, TOMA 로고) ---
        // 여기서 onBackClick 콜백을 전달합니다.
        TomaSettingsAppBar(onBackClick = onBackClick)

        Spacer(modifier = Modifier.height(32.dp))

        // --- 4. GENERAL 섹션 (Language, Text Size, Voice Settings) ---
        SettingsSectionHeader(title = "일반")
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.itemGroupModifier()) {
            SettingsItem(
                icon = Icons.Default.Language, // Globe 아이콘
                iconDesc = "Language Icon",
                title = "언어 설정",
                rightText = "한국어",
                onClick = { /* TODO: 언어 변경 */ }
            )
            // 구분선
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            SettingsItem(
                icon = Icons.Default.TextFormat, // Tt 아이콘
                iconDesc = "Text Size Icon",
                title = "글자 크기",
                rightText = "Standard",
                onClick = { /* TODO: 글자 크기 변경 */ }
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            SettingsItem(
                icon = Icons.Default.RecordVoiceOver, // Voice 아이콘
                iconDesc = "Voice Settings Icon",
                title = "음성 설정",
                onClick = { /* TODO: 음성 설정 */ }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- 5. NOTIFICATIONS 섹션 (Push, Email) ---
        SettingsSectionHeader(title = "알림")
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.itemGroupModifier()) {
            SettingsItem(
                icon = Icons.Default.NotificationsNone, // Bell 아이콘
                iconDesc = "Push Notifications Icon",
                title = "푸시 알림 설정",
                onClick = { /* TODO: 푸시 설정 */ }
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            SettingsItem(
                icon = Icons.Default.MailOutline, // Envelope 아이콘
                iconDesc = "Email Newsletters Icon",
                title = "이메일 수신 설정",
                onClick = { /* TODO: 이메일 설정 */ }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- 6. SUPPORT 섹션 (Help Center, Contact Us) ---
        SettingsSectionHeader(title = "지원")
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.itemGroupModifier()) {
            SettingsItem(
                icon = Icons.Default.HelpOutline, // Question Mark 아이콘
                iconDesc = "Help Center Icon",
                title = "고객센터",
                onClick = { /* TODO: 고객센터 */ }
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            SettingsItem(
                icon = Icons.Default.HelpOutline, // Question Mark 아이콘
                iconDesc = "Contact Us Icon",
                title = "문의하기",
                onClick = { /* TODO: 문의하기 */ }
            )
        }

        // 하단 여백
        Spacer(modifier = Modifier.height(48.dp))
    }
}

// --- 7. 공통 UI 컴포넌트 및 모디파이어 정의 ---

// 상단 헤더 바
@Composable
fun TomaSettingsAppBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 왼쪽: 뒤로가기 화살표 버튼
        IconButton(onClick = onBackClick) { // 전달받은 콜백을 실행합니다.
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.Black
            )
        }

        // 중앙: '설정' 타이틀
        Text(
            text = "설정",
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 오른쪽: 'TOMA' 로고 (오렌지 포인트 색상 적용)
        Text(
            text = "TOMA",
            color = TomaPointOrange, // 요청하신 오렌지 색상 사용
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

// 섹션 헤더 제목 (GENERAL, NOTIFICATIONS, SUPPORT)
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = TomaPointOrange, // 요청하신 오렌지 색상 사용
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp)
    )
}

// 설정 아이템 그룹 카드 스타일 Modifier
fun Modifier.itemGroupModifier(): Modifier = this
    .fillMaxWidth()
    .shadow(
        elevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
        spotColor = Color.Black.copy(0.02f)
    )
    .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
    .padding(vertical = 8.dp) // 그룹 내부 위아래 여백

// 개별 설정 아이템
@Composable
fun SettingsItem(
    icon: ImageVector,
    iconDesc: String,
    title: String,
    subtext: String? = null,
    rightText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 아이콘 (오렌지 톤 배경 서클 + 오렌지 아이콘 사용)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TomaItemGroupBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconDesc,
                tint = TomaPointOrange // 요청하신 오렌지 색상 사용
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 타이틀 + 서브텍스트 컬럼
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.Black,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            subtext?.let {
                Text(
                    text = it,
                    color = TomaSecondaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }

        // 오른쪽 텍스트 (한국어, Standard 등)
        rightText?.let {
            Text(
                text = it,
                color = TomaSecondaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        // Chevron (오른쪽 화살표)
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Navigate Right",
            tint = TomaSecondaryText,
            modifier = Modifier.size(24.dp)
        )
    }
}