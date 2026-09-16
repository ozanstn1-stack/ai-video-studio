package com.aivideostudio.domain.model

/** What the user is trying to produce. Drives the highlight-selection strategy. */
enum class CreationMode(val displayName: String, val description: String) {
    AUTO("Auto", "Let the AI decide what matters most"),
    TRAVEL("Travel", "Places, views and movement"),
    ACTION("Action", "Fast, high energy moments"),
    VLOG("Vlog", "Talking head with story"),
    FOOD("Food", "Close-ups, texture and detail"),
    CINEMATIC("Cinematic", "Slow, wide, beautiful shots"),
    SPORTS("Sports", "Speed, impact and highlights"),
    ;

    companion object {
        fun fromName(value: String?): CreationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class ProjectStatus {
    DRAFT,
    IMPORTING,
    READY,
    ANALYZING,
    REVIEW,
    GENERATED,
    EXPORTED,
    FAILED,
    ;

    val isBusy: Boolean get() = this == IMPORTING || this == ANALYZING
    val isFinished: Boolean get() = this == GENERATED || this == EXPORTED
}

enum class AspectRatio(val widthRatio: Int, val heightRatio: Int, val label: String) {
    VERTICAL_9_16(9, 16, "9:16"),
    LANDSCAPE_16_9(16, 9, "16:9"),
    SQUARE_1_1(1, 1, "1:1"),
    PORTRAIT_4_5(4, 5, "4:5"),
    ;

    val aspectValue: Float get() = widthRatio.toFloat() / heightRatio.toFloat()
    val isPortrait: Boolean get() = heightRatio > widthRatio

    /** Target render size in pixels for the export presets we support. */
    fun renderSize(): Pair<Int, Int> = when (this) {
        VERTICAL_9_16 -> 1080 to 1920
        LANDSCAPE_16_9 -> 1920 to 1080
        SQUARE_1_1 -> 1080 to 1080
        PORTRAIT_4_5 -> 1080 to 1350
    }

    companion object {
        fun fromName(value: String?): AspectRatio =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: VERTICAL_9_16
    }
}

enum class VideoQuality(val label: String, val scale: Float, val bitrateMultiplier: Float) {
    GOOD("Good", 0.66f, 0.6f),
    HIGH("High", 1.0f, 1.0f),
    MAXIMUM("Maximum", 1.0f, 1.6f),
    ;

    companion object {
        fun fromName(value: String?): VideoQuality =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: HIGH
    }
}

enum class FrameRateOption(val label: String, val fps: Int?) {
    SOURCE("Source FPS", null),
    FPS_24("24 FPS", 24),
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60),
    ;

    companion object {
        fun fromName(value: String?): FrameRateOption =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SOURCE
    }
}

enum class SubtitleStyle(val displayName: String) {
    CLEAN("Clean"),
    BOLD("Bold"),
    MINIMAL("Minimal"),
    CREATOR("Creator"),
    DYNAMIC("Dynamic"),
    ;

    companion object {
        fun fromName(value: String?): SubtitleStyle =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CREATOR
    }
}

enum class CropMode {
    /** No crop, output aspect handles the framing. */
    FIT,

    /** Even crop from the centre — predictable but can cut subjects. */
    CENTER,

    /** Subject aware crop driven by face detection + saliency score. */
    SMART,
    ;

    companion object {
        fun fromName(value: String?): CropMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SMART
    }
}
