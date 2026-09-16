package com.aivideostudio.ai.crop

import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.media.model.CropDecision
import com.aivideostudio.media.model.FaceSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cropping is where wrong maths is most visible, so the geometry is pinned down
 * here: a portrait crop of landscape footage must never exceed the frame, and a
 * subject off to one side must pull the window with it.
 */
class SmartCropperTest {

    private val cropper = SmartCropper()

    @Test
    fun `landscape source cropped to portrait keeps a valid window`() {
        val rect = cropper.toCropRect(
            decision = CropDecision(centerX = 0.5f, centerY = 0.5f, scale = 1f),
            sourceWidth = 3840,
            sourceHeight = 2160,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
        )

        assertThat(rect.left).isGreaterThan(-1.001f)
        assertThat(rect.right).isLessThan(1.001f)
        assertThat(rect.bottom).isGreaterThan(-1.001f)
        assertThat(rect.top).isLessThan(1.001f)
        assertThat(rect.width).isGreaterThan(0f)
        assertThat(rect.height).isGreaterThan(0f)
    }

    @Test
    fun `portrait crop of landscape footage is narrower than the frame`() {
        val rect = cropper.toCropRect(
            decision = CropDecision(centerX = 0.5f, centerY = 0.5f, scale = 1f),
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
        )
        assertThat(rect.width).isLessThan(2f)
    }

    @Test
    fun `rotation swaps the effective source orientation`() {
        val unrotated = cropper.orientedSize(1920, 1080, 0)
        val rotated = cropper.orientedSize(1920, 1080, 90)

        assertThat(unrotated).isEqualTo(1920 to 1080)
        assertThat(rotated).isEqualTo(1080 to 1920)
    }

    @Test
    fun `a subject on the right moves the crop window right`() {
        val faces = listOf(
            FaceSample(timeMs = 1_000, coverage = 0.15f, centerX = 0.85f, centerY = 0.5f, faceCount = 1),
        )
        val decision = cropper.decide(
            sourceWidth = 3840,
            sourceHeight = 2160,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
            faceSamples = faces,
            startMs = 0,
            endMs = 5_000,
        )

        assertThat(decision.centerX).isGreaterThan(0.5f)
        assertThat(decision.confidence).isGreaterThan(0f)
    }

    @Test
    fun `with no faces the crop stays centred and reports no confidence`() {
        val decision = cropper.decide(
            sourceWidth = 3840,
            sourceHeight = 2160,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
            faceSamples = emptyList(),
            startMs = 0,
            endMs = 5_000,
        )

        assertThat(decision.centerX).isWithin(0.001f).of(0.5f)
        assertThat(decision.centerY).isWithin(0.001f).of(0.5f)
        assertThat(decision.confidence).isWithin(0.001f).of(0f)
    }

    @Test
    fun `faces outside the requested range are ignored`() {
        val faces = listOf(
            FaceSample(timeMs = 50_000, coverage = 0.2f, centerX = 0.9f, centerY = 0.5f, faceCount = 1),
        )
        val decision = cropper.decide(
            sourceWidth = 3840,
            sourceHeight = 2160,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
            faceSamples = faces,
            startMs = 0,
            endMs = 5_000,
        )

        assertThat(decision.centerX).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `crop centre is clamped so the window never leaves the frame`() {
        val faces = listOf(
            FaceSample(timeMs = 1_000, coverage = 0.4f, centerX = 1.0f, centerY = 0.0f, faceCount = 1),
        )
        val decision = cropper.decide(
            sourceWidth = 3840,
            sourceHeight = 2160,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
            faceSamples = faces,
            startMs = 0,
            endMs = 5_000,
        )

        assertThat(decision.centerX).isLessThan(1f)
        assertThat(decision.centerY).isGreaterThan(0f)
    }

    @Test
    fun `zoom reduces the size of the kept window`() {
        val wide = cropper.toCropRect(
            decision = CropDecision(scale = 1f),
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
        )
        val zoomed = cropper.toCropRect(
            decision = CropDecision(scale = 2f),
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
        )

        assertThat(zoomed.width).isLessThan(wide.width)
        assertThat(zoomed.height).isLessThan(wide.height)
    }

    @Test
    fun `matching aspect ratios need no crop at all`() {
        val mode = cropper.strategyFor(
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            targetAspect = AspectRatio.LANDSCAPE_16_9,
            hasFaceSamples = false,
            requested = CropMode.SMART,
        )
        assertThat(mode).isEqualTo(CropMode.FIT)
    }

    @Test
    fun `smart crop is only chosen when faces were actually found`() {
        val withFaces = cropper.strategyFor(
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
            hasFaceSamples = true,
            requested = CropMode.SMART,
        )
        val withoutFaces = cropper.strategyFor(
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            targetAspect = AspectRatio.VERTICAL_9_16,
            hasFaceSamples = false,
            requested = CropMode.SMART,
        )

        assertThat(withFaces).isEqualTo(CropMode.SMART)
        assertThat(withoutFaces).isEqualTo(CropMode.CENTER)
    }
}
