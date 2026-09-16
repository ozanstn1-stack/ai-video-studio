package com.aivideostudio.ai.model

import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.TranscriptSegment
import com.aivideostudio.domain.model.VideoAsset

/**
 * Everything a provider is allowed to know about a piece of footage. Providers
 * receive this instead of database entities so they can never accidentally
 * write to the project.
 */
data class VideoContext(
    val projectId: Long,
    val projectName: String,
    val mode: CreationMode,
    val assets: List<VideoAsset>,
    val scenes: List<Scene>,
    val transcript: String,
    val transcriptSegments: List<TranscriptSegment>,
    val highlightSummaries: List<HighlightSummary> = emptyList(),
    val targetShortCount: Int = 3,
    val targetShortDurationSec: Int = 30,
    val language: String? = null,
    /**
     * A handful of small JPEGs written to the cache directory. Providers that
     * support vision read these; nothing else does, and the user is asked for
     * consent before they are ever uploaded.
     */
    val frames: List<FrameRef> = emptyList(),
) {
    val totalDurationMs: Long get() = assets.sumOf { it.durationMs }
    val assetNames: List<String> get() = assets.map { it.displayName }
    val isDjiFootage: Boolean get() = assets.any { it.isDjiFootage }
}

/** A frame that has already been written to disk, ready to be attached. */
data class FrameRef(
    val path: String,
    val timeMs: Long,
    val assetName: String,
)

data class HighlightSummary(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val transcript: String?,
    val overallScore: Float,
)

data class SceneSummary(
    val assetName: String,
    val startMs: Long,
    val endMs: Long,
    val brightness: Float,
    val motion: Float,
    val sharpness: Float,
    val framing: String,
    val issue: String,
)

/** Semantic layer output: what the footage is about. */
data class SceneAnalysisResult(
    val subjects: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val mood: String? = null,
    val tags: List<String> = emptyList(),
    val sceneLabels: Map<Long, String> = emptyMap(),
    val storySummary: String? = null,
    val provider: String = "local",
    val confidence: Float = 0f,
) {
    val isEmpty: Boolean
        get() = subjects.isEmpty() && tags.isEmpty() && storySummary.isNullOrBlank()

    companion object {
        val EMPTY = SceneAnalysisResult()
    }
}

data class TranscriptionRequest(
    val projectId: Long,
    val assets: List<TranscriptionSource>,
    val languageHint: String? = null,
    val onProgress: (Float) -> Unit = {},
) {
    val totalDurationMs: Long get() = assets.sumOf { it.durationMs }
}

data class TranscriptionSource(
    val assetId: Long,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    /**
     * A compressed audio-only file produced by [com.aivideostudio.media.audio.EncodedAudioExtractor].
     * Cloud providers upload this instead of the video, which keeps the payload
     * to a few megabytes even for long recordings.
     */
    val audioPath: String? = null,
    /**
     * Ranges where the local audio analysis found speech energy. Used to place
     * recognised sentences on the timeline when the speech engine cannot report
     * timestamps of its own.
     */
    val speechRanges: List<LongRange> = emptyList(),
)

data class TranscriptionResult(
    val segments: List<TranscriptSegment>,
    val language: String?,
    val provider: String,
    val averageConfidence: Float,
    val isComplete: Boolean,
    val note: String? = null,
) {
    val fullText: String get() = segments.joinToString(" ") { it.text }.trim()
    val hasWords: Boolean get() = segments.any { it.text.isNotBlank() }

    companion object {
        fun empty(provider: String, note: String?) = TranscriptionResult(
            segments = emptyList(),
            language = null,
            provider = provider,
            averageConfidence = 0f,
            isComplete = true,
            note = note,
        )
    }
}

data class TitleSuggestion(val text: String, val style: String)

data class DescriptionResult(
    val description: String,
    val hashtags: List<String>,
    val keywords: List<String> = emptyList(),
)

/** Which capabilities a provider can actually serve right now. */
data class ProviderCapabilities(
    val available: Set<AiCapability>,
    val requiresNetwork: Boolean,
    val reason: String? = null,
)

/** Outcome of a provider call, so callers can fall back without exceptions. */
sealed interface AiResult<out T> {
    data class Success<T>(val value: T, val provider: String = "") : AiResult<T>
    data class Failure(val message: String, val technical: String? = null) : AiResult<Nothing>
}

inline fun <T> AiResult<T>.onSuccess(block: (T) -> Unit): AiResult<T> {
    if (this is AiResult.Success) block(value)
    return this
}

fun <T> AiResult<T>.valueOrNull(): T? = (this as? AiResult.Success)?.value

data class ProjectBlueprint(
    val project: Project,
    val assets: List<VideoAsset>,
)
