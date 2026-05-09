package com.capstone.toma.ui.screen

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import com.capstone.toma.OpenAiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.RecipeStorageViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.Locale

@Composable
fun RecipeDetailScreen(
    keyword: String = "",
    recipeDataJson: String? = null,
    onBackClick: () -> Unit = {}
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
        onFavoriteClick = { storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite) }
    )
}

@Composable
fun RecipeDetailContent(
    keyword: String,
    recipeDataJson: String?,
    isFavorite: Boolean,
    onBackClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val context = LocalContext.current

    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val steps = remember(recipeData) {
        recipeData?.optJSONArray("steps")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
    }
    val ingredients = remember(recipeData) {
        recipeData?.optJSONArray("ingredients")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
    }
    val difficulty = recipeData?.optString("difficulty") ?: "보통"
    val timeStr = recipeData?.optString("time") ?: "20분"

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val totalSteps = steps.size

    // TTS + 오디오 포커스
    var ttsEnabled by remember { mutableStateOf(false) }
    var ttsSpeaking by remember { mutableStateOf(false) }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val audioFocusRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
        } else null
    }

    fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    // Timer
    var timerSecondsLeft by remember { mutableIntStateOf(0) }
    var timerTotalSeconds by remember { mutableIntStateOf(0) }
    var timerRunning by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }

    // 현재 단계 감지 시간 (타이머 프리셋 + 음성 명령용)
    val currentStepText = if (currentStepIndex > 0) steps.getOrNull(currentStepIndex - 1) ?: "" else ""
    val stepTimeCues = remember(currentStepText) {
        extractCookingCues(currentStepText).filter { it.label.startsWith("⏱") }.map { it.label.removePrefix("⏱ ") }
    }

    // Voice command (mic button)
    val scope = rememberCoroutineScope()
    var isVoiceRecording by remember { mutableStateOf(false) }
    var isVoiceTranscribing by remember { mutableStateOf(false) }
    val voiceRecorder = remember { arrayOf<MediaRecorder?>(null) }
    val voiceVadJob = remember { arrayOf<kotlinx.coroutines.Job?>(null) }
    val voiceAudioFile = remember { File(context.cacheDir, "detail_voice.m4a") }

    LaunchedEffect(timerRunning) {
        while (isActive && timerRunning && timerSecondsLeft > 0) {
            delay(1000)
            timerSecondsLeft--
        }
        if (timerRunning && timerSecondsLeft <= 0) {
            timerRunning = false
            // 알림음
            runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 1200) }
            // TTS 안내
            val params = android.os.Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            requestAudioFocus()
            ttsRef.value?.speak("타이머 종료! 조리가 완료되었습니다.", TextToSpeech.QUEUE_ADD, params, "timer_done")
        }
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
                engine?.setPitch(1.0f)
                engine?.setSpeechRate(0.88f)
                ttsRef.value = engine
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
            ttsRef.value = null
            releaseAudioFocus()
        }
    }

    fun speakStep() {
        val tts = ttsRef.value ?: return
        val text = if (currentStepIndex == 0) {
            if (ingredients.isEmpty()) "재료 정보가 없습니다."
            else "필요한 재료입니다. ${ingredients.joinToString(". ")}"
        } else {
            val stepText = steps.getOrNull(currentStepIndex - 1) ?: ""
            buildStepSpeech(currentStepIndex, stepText)
        }
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        requestAudioFocus()
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { ttsSpeaking = true }
            override fun onDone(utteranceId: String?) { ttsSpeaking = false; releaseAudioFocus() }
            override fun onError(utteranceId: String?) { ttsSpeaking = false; releaseAudioFocus() }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "step_$currentStepIndex")
    }

    fun stopVoiceRecording(transcribe: Boolean) {
        voiceVadJob[0]?.cancel(); voiceVadJob[0] = null
        runCatching { voiceRecorder[0]?.stop(); voiceRecorder[0]?.release() }
        voiceRecorder[0] = null
        isVoiceRecording = false
        if (!transcribe) return
        isVoiceTranscribing = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    OpenAiManager().transcribeAudio(voiceAudioFile) { cont.resume(it ?: "") }
                }
            }
            isVoiceTranscribing = false
            if (text.isBlank()) return@launch

            when (parseVoiceCommand(text)) {
                is VoiceCmd.StartTimer -> {
                    val secs = (parseVoiceCommand(text) as VoiceCmd.StartTimer).seconds
                    timerSecondsLeft = secs; timerTotalSeconds = secs; timerRunning = true
                    ttsRef.value?.speak("${secs / 60}분 타이머를 시작합니다.", TextToSpeech.QUEUE_FLUSH, null, "vc")
                }
                VoiceCmd.PauseTimer -> {
                    timerRunning = false
                    ttsRef.value?.speak("타이머를 일시정지했습니다.", TextToSpeech.QUEUE_FLUSH, null, "vc")
                }
                VoiceCmd.ResumeTimer -> {
                    if (timerSecondsLeft > 0) { timerRunning = true }
                    ttsRef.value?.speak("타이머를 재개합니다.", TextToSpeech.QUEUE_FLUSH, null, "vc")
                }
                VoiceCmd.ResetTimer -> {
                    timerRunning = false; timerSecondsLeft = timerTotalSeconds
                    ttsRef.value?.speak("타이머를 초기화했습니다.", TextToSpeech.QUEUE_FLUSH, null, "vc")
                }
                VoiceCmd.NextStep -> {
                    if (currentStepIndex < totalSteps) currentStepIndex++
                }
                VoiceCmd.PrevStep -> {
                    if (currentStepIndex > 0) currentStepIndex--
                }
                VoiceCmd.StartTimerFromStep -> {
                    val secs = stepTimeCues.firstOrNull()?.let { parseTimeToSeconds(it) } ?: 0
                    if (secs > 0) {
                        timerSecondsLeft = secs; timerTotalSeconds = secs; timerRunning = true
                        ttsRef.value?.speak("${secs / 60}분 타이머를 시작합니다.", TextToSpeech.QUEUE_FLUSH, null, "vc")
                    }
                }
                VoiceCmd.Unknown -> { /* 인식 못함, 무시 */ }
            }
        }
    }

    fun startVoiceRecording() {
        runCatching {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                     else @Suppress("DEPRECATION") MediaRecorder()
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(voiceAudioFile.absolutePath)
                prepare(); start()
            }
            voiceRecorder[0] = mr
            isVoiceRecording = true
            voiceVadJob[0] = scope.launch {
                val silenceMs = 1500L
                val maxMs = 15_000L
                val start = System.currentTimeMillis()
                var silenceStart = 0L
                while (isActive) {
                    delay(100)
                    if (System.currentTimeMillis() - start > maxMs) { stopVoiceRecording(true); break }
                    val amp = voiceRecorder[0]?.maxAmplitude ?: 0
                    if (amp < 500) {
                        if (silenceStart == 0L) silenceStart = System.currentTimeMillis()
                        else if (System.currentTimeMillis() - silenceStart >= silenceMs) { stopVoiceRecording(true); break }
                    } else silenceStart = 0L
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceVadJob[0]?.cancel()
            runCatching { voiceRecorder[0]?.stop(); voiceRecorder[0]?.release() }
        }
    }

    // 단계 변경 또는 TTS 토글 시 자동 읽기
    LaunchedEffect(currentStepIndex, ttsEnabled) {
        if (ttsEnabled) speakStep()
        else { ttsRef.value?.stop(); ttsSpeaking = false }
    }

    if (showTimerSheet) {
        TimerBottomSheet(
            timerSecondsLeft = timerSecondsLeft,
            timerTotalSeconds = timerTotalSeconds,
            timerRunning = timerRunning,
            stepTimeCues = stepTimeCues,
            onStart = { seconds ->
                timerSecondsLeft = seconds
                timerTotalSeconds = seconds
                timerRunning = true
            },
            onPause = { timerRunning = false },
            onResume = { timerRunning = true },
            onReset = { timerRunning = false; timerSecondsLeft = timerTotalSeconds },
            onDismiss = { showTimerSheet = false }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8F9FA)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            RecipeTopBar(
                onBackClick = {
                    ttsRef.value?.stop()
                    onBackClick()
                },
                keyword = title,
                isFavorite = isFavorite,
                onFavoriteClick = onFavoriteClick
            )

            // 타이머 실행 중 미니 상태 바
            if (timerRunning && !showTimerSheet) {
                TimerStatusBar(
                    secondsLeft = timerSecondsLeft,
                    onClick = { showTimerSheet = true }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val progress = if (totalSteps > 0) (currentStepIndex.toFloat() / totalSteps.toFloat()) else 0f
            ProgressSection(current = currentStepIndex, total = totalSteps, progress = progress)

            Spacer(modifier = Modifier.height(32.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (currentStepIndex == 0) {
                    IngredientsSection(ingredients)
                } else {
                    CurrentStepSection(
                        stepNumber = currentStepIndex,
                        stepText = steps.getOrNull(currentStepIndex - 1) ?: ""
                    )
                }
            }

            InfoCardRow(timeStr, difficulty)

            Spacer(modifier = Modifier.height(24.dp))

            AiSuggestionSection(
                ttsEnabled = ttsEnabled,
                timerRunning = timerRunning,
                timerSecondsLeft = timerSecondsLeft,
                onToggleTts = { ttsEnabled = !ttsEnabled },
                onTimerClick = { showTimerSheet = true }
            )

            Spacer(modifier = Modifier.height(32.dp))

            BottomControlSection(
                onPrevClick = { if (currentStepIndex > 0) currentStepIndex-- },
                onNextClick = { if (currentStepIndex < totalSteps) currentStepIndex++ },
                isVoiceRecording = isVoiceRecording,
                isVoiceTranscribing = isVoiceTranscribing,
                onMicClick = {
                    if (isVoiceRecording) stopVoiceRecording(true)
                    else startVoiceRecording()
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RecipeTopBar(onBackClick: () -> Unit, keyword: String, isFavorite: Boolean, onFavoriteClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(40.dp).clickable { onBackClick() },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEEEEEE))
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(10.dp), tint = Color.Black)
        }
        Text(
            text = titleCase(keyword),
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProgressSection(current: Int, total: Int, progress: Float) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (current == 0) "재료 준비" else "단계 $current",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TomaMainOrange
            )
            Text(
                text = " / $total",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.LightGray,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(progress * 100).toInt()}% 완료",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE9ECEF), CircleShape)) {
            Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(
                brush = Brush.horizontalGradient(listOf(TomaMainOrange, Color(0xFFFFB347))),
                shape = CircleShape
            ))
        }
    }
}

