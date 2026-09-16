package com.aivideostudio.media.probe

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aivideostudio.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads everything we need to know about a source file without decoding it.
 * `MediaExtractor` gives accurate track formats; `MediaMetadataRetriever` fills
 * in duration, rotation and the recording date.
 */
@Singleton
class MediaProbe @Inject constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    data class Result(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val bitrate: Int = 0,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val audioChannels: Int = 0,
        val audioSampleRate: Int = 0,
        val rotationDegrees: Int = 0,
        val recordedAtEpochMs: Long = 0L,
        val hasAudio: Boolean = false,
        val hasVideo: Boolean = false,
        val error: String? = null,
    ) {
        val isValid: Boolean get() = error == null && hasVideo && durationMs > 0L
    }

    suspend fun probe(uriString: String): Result = withContext(dispatchers.io) {
        val uri = Uri.parse(uriString)
        var tracks = TrackInfo()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            tracks = readTracks(extractor)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // A container we cannot parse is a hard failure the user must see.
            return@withContext Result(error = describe(error))
        } finally {
            runCatching { extractor.release() }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val rotation = tracks.rotation.takeIf { it != 0 }
                ?: retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

            Result(
                durationMs = duration,
                width = tracks.width,
                height = tracks.height,
                fps = tracks.fps,
                bitrate = tracks.bitrate,
                videoCodec = tracks.videoMime,
                audioCodec = tracks.audioMime,
                audioChannels = tracks.audioChannels,
                audioSampleRate = tracks.audioSampleRate,
                rotationDegrees = rotation,
                recordedAtEpochMs = retriever.recordingDate(),
                hasAudio = tracks.audioMime != null,
                hasVideo = tracks.videoMime != null,
            ).let { result ->
                if (!result.isValid) {
                    result.copy(
                        error = when {
                            !result.hasVideo -> "This file does not contain a video track"
                            result.durationMs <= 0L -> "The video length could not be read"
                            else -> null
                        },
                    )
                } else {
                    result
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result(error = describe(error))
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readTracks(extractor: MediaExtractor): TrackInfo {
        var info = TrackInfo()
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && info.videoMime == null -> {
                    info = info.copy(
                        videoMime = mime,
                        width = format.optInt(MediaFormat.KEY_WIDTH),
                        height = format.optInt(MediaFormat.KEY_HEIGHT),
                        fps = format.optFloat(MediaFormat.KEY_FRAME_RATE),
                        bitrate = format.optInt(MediaFormat.KEY_BIT_RATE),
                        rotation = format.optInt("rotation-degrees"),
                    )
                }

                mime.startsWith("audio/") && info.audioMime == null -> {
                    info = info.copy(
                        audioMime = mime,
                        audioChannels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT),
                        audioSampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE),
                    )
                }
            }
        }
        return info
    }

    private data class TrackInfo(
        val videoMime: String? = null,
        val audioMime: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val bitrate: Int = 0,
        val rotation: Int = 0,
        val audioChannels: Int = 0,
        val audioSampleRate: Int = 0,
    )

    private fun MediaFormat.optInt(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.optFloat(key: String): Float = when {
        !containsKey(key) -> 0f
        else -> runCatching { getFloat(key) }
            .recoverCatching { getInteger(key).toFloat() }
            .getOrDefault(0f)
    }

    private fun MediaMetadataRetriever.metadataLong(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0L

    private fun MediaMetadataRetriever.metadataInt(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0

    private fun MediaMetadataRetriever.recordingDate(): Long {
        val raw = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            ?: extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { "$it" }
            ?: return 0L
        val patterns = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy",
        )
        patterns.forEach { pattern ->
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                if (pattern.contains("'Z'")) format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(raw)?.time?.let { return it }
            }
        }
        return 0L
    }

    private fun describe(error: Exception): String = when (error) {
        is java.io.FileNotFoundException -> "The video file could not be opened"
        is java.io.IOException -> "The video file appears to be damaged"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Unsupported video format"
    }
}
