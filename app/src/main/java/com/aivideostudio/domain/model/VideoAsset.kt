package com.aivideostudio.domain.model

data class Project(
    val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val mode: CreationMode = CreationMode.AUTO,
    val targetShortCount: Int = 3,
    val targetShortDurationSec: Int = 30,
    val aspectRatio: AspectRatio = AspectRatio.VERTICAL_9_16,
    val quality: VideoQuality = VideoQuality.HIGH,
    val frameRate: FrameRateOption = FrameRateOption.SOURCE,
    val coverPath: String? = null,
    val tag: String? = null,
    val transcriptLanguage: String? = null,
    val aiSummary: String? = null,
    val lastError: String? = null,
) {
    val isSource: Boolean get() = tag == TAG_DJI_FOLDER

    companion object {
        const val TAG_DJI_FOLDER = "dji-folder"
    }
}

data class VideoAsset(
    val id: Long = 0L,
    val projectId: Long,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val bitrate: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val audioSampleRate: Int = 0,
    val sizeBytes: Long = 0L,
    val rotationDegrees: Int = 0,
    val recordedAtEpochMs: Long = 0L,
    val thumbnailPath: String? = null,
    val isDjiFootage: Boolean = false,
    val hasAudioTrack: Boolean = true,
    val probeState: ProbeState = ProbeState.PENDING,
    val probeError: String? = null,
    val orderIndex: Int = 0,
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) {
            val shortEdge = minOf(width, height)
            when {
                shortEdge >= 2160 -> "4K"
                shortEdge >= 1440 -> "2K"
                shortEdge >= 1080 -> "1080p"
                shortEdge >= 720 -> "720p"
                else -> "${shortEdge}p"
            }
        } else {
            "Unknown"
        }

    val orientedWidth: Int get() = if (rotationDegrees % 180 == 90) height else width
    val orientedHeight: Int get() = if (rotationDegrees % 180 == 90) width else height

    val hasValidMetadata: Boolean get() = probeState == ProbeState.READY && durationMs > 0
}

enum class ProbeState { PENDING, RUNNING, READY, FAILED }
