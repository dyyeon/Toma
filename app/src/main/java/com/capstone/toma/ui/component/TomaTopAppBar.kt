package com.capstone.toma.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.R
import com.capstone.toma.ui.theme.*

/** TomaTopAppBar의 컨텐츠(아이콘/제목 줄) 높이. 모든 화면에서 동일합니다. */
val TomaTopAppBarHeight: Dp = 56.dp

/** 상단 바 위쪽(상태바 영역) 여백. 모든 화면에서 동일하게 적용됩니다. */
val TomaTopAppBarTopPadding: Dp = 24.dp

/**
 * TOMA 앱의 통일된 상단 바.
 *
 * - 홈 화면: [onMenuClick]을 전달하면 토마토 로고 + "To-ma" + 햄버거 메뉴 형태로 표시됩니다.
 * - 그 외 화면: [showBackButton] = true 로 호출하면 좌측에 뒤로가기 버튼이 활성화되고,
 *   [title]을 전달하면 가운데에 제목이, 우측에는 "TOMA" 브랜드 텍스트가 표시됩니다.
 *
 * 모든 화면에서 동일한 높이/배치를 유지하기 위해 상단 여백([TomaTopAppBarTopPadding])과
 * 컨텐츠 높이([TomaTopAppBarHeight])를 컴포넌트 내부에서 직접 관리합니다.
 */
@Composable
fun TomaTopAppBar(
    title: String? = null,
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    onMenuClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TomaTopAppBarTopPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TomaTopAppBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [왼쪽] 뒤로가기 버튼 또는 토마토 로고
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (showBackButton) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로 가기",
                            tint = TomaPrimaryText
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_tomato),
                            contentDescription = "Toma Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // [가운데] 제목(서브 화면) 또는 홈의 "To-ma" 워드마크
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                when {
                    title != null -> {
                        Text(
                            text = title,
                            color = TomaPrimaryText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 홈 모드(뒤로가기 없음, 제목 없음)에서만 워드마크 표시
                    !showBackButton -> {
                        Text(
                            text = "To-ma",
                            color = TomaMainOrange,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            // [오른쪽] 햄버거 메뉴(홈) 또는 "TOMA" 브랜드 텍스트
            Box(
                contentAlignment = Alignment.CenterEnd
            ) {
                if (onMenuClick != null) {
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
                } else {
                    Text(
                        text = "TOMA",
                        color = TomaMainOrange,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
