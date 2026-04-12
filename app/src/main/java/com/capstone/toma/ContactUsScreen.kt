package com.capstone.toma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactUsScreen(onBackClick: () -> Unit = {}) {
    val TomaPointOrange = Color(0xFFEE8C2B)
    val TomaSettingsBg = Color(0xFFFFFBFA)
    val TomaItemGroupBg = Color(0xFFF7F2F0)

    var email by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(TomaSettingsBg).padding(top = 48.dp, start = 20.dp, end = 20.dp)) {
        // 상단 바
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("문의하기", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        // 전체를 감싸는 큰 카드 섹션
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(1.dp, RoundedCornerShape(16.dp))
                .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text("문의 내용을 남겨주세요", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(16.dp))

            // 1. 이메일 입력칸 (하얀색 막대기 제거 및 정식 배경색 적용!)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("답변 받을 이메일") },
                placeholder = { Text("example@toma.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TomaPointOrange,
                    unfocusedBorderColor = Color.LightGray,
                    focusedLabelColor = TomaPointOrange,
                    cursorColor = TomaPointOrange,
                    focusedContainerColor = Color.White,   // 👈 이렇게 안쪽 배경색을 지정해야 합니다!
                    unfocusedContainerColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 문의 내용 입력칸
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("상세 내용") },
                placeholder = { Text("문의하실 내용을 입력해 주세요.") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                maxLines = 10,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TomaPointOrange,
                    unfocusedBorderColor = Color.LightGray,
                    focusedLabelColor = TomaPointOrange,
                    cursorColor = TomaPointOrange,
                    focusedContainerColor = Color.White,   // 👈 여기도 마찬가지!
                    unfocusedContainerColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 보내기 버튼
            Button(
                onClick = { /* 전송 로직 */ },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TomaPointOrange),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("보내기", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}