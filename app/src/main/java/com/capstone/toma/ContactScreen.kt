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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactUsScreen(onBackClick: () -> Unit = {}) {
    val TomaPointOrange = Color(0xFFEE8C2B)
    val TomaSettingsBg = Color(0xFFFFFBFA)

    var email by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(TomaSettingsBg).padding(top = 48.dp, start = 20.dp, end = 20.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("문의하기", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("답변 받을 이메일") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TomaPointOrange, focusedLabelColor = TomaPointOrange, cursorColor = TomaPointOrange)
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = message, onValueChange = { message = it }, label = { Text("문의 내용") }, modifier = Modifier.fillMaxWidth().height(200.dp), maxLines = 8,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TomaPointOrange, focusedLabelColor = TomaPointOrange, cursorColor = TomaPointOrange)
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { /* 전송 로직 */ }, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TomaPointOrange), shape = RoundedCornerShape(12.dp)
        ) {
            Text("보내기", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}