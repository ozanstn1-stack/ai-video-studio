package com.aivideostudio.ai.crop

import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.media.model.CropDecision
import com.aivideostudio.media.model.FaceSample
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subject-aware cropping.
 *
 * A centre crop of 16:9 footage to 9:16 throws away most of the frame and often
 * cuts the subject in half. This computes the largest window with the target
 * aspect ratio that still contains the detected people, and only falls back to
 * the geometric centre when there is nothing to track.
 *
 * The result is stored as a normalised centre plus a zoom factor, which is what
 * both the editor UI and the export pipeline consume.
 */
@Singleton
class SmartCropper @Inject constructor() {

    /** Media3 crop coordinates in normalised device coordinates (-1..1). */
    data class CropRect(
        val left: Float,
        val right: Float,
        val bottom: Float,
        val top: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = top - bottom
    }

    fun decide(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetAspect: AspectRatio,
        faceSamples: List<FaceSample>,
        startMs: Long,
        endMs: Long,
        zoom: Float = 1f,
    ): CropDecision {
        val (sourceW, sourceH) = orientedSize(sourceWidth, sourceHeight, rotationDegrees)
        if (sourceW <= 0 || sourceH <= 0) return CropDecision()

        val geometry = geometryFor(sourceW, sourceH, targetAspect, zoom)
        val relevant = faceSamples.filter { it.timeMs in startMs..endMs && it.coverage > 0f }

        if (relevant.isEmpty()) {
            return CropDecision(
                centerX = geometry.clampX(DEFAULT_CENTER),
                centerY = geometry.clampY(DEFAULT_CENTER),
                scale = zoom,
                confidence = 0f,
            )
        }

        // Weight by how much of the frame each face occupies so a large, close
        // subject wins over a small background figure.
        var weightSum = 0f
        var weightedX = 0f
        var weightedY = 0f
        var coveragePeak = 0f
        relevant.forEach { sample ->
            val weight = (sample.coverage * FACE_WEIGHT_SCALE).coerceAtLeast(MIN_FACE_WEIGHT)
            weightSum += weight
            weightedX += sample.centerX * weight
            weightedY += sample.centerY * weight
            if (sample.coverage > coveragePeak) coveragePeak = sample.coverage
        }

        val rawX = if (weightSum <= 0f) DEFAULT_CENTER else weightedX / weightSum
        val rawY = if (weightSum <= 0f) DEFAULT_CENTER else weightedY / weightSum

        return CropDecision(
            centerX = geometry.clampX(rawX),
            centerY = geometry.clampY(rawY),
            scale = zoom,
            confidence = (coveragePeak * CONFIDENCE_SCALE).coerceIn(0f, 1f),
        )
    }

