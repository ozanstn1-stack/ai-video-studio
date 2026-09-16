package com.aivideostudio.domain.model

/**
 * One rendered Short. The clip owns the AI-authored metadata (title,
 * description) and delegates the editable timeline to [TimelineClip] rows.
 */
data class GeneratedClip(
    val id: Long = 0L,
    val projectId: Long,
    val index: Int,
    val title: String,
    val titles: List<String> = emptyList(),
    val description: String = "",
    val hashtags: List<String> = emptyList(),
    val hookText: String? = null,
    val hookHighlightId: Long? = null,
    val label: HighlightLabel = HighlightLabel.MOMENT,
    val sourceAssetId: Long? = null,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = 0L,
    val durationMs: Long = 0L,
    val aspectRatio: AspectRatio = AspectRatio.VERTICAL_9_16,
    val cropMode: CropMode = CropMode.SMART,
    val storyRole: StoryRole = StoryRole.CLIMAX,
    val storySummary: String? = null,
    val thumbnailPath: String? = null,
    val outputPath: String? = null,
    val status: ClipStatus = ClipStatus.DRAFT,
    val createdAt: Long = 0L,
    val lastExportId: Long? = null,
) {
    val durationLabel: String
        get() = "${(durationMs / 1000).coerceAtLeast(0)} sec"

    val isReady: Boolean get() = status == ClipStatus.READY || status == ClipStatus.EXPORTED
}

enum class ClipStatus { DRAFT, RENDERING, READY, EXPORTED, FAILED }

/**
 * The editable representation of a clip. A Short is a sequence of [TimelineClip]
 * rows pointing back at untouched source ranges — the non-destructive guarantee.
 */
data class TimelineClip(
    val id: Long = 0L,
    val clipId: Long,
    val projectId: Long,
    val assetId: Long,
    val orderIndex: Int,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1f,
    val cropMode: CropMode = CropMode.SMART,
    val cropCenterX: Float = 0.5f,
    val cropCenterY: Float = 0.5f,
    val cropScale: Float = 1f,
    val storyRole: StoryRole = StoryRole.CLIMAX,
    val highlightId: Long? = null,
    val origin: ClipOrigin = ClipOrigin.AI,
) {
    val durationMs: Long get() = ((sourceEndMs - sourceStartMs).coerceAtLeast(0L) / playbackSpeed).toLong()
}

enum class ClipOrigin { AI, USER_TRIM, USER_SPLIT, USER_ADDED }

data class Caption(
    val id: Long = 0L,
    val projectId: Long,
    val clipId: Long,
    val timelineClipId: Long? = null,
    val index: Int,
    /** Times are relative to the *rendered* Short timeline, not the source file. */
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: SubtitleStyle = SubtitleStyle.CREATOR,
    val positionY: Float = 0.78f,
    val isEmphasised: Boolean = false,
    val emphasisWords: List<String> = emptyList(),
    val isEdited: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class TextOverlay(
    val id: Long = 0L,
    val projectId: Long,
    val clipId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.2f,
    val fontSizeSp: Float = 32f,
    val isTitle: Boolean = false,
)

data class AudioBed(
    val id: Long = 0L,
    val projectId: Long,
    val clipId: Long,
    val uri: String? = null,
    val title: String? = null,
    val volume: Float = 0.35f,
    val originalAudioMuted: Boolean = false,
    val originalVolume: Float = 1f,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
)

/** Everything the editor needs in one snapshot. */
data class ClipEditState(
    val clip: GeneratedClip,
    val timeline: List<TimelineClip> = emptyList(),
    val captions: List<Caption> = emptyList(),
    val overlays: List<TextOverlay> = emptyList(),
    val audio: AudioBed? = null,
    val hookText: String? = null,
) {
    val totalDurationMs: Long get() = timeline.sumOf { it.durationMs }
}

data class AiSuggestion(
    val id: String,
    val category: SuggestionCategory,
    val title: String,
    val detail: String,
    val isApplied: Boolean = false,
)

enum class SuggestionCategory(val displayName: String) {
    PACING("Pacing"),
    AUDIO("Audio"),
    CAPTIONS("Captions"),
    CROP("Framing"),
    HOOK("Hook"),
    OUTRO("Ending"),
    ;
}
