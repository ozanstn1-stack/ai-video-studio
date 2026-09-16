package com.aivideostudio.domain.repository

import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.AiSuggestion
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.ClipEditState
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.StorageUsage
import com.aivideostudio.domain.model.TextOverlay
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.Transcript
import com.aivideostudio.domain.model.TranscriptSegment
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.model.VideoQuality
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    fun observeRecentProjects(limit: Int = 6): Flow<List<Project>>
    fun observeActiveProjects(): Flow<List<Project>>
    fun observeProjectCount(): Flow<Int>
    fun observeProject(projectId: Long): Flow<Project?>

    suspend fun getProject(projectId: Long): Project?
    suspend fun createProject(
        name: String,
        mode: CreationMode,
        shortCount: Int,
        shortDurationSec: Int,
        aspectRatio: AspectRatio,
        quality: VideoQuality,
        frameRate: FrameRateOption,
        tag: String? = null,
    ): Long

    suspend fun updateOptions(
        projectId: Long,
        mode: CreationMode,
        shortCount: Int,
        shortDurationSec: Int,
        aspectRatio: AspectRatio,
        quality: VideoQuality,
        frameRate: FrameRateOption,
    )

    suspend fun updateStatus(projectId: Long, status: ProjectStatus)
    suspend fun updateCover(projectId: Long, path: String?)
    suspend fun updateSummary(projectId: Long, summary: String?)
    suspend fun recordError(projectId: Long, message: String?)
    suspend fun rename(projectId: Long, name: String)
    suspend fun deleteProject(projectId: Long)
    suspend fun touch(projectId: Long)
}

interface MediaRepository {
    fun observeAssets(projectId: Long): Flow<List<VideoAsset>>
    suspend fun getAssets(projectId: Long): List<VideoAsset>
    suspend fun getAsset(assetId: Long): VideoAsset?
    suspend fun getUnprobedAssets(projectId: Long): List<VideoAsset>

    /** Adds URIs to a project, skipping duplicates. Returns the newly inserted ids. */
    suspend fun addAssets(projectId: Long, uris: List<String>): List<Long>

    suspend fun updateMetadata(asset: VideoAsset)
    suspend fun updateThumbnail(assetId: Long, path: String?)
    suspend fun updateProbeState(assetId: Long, state: com.aivideostudio.domain.model.ProbeState, error: String? = null)
    suspend fun removeAsset(assetId: Long)
    suspend fun totalDurationMs(projectId: Long): Long
    fun observeTotalSourceBytes(): Flow<Long>
}

interface AnalysisRepository {
    suspend fun replaceScenes(assetId: Long, scenes: List<Scene>)
    suspend fun getScenesForProject(projectId: Long): List<Scene>
    suspend fun getScenesForAsset(assetId: Long): List<Scene>
    fun observeScenes(assetId: Long): Flow<List<Scene>>
    suspend fun updateSceneIssue(sceneId: Long, issue: com.aivideostudio.domain.model.SceneIssue, duplicateOf: Long?)
    suspend fun updateSceneAudio(sceneId: Long, speechRatio: Float, loudness: Float)
    suspend fun updateSceneFace(
        sceneId: Long,
        coverage: Float,
        centerX: Float,
        centerY: Float,
        framing: com.aivideostudio.domain.model.Framing,
    )
    suspend fun sceneCount(projectId: Long): Int

    suspend fun saveTranscript(transcript: Transcript, segments: List<TranscriptSegment>): Long
    suspend fun getTranscript(projectId: Long): Transcript?
    fun observeTranscript(projectId: Long): Flow<Transcript?>
    suspend fun getSegments(projectId: Long): List<TranscriptSegment>
    fun observeSegments(projectId: Long): Flow<List<TranscriptSegment>>
    suspend fun segmentCount(projectId: Long): Int

    suspend fun saveSemanticResult(projectId: Long, result: com.aivideostudio.ai.model.SceneAnalysisResult)
    suspend fun getSemanticResult(projectId: Long): com.aivideostudio.ai.model.SceneAnalysisResult

    suspend fun replaceHighlights(projectId: Long, highlights: List<Highlight>)
    suspend fun getHighlights(projectId: Long): List<Highlight>
    fun observeHighlights(projectId: Long): Flow<List<Highlight>>
    suspend fun setHighlightSelection(id: Long, selected: Boolean, rejected: Boolean)
    suspend fun highlightCount(projectId: Long): Int
}

