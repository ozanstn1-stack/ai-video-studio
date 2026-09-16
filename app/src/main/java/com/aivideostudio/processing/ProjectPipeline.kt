package com.aivideostudio.processing

import android.graphics.Bitmap
import com.aivideostudio.ai.model.FrameRef
import com.aivideostudio.ai.model.SceneAnalysisResult
import com.aivideostudio.ai.model.TranscriptionRequest
import com.aivideostudio.ai.model.TranscriptionSource
import com.aivideostudio.ai.model.VideoContext
import com.aivideostudio.ai.provider.AiProviderRegistry
import com.aivideostudio.core.common.Constants
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.media.AppFiles
import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.Framing
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.ProbeState
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.Transcript
import com.aivideostudio.domain.model.TranscriptSegment
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.repository.AnalysisRepository
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.media.analysis.AudioAnalyzer
import com.aivideostudio.media.analysis.FaceAnalyzer
import com.aivideostudio.media.analysis.FrameFeatureExtractor
import com.aivideostudio.media.analysis.SceneDetector
import com.aivideostudio.media.audio.AudioFeatureExtractor
import com.aivideostudio.media.audio.EncodedAudioExtractor
import com.aivideostudio.media.frames.FrameSampler
import com.aivideostudio.media.model.AudioProfile
import com.aivideostudio.media.model.FaceSample
import com.aivideostudio.media.probe.MediaProbe
import com.aivideostudio.media.thumbnail.ThumbnailGenerator
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Runs one stage of the analysis pipeline.
 *
 * Every stage is idempotent and independently resumable: the caller passes the
 * already-completed stages, and anything finished is skipped. This is what lets
 * the app be killed during transcription and pick up where it left off instead
 * of starting the whole analysis again.
 */
