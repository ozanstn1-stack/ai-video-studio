package com.aivideostudio.domain.model

/**
 * The resumable analysis pipeline. The order matters: workers chain in this
 * sequence and a job that has already reached a later stage is never repeated.
 */
enum class PipelineStage(val displayName: String, val weight: Float) {
    IMPORT("Importing videos", 0.02f),
    PROBE_VIDEO("Reading video files", 0.04f),
    EXTRACT_METADATA("Extracting metadata", 0.04f),
    GENERATE_THUMBNAILS("Generating thumbnails", 0.06f),
    SCENE_DETECTION("Detecting scenes", 0.16f),
    AUDIO_ANALYSIS("Analyzing audio", 0.14f),
    TRANSCRIPTION("Transcribing speech", 0.18f),
    SEMANTIC_ANALYSIS("Understanding content", 0.10f),
    HIGHLIGHT_DETECTION("Finding highlights", 0.10f),
    SHORT_GENERATION("Creating your Shorts", 0.08f),
    RENDER("Preparing clips", 0.06f),
    EXPORT("Exporting", 0.02f),
    DONE("Finished", 0f),
    ;

    val next: PipelineStage?
        get() = entries.getOrNull(ordinal + 1)?.takeIf { it != DONE }

    companion object {
        fun fromName(value: String?): PipelineStage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: IMPORT

        /** Cumulative progress (0..1) once [stage] has fully completed. */
        fun completedWeight(stage: PipelineStage): Float {
            val stages = entries.filter { it != DONE }
            val index = stages.indexOf(stage)
            if (index < 0) return 1f
            return stages.take(index + 1).sumOf { it.weight.toDouble() }.toFloat().coerceIn(0f, 1f)
        }
    }
}

data class AiJob(
    val id: Long = 0L,
    val projectId: Long,
    val stage: PipelineStage,
    val status: JobStatus,
    val progress: Float = 0f,
    val attempt: Int = 0,
    val provider: String? = null,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val finishedStages: List<PipelineStage> = emptyList(),
    val errorMessage: String? = null,
    val technicalDetail: String? = null,
    val usedCloud: Boolean = false,
) {
    val isResumable: Boolean get() = status == JobStatus.FAILED || status == JobStatus.PAUSED
    val displayError: String?
        get() = errorMessage?.takeIf { it.isNotBlank() }
}

enum class JobStatus { QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED }

data class ExportRecord(
    val id: Long = 0L,
    val projectId: Long,
    val clipId: Long?,
    val fileName: String,
    val path: String? = null,
    val uri: String? = null,
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 30,
    val quality: VideoQuality = VideoQuality.HIGH,
    val bitrate: Int = 0,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val status: ExportStatus = ExportStatus.QUEUED,
    val progress: Float = 0f,
    val createdAt: Long = 0L,
    val completedAt: Long = 0L,
    val errorMessage: String? = null,
) {
    val isCompleted: Boolean get() = status == ExportStatus.COMPLETED
    val resolutionLabel: String get() = "$width \u00D7 $height"
}

enum class ExportStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

/** Lightweight aggregate used by the Home and Storage screens. */
data class StorageUsage(
    val originalMediaBytes: Long = 0L,
    val generatedClipBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val tempBytes: Long = 0L,
) {
    val totalBytes: Long get() = originalMediaBytes + generatedClipBytes + cacheBytes + tempBytes
    val reclaimableBytes: Long get() = cacheBytes + tempBytes
}
