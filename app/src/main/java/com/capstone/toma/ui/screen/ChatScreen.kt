package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.ui.theme.*
import androidx.compose.ui.res.painterResource
import com.capstone.toma.R
import com.capstone.toma.ui.component.TomaTopAppBar

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: String
)

data class AiChatUiState(
    val inputText: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val errorDialogMessage: String? = null
)

@Composable
fun AiChatScreen(
    uiState: AiChatUiState,
    onBackClick: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onMicClick: () -> Unit,
    onErrorDismiss: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // 에러 다이얼로그 노출
    uiState.errorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            title = { Text(text = "분석 실패", fontWeight = FontWeight.Bold) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = onErrorDismiss) {
                    Text("확인", color = TomaMainOrange)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = TomaBackground,
        topBar = {
            TomaTopAppBar(
                title = "AI 채팅",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // [채팅 목록 영역]
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                items(uiState.messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }

                if (uiState.isTyping) {
                    item {
                        TypingIndicatorBubble()
                    }
                }
            }

            ChatInputBar(
                inputText = uiState.inputText,
                isTyping = uiState.isTyping,
                onInputTextChange = onInputTextChange,
                onSendMessage = onSendMessage,
                onMicClick = onMicClick
            )
        }
    }
}

@Composable
fun ChatTopAppBar(onBackClick: () -> Unit) {
    Surface(
        color = TomaBackground,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로 가기",
                    tint = TomaPrimaryText
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(

            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_tomato),
                    contentDescription = "Toma Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = "TOMA",
                    color = TomaPrimaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser

    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    val bubbleBgColor = if (isUser) TomaMainOrange else Color.White
    val textColor = if (isUser) Color.White else TomaPrimaryText

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isUser) {
                Text(
                    text = message.timestamp,
                    color = TomaSecondaryText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp, bottom = 2.dp)
                )
            }

            Surface(
                shape = bubbleShape,
                color = bubbleBgColor,
                shadowElevation = if (isUser) 2.dp else 4.dp,
                modifier = Modifier.widthIn(max = 260.dp)
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            if (isUser) {
                Text(
                    text = message.timestamp,
                    color = TomaSecondaryText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
fun TypingIndicatorBubble() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            color = Color.White,
            shadowElevation = 4.dp,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(
                text = "레시피를 고민 중이에요...",
                color = TomaSecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    isTyping: Boolean,
    onInputTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onMicClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onMicClick,
                enabled = !isTyping,
                modifier = Modifier
                    .size(42.dp)
                    .background(if (isTyping) Color(0xFFF5F5F5) else TomaBackground, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "음성 입력",
                    tint = if (isTyping) Color.LightGray else TomaMainOrange
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp, max = 120.dp)
                    .background(TomaBackground, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    enabled = !isTyping,
                    textStyle = LocalTextStyle.current.copy(
                        color = if (isTyping) Color.Gray else TomaPrimaryText,
                        fontSize = 15.sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) onSendMessage()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = if (isTyping) "분석 중입니다..." else "메시지를 입력하세요...",
                                color = TomaSecondaryText,
                                fontSize = 15.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // [전송 버튼]
            val isSendEnabled = inputText.isNotBlank() && !isTyping
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isSendEnabled) TomaMainOrange else Color(0xFFE0E0E0))
                    .clickable(enabled = isSendEnabled) { onSendMessage() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "전송",
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 2.dp)
                )
            }
        }
    }
}

// UI 테스트용 프리뷰
@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewAiChatScreen() {
    var inputText by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage("1", "안녕하세요! TOMA AI 요리사입니다. 냉장고에 어떤 재료가 있으신가요?", false, "오후 1:00"),
                ChatMessage("2", "집에 양파랑 계란, 그리고 스팸이 조금 남았어. 이걸로 뭐 해 먹을 수 있을까?", true, "오후 1:01"),
                ChatMessage("3", "그 재료라면 '스팸 양파 계란 볶음밥'이나 '폭신한 스팸 계란찜'을 추천해 드려요! 어떤 걸 만들어 볼까요?", false, "오후 1:01")
            )
        )
    }

    AiChatScreen(
        uiState = AiChatUiState(
            inputText = inputText,
            messages = messages,
            isTyping = false
        ),
        onBackClick = {},
        onInputTextChange = { inputText = it },
        onSendMessage = {
            messages = messages + ChatMessage("new", inputText, true, "방금")
            inputText = ""
        },
        onMicClick = {}
    )
}