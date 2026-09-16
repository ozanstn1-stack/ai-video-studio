package com.aivideostudio.media.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.media.model.AudioFrame
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streams the audio track through the platform decoder and reduces it to a
 * compact energy profile. PCM never accumulates: each window is measured and
 * discarded, so a two hour recording costs the same memory as a one minute one.
 *
 * These measurements drive silence removal, speech detection and the waveform
 * drawn under the timeline.
 */
@Singleton
class AudioFeatureExtractor @Inject constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    data class Result(
        val frames: List<AudioFrame>,
        val sampleRate: Int,
        val channels: Int,
        val error: String? = null,
    )

    suspend fun extract(
        uriString: String,
        durationMs: Long,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(dispatchers.default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriString), null)
            val trackIndex = findAudioTrack(extractor)
                ?: return@withContext Result(emptyList(), 0, 0, "no-audio")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext Result(emptyList(), 0, 0, "unknown-audio-format")

            if (startMs > 0L) {
                extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val state = DecodeState(
                startMs = startMs,
                endMs = if (endMs == Long.MAX_VALUE) durationMs else endMs,
                totalDurationMs = durationMs,
            )
            decodeLoop(extractor, codec, state, onProgress)

            Result(
                frames = state.frames,
                sampleRate = state.outputSampleRate,
                channels = state.outputChannels,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Result(emptyList(), 0, 0, error.message ?: "audio-decode-failed")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun decodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        state: DecodeState,
        onProgress: (Float) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputFinished = false
        var outputFinished = false
        var lastReported = -1f

        while (!outputFinished) {
            coroutineContext.ensureActive()

            if (!inputFinished) {
                val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputFinished = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    state.outputSampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                    state.outputChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                    state.encoding = format.intOr(
                        MediaFormat.KEY_PCM_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                    )
                    state.targetSampleRate = minOf(state.outputSampleRate, ANALYSIS_SAMPLE_RATE)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        val presentationMs = TimeUnit.MICROSECONDS.toMillis(bufferInfo.presentationTimeUs)
                        if (presentationMs <= state.endMs) {
                            state.consume(buffer)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    val presentationMs = TimeUnit.MICROSECONDS.toMillis(bufferInfo.presentationTimeUs)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputFinished = true
                    }
                    if (presentationMs >= state.endMs) outputFinished = true

                    val progress = state.progress()
                    if (progress - lastReported > 0.02f) {
                        lastReported = progress
                        onProgress(progress)
                    }
                }
            }
        }
        state.flush()
    }

    /**
     * Downmixes to mono, decimates to a fixed analysis rate and accumulates one
     * [AudioFrame] per [WINDOW_MS] window. Time is derived from the sample count
     * so it stays correct regardless of the decoder's chunking.
     */
    private class DecodeState(
        val startMs: Long,
        val endMs: Long,
        val totalDurationMs: Long,
    ) {
        val frames = ArrayList<AudioFrame>(2048)
        var outputSampleRate: Int = 44_100
        var outputChannels: Int = 1
        var encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT
        var targetSampleRate: Int = ANALYSIS_SAMPLE_RATE

        private var decimatedSamples: Long = 0L
        private var windowStartSample: Long = 0L
        private var decimationAccumulator: Double = 0.0
        private var windowSumSquares: Double = 0.0
        private var windowPeak: Float = 0f
        private var windowCrossings: Int = 0
        private var windowSamples: Int = 0
        private var previousSample: Float = 0f
        private val samplesPerWindow: Long
            get() = (targetSampleRate.toLong() * WINDOW_MS / 1000L).coerceAtLeast(1L)

        fun progress(): Float =
            ((startMs + decimatedSamples * 1000L / targetSampleRate).toFloat() / totalDurationMs)
                .coerceIn(0f, 1f)

        fun consume(buffer: ByteBuffer) {
            buffer.order(ByteOrder.nativeOrder())
            val step = outputSampleRate.toDouble() / targetSampleRate.toDouble()
            val channels = outputChannels

            if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = buffer.asFloatBuffer()
                val available = floats.remaining()
                var index = 0
                while (index + channels <= available) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += floats.get(index + channel)
                    index += channels
                    accept(sum / channels, step)
                }
            } else {
                val shorts = buffer.asShortBuffer()
                val available = shorts.remaining()
                var index = 0
                while (index + channels <= available) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += shorts.get(index + channel) / 32768f
                    index += channels
                    accept(sum / channels, step)
                }
            }
        }

        private fun accept(mono: Float, step: Double) {
            decimationAccumulator += 1.0
            if (decimationAccumulator < step) return
            decimationAccumulator -= step

            val value = mono.coerceIn(-1f, 1f)
            windowSumSquares += value.toDouble() * value
            windowPeak = maxOf(windowPeak, abs(value))
            if ((value >= 0f) != (previousSample >= 0f)) windowCrossings++
            previousSample = value
            windowSamples++
            decimatedSamples++

            if (decimatedSamples - windowStartSample >= samplesPerWindow) flushWindow()
        }

        private fun flushWindow() {
            if (windowSamples == 0) return
            val startOfWindow = windowStartSample * 1000L / targetSampleRate
            val rms = sqrt(windowSumSquares / windowSamples).toFloat().coerceIn(0f, 1f)
            frames += AudioFrame(
                timeMs = startMs + startOfWindow,
                rms = rms,
                peak = windowPeak.coerceIn(0f, 1f),
                zeroCrossingRate = (windowCrossings.toFloat() / windowSamples).coerceIn(0f, 1f),
            )
            windowStartSample = decimatedSamples
            windowSumSquares = 0.0
            windowPeak = 0f
            windowCrossings = 0
            windowSamples = 0
        }

        fun flush() = flushWindow()
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
        const val ANALYSIS_SAMPLE_RATE = 16_000
        const val WINDOW_MS = 100
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
