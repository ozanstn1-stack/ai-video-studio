package com.aivideostudio.ui.screens.about

import androidx.compose.runtime.Composable

@Composable
fun AboutScreen(onBack: () -> Unit) {
    com.aivideostudio.ui.screens.settings.AboutContent(onBack = onBack)
}
