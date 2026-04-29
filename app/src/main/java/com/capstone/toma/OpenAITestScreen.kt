package com.capstone.toma

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.toma.viewmodel.VoiceViewModel
import com.capstone.toma.viewmodel.VoiceState

/**
 * CHANGED: openWakeWord migration - Test screen updated for new architecture
 */
@Composable
fun OpenAITestScreen(
    viewModel: VoiceViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Status mapping for test screen
    val statusText = when (uiState) {
        VoiceUiState.Idle -> "READY - 'Hey Toma'라고 불러보세요"
        VoiceUiState.Listening -> "🎤 LISTENING... 명령을 말씀하세요"
        VoiceUiState.Processing -> "⏳ PROCESSING... AI가 분석 중입니다"
        VoiceUiState.Speaking -> "🔊 SPEAKING... 응답 중"
        is VoiceUiState.Result -> (uiState as VoiceUiState.Result).text
        is VoiceUiState.Error -> "❌ ERROR: ${(uiState as VoiceUiState.Error).message}"
        else -> "준비 중..."
    }

    // Microphone permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordGranted) {
            // Handle permission denied
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🍅 토마 AI 음성 제어 테스트 (Realtime)", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(30.dp))

        // State Display
        Surface(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Manual control button
        Button(
            onClick = { viewModel.onMicClick() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState == VoiceUiState.Listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.size(width = 220.dp, height = 55.dp)
        ) {
            Text(if (uiState == VoiceUiState.Listening) "중지" else "수동 시작")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Tip: '헤이 토마'라고 부르면 자동으로 감지됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
