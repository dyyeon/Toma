package com.capstone.toma.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaCardBorder = Color(0xFFF1F3F5)

@Composable
fun PrivacyPolicyScreen(onBackClick: () -> Unit = {}) {
    Scaffold(
        containerColor = TomaBackground,
        topBar = {
            PrivacyTopBar(onBackClick = onBackClick)
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

            // 상단 안내 배지
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, TomaCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = TomaMainOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "TOMA는 사용자의 개인정보를 소중히 보호하며, 서비스 제공에 필요한 최소한의 데이터만 활용합니다.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 본문 내용 (조항별로 스타일링)
            PolicyArticle(
                number = "제1조",
                title = "개인정보의 처리 목적",
                content = "TOMA(이하 '본 앱')는 다음의 목적을 위하여 개인정보를 처리합니다.\n" +
                        "1. AI 맞춤형 레시피 검색 및 추천 서비스 제공\n" +
                        "2. 음성 인식(STT) 기반의 대화형 가이드 제공\n" +
                        "3. 푸시 알림 및 이벤트/공지사항 안내\n" +
                        "4. 사용자 문의(고객센터) 확인 및 답변"
            )

            PolicyArticle(
                number = "제2조",
                title = "처리하는 개인정보의 항목",
                content = "본 앱은 서비스 제공을 위해 아래와 같은 최소한의 데이터를 수집합니다.\n" +
                        "1. 필수 항목: 음성 데이터(마이크), 이미지 데이터, 텍스트 검색어\n" +
                        "2. 선택 항목: 이메일 주소, 기기 푸시 알림 토큰\n" +
                        "* 음성 및 이미지 데이터는 AI 분석을 위해 일시적으로 사용되며, 서버에 영구 저장되지 않습니다."
            )

            PolicyArticle(
                number = "제3조",
                title = "개인정보의 외부 API 위탁",
                content = "본 앱은 AI 분석 및 음성 인식을 위해 Google(Gemini) 및 OpenAI(Whisper) 등의 외부 API를 활용하며, 분석 목적 외에는 데이터를 사용하지 않습니다."
            )

            PolicyArticle(
                number = "제4조",
                title = "개인정보의 파기",
                content = "본 앱은 개인정보 처리 목적이 달성된 경우 지체 없이 해당 정보를 파기합니다."
            )

            PolicyArticle(
                number = "제5조",
                title = "이용자의 권리",
                content = "이용자는 기기 '설정' 메뉴를 통해 마이크, 카메라 접근 권한 및 알림 수신 동의를 언제든지 철회할 수 있습니다."
            )

            PolicyArticle(
                number = "제6조",
                title = "문의처 및 책임자",
                content = "- 책임자: 국지민 (TOMA 개발팀)\n" +
                        "- 이메일: kook0707@mju.ac.kr"
            )

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "공고일자: 2024년 5월 1일\n시행일자: 2024년 5월 1일",
                fontSize = 12.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(bottom = 40.dp)
            )
        }
    }
}

@Composable
private fun PrivacyTopBar(onBackClick: () -> Unit) {
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
            text = "개인정보 처리방침",
            color = Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PolicyArticle(number: String, title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 16.dp)
                    .background(TomaMainOrange, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$number ($title)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = content,
            fontSize = 14.sp,
            lineHeight = 24.sp,
            color = Color(0xFF495057),
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PrivacyPolicyScreenPreview() {
    PrivacyPolicyScreen()
}