@Composable
private fun IngredientsSection(ingredients: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ShoppingBasket, contentDescription = null, tint = TomaMainOrange, modifier = Modifier.size(20.dp))
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
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(TomaMainOrange, CircleShape))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(ingredient, fontSize = 16.sp, color = Color(0xFF495057), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentStepSection(stepNumber: Int, stepText: String) {
    val cues = remember(stepText) { extractCookingCues(stepText) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stepNumber.toString(),
            modifier = Modifier.align(Alignment.TopStart).offset(y = (-20).dp).alpha(0.05f),
            fontSize = 160.sp, fontWeight = FontWeight.Black, color = Color.Black
        )
        Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Text("How to Cook", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TomaMainOrange, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stepText,
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF212529),
                lineHeight = 38.sp
            )

            if (cues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cues.forEach { CookingCueChip(it) }
                }
            }
        }
    }
}

@Composable
private fun CookingCueChip(cue: CookingCue) {
    val color = Color(cue.colorHex)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = cue.label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InfoCardRow(time: String, difficulty: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
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
    ttsEnabled: Boolean = false,
    timerRunning: Boolean = false,
    timerSecondsLeft: Int = 0,
    onToggleTts: () -> Unit = {},
    onTimerClick: () -> Unit = {}
) {
    val timerLabel = when {
        timerRunning -> formatTime(timerSecondsLeft)
        else -> "타이머"
    }
    Column {
        Text("AI 제안", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SuggestionCard(
                modifier = Modifier.weight(1f),
                label = "팁 확인",
                icon = Icons.Default.Lightbulb,
                active = false,
                onClick = {}
            )
            SuggestionCard(
                modifier = Modifier.weight(1f),
                label = timerLabel,
                icon = Icons.Default.AvTimer,
                active = timerRunning,
                onClick = onTimerClick
            )
            SuggestionCard(
                modifier = Modifier.weight(1f),
                label = if (ttsEnabled) "음성 ON" else "음성안내",
                icon = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                active = ttsEnabled,
                onClick = onToggleTts
            )
        }
    }
}

@Composable
private fun SuggestionCard(
    modifier: Modifier = Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (active) TomaMainOrange.copy(alpha = 0.12f) else Color(0xFFF1F3F5),
        border = if (active) BorderStroke(1.dp, TomaMainOrange.copy(alpha = 0.3f)) else null
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (active) TomaMainOrange else Color.DarkGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) TomaMainOrange else Color.DarkGray
            )
        }
    }
}

