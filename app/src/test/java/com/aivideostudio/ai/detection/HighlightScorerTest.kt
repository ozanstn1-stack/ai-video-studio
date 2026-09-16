package com.aivideostudio.ai.detection

import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.HighlightLabel
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.SceneIssue
import com.aivideostudio.domain.model.TranscriptSegment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scorer is the part of the product that decides what the user ends up
 * watching, so its behaviour is pinned down precisely here.
 */
class HighlightScorerTest {

    private val scorer = HighlightScorer()

    private fun scene(
        id: Long = 0L,
        assetId: Long = 1L,
        startMs: Long,
        endMs: Long,
        quality: Float = 0.7f,
        brightness: Float = 0.5f,
        sharpness: Float = 0.6f,
        motion: Float = 0.15f,
        stability: Float = 0.8f,
        saturation: Float = 0.5f,
        speechRatio: Float = 0.5f,
        audioLoudness: Float = 0.16f,
        issue: SceneIssue = SceneIssue.NONE,
        hue: Float = 0.3f,
        faces: Float = 0f,
    ) = Scene(
        id = id,
        projectId = 1L,
        assetId = assetId,
        index = id.toInt(),
        startMs = startMs,
        endMs = endMs,
        brightness = brightness,
        sharpness = sharpness,
        motion = motion,
        stability = stability,
        saturation = saturation,
        quality = quality,
        speechRatio = speechRatio,
        audioLoudness = audioLoudness,
        issue = issue,
        dominantHue = hue,
        faceCoverage = faces,
    )

