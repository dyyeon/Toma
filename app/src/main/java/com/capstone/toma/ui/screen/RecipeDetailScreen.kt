package com.capstone.toma.ui.screen

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.RecipeDetailViewModel
import com.capstone.toma.viewmodel.RecipeStorageViewModel
import com.capstone.toma.viewmodel.StepTimerState
import com.capstone.toma.viewmodel.VoiceViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@Composable
fun RecipeDetailScreen(
    keyword: String = "",
    recipeDataJson: String? = null,
    sourceType: RecipeSourceType = RecipeSourceType.TEXT,
    fromStorage: Boolean = false,
    onBackClick: () -> Unit = {},
    onFinish: (String, String?) -> Unit = { _, _ -> },
    voiceViewModel: VoiceViewModel
) {
    val storageViewModel: RecipeStorageViewModel = viewModel()
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val isFavorite by storageViewModel.isRecipeSaved(title).collectAsState(initial = false)

    RecipeDetailContent(
        keyword = keyword,
        recipeDataJson = recipeDataJson,
        isFavorite = isFavorite,
        fromStorage = fromStorage,
        onBackClick = onBackClick,
        onFavoriteClick = { storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite, sourceType) },
        onFinish = onFinish,
        voiceViewModel = voiceViewModel,
        storageViewModel = storageViewModel,
        sourceType = sourceType
    )
}

