package com.aivideostudio.media.analysis

import com.aivideostudio.core.common.Constants
import com.aivideostudio.domain.model.Framing
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.SceneIssue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Groups sampled frames into visually coherent scenes and flags the ones that
 * should be dropped.
 *
 * Frames are consumed one at a time and reduced to a compact record, so memory
 * stays flat no matter how long the source is. All decisions are pure
 * arithmetic on those records, which makes the whole detector unit testable.
 */
@Singleton
class SceneDetector @Inject constructor(
    private val featureExtractor: FrameFeatureExtractor,
) {

    /**
     * A stream-friendly builder. Feed it every sampled frame, then call [build].
     * Only compact per-frame records are retained.
     */
    inner class Builder(private val assetId: Long, private val projectId: Long) {

        private val records = ArrayList<FrameRecord>(512)
        private var previousGrid: FrameFeatureExtractor.Grid? = null
        private var previousRecord: FrameRecord? = null
        private var sceneStartMs = -1L
        private var sceneIndex = 0
        private val scenes = ArrayList<Scene>()
        private var sceneAccumulator = Accumulator()
        private var sceneHistogram = FloatArray(FrameFeatureExtractor.HISTOGRAM_BINS)
        private var sceneHueAccumulator = 0f
        private var sceneCount = 0

        fun add(stats: FrameFeatureExtractor.Stats) {
            val previous = previousGrid
            val motion = previous?.let { featureExtractor.motionBetween(it, stats.grid) } ?: 0f
            val shake = previous?.let { featureExtractor.shakeBetween(it, stats.grid, motion) } ?: 0f

            val record = FrameRecord(
                timeMs = stats.timeMs,
                brightness = stats.brightness,
                sharpness = stats.sharpness,
                saturation = stats.saturation,
                hue = stats.hue,
                topBrightness = stats.grid.topBrightness,
                bottomBrightness = stats.grid.bottomBrightness,
                motion = motion,
                stability = (1f - shake).coerceIn(0f, 1f),
                histogram = stats.histogram,
                texture = featureExtractor.textureLevel(stats),
            )

            if (sceneStartMs < 0L) sceneStartMs = stats.timeMs

            val cut = previousRecord?.let { previousRecord ->
                shouldCut(previousRecord, record, stats.timeMs - sceneStartMs)
            } ?: false

            if (cut) {
                closeScene(record.timeMs)
                if (scenes.size >= Constants.MAX_SCENES_PER_ASSET) {
                    records.clear()
                    previousGrid = stats.grid
                    previousRecord = record
                    return
                }
            }

            record.into(sceneAccumulator)
            sceneHistogram = merge(sceneHistogram, stats.histogram)
            sceneHueAccumulator += stats.hue
            sceneCount++
            records += record

            previousGrid = stats.grid
            previousRecord = record
        }

        /** Closes the trailing scene and runs duplicate detection. */
        fun build(videoDurationMs: Long): List<Scene> {
            if (records.isNotEmpty()) closeScene(records.last().timeMs + Constants.ANALYSIS_SAMPLE_INTERVAL_MS)
            val finite = scenes
                .filter { it.endMs > it.startMs }
                .sortedBy { it.startMs }
                .mapIndexed { index, scene -> scene.copy(index = index) }
            return markDuplicates(finite, videoDurationMs)
        }

        private fun shouldCut(
            previous: FrameRecord,
            current: FrameRecord,
            sceneDurationMs: Long,
        ): Boolean {
            if (sceneDurationMs < Constants.MIN_SCENE_DURATION_MS) return false
            val histogramShift = featureExtractor.histogramDistance(previous.histogram, current.histogram)
            val brightnessJump = abs(current.brightness - previous.brightness)
            return histogramShift >= HISTOGRAM_CUT_THRESHOLD || brightnessJump >= BRIGHTNESS_CUT_THRESHOLD
        }

        private fun closeScene(endMs: Long) {
            if (records.isEmpty()) {
                sceneStartMs = endMs
                return
            }
            val scene = sceneAccumulator.toScene(
                id = 0L,
                projectId = projectId,
                assetId = assetId,
                index = sceneIndex++,
                startMs = sceneStartMs,
                endMs = endMs.coerceAtLeast(sceneStartMs + 1),
                histogram = normalize(sceneHistogram),
                averageHue = if (sceneCount == 0) 0f else sceneHueAccumulator / sceneCount,
            )
            if (scene != null) scenes += scene

            sceneAccumulator = Accumulator()
            sceneHistogram = FloatArray(FrameFeatureExtractor.HISTOGRAM_BINS)
            sceneHueAccumulator = 0f
            sceneCount = 0
            sceneStartMs = endMs
        }

        private fun normalize(histogram: FloatArray): FloatArray {
            val total = histogram.sum().takeIf { it > 0f } ?: 1f
            return FloatArray(histogram.size) { histogram[it] / total }
        }

        private fun merge(target: FloatArray, source: FloatArray): FloatArray =
            FloatArray(target.size) { target[it] + source[it] }
    }

    fun newBuilder(assetId: Long, projectId: Long): Builder = Builder(assetId, projectId)

    /**
     * Flags scenes that repeat the same shot. Only the compact signature that
     * survives the streaming pass is compared, which is deliberately
     * conservative: a false "duplicate" would silently remove real footage from
     * the selection, so the tolerances are tight.
     */
    private fun markDuplicates(scenes: List<Scene>, videoDurationMs: Long): List<Scene> {
        if (videoDurationMs <= 0L) return scenes
        val result = ArrayList<Scene>(scenes.size)
        scenes.forEach { scene ->
            val duplicate = result.firstOrNull { candidate ->
                candidate.issue == SceneIssue.NONE &&
                    scene.issue == SceneIssue.NONE &&
                    abs(candidate.dominantHue - scene.dominantHue) < DUPLICATE_HUE_TOLERANCE &&
                    abs(candidate.quality - scene.quality) < DUPLICATE_QUALITY_TOLERANCE &&
                    abs(candidate.durationMs - scene.durationMs) <= DUPLICATE_DURATION_TOLERANCE_MS
            }
            result += if (duplicate != null && scene.durationMs in MIN_DUPLICATE_LENGTH_MS..MAX_DUPLICATE_LENGTH_MS) {
                scene.copy(issue = SceneIssue.DUPLICATE, duplicateOfSceneId = duplicate.id)
            } else {
                scene
            }
        }
        return result
    }

    private class FrameRecord(
        val timeMs: Long,
        val brightness: Float,
        val sharpness: Float,
        val saturation: Float,
        val hue: Float,
        val topBrightness: Float,
        val bottomBrightness: Float,
        val motion: Float,
        val stability: Float,
        val histogram: FloatArray,
        val texture: Float,
    ) {
        fun into(accumulator: Accumulator) = accumulator.add(this)
    }

    private class Accumulator {
        var count = 0
        var brightness = 0f
        var sharpness = 0f
        var saturation = 0f
        var motion = 0f
        var stability = 0f
        var texture = 0f
        var topBrightness = 0f
        var bottomBrightness = 0f
        var motionVariance = 0f
        private var motionSquares = 0f

        fun add(record: FrameRecord) {
            count++
            brightness += record.brightness
            sharpness += record.sharpness
            saturation += record.saturation
            motion += record.motion
            stability += record.stability
            texture += record.texture
            topBrightness += record.topBrightness
            bottomBrightness += record.bottomBrightness
            motionSquares += record.motion * record.motion
        }

        fun toScene(
            id: Long,
            projectId: Long,
            assetId: Long,
            index: Int,
            startMs: Long,
            endMs: Long,
            histogram: FloatArray,
            averageHue: Float,
        ): Scene? {
            if (count == 0) return null
            val n = count.toFloat()
            val avgBrightness = brightness / n
            val avgSharpness = sharpness / n
            val avgMotion = motion / n
            val avgStability = stability / n
            val avgTexture = texture / n
            val avgTop = topBrightness / n
            val avgBottom = bottomBrightness / n
            val meanMotion = avgMotion
            motionVariance = (motionSquares / n) - (meanMotion * meanMotion)

            val durationMs = endMs - startMs
            val framing = classifyFraming(avgTop, avgBottom, avgSharpness, avgStability, avgMotion)
            val issue = classifyIssue(
                brightness = avgBrightness,
                sharpness = avgSharpness,
                motion = avgMotion,
                stability = avgStability,
                texture = avgTexture,
                framing = framing,
                durationMs = durationMs,
            )

            val quality = computeQuality(
                brightness = avgBrightness,
                sharpness = avgSharpness,
                stability = avgStability,
                motion = avgMotion,
                issue = issue,
            )

            return Scene(
                id = id,
                projectId = projectId,
                assetId = assetId,
                index = index,
                startMs = startMs,
                endMs = endMs,
                brightness = avgBrightness.coerceIn(0f, 1f),
                sharpness = avgSharpness.coerceIn(0f, 1f),
                motion = avgMotion.coerceIn(0f, 1f),
                stability = avgStability.coerceIn(0f, 1f),
                saturation = (saturation / n).coerceIn(0f, 1f),
                quality = quality,
                dominantHue = averageHue.coerceIn(0f, 1f),
                framing = framing,
                issue = issue,
            )
        }

        private fun classifyFraming(
            top: Float,
            bottom: Float,
            sharpness: Float,
            stability: Float,
            motion: Float,
        ): Framing = when {
            bottom > top * GROUND_BRIGHTNESS_RATIO && sharpness < GROUND_SHARPNESS_MAX -> Framing.GROUND
            top > bottom * SKY_BRIGHTNESS_RATIO && sharpness < SKY_SHARPNESS_MAX -> Framing.SKY
            motion > CLOSE_MOTION_THRESHOLD && stability < WIDE_STABILITY_MAX -> Framing.WIDE
            motion < STILL_MOTION_THRESHOLD && sharpness > CLOSE_SHARPNESS_MIN -> Framing.CLOSE_UP
            else -> Framing.MEDIUM
        }

        private fun classifyIssue(
            brightness: Float,
            sharpness: Float,
            motion: Float,
            stability: Float,
            texture: Float,
            framing: Framing,
            durationMs: Long,
        ): SceneIssue = when {
            brightness < LENS_COVERED_BRIGHTNESS && sharpness < LENS_COVERED_SHARPNESS ->
                SceneIssue.LENS_COVERED

            brightness < DARK_BRIGHTNESS -> SceneIssue.TOO_DARK
            brightness > OVEREXPOSED_BRIGHTNESS -> SceneIssue.OVEREXPOSED
            sharpness < BLURRY_SHARPNESS -> SceneIssue.BLURRY
            framing == Framing.GROUND -> SceneIssue.GROUND_SHOT
            motion < STATIC_MOTION && durationMs >= STATIC_MIN_DURATION_MS -> SceneIssue.STATIC
            stability < SHAKY_STABILITY && motion > SHAKY_MOTION -> SceneIssue.SHAKY
            texture < EMPTY_TEXTURE && motion < EMPTY_MOTION -> SceneIssue.EMPTY
            else -> SceneIssue.NONE
        }

        private fun computeQuality(
            brightness: Float,
            sharpness: Float,
            stability: Float,
            motion: Float,
            issue: SceneIssue,
        ): Float {
            if (issue.isHardFail) return 0f
            // A well exposed, sharp, steady but not frozen shot scores highest.
            val exposure = 1f - (abs(brightness - IDEAL_BRIGHTNESS) / IDEAL_BRIGHTNESS).coerceIn(0f, 1f)
            val motionSweetSpot = 1f - (abs(motion - IDEAL_MOTION) / IDEAL_MOTION).coerceIn(0f, 1f)
            val raw = exposure * 0.34f + sharpness * 0.34f + stability * 0.18f + motionSweetSpot * 0.14f
            return (raw * (1f - issue.severity * 0.5f)).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val HISTOGRAM_CUT_THRESHOLD = 0.46f
        const val BRIGHTNESS_CUT_THRESHOLD = 0.32f

        const val GROUND_BRIGHTNESS_RATIO = 1.55f
        const val GROUND_SHARPNESS_MAX = 0.30f
        const val SKY_BRIGHTNESS_RATIO = 1.7f
        const val SKY_SHARPNESS_MAX = 0.22f
        const val CLOSE_MOTION_THRESHOLD = 0.28f
        const val WIDE_STABILITY_MAX = 0.55f
        const val STILL_MOTION_THRESHOLD = 0.05f
        const val CLOSE_SHARPNESS_MIN = 0.45f

        const val LENS_COVERED_BRIGHTNESS = 0.055f
        const val LENS_COVERED_SHARPNESS = 0.10f
        const val DARK_BRIGHTNESS = 0.13f
        const val OVEREXPOSED_BRIGHTNESS = 0.94f
        const val BLURRY_SHARPNESS = 0.13f
        const val STATIC_MOTION = 0.018f
        const val STATIC_MIN_DURATION_MS = 4_500L
        const val SHAKY_STABILITY = 0.24f
        const val SHAKY_MOTION = 0.24f
        const val EMPTY_TEXTURE = 0.32f
        const val EMPTY_MOTION = 0.06f

        const val IDEAL_BRIGHTNESS = 0.48f
        const val IDEAL_MOTION = 0.16f

        const val DUPLICATE_HUE_TOLERANCE = 0.035f
        const val DUPLICATE_QUALITY_TOLERANCE = 0.06f
        const val DUPLICATE_DURATION_TOLERANCE_MS = 900L
        val MIN_DUPLICATE_LENGTH_MS = 1_200L
        val MAX_DUPLICATE_LENGTH_MS = 25_000L
    }
}