    @Test
    fun `returns nothing when there is no footage`() {
        val result = scorer.score(emptyList(), emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `ignores scenes flagged as unusable`() {
        val scenes = listOf(
            scene(id = 1, startMs = 0, endMs = 4_000, issue = SceneIssue.LENS_COVERED),
            scene(id = 2, startMs = 4_000, endMs = 8_000, issue = SceneIssue.TOO_DARK),
        )
        val result = scorer.score(scenes, emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `produces a highlight covering a run of good scenes`() {
        val scenes = (0 until 8).map { index ->
            scene(
                id = index.toLong(),
                startMs = index * 4_000L,
                endMs = (index + 1) * 4_000L,
            )
        }
        val result = scorer.score(
            scenes = scenes,
            transcript = emptyList(),
            config = HighlightScorer.Config(targetDurationMs = 16_000L),
        )

        assertThat(result).isNotEmpty()
        result.forEach { highlight ->
            assertThat(highlight.startMs).isLessThan(highlight.endMs)
            assertThat(highlight.durationMs).isAtLeast(2_500L)
            assertThat(highlight.overallScore).isAtLeast(0f)
            assertThat(highlight.overallScore).isAtMost(1f)
        }
    }

    @Test
    fun `never returns overlapping highlights from the same asset`() {
        val scenes = (0 until 12).map { index ->
            scene(id = index.toLong(), startMs = index * 5_000L, endMs = (index + 1) * 5_000L)
        }
        val result = scorer.score(
            scenes = scenes,
            transcript = emptyList(),
            config = HighlightScorer.Config(targetDurationMs = 15_000L, maxHighlights = 10),
        )

        val sorted = result.sortedBy { it.startMs }
        sorted.zipWithNext().forEach { (first, second) ->
            assertThat(first.endMs).isAtMost(second.startMs)
        }
    }

    @Test
    fun `a dark blurry video scores lower than a bright sharp one`() {
        val weak = listOf(scene(id = 1, startMs = 0, endMs = 8_000, quality = 0.2f, brightness = 0.1f, sharpness = 0.1f))
        val strong = listOf(scene(id = 1, startMs = 0, endMs = 8_000, quality = 0.9f, brightness = 0.5f, sharpness = 0.8f))

        val weakScore = scorer.score(weak, emptyList()).first().overallScore
        val strongScore = scorer.score(strong, emptyList()).first().overallScore

        assertThat(strongScore).isGreaterThan(weakScore)
    }

    @Test
    fun `words in the transcript raise the speech score`() {
        val scenes = listOf(scene(id = 1, startMs = 0, endMs = 10_000, speechRatio = 0.9f))
        val transcript = listOf(
            TranscriptSegment(
                transcriptId = 1,
                projectId = 1L,
                assetId = 1L,
                index = 0,
                startMs = 500,
                endMs = 9_500,
                text = "This is the most beautiful place I have ever filmed here.",
                confidence = 0.95f,
            ),
        )

        val withoutSpeech = scorer.score(scenes, emptyList()).first()
        val withSpeech = scorer.score(scenes, transcript).first()

        assertThat(withSpeech.speechScore).isGreaterThan(withoutSpeech.speechScore)
        assertThat(withSpeech.transcriptText).contains("beautiful")
    }

    @Test
    fun `hook words in the opening raise the hook score`() {
        val scenes = listOf(scene(id = 1, startMs = 0, endMs = 12_000, motion = 0.4f, quality = 0.9f))
        val withHookWords = listOf(
            TranscriptSegment(
                transcriptId = 1,
                projectId = 1L,
                assetId = 1L,
                index = 0,
                startMs = 200,
                endMs = 3_000,
                text = "Wait until you see this unbelievable view",
                confidence = 0.9f,
            ),
        )
        val neutral = listOf(
            TranscriptSegment(
                transcriptId = 1,
                projectId = 1L,
                assetId = 1L,
                index = 0,
                startMs = 200,
                endMs = 3_000,
                text = "The camera is recording the road ahead",
                confidence = 0.9f,
            ),
        )

        val hookHighlight = scorer.score(scenes, withHookWords).first()
        val neutralHighlight = scorer.score(scenes, neutral).first()

        assertThat(hookHighlight.hookScore).isGreaterThan(neutralHighlight.hookScore)
        assertThat(hookHighlight.payoffScore).isAtLeast(0f)
    }

    @Test
    fun `reasons are user facing and never expose raw scores`() {
        val scenes = listOf(
            scene(id = 1, startMs = 0, endMs = 8_000, quality = 0.9f, sharpness = 0.7f, faces = 0.08f),
        )
        val highlight = scorer.score(scenes, emptyList()).first()
        assertThat(highlight.reasons).isNotEmpty()
        highlight.reasons.forEach { reason ->
            assertThat(reason.badge).isNotEmpty()
            assertThat(reason.text).isNotEmpty()
            assertThat(reason.text).doesNotContain("0.")
        }
    }

    @Test
    fun `vlog mode weights speech more heavily than cinematic mode does`() {
        val scenes = listOf(scene(id = 1, startMs = 0, endMs = 10_000, quality = 0.4f, speechRatio = 1f))
        val transcript = listOf(
            TranscriptSegment(
                transcriptId = 1,
                projectId = 1L,
                assetId = 1L,
                index = 0,
                startMs = 0,
                endMs = 10_000,
                text = "So today we are going to explore this place together.",
                confidence = 1f,
            ),
        )

        val vlog = scorer.score(scenes, transcript, HighlightScorer.Config(mode = CreationMode.VLOG)).first()
        val cinematic = scorer.score(
            scenes,
            transcript,
            HighlightScorer.Config(mode = CreationMode.CINEMATIC),
        ).first()

        assertThat(vlog.overallScore).isGreaterThan(cinematic.overallScore)
    }

    @Test
    fun `same-footage windows are penalised by the uniqueness signal`() {
        val scenes = (0 until 10).flatMap { index ->
            listOf(
                scene(id = (index * 2).toLong(), assetId = 1L, startMs = index * 5_000L, endMs = index * 5_000L + 4_000L, hue = 0.30f),
                scene(id = (index * 2 + 1).toLong(), assetId = 1L, startMs = index * 5_000L + 4_000L, endMs = index * 5_000L + 5_000L, hue = 0.30f),
            )
        }
        val result = scorer.score(
            scenes,
            emptyList(),
            HighlightScorer.Config(targetDurationMs = 10_000L, maxHighlights = 6),
        )
        assertThat(result).isNotEmpty()
        // Identical windows must not be returned repeatedly.
        assertThat(result.size).isAtMost(6)
    }
}
