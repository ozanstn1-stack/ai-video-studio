package com.aivideostudio.processing

import com.aivideostudio.ai.crop.SmartCropper
import com.aivideostudio.ai.detection.HighlightScorer
import com.aivideostudio.ai.generation.StoryBuilder
import com.aivideostudio.ai.model.AiResult
import com.aivideostudio.ai.model.DescriptionResult
import com.aivideostudio.ai.model.SceneAnalysisResult
import com.aivideostudio.ai.model.VideoContext
import com.aivideostudio.ai.provider.AiProviderRegistry
import com.aivideostudio.ai.subtitle.SubtitleBuilder
import com.aivideostudio.ai.text.LocalDescriptionGenerator
import com.aivideostudio.ai.text.LocalTitleGenerator
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.TranscriptSegment
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.repository.AnalysisRepository
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.domain.repository.UserPreferences
import com.aivideostudio.media.model.FaceSample
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the analysis into something the user can actually watch.
 *
 * Responsibilities, in order:
 *  1. score the locally measured scenes into candidate moments;
 *  2. assemble those moments into short, coherent sequences;
 *  3. work out the subject-aware crop for every segment;
 *  4. map the transcript onto the rendered timeline as captions;
 *  5. ask the configured provider for titles and a description.
 *
 * Steps 1, 3 and 4 are pure computation over persisted data, so regenerating a
 * project's Shorts never requires re-analysing the footage.
 */
