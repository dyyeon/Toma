package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.BuildConfig

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaCardBorder = Color(0xFFF1F3F5)

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onPushClick: () -> Unit = {},
    onEmailClick: () -> Unit = {},
    onCustomerCenterClick: () -> Unit = {},
    onContactClick: () -> Unit = {},
    onSpeakerEnrollmentClick: () -> Unit = {},
    onClearRecentHistoryClick: () -> Unit = {}
) {
    var showClearRecentHistoryDialog by remember { mutableStateOf(false) }

    if (showClearRecentHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearRecentHistoryDialog = false },
            title = {
                Text(
                    text = "최근 기록 삭제",
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "최근 기록을 모두 삭제하시겠습니까? (삭제된 데이터는 복구할 수 없습니다)",
                    color = Color(0xFF495057),
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearRecentHistoryDialog = false
                        onClearRecentHistoryClick()
                    }
                ) {
                    Text(
                        text = "삭제",
                        color = Color(0xFFE03131),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearRecentHistoryDialog = false }) {
                    Text(
                        text = "취소",
                        color = Color(0xFF868E96),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }

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

            SectionHeader(title = "개인화 설정", icon = Icons.Default.Person)
            SettingsCard {
                SettingsItemRow(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "화자등록관리",
                    onClick = onSpeakerEnrollmentClick
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            SectionHeader(title = "데이터 관리", icon = Icons.Default.Storage)
            SettingsCard {
                SettingsItemRow(
                    icon = Icons.Default.DeleteOutline,
                    title = "최근기록삭제",
                    onClick = { showClearRecentHistoryDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            SectionHeader(title = "지원", icon = Icons.Default.SupportAgent)
            SettingsCard {
                SettingsItemRow(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = "고객센터",
                    onClick = onCustomerCenterClick
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            AppVersionInfoRow(versionText = "v${BuildConfig.VERSION_NAME}")

            Spacer(modifier = Modifier.height(40.dp))
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
            text = "설정",
            color = Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "TOMA",
            color = TomaMainOrange,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
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
private fun SettingsItemRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = TomaMainOrange.copy(alpha = 0.1f),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TomaMainOrange,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "이동",
            tint = Color(0xFFADB5BD),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AppVersionInfoRow(versionText: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "앱 버전 정보",
                color = Color(0xFF495057),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = versionText,
                color = TomaMainOrange,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    Box(modifier = Modifier.background(TomaBackground)) {
        SettingsScreen()
    }
}
