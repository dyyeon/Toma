package com.capstone.toma.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaCardBorder = Color(0xFFF1F3F5)
private val TomaPrimaryText = Color(0xFF212529)
private val TomaSecondaryText = Color(0xFF868E96)

@Composable
fun CustomerCenterScreen(
    onBackClick: () -> Unit = {}
) {
    val faqList = listOf(
        Pair(
            "음성 가이드는 어떻게 사용하나요?",
            "홈 화면에서 마이크 버튼을 누르고 '메뉴 추천해줘' 혹은 '간단한 레시피 알려줘'라고 말씀하시면 TOMA가 똑똑하게 찾아드립니다!"
        ),
        Pair(
            "맞춤 레시피 기준이 무엇인가요?",
            "사용자가 최근 검색한 요리, 자주 찾는 식재료, 그리고 설정해 둔 선호/비선호 재료 데이터를 바탕으로 AI가 가장 적합한 레시피를 추천해 줍니다."
        ),
        Pair(
            "문의는 어디로 할 수 있나요?",
            "관련 문의는 TOMA 개발팀의 책임자에게 이메일(kook0707@mju.ac.kr)로 남겨주시면 됩니다. 보내주신 내용을 확인하는 대로 신속하고 자세하게 안내해 드리겠습니다. 서비스 이용과 관련하여 궁금하신 점이나 불편한 사항이 있으신 경우, 언제든지 저희 쪽으로 연락해 주시기 바랍니다. 감사합니다."
        )
    )

    var expandedIndex by remember { mutableIntStateOf(-1) }

    Scaffold(
        containerColor = TomaBackground,
        topBar = {
            CustomerCenterTopBar(onBackClick = onBackClick)
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

            Text(
                text = "자주 묻는 질문 (FAQ)",
                color = TomaPrimaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            faqList.forEachIndexed { index, faq ->
                val isExpanded = expandedIndex == index
                FaqItemCard(
                    question = faq.first,
                    answer = faq.second,
                    isExpanded = isExpanded,
                    onClick = { expandedIndex = if (isExpanded) -1 else index }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun CustomerCenterTopBar(onBackClick: () -> Unit) {
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
                tint = TomaPrimaryText
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = "고객센터",
            color = TomaPrimaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FaqItemCard(
    question: String,
    answer: String,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val arrowRotationDegree by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "arrowRotation"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, TomaCardBorder),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TomaMainOrange.copy(alpha = 0.1f),
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Text(
                        text = "Q",
                        color = TomaMainOrange,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = question,
                    color = TomaPrimaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "확장",
                    tint = TomaSecondaryText,
                    modifier = Modifier.rotate(arrowRotationDegree)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 1.dp,
                        color = TomaCardBorder
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFBFBFC))
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE9ECEF),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                text = "A",
                                color = TomaSecondaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Text(
                            text = answer,
                            color = TomaSecondaryText,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CustomerCenterScreenPreview() {
    CustomerCenterScreen()
}
