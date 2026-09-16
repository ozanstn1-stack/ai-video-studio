package com.aivideostudio.media.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.aivideostudio.core.common.DispatcherProvider
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Copies the compressed audio track into a small `.m4a` file without touching
 * the video and without re-encoding. This is what makes cloud transcription
 * affordable: a 30 minute clip becomes a couple of megabytes instead of
 * hundreds, and the operation is a fast remux rather than a decode.
 */
@Singleton
class EncodedAudioExtractor @Inject constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    data class Result(
        val file: File?,
        val durationMs: Long,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = file != null
    }

    /**
     * @param maxDurationMs splits the request into chunks; providers reject
     *   payloads over a size limit, so callers pass their own ceiling.
     */
    suspend fun extract(
        uriString: String,
        outputFile: File,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
    ): Result = withContext(dispatchers.io) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriString), null)
            val trackIndex = findAudioTrack(extractor)
                ?: return@withContext Result(null, 0L, "no-audio")

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime !in MUXABLE_AUDIO_TYPES) {
                return@withContext Result(null, 0L, "unsupported-audio-codec:$mime")
            }

            extractor.selectTrack(trackIndex)
            if (startMs > 0L) extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val targetTrack = muxer.addTrack(inputFormat)
            muxer.start()

            val maxSampleSize = inputFormat.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_MAX_SAMPLE)
            val buffer = ByteBuffer.allocate(maxSampleSize.coerceAtLeast(DEFAULT_MAX_SAMPLE))
            val info = MediaCodec.BufferInfo()
            val baseUs = startMs * 1_000L
            var lastUs = 0L
            var written = 0L

            while (true) {
                coroutineContext.ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleUs = extractor.sampleTime
                if (sampleUs > endMs * 1_000L) break
                if (sampleUs >= baseUs) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = sampleUs - baseUs
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(targetTrack, buffer, info)
                    lastUs = info.presentationTimeUs
                    written += size
                }
                if (!extractor.advance()) break
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (written == 0L) {
                outputFile.delete()
                Result(null, 0L, "no-samples-in-range")
            } else {
                Result(outputFile, lastUs / 1_000L)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            outputFile.delete()
            Result(null, 0L, error.message ?: "audio-remux-failed")
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return null
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    companion object {
        /** Container/codec combinations that MP4 muxing supports. */
        val MUXABLE_AUDIO_TYPES = setOf("audio/mp4a-latm", "audio/mpeg", "audio/3gpp", "audio/amr-wb")

        private const val DEFAULT_MAX_SAMPLE = 64 * 1024
    }
}
