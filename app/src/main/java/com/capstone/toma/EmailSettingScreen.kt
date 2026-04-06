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
fun EmailSettingScreen(onBackClick: () -> Unit = {}) {
    val TomaPointOrange = Color(0xFFEE8C2B)
    val TomaSettingsBg = Color(0xFFFFFBFA)
    val TomaItemGroupBg = Color(0xFFF7F2F0)

    var isEmailEnabled by remember { mutableStateOf(false) }
    var emailAddress by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(TomaSettingsBg).padding(top = 48.dp, start = 20.dp, end = 20.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("이메일 수신 설정", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        Column(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(TomaItemGroupBg, RoundedCornerShape(16.dp)).padding(vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("이메일 소식 받기", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Switch(checked = isEmailEnabled, onCheckedChange = { isEmailEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TomaPointOrange))
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("수신 받을 이메일 주소", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailAddress, onValueChange = { emailAddress = it }, placeholder = { Text("example@toma.com", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TomaPointOrange, cursorColor = TomaPointOrange)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = TomaPointOrange), shape = RoundedCornerShape(12.dp)) {
                    Text("저장하기", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}