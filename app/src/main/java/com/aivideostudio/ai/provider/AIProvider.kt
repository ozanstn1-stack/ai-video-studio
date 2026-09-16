package com.aivideostudio.ai.provider

import com.aivideostudio.ai.model.AiResult
import com.aivideostudio.ai.model.DescriptionResult
import com.aivideostudio.ai.model.ProviderCapabilities
import com.aivideostudio.ai.model.SceneAnalysisResult
import com.aivideostudio.ai.model.TitleSuggestion
import com.aivideostudio.ai.model.TranscriptionRequest
import com.aivideostudio.ai.model.TranscriptionResult
import com.aivideostudio.ai.model.VideoContext
import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.Scene

/**
 * The contract every AI backend implements.
 *
 * The app never depends on a concrete provider: the pipeline asks the registry
 * for a provider per capability, so a device with no network and no configured
 * API key still gets a complete, if more conservative, analysis.
 *
 * Implementations must be safe to cancel and must never throw for expected
 * failures — return [AiResult.Failure] so callers can fall back cleanly.
 */
interface AIProvider {

    val id: String

    val displayName: String

    /** Whether using this provider sends data off the device. */
    val requiresNetwork: Boolean

    /** What this provider can do right now, given config and connectivity. */
    fun capabilities(): ProviderCapabilities

    suspend fun isAvailable(): Boolean = capabilities().available.isNotEmpty()

    /**
     * Word-level (or at least phrase-level) speech transcription with timestamps
     * relative to each source asset.
     */
    suspend fun transcribe(request: TranscriptionRequest): AiResult<TranscriptionResult>

    /** Semantic understanding: subjects, places, activities, mood and tags. */
    suspend fun analyzeScenes(
        scenes: List<Scene>,
        context: VideoContext,
    ): AiResult<SceneAnalysisResult>

    /**
     * Re-ranks locally detected highlights using semantic reasoning. Providers
     * that cannot do this return the input unchanged rather than failing.
     */
    suspend fun detectHighlights(
        scenes: List<Scene>,
        context: VideoContext,
        localCandidates: List<Highlight>,
    ): AiResult<List<Highlight>>

    suspend fun generateTitles(
        context: VideoContext,
        highlight: Highlight?,
    ): AiResult<List<TitleSuggestion>>

    suspend fun generateDescription(
        context: VideoContext,
        analysis: SceneAnalysisResult,
        highlight: Highlight?,
    ): AiResult<DescriptionResult>

    companion object {
        val ALL_CAPABILITIES = AiCapability.entries.toSet()
    }
}
