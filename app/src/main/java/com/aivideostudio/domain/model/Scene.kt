package com.aivideostudio.domain.model

/**
 * A visually coherent stretch of a single source asset, produced by the local
 * scene detector. All quality fields are normalised to `0f..1f` where higher is
 * better, so that the highlight scorer can combine them without special cases.
 */
data class Scene(
    val id: Long = 0L,
    val projectId: Long,
    val assetId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val brightness: Float = 0f,
    val sharpness: Float = 0f,
    val motion: Float = 0f,
    val stability: Float = 0f,
    val saturation: Float = 0f,
    val quality: Float = 0f,
    val faceCoverage: Float = 0f,
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val dominantHue: Float = 0f,
    val framing: Framing = Framing.UNKNOWN,
    val issue: SceneIssue = SceneIssue.NONE,
    val duplicateOfSceneId: Long? = null,
    val speechRatio: Float = 0f,
    val audioLoudness: Float = 0f,
    val label: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** Scenes the pipeline should quietly drop unless the user overrides it. */
    val isDiscardable: Boolean get() = issue != SceneIssue.NONE

    fun overlaps(start: Long, end: Long): Boolean = startMs < end && endMs > start

    fun overlapMs(start: Long, end: Long): Long =
        (minOf(endMs, end) - maxOf(startMs, start)).coerceAtLeast(0L)

    companion object {
        const val FULL_FRAME = 0
    }
}

enum class Framing(val displayName: String) {
    UNKNOWN("Framing"),
    WIDE("Wide shot"),
    MEDIUM("Medium shot"),
    CLOSE_UP("Close-up"),
    GROUND("Pointed at the ground"),
    SKY("Pointed at the sky"),
    ;

    companion object {
        fun fromName(value: String?): Framing =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Problems the analyser can detect on its own. Each one carries the user facing
 * wording so the review screen never has to build sentences out of enum names.
 */
enum class SceneIssue(
    val displayName: String,
    val recommendation: String,
    val severity: Float,
) {
    NONE("", "", 0f),
    BLURRY("Blurry", "This shot is out of focus", 0.7f),
    TOO_DARK("Too dark", "Almost nothing is visible here", 0.8f),
    OVEREXPOSED("Overexposed", "The highlights are blown out", 0.6f),
    STATIC("Static", "Nothing happens in this shot", 0.5f),
    SHAKY("Shaky", "Camera movement is uncomfortable", 0.55f),
    LENS_COVERED("Lens covered", "The lens was probably blocked", 0.95f),
    GROUND_SHOT("Wrong direction", "The camera is pointed at the ground", 0.75f),
    DUPLICATE("Duplicate", "This looks like another shot in the same clip", 0.45f),
    EMPTY("Empty scene", "No subject was found in this shot", 0.4f),
    SILENCE("Silent", "This section has no usable audio", 0.35f),
    ;

    val isHardFail: Boolean get() = severity >= 0.7f
    val isUsable: Boolean get() = this == NONE
}

/** Why a particular moment was picked, in plain language. */
data class HighlightReason(val badge: String, val text: String)
