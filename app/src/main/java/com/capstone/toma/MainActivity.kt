package com.capstone.toma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.capstone.toma.navigation.TomaNavHost
import com.capstone.toma.ui.theme.TomaTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        setContent {
            TomaApp()
        }
    }
}

@Composable
fun TomaApp() {
    TomaTheme {
        TomaNavHost()
    }
}