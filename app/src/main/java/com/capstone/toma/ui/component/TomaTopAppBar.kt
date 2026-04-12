package com.capstone.toma.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.R
import com.capstone.toma.ui.theme.*

@Composable
fun TomaTopAppBar(
    onMenuClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 🌟 [왼쪽] 빨간 동그라미를 제거하고 로고 이미지만 배치
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // 배경이 있던 내부 Box를 단순히 이미지 컨테이너로만 사용합니다.
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_tomato),
                        contentDescription = "Toma Logo",
                        tint = Color.Unspecified, // 이미지 원본 색상 유지
                        modifier = Modifier.size(32.dp) // 동그라미가 사라졌으니 크기를 조금 더 키워도 좋습니다 (기존 24dp -> 32dp)
                    )
                }
            }


            Box(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "To-ma",
                    color = TomaMainOrange,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // [오른쪽] 햄버거 메뉴 버튼 영역
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.background(TomaCard, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "메뉴 열기",
                        tint = TomaPrimaryText
                    )
                }
            }
        }
    }
}