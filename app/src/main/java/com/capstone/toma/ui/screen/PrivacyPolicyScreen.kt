package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrivacyPolicyScreen(onBackClick: () -> Unit = {}) {
    val TomaSettingsBg = Color(0xFFFFFBFA)
    val TomaItemGroupBg = Color(0xFFF7F2F0)

    Column(modifier = Modifier.fillMaxSize().background(TomaSettingsBg).padding(top = 48.dp, start = 20.dp, end = 20.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black)
            }
            Text("개인정보 처리방침", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // 남은 공간 꽉 채우기
                .shadow(1.dp, RoundedCornerShape(16.dp))
                .background(TomaItemGroupBg, RoundedCornerShape(16.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()) // 스크롤 가능하게!
        ) {
            Text(
                text = """
        TOMA 개인정보 처리방침
        
        제1조 (개인정보의 처리 목적)
        TOMA(이하 '본 앱')는 다음의 목적을 위하여 개인정보를 처리합니다.
        1. AI 맞춤형 레시피 검색 및 추천 서비스 제공
        2. 음성 인식(STT) 기반의 대화형 가이드 제공
        3. 푸시 알림 및 이벤트/공지사항 안내
        4. 사용자 문의(고객센터) 확인 및 답변

        제2조 (처리하는 개인정보의 항목)
        본 앱은 서비스 제공을 위해 아래와 같은 최소한의 데이터를 수집합니다.
        1. 필수 항목: 음성 데이터(마이크), 이미지 데이터, 텍스트 검색어
        2. 선택 항목: 이메일 주소, 기기 푸시 알림 토큰
        *음성 및 이미지 데이터는 AI 분석을 위해 일시적으로 사용되며, 서버에 영구 저장되지 않습니다.

        제3조 (개인정보의 외부 API 위탁)
        본 앱은 AI 분석 및 음성 인식을 위해 Google(Gemini) 및 OpenAI(Whisper) 등의 외부 API를 활용하며, 분석 목적 외에는 데이터를 사용하지 않습니다.

        제4조 (개인정보의 파기)
        본 앱은 개인정보 처리 목적이 달성된 경우 지체 없이 해당 정보를 파기합니다.

        제5조 (이용자의 권리)
        이용자는 기기 '설정' 메뉴를 통해 마이크, 카메라 접근 권한 및 알림 수신 동의를 언제든지 철회할 수 있습니다.

        제6조 (문의처)
        - 책임자: 국지민 (TOMA 개발팀)
        - 이메일: kook0707@mju.ac.kr
    """.trimIndent(),
                color = Color.DarkGray,
                fontSize = 14.sp,
                lineHeight = 24.sp
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}