package com.aivideostudio.media.frames

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aivideostudio.core.common.Constants
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.media.model.FrameSample
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Pulls small frames out of a source file on a fixed cadence.
 *
 * Two deliberate choices keep this usable on hour-long 4K files:
 *  - frames are requested from the nearest sync sample, so no full decode pass
 *    is needed and the retriever can seek cheaply;
 *  - the sample count is capped, so a 60 minute video is measured at a coarser
 *    interval instead of taking longer than the user is willing to wait.
 *
 * Nothing is ever buffered: the caller receives one frame at a time and owns it.
 */
@Singleton
class FrameSampler @Inject constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    data class Result(
        val sampledFrames: Int,
        val intervalMs: Long,
        val error: String? = null,
    )

    /**
     * @param onFrame invoked for each sampled frame. The bitmap is recycled by
     *   the sampler immediately after the callback returns, so it must not be
     *   retained.
     */
    suspend fun sample(
        uriString: String,
        durationMs: Long,
        maxSamples: Int = MAX_SAMPLES,
        targetWidth: Int = DEFAULT_TARGET_WIDTH,
        minIntervalMs: Long = Constants.ANALYSIS_SAMPLE_INTERVAL_MS,
        onProgress: (Float) -> Unit = {},
        onFrame: suspend (FrameSample) -> Unit,
    ): Result = withContext(dispatchers.default) {
        if (durationMs <= 0L) return@withContext Result(0, 0L, "The video length is unknown")

        val interval = computeInterval(durationMs, maxSamples, minIntervalMs)
        val retriever = MediaMetadataRetriever()
        var count = 0
        try {
            retriever.setDataSource(context, Uri.parse(uriString))
            var timeMs = 0L
            while (timeMs < durationMs) {
                coroutineContext.ensureActive()
                val bitmap = retriever.frameAt(timeMs, targetWidth)
                if (bitmap != null) {
                    try {
                        onFrame(FrameSample(timeMs, bitmap))
                        count++
                    } finally {
                        bitmap.recycle()
                    }
                }
                timeMs += interval
                onProgress((timeMs.toFloat() / durationMs).coerceIn(0f, 1f))
            }
            Result(count, interval)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result(count, interval, error.message ?: "Frames could not be decoded")
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Grabs a single frame, used for thumbnails and clip covers. */
    suspend fun frameAt(uriString: String, timeMs: Long, targetWidth: Int): Bitmap? =
        withContext(dispatchers.io) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uriString))
                retriever.frameAt(timeMs, targetWidth)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun MediaMetadataRetriever.frameAt(timeMs: Long, targetWidth: Int): Bitmap? =
        runCatching {
            getScaledFrameAtTime(
                timeMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetWidth,
            )
        }.getOrNull()

    private fun computeInterval(durationMs: Long, maxSamples: Int, minIntervalMs: Long): Long {
        if (durationMs <= 0L) return minIntervalMs
        val natural = durationMs / maxSamples
        return maxOf(minIntervalMs, natural)
    }

    companion object {
        const val MAX_SAMPLES = 900
        const val DEFAULT_TARGET_WIDTH = 220

        /** Denser sampling used for the short, high-detail smart-crop pass. */
        const val CROP_TARGET_WIDTH = 240
    }
}
