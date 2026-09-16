package com.aivideostudio.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Dark-first palette. The app is designed to be used in a dim room while
 * reviewing footage, so the surfaces stay near-black and colour is reserved for
 * meaning: violet for AI, cyan for time/timeline, pink for highlights.
 */
object StudioColors {
    val Background = Color(0xFF08080C)
    val Surface = Color(0xFF101017)
    val SurfaceElevated = Color(0xFF16161F)
    val SurfaceVariant = Color(0xFF1D1D28)
    val SurfaceHighest = Color(0xFF24242F)

    val Primary = Color(0xFF8B5CF6)
    val PrimaryBright = Color(0xFFA78BFA)
    val PrimaryDim = Color(0xFF5B3FC4)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFF2A1F52)

    val Secondary = Color(0xFF22D3EE)
    val OnSecondary = Color(0xFF04222A)
    val SecondaryContainer = Color(0xFF0E3B45)

    val Tertiary = Color(0xFFF472B6)
    val OnTertiary = Color(0xFF3A0C22)
    val TertiaryContainer = Color(0xFF44172E)

    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFEF4444)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFF3F1315)

    val Outline = Color(0xFF32323F)
    val OutlineVariant = Color(0xFF23232E)

    val TextPrimary = Color(0xFFF4F4F7)
    val TextSecondary = Color(0xFFA7A7B8)
    val TextTertiary = Color(0xFF6E6E82)

    val Scrim = Color(0xCC000000)

    /** Editing timeline colours. */
    val TimelineClipAi = Color(0xFF8B5CF6)
    val TimelineClipUser = Color(0xFF22D3EE)
    val TimelinePlayhead = Color(0xFFFF4D6D)
    val TimelineCut = Color(0xFFFBBF24)
    val TimelineDiscarded = Color(0xFF3A3A46)
    val WaveformSpeech = Color(0xFF34D399)
    val WaveformSilence = Color(0xFF4B5563)
    val WaveformLoud = Color(0xFFFBBF24)
}
