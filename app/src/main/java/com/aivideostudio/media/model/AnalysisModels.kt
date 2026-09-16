package com.aivideostudio.media.model

import android.graphics.Bitmap

/** A single decoded, downscaled frame handed to the analyzers. */
data class FrameSample(
    val timeMs: Long,
    val bitmap: Bitmap,
)

/**
 * Per-frame measurements. Every value is normalised to `0f..1f` so the scoring
 * code never has to know about pixel units. [histogram] is a compact hue
 * signature used for scene-cut and duplicate detection.
 */
data class FrameFeatures(
    val timeMs: Long,
    val brightness: Float,
    val sharpness: Float,
    val saturation: Float,
    val hue: Float,
    val topBrightness: Float,
    val bottomBrightness: Float,
    val motion: Float,
    val stability: Float,
    val faceCoverage: Float = 0f,
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val histogram: FloatArray = FloatArray(HISTOGRAM_BINS),
) {
    val framingIsGroundLike: Boolean
        get() = bottomBrightness > topBrightness * 1.6f && sharpness < 0.28f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameFeatures) return false
        return timeMs == other.timeMs &&
            brightness == other.brightness &&
            sharpness == other.sharpness &&
            histogram.contentEquals(other.histogram)
    }

    override fun hashCode(): Int {
        var result = timeMs.hashCode()
        result = 31 * result + brightness.hashCode()
        result = 31 * result + histogram.contentHashCode()
        return result
    }

    companion object {
        const val HISTOGRAM_BINS = 16
    }
}

/** One analysis window of audio energy. */
data class AudioFrame(
    val timeMs: Long,
    val rms: Float,
    val peak: Float,
    val zeroCrossingRate: Float,
)

/** Derived, project-level audio profile used by the scorer and the timeline UI. */
data class AudioProfile(
    val frames: List<AudioFrame>,
    val speechRanges: List<LongRange>,
    val silenceRanges: List<LongRange>,
    val clippingRanges: List<LongRange>,
    val noiseFloor: Float,
    val averageLoudness: Float,
    val waveform: FloatArray,
) {
    val hasSpeech: Boolean get() = speechRanges.isNotEmpty()

    fun speechRatioBetween(startMs: Long, endMs: Long): Float {
        if (endMs <= startMs) return 0f
        val overlap = speechRanges.sumOf { range ->
            (minOf(range.last, endMs) - maxOf(range.first, startMs)).coerceAtLeast(0L)
        }
        return (overlap.toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

    fun silenceRatioBetween(startMs: Long, endMs: Long): Float {
        if (endMs <= startMs) return 0f
        val overlap = silenceRanges.sumOf { range ->
            (minOf(range.last, endMs) - maxOf(range.first, startMs)).coerceAtLeast(0L)
        }
        return (overlap.toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

    fun loudnessBetween(startMs: Long, endMs: Long): Float {
        if (endMs <= startMs) return 0f
        val inRange = frames.filter { it.timeMs in startMs until endMs }
        if (inRange.isEmpty()) return 0f
        return inRange.map { it.rms }.average().toFloat().coerceIn(0f, 1f)
    }

    fun hasClipping(startMs: Long, endMs: Long): Boolean =
        clippingRanges.any { it.first < endMs && it.last > startMs }

    companion object {
        val EMPTY = AudioProfile(
            frames = emptyList(),
            speechRanges = emptyList(),
            silenceRanges = emptyList(),
            clippingRanges = emptyList(),
            noiseFloor = 0f,
            averageLoudness = 0f,
            waveform = FloatArray(0),
        )
    }
}

/** Result of the ML Kit face pass over the sampled frames. */
data class FaceSample(
    val timeMs: Long,
    val coverage: Float,
    val centerX: Float,
    val centerY: Float,
    val faceCount: Int,
    val isSmiling: Boolean = false,
)

/** Smart-crop decision for a source range, expressed as normalised values. */
data class CropDecision(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val scale: Float = 1f,
    val confidence: Float = 0f,
)
