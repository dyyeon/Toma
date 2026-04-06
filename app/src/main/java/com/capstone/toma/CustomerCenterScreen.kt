package com.capstone.toma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
fun CustomerCenterScreen(onBackClick: () -> Unit = {}) {
    val TomaPointOrange = Color(0xFFEE8C2B)
    val TomaSettingsBg = Color(0xFFFFFBFA)
    val TomaItemGroupBg = Color(0xFFF7F2F0)

    // TOMA 앱 맞춤형 FAQ 데이터
    val faqList = listOf(
        Pair("Q. 음성 가이드는 어떻게 사용하나요?", "홈 화면에서 마이크 버튼을 누르고 '메뉴 추천해줘' 혹은 '간단한 레시피 알려줘'라고 말씀하시면 TOMA가 똑똑하게 찾아드립니다!"),
        Pair("Q. 맞춤 레시피 기준이 무엇인가요?", "사용자가 최근 검색한 요리, 자주 찾는 식재료, 그리고 설정해 둔 선호/비선호 재료 데이터를 바탕으로 AI가 가장 적합한 레시피를 추천해 줍니다."),
        Pair("Q. 푸시 알림이 오지 않아요.", "기기의 설정 > 애플리케이션 > TOMA > 알림 메뉴에서 알림이 허용되어 있는지 확인해 주세요. 앱 내 '푸시 알림 설정'에서도 전체 허용이 켜져 있어야 합니다."),
        Pair("Q. 등록한 이메일을 변경하고 싶어요.", "설정 > 이메일 수신 설정 메뉴에서 기존 이메일을 지우고 새로운 이메일을 입력한 뒤 '저장하기' 버튼을 누르시면 반영됩니다.")
    )

    // 몇 번째 질문이 펼쳐져 있는지 기억하는 변수 (-1은 모두 접힌 상태)
    var expandedIndex by remember { mutableStateOf(-1) }

    Column(modifier = Modifier.fillMaxSize().background(TomaSettingsBg).padding(top = 48.dp, start = 20.dp, end = 20.dp)) {
        // 상단 바
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("고객센터", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        Text("자주 묻는 질문 (FAQ)", color = TomaPointOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        Spacer(modifier = Modifier.height(12.dp))

        // FAQ 리스트 영역
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            faqList.forEachIndexed { index, faq ->
                val isExpanded = expandedIndex == index

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .shadow(1.dp, RoundedCornerShape(16.dp))
                        .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                ) {
                    // 1. 질문 영역 (터치하면 펼쳐짐/접힘)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedIndex = if (isExpanded) -1 else index }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = faq.first, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand",
                            tint = TomaPointOrange
                        )
                    }

                    // 2. 답변 영역 (해당 질문이 터치되었을 때만 등장!)
                    if (isExpanded) {
                        Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.LightGray.copy(0.5f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                                .padding(16.dp)
                        ) {
                            Text(text = faq.second, color = Color.DarkGray, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}