package com.capstone.toma

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.capstone.toma.navigation.TomaNavHost
import com.capstone.toma.ui.theme.TomaTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.capstone.toma.viewmodel.VoiceViewModel

/**
 * CHANGED: openWakeWord migration - Swapped Vosk with new Audio & WakeWord logic
 */
class MainActivity : ComponentActivity() {

    private val voiceViewModel: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Ensure necessary permissions
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.SCHEDULE_EXACT_ALARM
            ), 1)
        }

        setContent {
            TomaTheme {
                TomaNavHost()
            }
        }
    }

    // Audio & WakeWord lifecycle is managed within VoiceViewModel (started in init, stopped in onCleared)
}