@Composable
private fun BottomControlSection(
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    isVoiceRecording: Boolean = false,
    isVoiceTranscribing: Boolean = false,
    onMicClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val micAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "micAlpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            modifier = Modifier.size(56.dp).clickable { onPrevClick() },
            shape = CircleShape, color = Color.White, border = BorderStroke(1.dp, Color(0xFFEEEEEE))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 단계", modifier = Modifier.padding(16.dp), tint = Color.Black)
        }

        // 가운데 음성 명령 마이크 버튼
        Surface(
            modifier = Modifier.size(76.dp).clickable { onMicClick() },
            shape = CircleShape,
            color = when {
                isVoiceRecording -> TomaMainOrange
                isVoiceTranscribing -> Color(0xFFE9ECEF)
                else -> TomaMainOrange
            },
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(64.dp).border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape))
                when {
                    isVoiceTranscribing -> CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = TomaMainOrange,
                        strokeWidth = 3.dp
                    )
                    else -> Icon(
                        Icons.Default.Mic,
                        contentDescription = if (isVoiceRecording) "녹음 중지" else "음성 명령",
                        modifier = Modifier.size(32.dp).graphicsLayer {
                            alpha = if (isVoiceRecording) micAlpha else 1f
                        },
                        tint = Color.White
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.size(56.dp).clickable { onNextClick() },
            shape = CircleShape,
            color = Color.Black,
            shadowElevation = 4.dp
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "다음 단계", modifier = Modifier.padding(16.dp), tint = Color.White)
        }
    }
}

