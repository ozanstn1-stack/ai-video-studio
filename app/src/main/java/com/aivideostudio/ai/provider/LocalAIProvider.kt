package com.aivideostudio.ai.provider

import com.aivideostudio.ai.detection.HighlightScorer
import com.aivideostudio.ai.model.AiResult
import com.aivideostudio.ai.model.DescriptionResult
import com.aivideostudio.ai.model.ProviderCapabilities
import com.aivideostudio.ai.model.SceneAnalysisResult
import com.aivideostudio.ai.model.TitleSuggestion
import com.aivideostudio.ai.model.TranscriptionRequest
import com.aivideostudio.ai.model.TranscriptionResult
import com.aivideostudio.ai.model.VideoContext
import com.aivideostudio.ai.text.KeywordExtractor
import com.aivideostudio.ai.text.LocalDescriptionGenerator
import com.aivideostudio.ai.text.LocalTitleGenerator
import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.Framing
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.SceneIssue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The always-available provider.
 *
 * It performs every AI-facing task with on-device computation: energy based
 * speech mapping, feature based scene understanding and template driven titles.
 * The results are genuinely derived from the footage, and the provider is
 * explicit about its limits through [SceneAnalysisResult.confidence] and the
 * transcription note rather than inventing content.
 */
@Singleton
class LocalAIProvider @Inject constructor(
    private val transcriptionEngine: LocalTranscriptionEngine,
    private val scorer: HighlightScorer,
    private val titleGenerator: LocalTitleGenerator,
    private val descriptionGenerator: LocalDescriptionGenerator,
    private val keywordExtractor: KeywordExtractor,
) : AIProvider {

    override val id: String = "local"

    override val displayName: String = "On-device"

    override val requiresNetwork: Boolean = false

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        available = AiCapability.entries.toSet(),
        requiresNetwork = false,
    )

    override suspend fun transcribe(request: TranscriptionRequest): AiResult<TranscriptionResult> =
        AiResult.Success(transcriptionEngine.transcribe(request), displayName)

    /**
     * Scene understanding without a language model: subjects come from the words
     * that recur in the transcript, "what is happening" from framing and motion,
     * and mood from the measured exposure and energy.
     */
    override suspend fun analyzeScenes(
        scenes: List<Scene>,
        context: VideoContext,
    ): AiResult<SceneAnalysisResult> {
        if (scenes.isEmpty() && context.transcript.isBlank()) {
            return AiResult.Success(SceneAnalysisResult.EMPTY, displayName)
        }

        val keywords = keywordExtractor.extract(context.transcript, limit = 8)
        val framingMix = scenes.groupingBy { it.framing }.eachCount()
        val activities = buildList {
            if ((framingMix[Framing.WIDE] ?: 0) > scenes.size / 4) add("wide landscape shots")
            if ((framingMix[Framing.CLOSE_UP] ?: 0) > scenes.size / 5) add("close-up details")
            if (scenes.any { it.motion > 0.28f }) add("movement and action")
            if (scenes.any { it.faceCoverage > 0.02f }) add("people on camera")
        }

        val averageBrightness = scenes.map { it.brightness }.average().toFloat()
        val averageMotion = scenes.map { it.motion }.average().toFloat()
        val mood = when {
            averageBrightness > 0.62f && averageMotion > 0.2f -> "bright and lively"
            averageBrightness > 0.62f -> "bright and calm"
            averageBrightness < 0.28f -> "dark and moody"
            averageMotion > 0.25f -> "energetic"
            else -> "calm"
        }

        val problemCount = scenes.count { it.issue != SceneIssue.NONE }
        val summary = buildString {
            append(scenes.size).append(" shots analyzed")
            if (problemCount > 0) append(", ").append(problemCount).append(" flagged for review")
            if (keywords.isNotEmpty()) append(". Main topics: ").append(keywords.take(3).joinToString(", "))
        }

        return AiResult.Success(
            SceneAnalysisResult(
                subjects = keywords.take(4),
                locations = emptyList(),
                activities = activities,
                mood = mood,
                tags = keywords,
                sceneLabels = framingMix.entries.associate { it.key.ordinal.toLong() to it.key.displayName },
                storySummary = summary,
                provider = displayName,
                confidence = if (context.transcript.isBlank()) 0.35f else 0.55f,
            ),
            displayName,
        )
    }

    /** Local candidates are already produced by [HighlightScorer]; nothing to add. */
    override suspend fun detectHighlights(
        scenes: List<Scene>,
        context: VideoContext,
        localCandidates: List<Highlight>,
    ): AiResult<List<Highlight>> = AiResult.Success(localCandidates, displayName)

    override suspend fun generateTitles(
        context: VideoContext,
        highlight: Highlight?,
    ): AiResult<List<TitleSuggestion>> =
        AiResult.Success(titleGenerator.generate(context, highlight), displayName)

    override suspend fun generateDescription(
        context: VideoContext,
        analysis: SceneAnalysisResult,
        highlight: Highlight?,
    ): AiResult<DescriptionResult> =
        AiResult.Success(descriptionGenerator.generate(context, analysis, highlight), displayName)
}
