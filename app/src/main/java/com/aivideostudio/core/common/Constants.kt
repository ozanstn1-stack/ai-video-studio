package com.aivideostudio.core.common

import android.net.Uri

/**
 * Central place for the small number of values that the whole app agrees on.
 * Keeping them here avoids magic numbers leaking into UI or analysis code.
 */
object Constants {

    /** Supported container / mime types for import. */
    val SUPPORTED_VIDEO_MIME_TYPES = setOf(
        "video/mp4",
        "video/quicktime",
        "video/x-matroska",
        "video/webm",
        "video/3gpp",
        "video/avi",
        "video/mpeg",
        "video/ts",
        "video/hevc",
        "video/avc",
    )

    val SUPPORTED_VIDEO_EXTENSIONS = setOf(
        "mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "mpg", "mpeg", "ts", "hevc", "lrv",
    )

    /** DJI cameras and most compact cameras write predictable file names. */
    val DJI_FILE_NAME_REGEX = Regex(
        """^(DJI_[0-9]{4,6}|DJI_[0-9]{8}_[0-9]{6}_[0-9]{3,4}.*|OSMO_[0-9]+.*|GOPR[0-9]{4}|GX[0-9]{6})\.(MP4|MOV|LRV)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Naming used by DJI for the low-resolution proxy files written next to the originals. */
    val PROXY_SUFFIXES = listOf("_LRV", "_lrv")

    const val DEFAULT_SHORT_DURATION_SEC = 30
    const val MIN_SHORT_DURATION_SEC = 10
    const val MAX_SHORT_DURATION_SEC = 180

    /** Frame sampling used by the lightweight visual analysis. */
    const val ANALYSIS_SAMPLE_INTERVAL_MS = 500L

    /** Minimum length of an analysed "scene" so the timeline does not explode into fragments. */
    const val MIN_SCENE_DURATION_MS = 1_500L
    const val MAX_SCENES_PER_ASSET = 900

    /** Highlight window constraints, in milliseconds. */
    const val MIN_HIGHLIGHT_DURATION_MS = 2_500L
    const val MAX_HIGHLIGHT_DURATION_MS = 90_000L

    /** Room database name. */
    const val DATABASE_NAME = "ai_video_studio.db"

    /** WorkManager tag used for every pipeline job belonging to a project. */
    fun pipelineTag(projectId: Long) = "pipeline_project_$projectId"

    const val NOTIFICATION_CHANNEL_PROCESSING = "ai_processing"
    const val NOTIFICATION_CHANNEL_EXPORT = "export"

    const val EXPORT_MIME_MP4 = "video/mp4"

    /** Confidence below which we tell the user the transcript may be inaccurate. */
    const val LOW_CONFIDENCE_THRESHOLD = 0.45

    fun isSupportedVideoUri(uri: Uri): Boolean = uri.scheme in setOf("content", "file")
}
