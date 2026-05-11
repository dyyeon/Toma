package com.capstone.toma.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.toma.viewmodel.SpeakerEnrollmentViewModel
import kotlinx.coroutines.delay

private val TomaMainOrange = Color(0xFFEE8C2B)
private val TomaBackground = Color(0xFFF8F9FA)
private val TomaPrimaryText = Color(0xFF212529)
private val TomaSecondaryText = Color(0xFF868E96)
private val TomaCardBorder = Color(0xFFF1F3F5)

/**
 * Speaker enrollment screen — collects 3 samples of the user reading a fixed
 * sentence so the on-device wake-word model can be personalized. Skipping is
 * always available; the app must work without enrollment.
 */
@Composable
fun SpeakerEnrollmentScreen(
    onFinish: () -> Unit,
    onBackClick: () -> Unit = {}
) {
    val vm: SpeakerEnrollmentViewModel = viewModel()
    val step by vm.step.collectAsState()
    val sampleIndex by vm.sampleIndex.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val context = LocalContext.current

    // Toast surface for transient errors (e.g. mic permission denied).
    LaunchedEffect(errorMessage) {
        val msg = errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        vm.consumeError()
    }

    // Mic permission launcher — only triggered from the Recording screen.
    var pendingStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) {
            vm.startRecordingSample()
        } else if (!granted) {
            Toast.makeText(context, "마이크 권한이 필요해요.", Toast.LENGTH_LONG).show()
        }
        pendingStart = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaBackground)
            .padding(24.dp)
    ) {
        when (step) {
            SpeakerEnrollmentViewModel.Step.Intro -> IntroContent(
                onStart = { vm.goToRecording() },
                onSkip = {
                    vm.skipEnrollment()
                    onFinish()
                },
                onBack = onBackClick
            )

            SpeakerEnrollmentViewModel.Step.Recording -> RecordingContent(
                sampleIndex = sampleIndex,
                isRecording = isRecording,
                onMicTap = {
                    if (isRecording) {
                        vm.stopRecordingSample()
                    } else {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            vm.startRecordingSample()
                        } else {
                            pendingStart = true
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onOpenSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                },
                onBack = onBackClick
            )

            SpeakerEnrollmentViewModel.Step.Complete -> {
                LaunchedEffect(Unit) {
                    delay(2000)
                    onFinish()
                }
                CompleteContent()
            }
        }
    }
}

@Composable
private fun IntroContent(
    onStart: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로", tint = TomaPrimaryText)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "내 목소리 등록하기",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TomaPrimaryText,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "헤이 토마를 더 정확하게 인식할 수 있어요",
            fontSize = 16.sp,
            color = TomaSecondaryText,
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = TomaMainOrange.copy(alpha = 0.1f),
                modifier = Modifier.size(180.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = TomaMainOrange,
                        modifier = Modifier.size(80.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = TomaMainOrange),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("등록하기", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onSkip,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("나중에 할게요", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TomaSecondaryText)
        }
    }
}

@Composable
private fun RecordingContent(
    sampleIndex: Int,
    isRecording: Boolean,
    onMicTap: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로", tint = TomaPrimaryText)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "아래 문장을 따라 읽어주세요",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TomaPrimaryText
        )

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, TomaCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "\"${SpeakerEnrollmentViewModel.TARGET_SENTENCE}\"",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = TomaPrimaryText,
                textAlign = TextAlign.Center,
                lineHeight = 30.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Progress counter: shows currently-collecting sample.
        val displayedIndex = (sampleIndex + 1).coerceAtMost(SpeakerEnrollmentViewModel.TOTAL_SAMPLES)
        Text(
            text = "$displayedIndex / ${SpeakerEnrollmentViewModel.TOTAL_SAMPLES}",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TomaMainOrange,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = onMicTap,
                shape = CircleShape,
                color = TomaMainOrange,
                shadowElevation = 4.dp,
                modifier = Modifier.size(140.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "정지" else "녹음",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isRecording) "탭하여 정지" else "탭하여 녹음 시작",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = TomaSecondaryText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = "권한 설정 열기",
                fontSize = 13.sp,
                color = TomaSecondaryText
            )
        }
    }
}

@Composable
private fun CompleteContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = TomaMainOrange,
            modifier = Modifier.size(96.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "등록 완료! 이제 헤이 토마를 불러보세요 😊",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TomaPrimaryText,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}

/**
 * Kept for compatibility with any caller still importing this symbol.
 * Not used by the new flow.
 */
@Composable
fun DotProgressBar(count: Int, total: Int, modifier: Modifier = Modifier) {
    // intentionally empty — legacy stub
}