interface ClipRepository {
    fun observeClips(projectId: Long): Flow<List<GeneratedClip>>
    fun observeFinishedClips(): Flow<List<GeneratedClip>>
    suspend fun getClips(projectId: Long): List<GeneratedClip>
    suspend fun getClip(clipId: Long): GeneratedClip?
    suspend fun saveClips(clips: List<GeneratedClip>): List<Long>
    suspend fun updateClip(clip: GeneratedClip)
    suspend fun updateClipTitle(clipId: Long, title: String)
    suspend fun updateClipDescription(clipId: Long, description: String, hashtags: List<String>)
    suspend fun updateClipHook(clipId: Long, hook: String?)
    suspend fun updateClipThumbnail(clipId: Long, path: String?)
    suspend fun updateClipOutput(clipId: Long, path: String?, status: com.aivideostudio.domain.model.ClipStatus)
    suspend fun deleteClip(clipId: Long)
    suspend fun deleteClipsForProject(projectId: Long)

    suspend fun getEditState(clipId: Long): ClipEditState?
    suspend fun replaceTimeline(clipId: Long, clips: List<TimelineClip>)
    suspend fun updateTimelineClip(clip: TimelineClip)
    suspend fun replaceCaptions(clipId: Long, captions: List<Caption>)
    suspend fun updateCaptionText(captionId: Long, text: String)
    suspend fun updateCaptionStyle(clipId: Long, style: com.aivideostudio.domain.model.SubtitleStyle)
    suspend fun updateCaptionPosition(clipId: Long, positionY: Float)
    suspend fun upsertOverlay(overlay: TextOverlay): Long
    suspend fun deleteOverlay(overlayId: Long)
    suspend fun upsertAudioBed(bed: AudioBed)
    suspend fun getAudioBed(clipId: Long): AudioBed?
    suspend fun saveSuggestions(clipId: Long, suggestions: List<AiSuggestion>)
    suspend fun getSuggestions(clipId: Long): List<AiSuggestion>
    suspend fun markSuggestionApplied(id: String)
}

interface JobRepository {
    fun observeLatestJob(projectId: Long): Flow<AiJob?>
    fun observeActiveJobs(): Flow<List<AiJob>>
    suspend fun getLatestJob(projectId: Long): AiJob?
    suspend fun upsertJob(job: AiJob): Long
    suspend fun updateProgress(
        jobId: Long,
        stage: PipelineStage,
        status: JobStatus,
        progress: Float,
        attempt: Int,
        finishedStages: List<PipelineStage>,
        errorMessage: String? = null,
        technicalDetail: String? = null,
        provider: String? = null,
        usedCloud: Boolean = false,
    )

    suspend fun deleteJobsForProject(projectId: Long)
}

interface ExportRepository {
    fun observeExports(): Flow<List<ExportRecord>>
    fun observeProjectExports(projectId: Long): Flow<List<ExportRecord>>
    fun observeRecentExports(limit: Int = 4): Flow<List<ExportRecord>>
    fun observeCompletedCount(): Flow<Int>
    fun observeExportBytes(): Flow<Long>
    suspend fun getExport(exportId: Long): ExportRecord?
    suspend fun getExportsForClip(clipId: Long): List<ExportRecord>
    suspend fun createExport(record: ExportRecord): Long
    suspend fun updateProgress(exportId: Long, progress: Float, status: com.aivideostudio.domain.model.ExportStatus)
    suspend fun complete(
        exportId: Long,
        path: String?,
        uri: String?,
        sizeBytes: Long,
        durationMs: Long,
    )
    suspend fun fail(exportId: Long, message: String)
    suspend fun deleteExport(exportId: Long)
    suspend fun deleteAllCompleted()

    /** Marks interrupted exports as failed so nothing is stuck in "running". */
    suspend fun reconcileInterrupted(): Int
}

interface StorageRepository {
    fun observeUsage(): Flow<StorageUsage>
    suspend fun refresh()
    suspend fun clearCache(): Long
    suspend fun deleteGeneratedFiles(): Long
    suspend fun deleteTemporaryFiles(): Long
    suspend fun availableBytes(): Long
}
