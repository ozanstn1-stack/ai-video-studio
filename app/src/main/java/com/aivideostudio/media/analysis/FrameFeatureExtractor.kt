package com.aivideostudio.media.analysis

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reduces a frame to cheap, comparable statistics.
 *
 * Everything is computed on a small luma grid (a 220px frame is resampled to
 * [GRID_WIDTH]) which keeps a full analysis pass over an hour of footage in the
 * low seconds rather than minutes.
 */
@Singleton
class FrameFeatureExtractor @Inject constructor() {

    /** A compact luma representation plus the per-column profile used for shake. */
    class Grid(
        val width: Int,
        val height: Int,
        val luma: FloatArray,
        val columnMeans: FloatArray,
        val topBrightness: Float,
        val bottomBrightness: Float,
    ) {
        fun centroidX(): Float {
            var weighted = 0f
            var total = 0f
            columnMeans.forEachIndexed { index, value ->
                weighted += value * index
                total += value
            }
            return if (total <= 0f) 0.5f else (weighted / total) / (width - 1).coerceAtLeast(1)
        }
    }

    class Stats(
        val timeMs: Long,
        val grid: Grid,
        val brightness: Float,
        val sharpness: Float,
        val saturation: Float,
        val hue: Float,
        val histogram: FloatArray,
    )

    fun analyse(timeMs: Long, bitmap: Bitmap): Stats {
        val grid = toGrid(bitmap)
        val sharpness = laplacianEnergy(grid)
        val (saturation, histogram, dominantHue) = colourProfile(bitmap)

        return Stats(
            timeMs = timeMs,
            grid = grid,
            brightness = grid.luma.average().toFloat().coerceIn(0f, 1f),
            sharpness = sharpness,
            saturation = saturation,
            hue = dominantHue,
            histogram = histogram,
        )
    }

    /** Mean absolute luma difference, normalised so 1f is a hard cut. */
    fun motionBetween(previous: Grid, current: Grid): Float {
        if (previous.luma.size != current.luma.size) return 0f
        var sum = 0f
        for (index in previous.luma.indices) {
            sum += abs(current.luma[index] - previous.luma[index])
        }
        val mean = sum / previous.luma.size
        return (mean / MOTION_FULL_SCALE).coerceIn(0f, 1f)
    }

    /**
     * Shake is a lateral shift of the frame's brightness centroid. A deliberate
     * pan and a shaky hand both move it, but a shaky hand moves it far more per
     * frame relative to the total motion.
     */
    fun shakeBetween(previous: Grid, current: Grid, motion: Float): Float {
        if (motion <= 0.001f) return 0f
        val shift = abs(current.centroidX() - previous.centroidX())
        return (shift / SHAKE_FULL_SCALE).coerceIn(0f, 1f)
    }

    fun histogramDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (index in a.indices) sum += abs(a[index] - b[index])
        return (sum / 2f).coerceIn(0f, 1f)
    }

    private fun toGrid(bitmap: Bitmap): Grid {
        val width = minOf(GRID_WIDTH, max(8, bitmap.width))
        val height = max(8, (width * bitmap.height.toFloat() / bitmap.width.toFloat()).toInt())
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val ownsBitmap = scaled !== bitmap
        if (ownsBitmap) scaled.recycle()

        val luma = FloatArray(width * height)
        val columnMeans = FloatArray(width)
        val columnSums = FloatArray(width)
        var topSum = 0f
        var topCount = 0
        var bottomSum = 0f
        var bottomCount = 0
        val third = height / 3

        for (row in 0 until height) {
            for (column in 0 until width) {
                val pixel = pixels[row * width + column]
                val value = (0.2126f * Color.red(pixel) +
                    0.7152f * Color.green(pixel) +
                    0.0722f * Color.blue(pixel)) / 255f
                luma[row * width + column] = value
                columnSums[column] += value
                when {
                    row < third -> {
                        topSum += value
                        topCount++
                    }

                    row >= height - third -> {
                        bottomSum += value
                        bottomCount++
                    }
                }
            }
        }

        for (column in 0 until width) columnMeans[column] = columnSums[column] / height

        return Grid(
            width = width,
            height = height,
            luma = luma,
            columnMeans = columnMeans,
            topBrightness = if (topCount == 0) 0f else topSum / topCount,
            bottomBrightness = if (bottomCount == 0) 0f else bottomSum / bottomCount,
        )
    }

    private fun laplacianEnergy(grid: Grid): Float {
        val width = grid.width
        val height = grid.height
        if (width < 3 || height < 3) return 0f
        var sum = 0f
        var count = 0
        for (row in 1 until height - 1) {
            for (column in 1 until width - 1) {
                val center = grid.luma[row * width + column]
                val laplacian = 4f * center -
                    grid.luma[(row - 1) * width + column] -
                    grid.luma[(row + 1) * width + column] -
                    grid.luma[row * width + column - 1] -
                    grid.luma[row * width + column + 1]
                sum += abs(laplacian)
                count++
            }
        }
        if (count == 0) return 0f
        return ((sum / count) / SHARPNESS_FULL_SCALE).coerceIn(0f, 1f)
    }

    private fun colourProfile(bitmap: Bitmap): Triple<Float, FloatArray, Float> {
        val width = minOf(COLOUR_GRID_WIDTH, max(8, bitmap.width))
        val height = max(8, (width * bitmap.height.toFloat() / bitmap.width.toFloat()).toInt())
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        val histogram = FloatArray(HISTOGRAM_BINS)
        var saturationSum = 0f
        val hsv = FloatArray(3)

        pixels.forEach { pixel ->
            Color.colorToHSV(pixel, hsv)
            val saturation = hsv[1]
            saturationSum += saturation
            val bin = ((hsv[0] / 360f) * HISTOGRAM_BINS).toInt().coerceIn(0, HISTOGRAM_BINS - 1)
            histogram[bin] += saturation + 0.05f
        }

        val total = histogram.sum().takeIf { it > 0f } ?: 1f
        for (index in histogram.indices) histogram[index] /= total

        var dominantBin = 0
        for (index in histogram.indices) if (histogram[index] > histogram[dominantBin]) dominantBin = index
        val dominantHue = (dominantBin + 0.5f) / HISTOGRAM_BINS

        return Triple(
            (saturationSum / pixels.size).coerceIn(0f, 1f),
            histogram,
            dominantHue,
        )
    }

    /** Colourfulness heuristic used together with sharpness to spot "empty" scenes. */
    fun textureLevel(stats: Stats): Float {
        val flatness = 1f - stats.sharpness
        val greyness = 1f - stats.saturation
        return (1f - sqrt(flatness * greyness)).coerceIn(0f, 1f)
    }

    companion object {
        const val GRID_WIDTH = 64
        const val COLOUR_GRID_WIDTH = 32
        const val HISTOGRAM_BINS = 16
        private const val MOTION_FULL_SCALE = 0.12f
        private const val SHAKE_FULL_SCALE = 0.10f
        private const val SHARPNESS_FULL_SCALE = 0.055f
    }
}