@Singleton
class ShortGenerator @Inject constructor(
    private val scorer: HighlightScorer,
    private val storyBuilder: StoryBuilder,
    private val subtitleBuilder: SubtitleBuilder,
    private val smartCropper: SmartCropper,
    private val providerRegistry: AiProviderRegistry,
    private val analysisRepository: AnalysisRepository,
    private val clipRepository: ClipRepository,
    private val mediaRepository: MediaRepository,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val localTitleGenerator: LocalTitleGenerator,
    private val localDescriptionGenerator: LocalDescriptionGenerator,
    private val dispatchers: DispatcherProvider,
) {

    data class Result(
        val succeeded: Boolean,
        val clipsCreated: Int = 0,
        val message: String? = null,
        val technical: String? = null,
    )

    /** Step 1: local scoring. Exposed so the highlight stage can persist it first. */
    fun scoreHighlights(
        project: Project,
        scenes: List<Scene>,
        transcript: List<TranscriptSegment>,
    ): List<Highlight> = scorer.score(
        scenes = scenes,
        transcript = transcript,
        config = HighlightScorer.Config(
            mode = project.mode,
            targetDurationMs = project.targetShortDurationSec * 1000L,
            maxHighlights = maxOf(project.targetShortCount * 6, 24),
        ),
    )

    suspend fun generate(
        projectId: Long,
        onProgress: (Float) -> Unit,
    ): Result = withContext(dispatchers.default) {
        val project = projectRepository.getProject(projectId)
            ?: return@withContext Result(false, message = "This project could not be loaded")
        val assets = mediaRepository.getAssets(projectId)
        val scenes = analysisRepository.getScenesForProject(projectId)
        val segments = analysisRepository.getSegments(projectId)
        val preferences = settingsRepository.snapshot()
        val semantic = analysisRepository.getSemanticResult(projectId)

        val highlights = analysisRepository.getHighlights(projectId)
            .filter { it.isSelected && !it.isRejected }
        if (highlights.isEmpty()) {
            return@withContext Result(false, message = "No moments were selected for this project")
        }

        val plans = storyBuilder.build(
            highlights = highlights.sortedByDescending { it.overallScore },
            shortCount = project.targetShortCount,
            targetDurationMs = project.targetShortDurationSec * 1000L,
        )
        if (plans.isEmpty()) {
            return@withContext Result(
                false,
                message = "There was not enough usable footage to build a Short",
            )
        }
        onProgress(0.35f)

        // Regenerating replaces the previous set; the original media is untouched.
        clipRepository.deleteClipsForProject(projectId)

        val assetById = assets.associateBy { it.id }
        val faceSamples = buildFaceSamples(scenes)
        val style = preferences.defaultSubtitleStyle
        val context = buildContext(project, assets, scenes, segments)

        val clipIds = ArrayList<Long>(plans.size)
        var failures = 0

        plans.forEachIndexed { index, plan ->
            val clip = storyBuilder.toClip(
                plan = plan,
                projectId = projectId,
                aspectRatio = project.aspectRatio,
                createdAt = System.currentTimeMillis(),
            )
            val clipId = clipRepository.saveClips(listOf(clip)).firstOrNull()
            if (clipId == null) {
                failures++
                return@forEachIndexed
            }
            clipIds += clipId

            val timeline = storyBuilder.toTimeline(plan, clipId, projectId).map { segment ->
                val asset = assetById[segment.assetId]
                val assetFaces = faceSamples[segment.assetId].orEmpty()
                val cropMode = if (asset == null) {
                    CropMode.CENTER
                } else {
                    smartCropper.strategyFor(
                        sourceWidth = asset.width,
                        sourceHeight = asset.height,
                        rotationDegrees = asset.rotationDegrees,
                        targetAspect = project.aspectRatio,
                        hasFaceSamples = assetFaces.isNotEmpty(),
                        requested = CropMode.SMART,
                    )
                }
                val decision = smartCropper.decide(
                    sourceWidth = asset?.width ?: 0,
                    sourceHeight = asset?.height ?: 0,
                    rotationDegrees = asset?.rotationDegrees ?: 0,
                    targetAspect = project.aspectRatio,
                    faceSamples = assetFaces,
                    startMs = segment.sourceStartMs,
                    endMs = segment.sourceEndMs,
                )
                segment.copy(
                    clipId = clipId,
                    cropMode = cropMode,
                    cropCenterX = decision.centerX,
                    cropCenterY = decision.centerY,
                    cropScale = decision.scale,
                )
            }
            clipRepository.replaceTimeline(clipId, timeline)

            val options = SubtitleBuilder.Options(style = style)
            val captions = subtitleBuilder.build(timeline, segments, options)
            val faceRegions = timeline.mapNotNull { segment ->
                val inside = faceSamples[segment.assetId].orEmpty()
                    .filter { it.coverage > 0f && it.timeMs in segment.sourceStartMs..segment.sourceEndMs }
                if (inside.isEmpty()) {
                    null
                } else {
                    SubtitleBuilder.FaceRegion(
                        startMs = 0L,
                        endMs = Long.MAX_VALUE,
                        centerY = inside.map { it.centerY }.average().toFloat(),
                    )
                }
            }
            clipRepository.replaceCaptions(
                clipId,
                subtitleBuilder.applyFaceAvoidance(captions, faceRegions, options),
            )

            enrichClipMetadata(
                clipId = clipId,
                context = context,
                semantic = semantic,
                highlight = plan.anchorHighlight,
                plan = plan,
            )
            onProgress(0.35f + 0.65f * ((index + 1f) / plans.size))
        }

        onProgress(1f)
        return@withContext if (clipIds.isNotEmpty()) {
            Result(true, clipIds.size)
        } else {
            Result(false, message = "The Shorts could not be created", technical = "failures=$failures")
        }
    }

    /**
     * Titles and description. The AI provider is asked first and the local
     * generator is the safety net, so a clip is never left untitled and no
     * feature silently disappears when the network does.
     */
    private suspend fun enrichClipMetadata(
        clipId: Long,
        context: VideoContext,
        semantic: SceneAnalysisResult,
        highlight: Highlight?,
        plan: StoryBuilder.ShortPlan,
    ) {
        val clipContext = context.copy(
            transcript = highlight?.transcriptText ?: context.transcript,
        )

        val titles = when (
            val result = providerRegistry.resolve(AiCapability.TITLE_GENERATION)
                .generateTitles(clipContext, highlight)
        ) {
            is AiResult.Success -> result.value.map { it.text }
            is AiResult.Failure -> emptyList()
        }.ifEmpty {
            localTitleGenerator.generate(clipContext, highlight).map { it.text }
        }

        val description: DescriptionResult = when (
            val result = providerRegistry.resolve(AiCapability.DESCRIPTION_GENERATION)
                .generateDescription(clipContext, semantic, highlight)
        ) {
            is AiResult.Success -> result.value
            is AiResult.Failure -> localDescriptionGenerator.generate(clipContext, semantic, highlight)
        }

        val chosen = titles.firstOrNull().orEmpty().ifBlank {
            highlight?.label?.displayName ?: "New Short"
        }

        clipRepository.updateClipTitle(clipId, chosen.take(MAX_TITLE_LENGTH))
        clipRepository.updateClipDescription(clipId, description.description, description.hashtags)
        clipRepository.updateClipHook(clipId, plan.hookText)

        clipRepository.getClip(clipId)?.let { clip: GeneratedClip ->
            clipRepository.updateClip(clip.copy(titles = titles.take(MAX_STORED_TITLES)))
        }
    }

    private fun buildContext(
        project: Project,
        assets: List<VideoAsset>,
        scenes: List<Scene>,
        segments: List<TranscriptSegment>,
    ): VideoContext = VideoContext(
        projectId = project.id,
        projectName = project.name,
        mode = project.mode,
        assets = assets,
        scenes = scenes,
        transcript = segments.joinToString(" ") { it.text },
        transcriptSegments = segments,
        targetShortCount = project.targetShortCount,
        targetShortDurationSec = project.targetShortDurationSec,
    )

    private fun buildFaceSamples(scenes: List<Scene>): Map<Long, List<FaceSample>> =
        scenes.filter { it.faceCoverage > 0f }
            .groupBy { it.assetId }
            .mapValues { (_, assetScenes) ->
                assetScenes.map { scene ->
                    FaceSample(
                        timeMs = (scene.startMs + scene.endMs) / 2,
                        coverage = scene.faceCoverage,
                        centerX = scene.faceCenterX,
                        centerY = scene.faceCenterY,
                        faceCount = 1,
                    )
                }
            }

    private companion object {
        const val MAX_TITLE_LENGTH = 90
        const val MAX_STORED_TITLES = 6
    }
}