    /**
     * Converts a decision into the NDC rectangle Media3's `Crop` effect expects.
     *
     * The source is first letterboxed into the target frame ("scale to fit"), and
     * the crop then selects a sub-rectangle of that letterboxed image. Because
     * both the frame and the crop share the target aspect ratio, the rectangle is
     * square in NDC units.
     */
    fun toCropRect(
        decision: CropDecision,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetAspect: AspectRatio,
    ): CropRect {
        val (sourceW, sourceH) = orientedSize(sourceWidth, sourceHeight, rotationDegrees)
        if (sourceW <= 0 || sourceH <= 0) return CropRect(-1f, 1f, -1f, 1f)

        val scale = minOf(
            TARGET_FRAME_WIDTH.toFloat() / sourceW,
            TARGET_FRAME_HEIGHT.toFloat() / sourceH,
        )
        val displayedWidthPx = sourceW * scale
        val displayedHeightPx = sourceH * scale

        val aspect = targetAspect.aspectValue
        var keptHeightPx = displayedHeightPx
        var keptWidthPx = keptHeightPx * aspect
        if (keptWidthPx > displayedWidthPx) {
            keptWidthPx = displayedWidthPx
            keptHeightPx = keptWidthPx / aspect
        }

        val zoom = decision.scale.coerceAtLeast(1f)
        keptWidthPx /= zoom
        keptHeightPx /= zoom

        // Half extents in NDC: the frame spans 2 NDC units across its own size.
        val halfWidthNdc = keptWidthPx / TARGET_FRAME_WIDTH
        val halfHeightNdc = keptHeightPx / TARGET_FRAME_HEIGHT

        val centerXNdc = ((decision.centerX - 0.5f) * displayedWidthPx) / (TARGET_FRAME_WIDTH / 2f)
        val centerYNdc = ((decision.centerY - 0.5f) * displayedHeightPx) / (TARGET_FRAME_HEIGHT / 2f)

        val maxX = (displayedWidthPx / TARGET_FRAME_WIDTH) - halfWidthNdc
        val maxY = (displayedHeightPx / TARGET_FRAME_HEIGHT) - halfHeightNdc
        val clampedX = centerXNdc.coerceIn(-maxX, maxX)
        val clampedY = centerYNdc.coerceIn(-maxY, maxY)

        return CropRect(
            left = clampedX - halfWidthNdc,
            right = clampedX + halfWidthNdc,
            bottom = clampedY - halfHeightNdc,
            top = clampedY + halfHeightNdc,
        )
    }

    /**
     * Larger of the two possible windows with the target aspect, plus the safe
     * range for the centre so the window never runs off the frame.
     */
    private fun geometryFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: AspectRatio,
        zoom: Float,
    ): Geometry {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val aspect = targetAspect.aspectValue
        val zoomFactor = zoom.coerceAtLeast(1f)

        val keptFractionWidth: Float
        val keptFractionHeight: Float
        if (sourceAspect > aspect) {
            // Source is wider than the target: keep the full height.
            keptFractionHeight = 1f
            keptFractionWidth = (aspect / sourceAspect) / zoomFactor
        } else {
            keptFractionWidth = 1f
            keptFractionHeight = (sourceAspect / aspect) / zoomFactor
        }
        return Geometry(keptFractionWidth.coerceIn(0.05f, 1f), keptFractionHeight.coerceIn(0.05f, 1f))
    }

    private data class Geometry(val keptWidth: Float, val keptHeight: Float) {
        fun clampX(value: Float): Float = value.coerceIn(keptWidth / 2f, 1f - keptWidth / 2f)
        fun clampY(value: Float): Float = value.coerceIn(keptHeight / 2f, 1f - keptHeight / 2f)
    }

    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees % 180 == 90) height to width else width to height

    /**
     * Chooses the crop strategy for an asset. When the source already matches the
     * target there is nothing to crop, and when faces cannot be resolved we fall
     * back to a plain centre crop so the result is still predictable.
     */
    fun strategyFor(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetAspect: AspectRatio,
        hasFaceSamples: Boolean,
        requested: CropMode,
    ): CropMode {
        if (requested == CropMode.FIT) return CropMode.FIT
        val (width, height) = orientedSize(sourceWidth, sourceHeight, rotationDegrees)
        if (width <= 0 || height <= 0) return CropMode.CENTER
        val sourceAspect = width.toFloat() / height.toFloat()
        if (kotlin.math.abs(sourceAspect - targetAspect.aspectValue) < ASPECT_MATCH_TOLERANCE) {
            return CropMode.FIT
        }
        return if (hasFaceSamples && requested == CropMode.SMART) CropMode.SMART else CropMode.CENTER
    }

    private companion object {
        const val DEFAULT_CENTER = 0.5f
        const val FACE_WEIGHT_SCALE = 12f
        const val MIN_FACE_WEIGHT = 0.02f
        const val CONFIDENCE_SCALE = 8f
        const val ASPECT_MATCH_TOLERANCE = 0.02f

        /** Reference frame used only to convert the decision into NDC units. */
        const val TARGET_FRAME_WIDTH = 1080f
        const val TARGET_FRAME_HEIGHT = 1920f
    }
}
