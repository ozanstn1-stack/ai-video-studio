package com.aivideostudio.media.analysis

import com.aivideostudio.media.model.AudioFrame
import com.aivideostudio.media.model.AudioProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Turns raw per-window energy into the things the product actually cares about:
 * where somebody is speaking, where nothing at all happens, and where the audio
 * is clipping.
 *
 * Everything here is deterministic and pure so it can be unit tested without a
 * device or a video file.
 */
@Singleton
class AudioAnalyzer @Inject constructor() {

    fun analyse(frames: List<AudioFrame>, durationMs: Long = 0L): AudioProfile {
        if (frames.isEmpty()) return AudioProfile.EMPTY

        val sorted = frames.sortedBy { it.timeMs }
        val noiseFloor = estimateNoiseFloor(sorted)
        val averageLoudness = sorted.map { it.rms }.average().toFloat()

        val speechThreshold = maxOf(noiseFloor * NOISE_TO_SPEECH_RATIO, MIN_SPEECH_RMS)
        val silenceThreshold = maxOf(noiseFloor * 1.35f, MIN_SILENCE_RMS)

        val speechRanges = buildRanges(
            frames = sorted,
            predicate = { frame ->
                frame.rms >= speechThreshold &&
                    frame.zeroCrossingRate in SPEECH_ZCR_RANGE &&
                    !frame.isClipping()
            },
            gapToleranceMs = SPEECH_GAP_TOLERANCE_MS,
            minDurationMs = MIN_SPEECH_DURATION_MS,
        )

        val silenceRanges = buildRanges(
            frames = sorted,
            predicate = { it.rms <= silenceThreshold && !it.isClipping() },
            gapToleranceMs = 0L,
            minDurationMs = MIN_SILENCE_DURATION_MS,
        )

        val clippingRanges = buildRanges(
            frames = sorted,
            predicate = { it.isClipping() },
            gapToleranceMs = 300L,
            minDurationMs = 120L,
        )

        return AudioProfile(
            frames = sorted,
            speechRanges = speechRanges,
            silenceRanges = silenceRanges,
            clippingRanges = clippingRanges,
            noiseFloor = noiseFloor,
            averageLoudness = averageLoudness,
            waveform = downsample(sorted, durationMs),
        )
    }

    /**
     * A robust noise floor: the 15th percentile of window energy. Using a
     * percentile rather than the mean keeps loud events from dragging the
     * "silence" reference upwards.
     */
    private fun estimateNoiseFloor(frames: List<AudioFrame>): Float {
        val values = frames.map { it.rms }.sorted()
        if (values.isEmpty()) return 0f
        val index = (values.size * NOISE_PERCENTILE).toInt().coerceIn(0, values.size - 1)
        return values[index]
    }

    private fun buildRanges(
        frames: List<AudioFrame>,
        predicate: (AudioFrame) -> Boolean,
        gapToleranceMs: Long,
        minDurationMs: Long,
    ): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        var start: Long? = null
        var lastMatch: Long = 0L

        frames.forEach { frame ->
            val end = frame.timeMs + WINDOW_MS
            if (predicate(frame)) {
                if (start == null) {
                    start = frame.timeMs
                } else if (gapToleranceMs == 0L && frame.timeMs - lastMatch > WINDOW_MS * 2) {
                    // Strict mode: any gap ends the range.
                    ranges += start!!..lastMatch
                    start = frame.timeMs
                }
                lastMatch = end
            } else if (start != null && frame.timeMs - lastMatch > gapToleranceMs) {
                ranges += start!!..lastMatch
                start = null
            }
        }
        start?.let { ranges += it..lastMatch }

        return ranges
            .filter { it.last - it.first >= minDurationMs }
            .sortedBy { it.first }
    }

    /** A fixed-resolution envelope used for drawing the timeline waveform. */
    private fun downsample(frames: List<AudioFrame>, durationMs: Long): FloatArray {
        val span = if (durationMs > 0L) durationMs else (frames.lastOrNull()?.timeMs ?: 0L)
        if (span <= 0L || frames.isEmpty()) return FloatArray(0)

        val buckets = WAVEFORM_BUCKETS
        val result = FloatArray(buckets)
        val bucketMs = span.toDouble() / buckets

        frames.forEach { frame ->
            val index = (frame.timeMs / bucketMs).toInt().coerceIn(0, buckets - 1)
            if (frame.peak > result[index]) result[index] = frame.peak
        }

        // A light smoothing pass so the drawn envelope reads as a shape.
        for (i in 1 until buckets - 1) {
            result[i] = (result[i - 1] + result[i] * 2f + result[i + 1]) / 4f
        }
        return result
    }

    /**
     * Detects the "long silence" the brief asks us to remove. Returns the ranges
     * worth cutting, ignoring the ones that are simply natural pauses.
     */
    fun removableSilence(
        profile: AudioProfile,
        minDurationMs: Long = REMOVABLE_SILENCE_MS,
    ): List<LongRange> = profile.silenceRanges.filter { it.last - it.first >= minDurationMs }

    private fun AudioFrame.isClipping(): Boolean =
        peak >= CLIPPING_PEAK && abs(zeroCrossingRate - 0.5f) < 0.5f

    private companion object {
        const val WINDOW_MS = 100L
        const val NOISE_PERCENTILE = 0.15
        const val NOISE_TO_SPEECH_RATIO = 2.6f
        const val MIN_SPEECH_RMS = 0.018f
        const val MIN_SILENCE_RMS = 0.008f
        val SPEECH_ZCR_RANGE = 0.02f..0.60f
        const val CLIPPING_PEAK = 0.995f
        const val SPEECH_GAP_TOLERANCE_MS = 350L
        const val MIN_SPEECH_DURATION_MS = 300L
        const val MIN_SILENCE_DURATION_MS = 700L
        const val REMOVABLE_SILENCE_MS = 1_800L
        const val WAVEFORM_BUCKETS = 480
    }
}
