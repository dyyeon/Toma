package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.capstone.toma.ui.theme.*
import com.capstone.toma.viewmodel.VoiceViewModel

/**
 * SpeakerEnrollmentScreen — DISABLED but kept for future use.
 */
@Composable
fun SpeakerEnrollmentScreen(
    voiceViewModel: VoiceViewModel,
    onEnrollmentComplete: () -> Unit,
    onBackClick: () -> Unit = {}
) {
    // This screen is currently disabled in the navigation flow.
    // If accessed, it shows a simple placeholder.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TomaBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("화자 등록 기능이 일시적으로 비활성화되었습니다.", color = TomaPrimaryText)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onEnrollmentComplete) {
            Text("건너뛰기")
        }
    }
}

@Composable
fun DotProgressBar(count: Int, total: Int, modifier: Modifier = Modifier) {
    // Placeholder
}