@Singleton
class ProjectPipeline @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val mediaRepository: MediaRepository,
    private val analysisRepository: AnalysisRepository,
    private val clipRepository: ClipRepository,
    private val jobRepository: JobRepository,
    private val exportRepository: ExportRepository,
    private val providerRegistry: AiProviderRegistry,
    private val mediaProbe: MediaProbe,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val frameSampler: FrameSampler,
    private val featureExtractor: FrameFeatureExtractor,
    private val sceneDetector: SceneDetector,
    private val faceAnalyzer: FaceAnalyzer,
    private val audioFeatureExtractor: AudioFeatureExtractor,
    private val encodedAudioExtractor: EncodedAudioExtractor,
    private val audioAnalyzer: AudioAnalyzer,
    private val shortGenerator: ShortGenerator,
    private val appFiles: AppFiles,
    private val dispatchers: DispatcherProvider,
) {

    /** Everything a stage may need that is expensive to recompute. */
    private val audioProfiles = HashMap<Long, AudioProfile>()
    private val faceSamples = HashMap<Long, List<FaceSample>>()

    data class Outcome(
        val stage: PipelineStage,
        val succeeded: Boolean,
        val progress: Float = 1f,
        val message: String? = null,
        val technical: String? = null,
        val usedCloud: Boolean = false,
    )

    suspend fun runStage(
        projectId: Long,
        stage: PipelineStage,
        onProgress: (Float) -> Unit = {},
    ): Outcome = when (stage) {
        PipelineStage.IMPORT -> importStage(projectId, onProgress)
        PipelineStage.PROBE_VIDEO, PipelineStage.EXTRACT_METADATA -> probeStage(projectId, onProgress)
        PipelineStage.GENERATE_THUMBNAILS -> thumbnailStage(projectId, onProgress)
        PipelineStage.SCENE_DETECTION -> sceneStage(projectId, onProgress)
        PipelineStage.AUDIO_ANALYSIS -> audioStage(projectId, onProgress)
        PipelineStage.TRANSCRIPTION -> transcriptionStage(projectId, onProgress)
        PipelineStage.SEMANTIC_ANALYSIS -> semanticStage(projectId, onProgress)
        PipelineStage.HIGHLIGHT_DETECTION -> highlightStage(projectId, onProgress)
        PipelineStage.SHORT_GENERATION -> generationStage(projectId, onProgress)
        PipelineStage.RENDER -> renderStage(projectId, onProgress)
        PipelineStage.EXPORT -> Outcome(PipelineStage.EXPORT, succeeded = true)
        PipelineStage.DONE -> Outcome(PipelineStage.DONE, succeeded = true)
    }

    suspend fun loadAudioProfiles(projectId: Long): Map<Long, AudioProfile> {
        if (audioProfiles.isNotEmpty()) return audioProfiles
        val scenes = analysisRepository.getScenesForProject(projectId)
        // Rebuild a coarse profile from persisted per-scene measurements.
        scenes.groupBy { it.assetId }.forEach { (assetId, assetScenes) ->
            if (audioProfiles.containsKey(assetId)) return@forEach
            val frames = assetScenes.map {
                com.aivideostudio.media.model.AudioFrame(
                    timeMs = it.startMs,
                    rms = it.audioLoudness,
                    peak = it.audioLoudness,
                    zeroCrossingRate = 0.15f,
                )
            }
            audioProfiles[assetId] = audioAnalyzer.analyse(frames)
        }
        return audioProfiles
    }

    fun faceSamplesFor(assetId: Long): List<FaceSample> = faceSamples[assetId].orEmpty()

    fun cacheFaceSamples(assetId: Long, samples: List<FaceSample>) {
        faceSamples[assetId] = samples
    }

    // ------------------------------------------------------------------ stages

    private suspend fun importStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val assets = mediaRepository.getAssets(projectId)
        if (assets.isEmpty()) {
            return Outcome(
                PipelineStage.IMPORT,
                succeeded = false,
                message = "Add at least one video to start",
            )
        }
        onProgress(1f)
        return Outcome(PipelineStage.IMPORT, succeeded = true)
    }

    private suspend fun probeStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val assets = mediaRepository.getAssets(projectId)
        if (assets.isEmpty()) {
            return Outcome(PipelineStage.PROBE_VIDEO, false, message = "No videos to read")
        }
        var failed = 0
        assets.forEachIndexed { index, asset ->
            val result = mediaProbe.probe(asset.uri)
            if (result.isValid) {
                mediaRepository.updateMetadata(
                    asset.copy(
                        durationMs = result.durationMs,
                        width = result.width,
                        height = result.height,
                        fps = if (result.fps > 0f) result.fps else DEFAULT_FPS,
                        bitrate = result.bitrate,
                        videoCodec = result.videoCodec,
                        audioCodec = result.audioCodec,
                        audioChannels = result.audioChannels,
                        audioSampleRate = result.audioSampleRate,
                        rotationDegrees = result.rotationDegrees,
                        recordedAtEpochMs = result.recordedAtEpochMs,
                        hasAudioTrack = result.hasAudio,
                    ),
                )
                mediaRepository.updateProbeState(asset.id, ProbeState.READY)
            } else {
                failed++
                mediaRepository.updateProbeState(
                    asset.id,
                    ProbeState.FAILED,
                    result.error ?: "This video could not be read",
                )
            }
            onProgress((index + 1).toFloat() / assets.size)
        }

        val usable = assets.size - failed
        return if (usable == 0) {
            Outcome(
                PipelineStage.PROBE_VIDEO,
                succeeded = false,
                message = "None of these videos could be read",
                technical = "$failed asset(s) failed to probe",
            )
        } else {
            // The first readable frame becomes the project cover.
            mediaRepository.getAssets(projectId)
                .firstOrNull { it.hasValidMetadata }
                ?.let { asset ->
                    thumbnailGenerator.generate(projectId, asset.id, asset.uri, asset.durationMs)
                        ?.let { path -> projectRepository.updateCover(projectId, path) }
                }
            Outcome(PipelineStage.PROBE_VIDEO, succeeded = true)
        }
    }

    private suspend fun thumbnailStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val assets = mediaRepository.getAssets(projectId).filter { it.hasValidMetadata }
        assets.forEachIndexed { index, asset ->
            if (asset.thumbnailPath == null || !File(asset.thumbnailPath).exists()) {
                thumbnailGenerator.generate(projectId, asset.id, asset.uri, asset.durationMs)
                    ?.let { mediaRepository.updateThumbnail(asset.id, it) }
            }
            onProgress((index + 1).toFloat() / assets.size.coerceAtLeast(1))
        }
        return Outcome(PipelineStage.GENERATE_THUMBNAILS, succeeded = true)
    }

    /**
     * Scene detection plus a light face pass. Faces are only sampled every few
     * seconds because the model is the most expensive part of the analysis and
     * per-scene resolution is all the cropper needs.
     */
    private suspend fun sceneStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val assets = mediaRepository.getAssets(projectId).filter { it.hasValidMetadata }
        if (assets.isEmpty()) {
            return Outcome(PipelineStage.SCENE_DETECTION, false, message = "No readable videos")
        }
        var totalScenes = 0
        assets.forEachIndexed { index, asset ->
            val builder = sceneDetector.newBuilder(asset.id, projectId)
            val samples = ArrayList<FaceSample>()

            frameSampler.sample(
                uriString = asset.uri,
                durationMs = asset.durationMs,
                onProgress = { fraction ->
                    onProgress((index + fraction) / assets.size)
                },
            ) { frame ->
                builder.add(featureExtractor.analyse(frame.timeMs, frame.bitmap))
                if (samples.size < MAX_FACE_SAMPLES &&
                    frame.timeMs >= samples.size * FACE_SAMPLE_INTERVAL_MS
                ) {
                    samples += faceAnalyzer.analyse(frame.timeMs, frame.bitmap)
                }
            }

            val scenes = builder.build(asset.durationMs)
            analysisRepository.replaceScenes(asset.id, scenes)
            totalScenes += scenes.size
            cacheFaceSamples(asset.id, samples)
            applyFacesToScenes(scenes, samples)
        }
        faceAnalyzer.close()
        return Outcome(
            PipelineStage.SCENE_DETECTION,
            succeeded = totalScenes > 0,
            message = if (totalScenes == 0) "No usable shots were found in this footage" else null,
        )
    }

    private suspend fun applyFacesToScenes(scenes: List<Scene>, samples: List<FaceSample>) {
        if (samples.isEmpty()) return
        scenes.forEach { scene ->
            val inside = samples.filter { it.timeMs in scene.startMs..scene.endMs }
            if (inside.isEmpty()) return@forEach
            val coverage = inside.map { it.coverage }.max()
            val withFaces = inside.filter { it.coverage > 0f }
            val centerX = if (withFaces.isEmpty()) 0.5f else withFaces.map { it.centerX }.average().toFloat()
            val centerY = if (withFaces.isEmpty()) 0.5f else withFaces.map { it.centerY }.average().toFloat()
            val framing = when {
                coverage > CLOSE_UP_COVERAGE -> Framing.CLOSE_UP
                coverage > MEDIUM_COVERAGE -> Framing.MEDIUM
                scene.framing == Framing.GROUND || scene.framing == Framing.SKY -> scene.framing
                coverage > 0f -> Framing.MEDIUM
                else -> scene.framing
            }
            analysisRepository.updateSceneFace(scene.id, coverage, centerX, centerY, framing)
        }
    }

    private suspend fun audioStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val assets = mediaRepository.getAssets(projectId).filter { it.hasValidMetadata }
        var anyAudio = false
        assets.forEachIndexed { index, asset ->
            if (!asset.hasAudioTrack) {
                audioProfiles[asset.id] = AudioProfile.EMPTY
                return@forEachIndexed
            }
            val result = audioFeatureExtractor.extract(
                uriString = asset.uri,
                durationMs = asset.durationMs,
                onProgress = { fraction -> onProgress((index + fraction) / assets.size) },
            )
            val profile = audioAnalyzer.analyse(result.frames, asset.durationMs)
            audioProfiles[asset.id] = profile
            if (profile.hasSpeech) anyAudio = true
            persistAudioToScenes(asset.id, profile)
            onProgress((index + 1).toFloat() / assets.size)
        }
        return Outcome(PipelineStage.AUDIO_ANALYSIS, succeeded = true, usedCloud = false)
    }

    private suspend fun persistAudioToScenes(assetId: Long, profile: AudioProfile) {
        analysisRepository.getScenesForAsset(assetId).forEach { scene ->
            if (profile.frames.isEmpty()) return@forEach
            val speech = profile.speechRatioBetween(scene.startMs, scene.endMs)
            val loudness = profile.loudnessBetween(scene.startMs, scene.endMs)
            analysisRepository.updateSceneAudio(scene.id, speech, loudness)
        }
    }

    private suspend fun transcriptionStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val assets = mediaRepository.getAssets(projectId).filter { it.hasValidMetadata && it.hasAudioTrack }
        if (assets.isEmpty()) {
            analysisRepository.saveTranscript(
                Transcript(
                    projectId = projectId,
                    provider = "none",
                    fullText = "",
                    createdAt = System.currentTimeMillis(),
                    note = "These clips have no audio track",
                ),
                emptyList(),
            )
            return Outcome(PipelineStage.TRANSCRIPTION, succeeded = true)
        }

        providerRegistry.refresh()
        val provider = providerRegistry.resolve(AiCapability.TRANSCRIPTION)
        var usedCloud = providerRegistry.usedCloud(AiCapability.TRANSCRIPTION)

        val collected = ArrayList<TranscriptSegment>()
        var language: String? = null
        var confidenceSum = 0f
        var confidenceCount = 0
        var failures = 0
        val notes = mutableListOf<String>()

        // Each chunk is transcribed on its own so the returned timestamps are
        // unambiguously chunk-relative and can simply be shifted back.
        val work = ArrayList<Triple<VideoAsset, Long, Long>>()
        assets.forEach { asset ->
            chunkRange(asset.durationMs).forEach { (start, end) -> work += Triple(asset, start, end) }
        }

        work.forEachIndexed { index, (asset, startMs, endMs) ->
            val speechRanges = audioProfiles[asset.id]?.speechRanges.orEmpty()
                .filter { it.first < endMs && it.last > startMs }
                .map { maxOf(it.first, startMs)..minOf(it.last, endMs) }

            val file = appFiles.newAudioFile(projectId, "${asset.id}_$index")
            val extracted = encodedAudioExtractor.extract(asset.uri, file, startMs, endMs)

            val source = TranscriptionSource(
                assetId = asset.id,
                uri = asset.uri,
                displayName = asset.displayName,
                durationMs = endMs - startMs,
                audioPath = extracted.file?.absolutePath,
                speechRanges = speechRanges.map { (it.first - startMs)..(it.last - startMs) },
            )

            when (val result = provider.transcribe(TranscriptionRequest(projectId, listOf(source)))) {
                is com.aivideostudio.ai.model.AiResult.Failure -> {
                    failures++
                    notes += result.message
                }

                is com.aivideostudio.ai.model.AiResult.Success -> {
                    language = language ?: result.value.language
                    result.value.note?.let { notes += it }
                    result.value.segments.forEach { segment ->
                        collected += segment.copy(
                            startMs = segment.startMs + startMs,
                            endMs = segment.endMs + startMs,
                        )
                        if (segment.confidence > 0f) {
                            confidenceSum += segment.confidence
                            confidenceCount++
                        }
                    }
                    if (result.provider.isNotEmpty() && result.provider != provider.displayName) {
                        usedCloud = usedCloud
                    }
                }
            }
            onProgress((index + 1).toFloat() / work.size)
        }

        cleanupAudio(projectId)

        if (collected.isEmpty() && failures == work.size && work.isNotEmpty()) {
            return Outcome(
                PipelineStage.TRANSCRIPTION,
                succeeded = false,
                message = "Speech transcription failed",
                technical = notes.firstOrNull(),
                usedCloud = usedCloud,
            )
        }

        val segments = collected.sortedBy { it.startMs }.mapIndexed { index, segment ->
            segment.copy(index = index)
        }
        analysisRepository.saveTranscript(
            Transcript(
                projectId = projectId,
                language = language,
                provider = provider.displayName,
                fullText = segments.joinToString(" ") { it.text }.trim(),
                createdAt = System.currentTimeMillis(),
                averageConfidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount,
                isComplete = failures == 0,
                note = notes.distinct().joinToString(". ").takeIf { it.isNotBlank() },
            ),
            segments,
        )

        return Outcome(PipelineStage.TRANSCRIPTION, succeeded = true, usedCloud = usedCloud)
    }

    private fun chunkRange(durationMs: Long): List<Pair<Long, Long>> {
        if (durationMs <= MAX_TRANSCRIBE_CHUNK_MS) return listOf(0L to durationMs)
        val chunks = ArrayList<Pair<Long, Long>>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + MAX_TRANSCRIBE_CHUNK_MS, durationMs)
            chunks += start to end
            start = end
        }
        return chunks
    }

    private suspend fun cleanupAudio(projectId: Long) {
        appFiles.projectAudioDir(projectId).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private suspend fun semanticStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val context = buildContext(projectId, includeFrames = true)
        providerRegistry.refresh()
        val provider = providerRegistry.resolve(AiCapability.SEMANTIC_ANALYSIS)
        val usedCloud = providerRegistry.usedCloud(AiCapability.SEMANTIC_ANALYSIS)

        return when (val result = provider.analyzeScenes(context.scenes, context)) {
            is com.aivideostudio.ai.model.AiResult.Failure -> Outcome(
                PipelineStage.SEMANTIC_ANALYSIS,
                succeeded = false,
                message = result.message,
                technical = result.technical,
                usedCloud = usedCloud,
            )

            is com.aivideostudio.ai.model.AiResult.Success -> {
                analysisRepository.saveSemanticResult(projectId, result.value)
                projectRepository.updateSummary(projectId, result.value.storySummary)
                onProgress(1f)
                Outcome(PipelineStage.SEMANTIC_ANALYSIS, succeeded = true, usedCloud = usedCloud)
            }
        }
    }

    private suspend fun highlightStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val project = projectRepository.getProject(projectId)
            ?: return Outcome(PipelineStage.HIGHLIGHT_DETECTION, false, message = "Project not found")
        val scenes = analysisRepository.getScenesForProject(projectId)
        val transcript = analysisRepository.getSegments(projectId)

        val local = shortGenerator.scoreHighlights(project, scenes, transcript)
        if (local.isEmpty()) {
            return Outcome(
                PipelineStage.HIGHLIGHT_DETECTION,
                succeeded = false,
                message = "No usable moments were found in this footage",
            )
        }
        onProgress(0.6f)

        providerRegistry.refresh()
        val provider = providerRegistry.resolve(AiCapability.HIGHLIGHT_REASONING)
        val usedCloud = providerRegistry.usedCloud(AiCapability.HIGHLIGHT_REASONING)
        val context = buildContext(projectId, includeFrames = false)

        val final = when (val result = provider.detectHighlights(scenes, context, local)) {
            is com.aivideostudio.ai.model.AiResult.Success -> result.value
            is com.aivideostudio.ai.model.AiResult.Failure -> local
        }

        analysisRepository.replaceHighlights(projectId, final)
        onProgress(1f)
        return Outcome(PipelineStage.HIGHLIGHT_DETECTION, succeeded = true, usedCloud = usedCloud)
    }

    private suspend fun generationStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val result = shortGenerator.generate(projectId, onProgress)
        return Outcome(
            PipelineStage.SHORT_GENERATION,
            succeeded = result.succeeded,
            message = result.message,
            technical = result.technical,
        )
    }

    private suspend fun renderStage(projectId: Long, onProgress: (Float) -> Unit): Outcome {
        val clips = clipRepository.getClips(projectId)
        val assets = mediaRepository.getAssets(projectId).associateBy { it.id }
        clips.forEachIndexed { index, clip ->
            if (clip.thumbnailPath == null) {
                val timeline = clipRepository.getEditState(clip.id)?.timeline.orEmpty()
                val first = timeline.firstOrNull()
                val asset = first?.let { assets[it.assetId] }
                if (first != null && asset != null) {
                    val middle = (first.sourceStartMs + first.sourceEndMs) / 2
                    thumbnailGenerator.generateForClip(projectId, clip.id, asset.uri, middle)
                        ?.let { clipRepository.updateClipThumbnail(clip.id, it) }
                }
            }
            onProgress((index + 1).toFloat() / clips.size.coerceAtLeast(1))
        }
        projectRepository.updateStatus(projectId, ProjectStatus.GENERATED)
        return Outcome(PipelineStage.RENDER, succeeded = true)
    }

    // ------------------------------------------------------------------ context

    suspend fun buildContext(projectId: Long, includeFrames: Boolean): VideoContext {
        val project = projectRepository.getProject(projectId)
        val assets = mediaRepository.getAssets(projectId)
        val scenes = analysisRepository.getScenesForProject(projectId)
        val transcript = analysisRepository.getTranscript(projectId)
        val segments = analysisRepository.getSegments(projectId)
        val highlights = analysisRepository.getHighlights(projectId)

        return VideoContext(
            projectId = projectId,
            projectName = project?.name.orEmpty(),
            mode = project?.mode ?: com.aivideostudio.domain.model.CreationMode.AUTO,
            assets = assets,
            scenes = scenes,
            transcript = transcript?.fullText.orEmpty(),
            transcriptSegments = segments,
            highlightSummaries = highlights.take(20).map {
                com.aivideostudio.ai.model.HighlightSummary(
                    startMs = it.startMs,
                    endMs = it.endMs,
                    label = it.label.displayName,
                    transcript = it.transcriptText,
                    overallScore = it.overallScore,
                )
            },
            targetShortCount = project?.targetShortCount ?: 3,
            targetShortDurationSec = project?.targetShortDurationSec ?: Constants.DEFAULT_SHORT_DURATION_SEC,
            language = transcript?.language,
            frames = if (includeFrames) writeAnalysisFrames(projectId, assets) else emptyList(),
        )
    }

    /**
     * Writes a handful of small JPEGs that a vision-capable provider can attach.
     * They live in the cache directory and are removed with the rest of the
     * analysis cache; nothing here touches the original media.
     */
    private suspend fun writeAnalysisFrames(
        projectId: Long,
        assets: List<VideoAsset>,
    ): List<FrameRef> = withContext(dispatchers.io) {
        val readable = assets.filter { it.hasValidMetadata }
        if (readable.isEmpty()) return@withContext emptyList()
        val perAsset = (MAX_ANALYSIS_FRAMES / readable.size).coerceAtLeast(1)
        val result = ArrayList<FrameRef>(MAX_ANALYSIS_FRAMES)
        readable.forEach { asset ->
            repeat(perAsset) { index ->
                val timeMs = ((index + 1f) / (perAsset + 1f) * asset.durationMs).toLong()
                val bitmap: Bitmap = frameSampler.frameAt(asset.uri, timeMs, FRAME_WIDTH)
                    ?: return@repeat
                val file = File(appFiles.projectFrameDir(projectId), "${asset.id}_$index.jpg")
                runCatching {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, out)
                    }
                    result += FrameRef(file.absolutePath, timeMs, asset.displayName)
                }
                bitmap.recycle()
                if (result.size >= MAX_ANALYSIS_FRAMES) return@withContext result
            }
        }
        result
    }

    suspend fun loadSemanticResult(projectId: Long): SceneAnalysisResult =
        analysisRepository.getSemanticResult(projectId)

    /**
     * Called once at startup. A process killed mid-export leaves a row in the
     * RUNNING state; the user must see a clear outcome instead of a progress bar
     * that never moves again.
     */
    suspend fun reconcileInterruptedExports(): Int = exportRepository.reconcileInterrupted()

    private companion object {
        const val DEFAULT_FPS = 30f
        const val MAX_FACE_SAMPLES = 160
        const val FACE_SAMPLE_INTERVAL_MS = 3_000L
        const val CLOSE_UP_COVERAGE = 0.11f
        const val MEDIUM_COVERAGE = 0.03f
        const val MAX_TRANSCRIBE_CHUNK_MS = 10 * 60 * 1000L
        const val MAX_ANALYSIS_FRAMES = 10
        const val FRAME_WIDTH = 640
        const val FRAME_QUALITY = 80
    }
}