data class CookingCue(val label: String, val colorHex: Long)

@Composable
private fun TimerStatusBar(secondsLeft: Int, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "timerPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "timerAlpha"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = TomaMainOrange.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, TomaMainOrange.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AvTimer,
                contentDescription = null,
                tint = TomaMainOrange.copy(alpha = alpha),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "타이머 진행 중 · ${formatTime(secondsLeft)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TomaMainOrange,
                modifier = Modifier.weight(1f)
            )
            Text("탭하여 열기", fontSize = 11.sp, color = TomaMainOrange.copy(alpha = 0.7f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerBottomSheet(
    timerSecondsLeft: Int,
    timerTotalSeconds: Int,
    timerRunning: Boolean,
    stepTimeCues: List<String>,
    onStart: (Int) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customMinutes by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("타이머", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF212529))
            Spacer(modifier = Modifier.height(28.dp))

            // 원형 진행 표시
            val progress = if (timerTotalSeconds > 0) timerSecondsLeft.toFloat() / timerTotalSeconds else 0f
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFE9ECEF),
                    strokeWidth = 10.dp
                )
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = TomaMainOrange,
                    strokeWidth = 10.dp,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatTime(timerSecondsLeft),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF212529)
                    )
                    if (timerTotalSeconds > 0) {
                        Text(
                            text = "/ ${formatTime(timerTotalSeconds)}",
                            fontSize = 13.sp,
                            color = Color(0xFF868E96)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 단계 감지 시간 프리셋
            if (stepTimeCues.isNotEmpty()) {
                Text("이 단계 조리 시간", fontSize = 12.sp, color = Color(0xFF868E96), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stepTimeCues.forEach { timeStr ->
                        val secs = parseTimeToSeconds(timeStr)
                        Surface(
                            onClick = { onStart(secs) },
                            shape = RoundedCornerShape(50),
                            color = TomaMainOrange.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, TomaMainOrange.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = timeStr,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TomaMainOrange,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // 직접 입력
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) customMinutes = it },
                    label = { Text("분 직접 입력") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TomaMainOrange,
                        focusedLabelColor = TomaMainOrange
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val mins = customMinutes.toIntOrNull() ?: 0
                        if (mins > 0) onStart(mins * 60)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TomaMainOrange),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("시작", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 제어 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 리셋
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFDEE2E6))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF495057))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("리셋", color = Color(0xFF495057), fontWeight = FontWeight.Bold)
                }
                // 일시정지 / 재개
                Button(
                    onClick = { if (timerRunning) onPause() else onResume() },
                    enabled = timerTotalSeconds > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timerRunning) Color(0xFF495057) else TomaMainOrange
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (timerRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (timerRunning) "일시정지" else "재개",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun parseTimeToSeconds(timeStr: String): Int {
    var total = 0
    Regex("(\\d+)시간").find(timeStr)?.groupValues?.get(1)?.toIntOrNull()?.let { total += it * 3600 }
    Regex("(\\d+)분").find(timeStr)?.groupValues?.get(1)?.toIntOrNull()?.let { total += it * 60 }
    Regex("(\\d+)초").find(timeStr)?.groupValues?.get(1)?.toIntOrNull()?.let { total += it }
    return total
}

private fun extractCookingCues(text: String): List<CookingCue> {
    val cues = mutableListOf<CookingCue>()

    // 조리법 감지 (먼저 등장하는 것 하나만)
    val methodPatterns = listOf(
        Regex("튀기") to CookingCue("튀기기", 0xFFFFB300),
        Regex("볶") to CookingCue("볶기", 0xFFE64A19),
        Regex("굽|노릇") to CookingCue("굽기", 0xFFBF360C),
        Regex("끓") to CookingCue("끓이기", 0xFF1565C0),
        Regex("삶") to CookingCue("삶기", 0xFF0277BD),
        Regex("데치") to CookingCue("데치기", 0xFF00838F),
        Regex("찌|김이\\s*오") to CookingCue("찌기", 0xFF00695C),
        Regex("졸") to CookingCue("졸이기", 0xFF6D4C41),
        Regex("재우") to CookingCue("재우기", 0xFF7B1FA2),
        Regex("무치") to CookingCue("무치기", 0xFF2E7D32),
    )
    methodPatterns.firstOrNull { it.first.containsMatchIn(text) }?.second?.let { cues.add(it) }

    // 불 세기 감지
    val heatPattern = Regex("(강불|센불|강한\\s*불|중불|중간\\s*불|약불|약한\\s*불|약한불)")
    heatPattern.find(text)?.value?.replace("\\s".toRegex(), "")?.let { heat ->
        val colorHex = when (heat) {
            "강불", "센불" -> 0xFFE03131L
            "중불" -> 0xFFFF8C00L
            else -> 0xFF4DABF7L
        }
        cues.add(CookingCue("🔥 $heat", colorHex))
    }

    // 조리 시간 감지
    val timePattern = Regex("(\\d+)\\s*~?\\s*(\\d+)?\\s*(시간|분|초)")
    timePattern.findAll(text).map { m ->
        val from = m.groupValues[1]
        val to = m.groupValues[2]
        val unit = m.groupValues[3]
        if (to.isNotBlank()) "$from~$to$unit" else "$from$unit"
    }.distinct().forEach { cues.add(CookingCue("⏱ $it", 0xFF4DABF7L)) }

    return cues
}

private fun buildStepSpeech(stepNumber: Int, stepText: String): String {
    val cues = extractCookingCues(stepText)
    val base = "단계 $stepNumber. $stepText"
    if (cues.isEmpty()) return base

    val summary = buildString {
        append(" 주의하세요.")
        val method = cues.firstOrNull { !it.label.startsWith("🔥") && !it.label.startsWith("⏱") }
        val heat = cues.firstOrNull { it.label.startsWith("🔥") }
        val times = cues.filter { it.label.startsWith("⏱") }.map { it.label.removePrefix("⏱ ") }

        if (method != null) append(" 조리 방법은 ${method.label}.")
        if (heat != null) append(" 불 세기는 ${heat.label.removePrefix("🔥 ")}.")
        if (times.isNotEmpty()) append(" 조리 시간은 ${times.joinToString(", ")}입니다.")
    }
    return base + summary
}

private sealed class VoiceCmd {
    data class StartTimer(val seconds: Int) : VoiceCmd()
    object StartTimerFromStep : VoiceCmd()
    object PauseTimer : VoiceCmd()
    object ResumeTimer : VoiceCmd()
    object ResetTimer : VoiceCmd()
    object NextStep : VoiceCmd()
    object PrevStep : VoiceCmd()
    object Unknown : VoiceCmd()
}

private fun parseVoiceCommand(text: String): VoiceCmd {
    val t = text.trim()
    // 다음/이전 단계
    if (t.contains(Regex("다음|next"))) return VoiceCmd.NextStep
    if (t.contains(Regex("이전|뒤로|back|prev"))) return VoiceCmd.PrevStep
    // 타이머 제어
    if (t.contains(Regex("(타이머|timer).*(정지|멈춰|일시정지|pause|stop)"))) return VoiceCmd.PauseTimer
    if (t.contains(Regex("(타이머|timer).*(취소|리셋|reset|초기화)"))) return VoiceCmd.ResetTimer
    if (t.contains(Regex("(타이머|timer).*(재개|계속|resume|다시)"))) return VoiceCmd.ResumeTimer
    // 이 단계 시간으로 타이머
    if (t.contains(Regex("(타이머|timer).*(시작|start)")) && !t.contains(Regex("\\d"))) return VoiceCmd.StartTimerFromStep
    // 시간 파싱해서 타이머 시작
    val secs = parseTimeToSeconds(t)
    if (secs > 0) return VoiceCmd.StartTimer(secs)
    // 타이머 시작 키워드만 있으면 step 시간으로
    if (t.contains(Regex("타이머|timer"))) return VoiceCmd.StartTimerFromStep
    return VoiceCmd.Unknown
}

private fun titleCase(str: String) = str.lowercase().replaceFirstChar { it.uppercase() }

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun RecipeDetailScreenPreview() {
    RecipeDetailContent(
        keyword = "간장계란밥",
        recipeDataJson = null,
        isFavorite = true,
        onBackClick = {},
        onFavoriteClick = {}
    )
}
