package com.aivideostudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aivideostudio.ui.StudioApp
import com.aivideostudio.ui.theme.AiVideoStudioTheme
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDestination = intent?.getStringExtra(EXTRA_DESTINATION)
        splash.setKeepOnScreenCondition { false }

        setContent {
            AiVideoStudioTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(StudioColors.Background),
                ) {
                    StudioApp(startTab = startDestination)
                }
            }
        }
    }

    private companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