@Composable
fun RecipeDetailContent(
    keyword: String,
    recipeDataJson: String?,
    isFavorite: Boolean,
    fromStorage: Boolean = false,
    onBackClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onFinish: (String, String?) -> Unit,
    voiceViewModel: VoiceViewModel,
    storageViewModel: RecipeStorageViewModel,
    sourceType: RecipeSourceType = RecipeSourceType.TEXT
) {
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        }
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

    val stepTimes = remember(recipeData) {
        recipeData?.optJSONArray("stepTimes")?.let { arr ->
            List(arr.length()) { arr.optInt(it, 0) }
        } ?: emptyList()
    }

    val difficulty = recipeData?.optString("difficulty") ?: "보통"
    val timeStr = recipeData?.optString("time")?.toIntOrNull()?.takeIf { it > 0 }?.let { m -> if (m < 60) "${m}분" else if (m % 60 == 0) "${m/60}시간" else "${m/60}시간 ${m%60}분" } ?: "-"

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val totalSteps = steps.size

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceUiState by voiceViewModel.uiState.collectAsState()

    var isTtsEnabled by remember { mutableStateOf(true) }
    var isTtsSpeaking by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsInstance by remember { mutableStateOf<TextToSpeech?>(null) }

    var lastSpokenKey by remember { mutableStateOf<String?>(null) }
    var suppressAutoTtsOnce by remember { mutableStateOf(false) }
    var pendingTtsResumeJob by remember { mutableStateOf<Job?>(null) }

    val recipeDetailViewModel: RecipeDetailViewModel = viewModel()
    val stepTimerState by recipeDetailViewModel.timerState.collectAsState()
    val stepTimerRemaining by recipeDetailViewModel.timerRemainingSeconds.collectAsState()
    val showStepTimer by recipeDetailViewModel.showTimer.collectAsState()

    val currentStepText =
        if (currentStepIndex == 0) "" else steps.getOrNull(currentStepIndex - 1).orEmpty()

    val isTimerRecommended = remember(currentStepIndex, stepTimes) {
        resolveStepTimerSeconds(
            currentStepIndex,
            if (currentStepIndex == 0) "" else steps.getOrNull(currentStepIndex - 1).orEmpty(),
            stepTimes
        ) > 0
    }

    fun hardStopTtsAndPrepareListening() {
        pendingTtsResumeJob?.cancel()
        try {
            if (ttsReady) ttsInstance?.stop()
        } catch (_: Exception) {
        }
        isTtsSpeaking = false
        voiceViewModel.isTtsSpeaking = false
    }

    fun speakText(
        key: String,
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ) {
        val tts = ttsInstance ?: return
        if (!ttsReady || text.isBlank()) return

        if (lastSpokenKey == key && isTtsSpeaking) {
            Log.d("TTS", "skip duplicate speak key=$key")
            return
        }

        pendingTtsResumeJob?.cancel()
        lastSpokenKey = key
        voiceViewModel.pauseAudioCapture()
        tts.speak(text, queueMode, null, key)
    }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null

        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.KOREAN
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        pendingTtsResumeJob?.cancel()
                        voiceViewModel.isTtsSpeaking = true
                        scope.launch { isTtsSpeaking = true }
                        Log.d("WakeWord", "🔇 TTS start id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        pendingTtsResumeJob?.cancel()
                        pendingTtsResumeJob = scope.launch {
                            voiceViewModel.isTtsSpeaking = false
                            isTtsSpeaking = false
                            Log.d("WakeWord", "✅ TTS finished id=$utteranceId — re-arming in 700ms")
                            delay(700)
                            voiceViewModel.resumeAudioCapture()
                            Log.d("WakeWord", "🔓 Re-armed after TTS playback")
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        pendingTtsResumeJob?.cancel()
                        scope.launch {
                            voiceViewModel.isTtsSpeaking = false
                            isTtsSpeaking = false
                            delay(150)
                            voiceViewModel.resumeAudioCapture()
                        }
                        Log.d("WakeWord", "❌ TTS error id=$utteranceId")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        pendingTtsResumeJob?.cancel()
                        scope.launch {
                            voiceViewModel.isTtsSpeaking = false
                            isTtsSpeaking = false
                            delay(150)
                            voiceViewModel.resumeAudioCapture()
                        }
                        Log.d("WakeWord", "❌ TTS error id=$utteranceId code=$errorCode")
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        pendingTtsResumeJob?.cancel()
                        voiceViewModel.isTtsSpeaking = false
                        scope.launch {
                            isTtsSpeaking = false
                            delay(150)
                            voiceViewModel.resumeAudioCapture()
                        }
                        Log.d("WakeWord", "🛑 TTS stopped id=$utteranceId interrupted=$interrupted")
                    }
                })

                ttsInstance = engine
                ttsReady = true
            }
        }

        onDispose {
            try {
                engine?.stop()
            } catch (_: Exception) {
            }
            try {
                engine?.shutdown()
            } catch (_: Exception) {
            }
            ttsInstance = null
            ttsReady = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    hardStopTtsAndPrepareListening()
                    voiceViewModel.onAppBackground()
                }

                Lifecycle.Event.ON_RESUME -> {
                    voiceViewModel.onAppForeground()
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        voiceViewModel.onStopTtsRequest = {
            hardStopTtsAndPrepareListening()
        }

        voiceViewModel.startWakeWord()

        onDispose {
            voiceViewModel.onStopTtsRequest = null
            hardStopTtsAndPrepareListening()
            voiceViewModel.stopWakeWord()
        }
    }

    LaunchedEffect(voiceUiState) {
        if (voiceUiState == VoiceUiState.Listening && isTtsSpeaking) {
            hardStopTtsAndPrepareListening()
            voiceViewModel.resumeAudioCapture()
        }
    }

    LaunchedEffect(currentStepIndex, ttsReady, isTtsEnabled) {
        if (!ttsReady || !isTtsEnabled) return@LaunchedEffect

        if (suppressAutoTtsOnce) {
            suppressAutoTtsOnce = false
            return@LaunchedEffect
        }

        val text = if (currentStepIndex == 0) {
            "재료 준비 단계입니다. 필수 재료는 ${ingredients.joinToString(", ")}입니다."
        } else {
            steps.getOrNull(currentStepIndex - 1).orEmpty()
        }

        speakText("auto_step_$currentStepIndex", text, TextToSpeech.QUEUE_FLUSH)
    }

    LaunchedEffect(voiceViewModel) {
        voiceViewModel.intentEvent.collect { intent ->
            Log.d("RecipeDetail", "intent=$intent step=$currentStepIndex/$totalSteps")

            when (intent) {
                TomaIntent.NEXT_STEP -> {
                    if (currentStepIndex < totalSteps) currentStepIndex++
                    else onFinish(keyword, recipeDataJson)
                }

                TomaIntent.PREVIOUS_STEP -> {
                    if (currentStepIndex > 0) currentStepIndex--
                }

                TomaIntent.REPEAT_STEP -> {
                    val text = if (currentStepIndex == 0) {
                        "재료 준비 단계입니다. ${ingredients.joinToString(", ")}"
                    } else {
                        steps.getOrNull(currentStepIndex - 1).orEmpty()
                    }
                    suppressAutoTtsOnce = true
                    speakText("repeat_step_$currentStepIndex", text, TextToSpeech.QUEUE_FLUSH)
                }

                TomaIntent.RECOMMENDED_TIMER -> {
                    if (isTimerRecommended) {
                        when (stepTimerState) {
                            StepTimerState.IDLE     -> recipeDetailViewModel.startTimer()
                            StepTimerState.RUNNING  -> recipeDetailViewModel.pauseTimer()
                            StepTimerState.PAUSED   -> recipeDetailViewModel.resumeTimer()
                            StepTimerState.FINISHED -> recipeDetailViewModel.restartTimer()
                        }
                    } else {
                        voiceViewModel.showTimerManualGuidance()
                    }
                }

                TomaIntent.CANCEL_TIMER -> {
                    if (stepTimerState == StepTimerState.RUNNING || stepTimerState == StepTimerState.PAUSED) {
                        recipeDetailViewModel.cancelTimer()
                    }
                }

                else -> Unit
            }
        }
    }

    LaunchedEffect(currentStepIndex) {
        val secs = resolveStepTimerSeconds(
            currentStepIndex,
            if (currentStepIndex == 0) "" else steps.getOrNull(currentStepIndex - 1).orEmpty(),
            stepTimes
        )
        recipeDetailViewModel.initStep(secs)
        voiceViewModel.onStepChanged(currentStepIndex)
        voiceViewModel.setRecommendedTimerForCurrentStep(if (secs > 0) (secs / 60).coerceAtLeast(1) else null)
    }

    LaunchedEffect(voiceViewModel, ttsReady) {
        if (!ttsReady) return@LaunchedEffect

        voiceViewModel.voiceAnnouncement.collect { message ->
            speakText(
                key = "announce_${System.currentTimeMillis()}",
                text = message,
                queueMode = TextToSpeech.QUEUE_ADD
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF8F9FA)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            RecipeTopBar(
                onBackClick = onBackClick,
                keyword = title,
                isFavorite = isFavorite,
                onFavoriteClick = {
                    storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite, sourceType)
                },
                sourceType = sourceType,
                fromStorage = fromStorage
            )

            AnimatedVisibility(
                visible = showStepTimer,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                StepTimerCard(
                    timerState = stepTimerState,
                    remainingSeconds = stepTimerRemaining,
                    onStart = { recipeDetailViewModel.startTimer() },
                    onPause = { recipeDetailViewModel.pauseTimer() },
                    onResume = { recipeDetailViewModel.resumeTimer() },
                    onRestart = { recipeDetailViewModel.restartTimer() },
                    onCancel = { recipeDetailViewModel.cancelTimer() },
                    onAdjust = { recipeDetailViewModel.adjustDuration(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                val progress =
                    if (totalSteps > 0) currentStepIndex.toFloat() / totalSteps.toFloat() else 0f

                ProgressSection(
                    current = currentStepIndex,
                    total = totalSteps,
                    progress = progress
                )

                Spacer(modifier = Modifier.height(32.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    if (currentStepIndex == 0) {
                        IngredientsSection(ingredients)
                    } else {
                        CurrentStepSection(
                            stepNumber = currentStepIndex,
                            stepText = steps.getOrNull(currentStepIndex - 1).orEmpty()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                InfoCardRow(timeStr, difficulty)

                Spacer(modifier = Modifier.height(24.dp))
            }

            CookingAssistControls(
                isTtsEnabled = isTtsEnabled,
                isTtsSpeaking = isTtsSpeaking,
                isTimerRecommended = isTimerRecommended,
                onTtsToggle = { isTtsEnabled = !isTtsEnabled },
                onTimerClick = {
                    if (isTimerRecommended) {
                        voiceViewModel.toggleRecommendedTimer()
                    } else {
                        voiceViewModel.showTimerManualGuidance()
                    }
                },
                onSpeechClick = {
                    if (isTtsSpeaking) {
                        hardStopTtsAndPrepareListening()
                        voiceViewModel.resumeAudioCapture()
                    } else {
                        val text = if (currentStepIndex == 0) {
                            "재료 준비 단계입니다. ${ingredients.joinToString(", ")}"
                        } else {
                            steps.getOrNull(currentStepIndex - 1).orEmpty()
                        }
                        suppressAutoTtsOnce = true
                        speakText("manual_step_$currentStepIndex", text, TextToSpeech.QUEUE_FLUSH)
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            BottomControlSection(
                onPrevClick = {
                    if (currentStepIndex > 0) currentStepIndex--
                },
                onNextClick = {
                    if (currentStepIndex < totalSteps) currentStepIndex++
                    else onFinish(keyword, recipeDataJson)
                },
                onMicClick = {
                    hardStopTtsAndPrepareListening()
                    voiceViewModel.onMicClick()
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
    onFavoriteClick: () -> Unit,
    sourceType: RecipeSourceType = RecipeSourceType.TEXT,
    fromStorage: Boolean = false
) {
    val badgeColor = when {
        fromStorage -> Color(0xFF2F9E44)
        sourceType == RecipeSourceType.YOUTUBE -> TomaMainOrange
        sourceType == RecipeSourceType.WEB -> Color(0xFF228BE6)
        sourceType == RecipeSourceType.IMAGE -> TomaMainRed
        else -> Color(0xFF4DABF7)
    }
    val badgeLabel = when {
        fromStorage -> "SAVED"
        sourceType == RecipeSourceType.YOUTUBE -> "YOUTUBE"
        sourceType == RecipeSourceType.WEB -> "WEB"
        sourceType == RecipeSourceType.IMAGE -> "IMAGE"
        else -> "TEXT"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = badgeColor.copy(alpha = 0.12f)
        ) {
            Text(
                text = badgeLabel,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = badgeColor
            )
        }
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
                        brush = Brush.horizontalGradient(
                            listOf(TomaMainOrange, Color(0xFFFFB347))
                        ),
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
            Text(
                text = "필수 재료 체크",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
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
                            text = ingredient,
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
                text = "How to Cook",
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
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = color
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun CookingAssistControls(
    isTtsEnabled: Boolean = true,
    isTtsSpeaking: Boolean = false,
    isTimerRecommended: Boolean = false,
    onTtsToggle: () -> Unit = {},
    onTimerClick: () -> Unit = {},
    onSpeechClick: () -> Unit = {}
) {
    Column {
        Spacer(modifier = Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val controls = listOf(
                "자동 안내" to if (isTtsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                "타이머" to Icons.Default.AvTimer,
                (if (isTtsSpeaking) "안내 중지" else "다시 듣기") to
                        if (isTtsSpeaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp
            )

            controls.forEachIndexed { index, (text, icon) ->
                val isStopButton = text == "안내 중지"
                val isTimerActive = text == "타이머" && isTimerRecommended

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            when (index) {
                                0 -> onTtsToggle()
                                1 -> onTimerClick()
                                2 -> onSpeechClick()
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
                    imageVector = if (isMicActive) Icons.Default.MicOff else Icons.Default.Mic,
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
                    Text(
                        text = "취소",
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun titleCase(str: String): String =
    str.lowercase().replaceFirstChar { it.uppercase() }

private fun resolveStepTimerSeconds(
    stepIndex: Int,
    stepText: String,
    stepTimes: List<Int>
): Int {
    // 1. If stepTimes[stepIndex - 1] exists and > 0, return that value (already in seconds)
    if (stepIndex > 0 && stepTimes.size >= stepIndex) {
        val preset = stepTimes[stepIndex - 1]
        if (preset > 0) return preset
    }

    // 2. Else parse stepText for "N분" → N*60, "N초" → N (use regex)
    val minutesRegex = "(\\d+)\\s*분".toRegex()
    val secondsRegex = "(\\d+)\\s*초".toRegex()
    
    val minsMatch = minutesRegex.find(stepText)
    val secsMatch = secondsRegex.find(stepText)
    
    if (minsMatch != null || secsMatch != null) {
        var totalSecs = 0
        minsMatch?.groupValues?.get(1)?.toIntOrNull()?.let { totalSecs += it * 60 }
        secsMatch?.groupValues?.get(1)?.toIntOrNull()?.let { totalSecs += it }
        if (totalSecs > 0) return totalSecs
    }

    // 3. Else if stepText contains any of: 끓, 볶, 굽, 찌, 튀, 재우, 삶, 분, 초, 시간
    //    → return 180 (default 3분)
    val keywords = listOf("끓", "볶", "굽", "찌", "튀", "재우", "삶", "분", "초", "시간")
    if (keywords.any { stepText.contains(it) }) {
        return 180
    }

    // 4. Else return 0 (no timer for this step)
    return 0
}

@Composable
fun StepTimerCard(
    timerState: StepTimerState,
    remainingSeconds: Int,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onAdjust: (Int) -> Unit
) {
    val mins = remainingSeconds / 60
    val secs = remainingSeconds % 60
    val timeText = "%02d:%02d".format(mins, secs)
    val orangeAccent = Color(0xFFFF6B2C)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            // Cancel button in top-right
            if (timerState != StepTimerState.FINISHED) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close, 
                        contentDescription = "Cancel", 
                        tint = Color.Gray, 
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // -1분 Button
                TextButton(onClick = { onAdjust(-60) }) {
                    Text("-1분", color = orangeAccent, fontWeight = FontWeight.Bold)
                }

                // Center Content
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    if (timerState == StepTimerState.FINISHED) {
                        Text("완료! 🎉", fontSize = 20.sp, fontWeight = FontWeight.Black, color = orangeAccent)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRestart,
                            colors = ButtonDefaults.buttonColors(containerColor = orangeAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("다시 시작", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(timeText, fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.Black)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val (btnText, btnAction) = when (timerState) {
                            StepTimerState.IDLE -> "시작" to onStart
                            StepTimerState.RUNNING -> "일시정지" to onPause
                            StepTimerState.PAUSED -> "재개" to onResume
                            else -> "" to {}
                        }
                        
                        Button(
                            onClick = btnAction,
                            colors = ButtonDefaults.buttonColors(containerColor = orangeAccent),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            Text(btnText, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // +1분 Button
                TextButton(onClick = { onAdjust(60) }) {
                    Text("+1분", color = orangeAccent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
