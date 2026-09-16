package com.aivideostudio.ai.subtitle

import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.ClipOrigin
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.TranscriptSegment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Subtitles are mapped from source time onto the rendered timeline, so the
 * synchronisation maths is the risky part and is covered explicitly.
 */
class SubtitleBuilderTest {

    private val builder = SubtitleBuilder()

    private fun timelineClip(
        id: Long = 0L,
        orderIndex: Int,
        assetId: Long = 1L,
        sourceStartMs: Long,
        sourceEndMs: Long,
        speed: Float = 1f,
    ) = TimelineClip(
        id = id,
        clipId = 1L,
        projectId = 1L,
        assetId = assetId,
        orderIndex = orderIndex,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        playbackSpeed = speed,
        origin = ClipOrigin.AI,
    )

    private fun segment(
        index: Int = 0,
        assetId: Long = 1L,
        startMs: Long,
        endMs: Long,
        text: String,
        confidence: Float = 0.9f,
    ) = TranscriptSegment(
        transcriptId = 1L,
        projectId = 1L,
        assetId = assetId,
        index = index,
        startMs = startMs,
        endMs = endMs,
        text = text,
        confidence = confidence,
    )

    @Test
    fun `no transcript produces no captions`() {
        val timeline = listOf(timelineClip(orderIndex = 0, sourceStartMs = 0, sourceEndMs = 10_000))
        assertThat(builder.build(timeline, emptyList())).isEmpty()
    }

    @Test
    fun `captions are placed on the rendered timeline, not source time`() {
        // Clip two starts at 60s in the source but at 0s in the output, because
        // the first clip is only 10 seconds long.
        val timeline = listOf(
            timelineClip(id = 1, orderIndex = 0, sourceStartMs = 0, sourceEndMs = 10_000),
            timelineClip(id = 2, orderIndex = 1, sourceStartMs = 60_000, sourceEndMs = 80_000),
        )
        val transcript = listOf(
            segment(index = 0, startMs = 61_000, endMs = 63_500, text = "This is the second clip"),
        )

        val captions = builder.build(timeline, transcript)

        assertThat(captions).hasSize(1)
        val caption = captions.first()
        assertThat(caption.startMs).isAtLeast(10_000L)
        assertThat(caption.startMs).isLessThan(13_000L)
    }

    @Test
    fun `captions from different assets do not bleed into each other`() {
        val timeline = listOf(
            timelineClip(id = 1, orderIndex = 0, assetId = 1L, sourceStartMs = 0, sourceEndMs = 10_000),
            timelineClip(id = 2, orderIndex = 1, assetId = 2L, sourceStartMs = 0, sourceEndMs = 10_000),
        )
        val transcript = listOf(
            segment(index = 0, assetId = 1L, startMs = 1_000, endMs = 5_000, text = "First camera speaking"),
            segment(index = 1, assetId = 2L, startMs = 1_000, endMs = 5_000, text = "Second camera speaking"),
        )

        val captions = builder.build(timeline, transcript)

        assertThat(captions).hasSize(2)
        val first = captions.first { it.timelineClipId == 1L }
        val second = captions.first { it.timelineClipId == 2L }
        assertThat(first.text).contains("First")
        assertThat(second.text).contains("Second")
        assertThat(second.startMs).isAtLeast(10_000L)
    }

    @Test
    fun `long speech is split into readable chunks`() {
        val timeline = listOf(timelineClip(orderIndex = 0, sourceStartMs = 0, sourceEndMs = 20_000))
        val transcript = listOf(
            segment(
                startMs = 0,
                endMs = 18_000,
                text = "Today we are going to explore one of the most beautiful places I have " +
                    "ever visited, and I promise you it is worth the walk to get there.",
            ),
        )

        val captions = builder.build(timeline, transcript)

        assertThat(captions.size).isGreaterThan(1)
        captions.forEach { caption ->
            assertThat(caption.text.length).isAtMost(60)
            assertThat(caption.endMs).isGreaterThan(caption.startMs)
        }
    }

    @Test
    fun `captions never overlap`() {
        val timeline = listOf(timelineClip(orderIndex = 0, sourceStartMs = 0, sourceEndMs = 60_000))
        val transcript = (0 until 6).map { index ->
            segment(
                index = index,
                startMs = index * 8_000L,
                endMs = index * 8_000L + 6_000L,
                text = "Sentence number $index with a reasonable amount of words",
            )
        }

        val captions = builder.build(timeline, transcript)

        captions.zipWithNext().forEach { (first, second) ->
            assertThat(first.endMs).isAtMost(second.startMs)
        }
    }

    @Test
    fun `speed changes are reflected in caption timing`() {
        val normal = listOf(timelineClip(orderIndex = 0, sourceStartMs = 0, sourceEndMs = 20_000, speed = 1f))
        val fast = listOf(timelineClip(orderIndex = 0, sourceStartMs = 0, sourceEndMs = 20_000, speed = 2f))
        val transcript = listOf(segment(startMs = 10_000, endMs = 12_000, text = "Halfway through the shot"))

        val normalCaption = builder.build(normal, transcript).first()
        val fastCaption = builder.build(fast, transcript).first()

        assertThat(fastCaption.startMs).isLessThan(normalCaption.startMs)
    }

    @Test
    fun `emphasis words are extracted for the dynamic style`() {
        val timeline = listOf(timelineClip(orderIndex = 0, sourceStartMs = 0, sourceEndMs = 10_000))
        val transcript = listOf(
            segment(startMs = 0, endMs = 8_000, text = "This view is absolutely incredible"),
        )

        val captions = builder.build(
            timeline,
            transcript,
            SubtitleBuilder.Options(style = SubtitleStyle.DYNAMIC),
        )

        assertThat(captions.first().emphasisWords).contains("INCREDIBLE")
    }

    @Test
    fun `captions are raised when a face occupies the lower frame`() {
        val captions = listOf(
            Caption(
                projectId = 1L,
                clipId = 1L,
                index = 0,
                startMs = 0,
                endMs = 3_000,
                text = "Hello there",
                positionY = 0.78f,
            ),
        )
        val faces = listOf(SubtitleBuilder.FaceRegion(0L, 5_000L, centerY = 0.7f))

        val result = builder.applyFaceAvoidance(captions, faces)

        assertThat(result.first().positionY).isLessThan(0.78f)
    }

    @Test
    fun `captions are left alone when nothing is in the lower frame`() {
        val captions = listOf(
            Caption(
                projectId = 1L,
                clipId = 1L,
                index = 0,
                startMs = 0,
                endMs = 3_000,
                text = "Hello there",
                positionY = 0.78f,
            ),
        )
        val faces = listOf(SubtitleBuilder.FaceRegion(0L, 5_000L, centerY = 0.2f))

        val result = builder.applyFaceAvoidance(captions, faces)

        assertThat(result.first().positionY).isWithin(0.001f).of(0.78f)
    }
}
