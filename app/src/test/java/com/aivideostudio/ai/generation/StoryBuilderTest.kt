package com.aivideostudio.ai.generation

import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.HighlightLabel
import com.aivideostudio.domain.model.StoryRole
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The generation step is what turns scores into something watchable, so the
 * structural guarantees of a Short are asserted here.
 */
class StoryBuilderTest {

    private val builder = StoryBuilder()

    private fun highlight(
        id: Long,
        assetId: Long = 1L,
        startMs: Long,
        endMs: Long,
        overall: Float = 0.8f,
        hook: Float = 0.5f,
        payoff: Float = 0.5f,
        speech: Float = 0.5f,
        label: HighlightLabel = HighlightLabel.MOMENT,
        transcript: String? = null,
    ) = Highlight(
        id = id,
        projectId = 1L,
        assetId = assetId,
        startMs = startMs,
        endMs = endMs,
        visualScore = 0.7f,
        audioScore = 0.7f,
        speechScore = speech,
        motionScore = 0.5f,
        interestScore = 0.6f,
        uniquenessScore = 0.6f,
        storyScore = 0.5f,
        overallScore = overall,
        hookScore = hook,
        payoffScore = payoff,
        label = label,
        transcriptText = transcript,
    )

    @Test
    fun `no highlights produce no plans`() {
        assertThat(builder.build(emptyList(), 3, 30_000L)).isEmpty()
    }

    @Test
    fun `creates the requested number of shorts when enough material exists`() {
        val highlights = (0 until 12).map { index ->
            highlight(
                id = index.toLong(),
                startMs = index * 20_000L,
                endMs = index * 20_000L + 12_000L,
                overall = 0.9f - index * 0.01f,
            )
        }

        val plans = builder.build(highlights, shortCount = 3, targetDurationMs = 30_000L)

        assertThat(plans).hasSize(3)
        plans.forEach { plan ->
            assertThat(plan.durationMs).isGreaterThan(0L)
            assertThat(plan.segments).isNotEmpty()
        }
    }

    @Test
    fun `a plan is built from the story roles in order`() {
        val highlights = listOf(
            highlight(id = 1, startMs = 0, endMs = 12_000, hook = 0.9f),
            highlight(id = 2, startMs = 20_000, endMs = 32_000, hook = 0.1f, overall = 0.85f),
            highlight(id = 3, startMs = 40_000, endMs = 52_000, payoff = 0.95f, overall = 0.7f),
            highlight(id = 4, startMs = 60_000, endMs = 72_000, hook = 0.2f, overall = 0.65f),
        )

        val plan = builder.build(highlights, shortCount = 1, targetDurationMs = 40_000L).first()

        val roles = plan.segments.map { it.role }
        assertThat(roles).contains(StoryRole.CLIMAX)
        // Role order in the output always follows the story arc.
        val expectedOrder = listOf(
            StoryRole.HOOK,
            StoryRole.CONTEXT,
            StoryRole.CLIMAX,
            StoryRole.PAYOFF,
        )
        val indices = roles.map { expectedOrder.indexOf(it) }
        assertThat(indices).isInOrder()
    }

    @Test
    fun `a single highlight still produces one usable segment`() {
        val highlights = listOf(highlight(id = 1, startMs = 5_000, endMs = 40_000))

        val plans = builder.build(highlights, shortCount = 3, targetDurationMs = 30_000L)

        assertThat(plans).hasSize(1)
        assertThat(plans.first().segments).hasSize(1)
        assertThat(plans.first().durationMs).isAtMost(40_000L)
    }

    @Test
    fun `segments stay inside their source highlight`() {
        val highlights = (0 until 6).map { index ->
            highlight(id = index.toLong(), startMs = index * 30_000L, endMs = index * 30_000L + 15_000L)
        }

        val plans = builder.build(highlights, shortCount = 4, targetDurationMs = 30_000L)

        plans.forEach { plan ->
            plan.segments.forEach { segment ->
                val source = highlights.first { it.id == segment.highlightId }
                assertThat(segment.sourceStartMs).isAtLeast(source.startMs)
                assertThat(segment.sourceEndMs).isAtMost(source.endMs)
                assertThat(segment.sourceEndMs).isGreaterThan(segment.sourceStartMs)
            }
        }
    }

    @Test
    fun `timeline rows are ordered and carry the project and clip ids`() {
        val plan = builder.build(
            listOf(
                highlight(id = 1, startMs = 0, endMs = 12_000, hook = 0.9f),
                highlight(id = 2, startMs = 20_000, endMs = 32_000, overall = 0.85f),
            ),
            shortCount = 1,
            targetDurationMs = 24_000L,
        ).first()

        val timeline = builder.toTimeline(plan, clipId = 42L, projectId = 7L)

        assertThat(timeline).isNotEmpty()
        assertThat(timeline.map { it.orderIndex }).isInOrder()
        timeline.forEach { row ->
            assertThat(row.clipId).isEqualTo(42L)
            assertThat(row.projectId).isEqualTo(7L)
            assertThat(row.assetId).isEqualTo(1L)
        }
    }

    @Test
    fun `generated clip metadata reflects the plan`() {
        val plan = builder.build(
            listOf(highlight(id = 1, startMs = 1_000, endMs = 9_000, label = HighlightLabel.HOOK)),
            shortCount = 1,
            targetDurationMs = 8_000L,
        ).first()

        val clip = builder.toClip(plan, projectId = 5L, aspectRatio = AspectRatio.VERTICAL_9_16, createdAt = 1L)

        assertThat(clip.projectId).isEqualTo(5L)
        assertThat(clip.durationMs).isEqualTo(plan.durationMs)
        assertThat(clip.aspectRatio).isEqualTo(AspectRatio.VERTICAL_9_16)
        assertThat(clip.sourceAssetId).isEqualTo(1L)
    }

    @Test
    fun `rejected highlights are never used`() {
        val highlights = listOf(
            highlight(id = 1, startMs = 0, endMs = 12_000).copy(isRejected = true),
            highlight(id = 2, startMs = 20_000, endMs = 32_000),
        )

        val plans = builder.build(highlights, shortCount = 3, targetDurationMs = 20_000L)

        plans.forEach { plan ->
            plan.segments.forEach { segment ->
                assertThat(segment.highlightId).isEqualTo(2L)
            }
        }
    }
}
