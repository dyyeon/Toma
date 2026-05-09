package com.capstone.toma.ui.screen

import android.media.MediaRecorder
import android.os.Build
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.OpenAiManager
import com.capstone.toma.R
import com.capstone.toma.ui.theme.*
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaCardBorder = Color(0xFFF1F3F5)
private val TomaPrimaryText = Color(0xFF212529)
private val TomaSecondaryText = Color(0xFF868E96)

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
    onMicClick: () -> Unit = {},
    onErrorDismiss: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<MediaRecorder?>(null) }
    val audioFile = remember { File(context.cacheDir, "chat_voice.m4a") }
    val vadJob = remember { arrayOf<kotlinx.coroutines.Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            vadJob[0]?.cancel()
            runCatching { recorder.value?.stop(); recorder.value?.release() }
            recorder.value = null
        }
    }

    fun stopAndTranscribe() {
        vadJob[0]?.cancel(); vadJob[0] = null
        runCatching { recorder.value?.stop(); recorder.value?.release() }
        recorder.value = null
        isRecording = false
        isTranscribing = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    OpenAiManager().transcribeAudio(audioFile) { result ->
                        cont.resume(result ?: "")
                    }
                }
            }
            isTranscribing = false
            if (text.isNotBlank()) {
                onInputTextChange(text)
                onSendMessage()
            }
        }
    }

    fun startRecording() {
        runCatching {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            recorder.value = mr
            isRecording = true

            vadJob[0] = scope.launch {
                val silenceThreshold = 500
                val silenceDurationMs = 1500L
                val maxDurationMs = 30_000L
                val recordingStart = System.currentTimeMillis()
                var silenceStart = 0L

                while (isActive) {
                    delay(100)
                    val elapsed = System.currentTimeMillis() - recordingStart
                    if (elapsed > maxDurationMs) { stopAndTranscribe(); break }

                    val amplitude = recorder.value?.maxAmplitude ?: 0
                    if (amplitude < silenceThreshold) {
                        if (silenceStart == 0L) silenceStart = System.currentTimeMillis()
                        else if (System.currentTimeMillis() - silenceStart >= silenceDurationMs) {
                            stopAndTranscribe(); break
                        }
                    } else {
                        silenceStart = 0L
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState.messages.size, uiState.isTyping) {
        val targetIndex = if (uiState.isTyping) uiState.messages.size else uiState.messages.size - 1
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    uiState.errorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            title = { Text(text = "분석 실패", fontWeight = FontWeight.Bold, color = TomaPrimaryText) },
            text = { Text(text = message, color = TomaSecondaryText) },
            confirmButton = {
                TextButton(onClick = onErrorDismiss) {
                    Text("확인", color = TomaMainOrange, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = TomaBackground,
        topBar = { ChatTopAppBar(onBackClick = onBackClick) },
        bottomBar = {
            ChatInputBar(
                inputText = uiState.inputText,
                isTyping = uiState.isTyping,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                onInputTextChange = onInputTextChange,
                onSendMessage = onSendMessage,
                onMicClick = { if (isRecording) stopAndTranscribe() else startRecording() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp) // 말풍선 간격 넓힘
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
        }
    }
}

@Composable
private fun ChatTopAppBar(onBackClick: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.95f),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onBackClick() },
                shape = CircleShape,
                color = Color.Transparent
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로 가기",
                    tint = TomaPrimaryText,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = CircleShape,
                color = TomaMainOrange.copy(alpha = 0.1f),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_tomato),
                    contentDescription = "Toma Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(6.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = "TOMA AI",
                    color = TomaPrimaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF20C997))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "답변 가능",
                        color = TomaSecondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, TomaCardBorder),
                modifier = Modifier
                    .size(32.dp)
                    .padding(bottom = 2.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_tomato),
                    contentDescription = "Toma AI",
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(5.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (isUser) {
            Text(
                text = message.timestamp,
                color = Color(0xFFADB5BD),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)
            )
        }

        val bubbleShape = if (isUser) {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
        } else {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        }

        val bubbleBgColor = if (isUser) TomaMainOrange else Color.White
        val textColor = if (isUser) Color.White else TomaPrimaryText

        Surface(
            shape = bubbleShape,
            color = bubbleBgColor,
            shadowElevation = if (isUser) 2.dp else 4.dp,
            border = if (isUser) null else BorderStroke(1.dp, TomaCardBorder),
            modifier = Modifier.widthIn(max = 250.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        if (!isUser) {
            Text(
                text = message.timestamp,
                color = Color(0xFFADB5BD),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun TypingIndicatorBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, TomaCardBorder),
            modifier = Modifier
                .size(32.dp)
                .padding(bottom = 2.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_tomato),
                contentDescription = "Toma AI",
                tint = Color.Unspecified,
                modifier = Modifier.padding(5.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp),
            color = Color.White,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, TomaCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "typing")
                val dotAlpha = List(3) { index ->
                    infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, delayMillis = index * 200),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotAlpha_$index"
                    )
                }

                dotAlpha.forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(TomaMainOrange.copy(alpha = alpha.value))
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    isTyping: Boolean,
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    onInputTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onMicClick: () -> Unit
) {
    val micBusy = isTyping || isTranscribing

    // 녹음 중 마이크 아이콘 호흡 애니메이션
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val micAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "micAlpha"
    )

    Surface(
        color = Color.White,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, TomaCardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // 마이크 버튼
            Surface(
                onClick = onMicClick,
                enabled = !micBusy,
                shape = CircleShape,
                color = when {
                    isRecording -> TomaMainOrange
                    micBusy -> Color(0xFFF1F3F5)
                    else -> TomaMainOrange.copy(alpha = 0.1f)
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isTranscribing -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = TomaMainOrange,
                            strokeWidth = 2.dp
                        )
                        isRecording -> Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "녹음 중지",
                            tint = Color.White.copy(alpha = micAlpha),
                            modifier = Modifier.size(24.dp)
                        )
                        else -> Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "음성 입력",
                            tint = if (micBusy) Color(0xFFADB5BD) else TomaMainOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp)
                    .background(Color(0xFFF1F3F5), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    enabled = !isTyping,
                    textStyle = LocalTextStyle.current.copy(
                        color = if (isTyping) Color.Gray else TomaPrimaryText,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (inputText.isNotBlank()) onSendMessage() }
                    ),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = when {
                                    isRecording -> "말씀해주세요... 🎙️"
                                    isTranscribing -> "음성 인식 중..."
                                    isTyping -> "답변을 기다리고 있어요..."
                                    else -> "메시지를 입력하세요"
                                },
                                color = TomaSecondaryText,
                                fontSize = 15.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            val isSendEnabled = inputText.isNotBlank() && !isTyping
            Surface(
                onClick = onSendMessage,
                enabled = isSendEnabled,
                shape = CircleShape,
                color = if (isSendEnabled) TomaMainOrange else Color(0xFFE9ECEF),
                shadowElevation = if (isSendEnabled) 4.dp else 0.dp,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "전송",
                        tint = if (isSendEnabled) Color.White else Color(0xFFADB5BD),
                        modifier = Modifier
                            .size(20.dp)
                            .padding(start = 2.dp)
                    )
                }
            }
        }
    }
}


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
            messages = messages + ChatMessage("new", inputText, true, "오후 1:02")
            inputText = ""
        },
        onMicClick = {}
    )
}

@Preview(name = "타이핑 중 상태", showBackground = true, showSystemUi = true)
@Composable
fun PreviewAiChatScreenTyping() {
    AiChatScreen(
        uiState = AiChatUiState(
            inputText = "",
            messages = listOf(
                ChatMessage("1", "집에 양파랑 계란, 그리고 스팸이 조금 남았어.", true, "오후 1:01")
            ),
            isTyping = true
        ),
        onBackClick = {},
        onInputTextChange = {},
        onSendMessage = {},
        onMicClick = {}
    )
}