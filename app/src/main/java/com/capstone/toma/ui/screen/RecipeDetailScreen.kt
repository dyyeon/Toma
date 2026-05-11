package com.capstone.toma.ui.screen

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.toma.TomaIntent
import com.capstone.toma.VoiceUiState
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.RecipeStorageViewModel
import com.capstone.toma.viewmodel.VoiceViewModel
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun RecipeDetailScreen(
    keyword: String = "",
    recipeDataJson: String? = null,
    onBackClick: () -> Unit = {},
    onFinish: (String, String?) -> Unit = { _, _ -> },
    voiceViewModel: VoiceViewModel
) {
    val storageViewModel: RecipeStorageViewModel = viewModel()
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val isFavorite by storageViewModel.isRecipeSaved(title).collectAsState(initial = false)

    RecipeDetailContent(
        keyword = keyword,
        recipeDataJson = recipeDataJson,
        isFavorite = isFavorite,
        onBackClick = onBackClick,
        onFavoriteClick = { storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite) },
        onFinish = onFinish,
        voiceViewModel = voiceViewModel,
        storageViewModel = storageViewModel
    )
}

@Composable
fun RecipeDetailContent(
    keyword: String,
    recipeDataJson: String?,
    isFavorite: Boolean,
    onBackClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onFinish: (String, String?) -> Unit,
    voiceViewModel: VoiceViewModel,
    storageViewModel: RecipeStorageViewModel
) {
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val steps = remember(recipeData) {
        recipeData?.optJSONArray("steps")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: emptyList()
    }
    val ingredients = remember(recipeData) {
        recipeData?.optJSONArray("ingredients")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: emptyList()
    }
    val difficulty = recipeData?.optString("difficulty") ?: "보통"
    val timeStr = recipeData?.optString("time") ?: "20분"

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val totalSteps = steps.size

    // One-way visibility latch: once the timer has been opened it stays visible
    // for the rest of the session.  Subsequent button presses restart the timer
    // value (via triggerTimer) without ever toggling this flag back to false,
    // which is what caused the disappear/reappear flicker.
    var showTimer by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceUiState by voiceViewModel.uiState.collectAsState()

    var isTtsEnabled by remember { mutableStateOf(true) }
    var isTtsSpeaking by remember { mutableStateOf(false) }
    // How many TTS utterances are currently in-flight (started but not yet done/stopped).
    // AtomicInteger gives TTS-thread-safe increments/decrements.
    // Disarm fires only on the 0→1 transition; re-arm fires only on the N→0 transition.
    val activeTtsCount = remember { AtomicInteger(0) }
    // Holds the single pending re-arm coroutine so rapid onDone→onStart sequences
    // can cancel a scheduled re-arm before it executes.
    val pendingTtsResume = remember { object { @Volatile var job: kotlinx.coroutines.Job? = null } }

    val isTimerRunning by voiceViewModel.isTimerRunning.collectAsState()
    val remainingSeconds by voiceViewModel.timerRemainingSeconds.collectAsState()

    var ttsReady by remember { mutableStateOf(false) }
    val tts: TextToSpeech = remember(context) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale.KOREAN
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    // All callbacks run on the TTS internal thread.
                    // wakeWordManager / @Volatile flag writes are thread-safe.
                    // Compose state (isTtsSpeaking) must be written via scope.launch (main thread).

                    // Called only on the 0→1 transition: first utterance of a TTS session.
                    // Idempotent — subsequent onStart calls for queued utterances cancel any
                    // pending re-arm but do NOT log another disarm or call pauseAudioCapture again.
                    private fun disarmOnFirstStart() {
                        pendingTtsResume.job?.cancel()
                        voiceViewModel.isTtsSpeaking = true
                        voiceViewModel.pauseAudioCapture()
                        scope.launch { isTtsSpeaking = true }
                        Log.d("WakeWord", "🔇 Disarmed during TTS playback | isTtsSpeaking=true")
                    }

                    // Called only on the N→0 transition: last utterance of a TTS session done.
                    // Clears the TTS flag immediately so returnToIdle() is unblocked, then waits
                    // 700 ms for audio tail-silence before actually re-arming the detector.
                    private fun scheduleRearmAfterLastDone() {
                        pendingTtsResume.job?.cancel()
                        pendingTtsResume.job = scope.launch {
                            voiceViewModel.isTtsSpeaking = false
                            isTtsSpeaking = false
                            Log.d("WakeWord", "✅ TTS finished — isTtsSpeaking=false, re-arming in 700ms")
                            delay(700)
                            voiceViewModel.resumeAudioCapture()
                            Log.d("WakeWord", "🔓 Re-armed after TTS playback")
                        }
                    }

                    override fun onStart(utteranceId: String?) {
                        val prev = activeTtsCount.getAndIncrement()
                        if (prev == 0) {
                            disarmOnFirstStart()
                        } else {
                            // Already disarmed from a previous onStart.
                            // Cancel any re-arm that snuck in between two queued utterances.
                            pendingTtsResume.job?.cancel()
                            Log.d("WakeWord", "🔇 TTS utterance start (active=${activeTtsCount.get()}) — already disarmed")
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        val remaining = activeTtsCount.decrementAndGet().coerceAtLeast(0)
                        if (remaining == 0) scheduleRearmAfterLastDone()
                        else Log.d("WakeWord", "TTS utterance done, $remaining still active")
                    }

                    override fun onError(utteranceId: String?) {
                        val remaining = activeTtsCount.decrementAndGet().coerceAtLeast(0)
                        if (remaining == 0) scheduleRearmAfterLastDone()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val remaining = activeTtsCount.decrementAndGet().coerceAtLeast(0)
                        if (remaining == 0) scheduleRearmAfterLastDone()
                    }

                    // onStop fires when TTS is explicitly cancelled — e.g., wake word triggered
                    // onStopTtsRequest, or the user tapped "안내 중지".  The initiating code is
                    // responsible for re-arming (wake-word flow via returnToIdle, or the button
                    // handler directly).  We just reset counter state and log.
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        activeTtsCount.set(0)
                        pendingTtsResume.job?.cancel()
                        voiceViewModel.isTtsSpeaking = false
                        scope.launch { isTtsSpeaking = false }
                        Log.d("WakeWord", "🛑 TTS stopped (interrupted=$interrupted) — re-arm delegated to caller")
                    }
                })
                ttsReady = true
            }
        }
        engine
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // 백그라운드/포그라운드 처리 — ON_PAUSE/ON_RESUME 기반
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (ttsReady) tts.stop()
                    isTtsSpeaking = false
                    activeTtsCount.set(0)
                    pendingTtsResume.job?.cancel()
                    voiceViewModel.isTtsSpeaking = false
                    voiceViewModel.onAppBackground()
                }
                Lifecycle.Event.ON_RESUME -> {
                    voiceViewModel.startWakeWord()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 화면 진입/이탈 처리
    DisposableEffect(Unit) {
        voiceViewModel.onStopTtsRequest = {
            if (ttsReady) {
                tts.stop()
                isTtsSpeaking = false
                activeTtsCount.set(0)
                pendingTtsResume.job?.cancel()
                // voiceViewModel.isTtsSpeaking is cleared by the onStop TTS callback
            }
        }
        voiceViewModel.startWakeWord()
        onDispose {
            voiceViewModel.onStopTtsRequest = null
            voiceViewModel.stopWakeWord()
            tts.stop()
            tts.shutdown()
        }
    }

    // TTS 재생 중 음성 인식 시작되면 즉시 TTS 중단
    LaunchedEffect(voiceUiState) {
        if (voiceUiState == VoiceUiState.Listening && isTtsSpeaking) {
            tts.stop()
            isTtsSpeaking = false
            activeTtsCount.set(0)
            pendingTtsResume.job?.cancel()
            // voiceViewModel.isTtsSpeaking cleared by the onStop TTS callback
        }
    }

    // 단계 변경 시 자동 TTS
    LaunchedEffect(currentStepIndex, ttsReady) {
        if (isTtsEnabled && ttsReady) {
            val text = if (currentStepIndex == 0)
                "재료 준비 단계입니다. 필수 재료는 ${ingredients.joinToString(", ")}입니다."
            else
                steps.getOrNull(currentStepIndex - 1) ?: ""
            if (text.isNotBlank()) {
                voiceViewModel.pauseAudioCapture()
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_auto")
            }
        }
    }

    // 음성 명령 처리
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.intentEvent.collect { intent ->
            android.util.Log.d(
                "RecipeDetail",
                "intentEvent received: $intent, step=$currentStepIndex / $totalSteps"
            )
            when (intent) {
                TomaIntent.NEXT_STEP -> {
                    if (currentStepIndex < totalSteps) currentStepIndex++
                    else onFinish(keyword, recipeDataJson)
                }
                TomaIntent.PREVIOUS_STEP -> {
                    if (currentStepIndex > 0) currentStepIndex--
                }
                TomaIntent.REPEAT_STEP -> {
                    val text = if (currentStepIndex == 0)
                        "재료 준비 단계입니다. ${ingredients.joinToString(", ")}"
                    else
                        steps.getOrNull(currentStepIndex - 1) ?: ""
                    if (ttsReady && text.isNotBlank()) {
                        voiceViewModel.pauseAudioCapture()
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_repeat")
                    }
                }
                TomaIntent.RECOMMENDED_TIMER -> {
                    val stepText = steps.getOrNull(currentStepIndex - 1) ?: ""
                    val minutes = "([0-9]+)\\s*분".toRegex()
                        .find(stepText)?.groupValues?.get(1)?.toIntOrNull()
                    if (minutes != null && minutes > 0) voiceViewModel.triggerTimer(minutes)
                    else voiceViewModel.showTimerManualGuidance()
                }
                // ✅ 추가 — 음성으로 타이머 취소 명령 처리
                TomaIntent.CANCEL_TIMER -> {
                    if (isTimerRunning) voiceViewModel.cancelTimer()
                }
                else -> {}
            }
        }
    }

    // 단계 변경 시 VoiceViewModel 동기화
    LaunchedEffect(currentStepIndex) {
        voiceViewModel.onStepChanged(currentStepIndex)
    }

    // 음성 안내 메시지 TTS
    LaunchedEffect(voiceViewModel, ttsReady) {
        if (!ttsReady) return@LaunchedEffect
        voiceViewModel.voiceAnnouncement.collect { message ->
            voiceViewModel.pauseAudioCapture()
            tts.speak(message, TextToSpeech.QUEUE_ADD, null, "announce_${System.currentTimeMillis()}")
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8F9FA)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            RecipeTopBar(
                onBackClick = onBackClick,
                keyword = title,
                isFavorite = isFavorite,
                onFavoriteClick = { storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite) }
            )

            // Latch: flip showTimer to true the first time the service starts a timer.
            // Never flip it back — resets are driven by triggerTimer(), not by visibility.
            LaunchedEffect(isTimerRunning) {
                if (isTimerRunning) showTimer = true
            }

            AnimatedVisibility(
                visible = showTimer,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                TimerDisplayCard(
                    remainingSeconds = remainingSeconds,
                    isRunning = isTimerRunning,
                    onCancel = { voiceViewModel.cancelTimer() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val progress = if (totalSteps > 0) currentStepIndex.toFloat() / totalSteps.toFloat() else 0f
            ProgressSection(current = currentStepIndex, total = totalSteps, progress = progress)

            Spacer(modifier = Modifier.height(32.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                if (currentStepIndex == 0) {
                    IngredientsSection(ingredients)
                } else {
                    CurrentStepSection(
                        stepNumber = currentStepIndex,
                        stepText = steps.getOrNull(currentStepIndex - 1) ?: ""
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            InfoCardRow(timeStr, difficulty)

            Spacer(modifier = Modifier.height(24.dp))

            val currentStepText = if (currentStepIndex == 0) "" else steps.getOrNull(currentStepIndex - 1) ?: ""
            val isTimerRecommended = "([0-9]+)\\s*분".toRegex().containsMatchIn(currentStepText)

            AiSuggestionSection(
                isTtsEnabled = isTtsEnabled,
                isTtsSpeaking = isTtsSpeaking,
                isTimerRecommended = isTimerRecommended,
                onTtsToggle = { isTtsEnabled = !isTtsEnabled },
                onTimerClick = {
                    val minutes = "([0-9]+)\\s*분".toRegex()
                        .find(currentStepText)?.groupValues?.get(1)?.toIntOrNull()
                    if (minutes != null && minutes > 0) voiceViewModel.triggerTimer(minutes)
                    else voiceViewModel.showTimerManualGuidance()
                },
                onSpeechClick = {
                    if (isTtsSpeaking) {
                        tts.stop()
                        isTtsSpeaking = false
                        activeTtsCount.set(0)
                        pendingTtsResume.job?.cancel()
                        voiceViewModel.isTtsSpeaking = false  // clear before resumeAudioCapture guard checks it
                        voiceViewModel.resumeAudioCapture()
                    } else {
                        val text = if (currentStepIndex == 0)
                            "재료 준비 단계입니다. ${ingredients.joinToString(", ")}"
                        else
                            steps.getOrNull(currentStepIndex - 1) ?: ""
                        if (ttsReady && text.isNotBlank()) {
                            voiceViewModel.pauseAudioCapture()
                            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_suggestion")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            BottomControlSection(
                onPrevClick = { if (currentStepIndex > 0) currentStepIndex-- },
                onNextClick = {
                    if (currentStepIndex < totalSteps) currentStepIndex++
                    else onFinish(keyword, recipeDataJson)
                },
                onMicClick = {
                    if (voiceUiState == VoiceUiState.Idle) voiceViewModel.startListeningManually()
                    else voiceViewModel.stopListeningManually()
                },
                isMicActive = voiceUiState == VoiceUiState.Listening
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RecipeTopBar(
    onBackClick: () -> Unit,
    keyword: String,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clickable { onBackClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEEEEEE))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.padding(10.dp),
                tint = Color.Black
            )
        }
        Text(
            text = titleCase(keyword),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProgressSection(current: Int, total: Int, progress: Float) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (current == 0) "재료 준비" else "단계 $current",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TomaMainOrange
            )
            Text(
                text = " / $total",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.LightGray,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(progress * 100).toInt()}% 완료",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color(0xFFE9ECEF), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(
                        brush = Brush.horizontalGradient(listOf(TomaMainOrange, Color(0xFFFFB347))),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun IngredientsSection(ingredients: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ShoppingBasket,
                contentDescription = null,
                tint = TomaMainOrange,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("필수 재료 체크", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
            shadowElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                ingredients.forEach { ingredient ->
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(TomaMainOrange, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            ingredient,
                            fontSize = 16.sp,
                            color = Color(0xFF495057),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentStepSection(stepNumber: Int, stepText: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stepNumber.toString(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = (-20).dp)
                .alpha(0.05f),
            fontSize = 160.sp,
            fontWeight = FontWeight.Black,
            color = Color.Black
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        ) {
            Text(
                "How to Cook",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TomaMainOrange,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stepText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF212529),
                lineHeight = 38.sp
            )
        }
    }
}

@Composable
private fun InfoCardRow(time: String, difficulty: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InfoCardDetail(
            modifier = Modifier.weight(1f),
            label = "요리시간",
            value = time,
            icon = Icons.Default.Timer,
            color = Color(0xFF4dabf7)
        )
        InfoCardDetail(
            modifier = Modifier.weight(1f),
            label = "난이도",
            value = difficulty,
            icon = Icons.Default.SignalCellularAlt,
            color = Color(0xFFfab005)
        )
    }
}

@Composable
private fun InfoCardDetail(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Surface(
        modifier = modifier.height(90.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFF1F3F5))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        }
    }
}

@Composable
private fun AiSuggestionSection(
    isTtsEnabled: Boolean = true,
    isTtsSpeaking: Boolean = false,
    isTimerRecommended: Boolean = false,
    onTtsToggle: () -> Unit = {},
    onTimerClick: () -> Unit = {},
    onSpeechClick: () -> Unit = {}
) {
    Column {
        Text("AI 제안", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val suggestions = listOf(
                "자동 안내" to if (isTtsEnabled) Icons.AutoMirrored.Filled.VolumeUp
                else Icons.AutoMirrored.Filled.VolumeOff,
                "타이머" to Icons.Default.AvTimer,
                (if (isTtsSpeaking) "안내 중지" else "다시듣기") to
                        if (isTtsSpeaking) Icons.Default.StopCircle
                        else Icons.AutoMirrored.Filled.VolumeUp
            )
            suggestions.forEach { (text, icon) ->
                val isStopButton = text == "안내 중지"
                val isTimerActive = text == "타이머" && isTimerRecommended

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            when (text) {
                                "자동 안내" -> onTtsToggle()
                                "타이머" -> onTimerClick()
                                "다시듣기", "안내 중지" -> onSpeechClick()
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = when {
                        text == "자동 안내" && isTtsEnabled -> TomaMainOrange.copy(alpha = 0.1f)
                        isTimerActive -> TomaMainOrange.copy(alpha = 0.1f)
                        isStopButton -> TomaMainRed.copy(alpha = 0.1f)
                        else -> Color(0xFFF1F3F5)
                    },
                    border = when {
                        text == "자동 안내" && isTtsEnabled ->
                            BorderStroke(1.dp, TomaMainOrange.copy(alpha = 0.3f))
                        isTimerActive ->
                            BorderStroke(1.dp, TomaMainOrange.copy(alpha = 0.3f))
                        isStopButton ->
                            BorderStroke(1.dp, TomaMainRed.copy(alpha = 0.3f))
                        else -> null
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = when {
                                text == "자동 안내" && isTtsEnabled -> TomaMainOrange
                                isTimerActive -> TomaMainOrange
                                isStopButton -> TomaMainRed
                                else -> Color.DarkGray
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                text == "자동 안내" && isTtsEnabled -> TomaMainOrange
                                isTimerActive -> TomaMainOrange
                                isStopButton -> TomaMainRed
                                else -> Color.DarkGray
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomControlSection(
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onMicClick: () -> Unit = {},
    isMicActive: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .clickable { onPrevClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEEEEEE))
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
                tint = Color.Black
            )
        }

        Surface(
            modifier = Modifier
                .size(76.dp)
                .clickable { onMicClick() },
            shape = CircleShape,
            color = if (isMicActive) Color(0xFFE53935) else TomaMainOrange,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                )
                Icon(
                    if (isMicActive) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isMicActive) "음성 인식 중지" else "음성 명령",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }

        Surface(
            modifier = Modifier
                .size(56.dp)
                .clickable { onNextClick() },
            shape = CircleShape,
            color = Color.Black,
            shadowElevation = 4.dp
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun TimerDisplayCard(
    remainingSeconds: Int,
    isRunning: Boolean,
    onCancel: () -> Unit
) {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    // Colour and label reflect running vs. stopped so the always-visible card
    // never looks stale after a natural completion or a cancel.
    val accentColor = if (isRunning) TomaMainOrange else Color(0xFF868E96)
    val statusLabel = if (isRunning) "타이머 작동 중" else "타이머 종료"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(accentColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    text = timeText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Black
                )
            }
            if (isRunning) {
                TextButton(onClick = onCancel) {
                    Text("취소", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun titleCase(str: String) = str.lowercase().replaceFirstChar { it.uppercase() }