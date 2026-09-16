package com.aivideostudio.ai.improve

import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.ClipOrigin
import com.aivideostudio.domain.model.SuggestionCategory
import com.aivideostudio.domain.model.TimelineClip
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The editor's AI advice has to be trustworthy: every suggestion here is
 * derived from the edit state, so these tests pin down when it fires and when it
 * stays quiet.
 */
class EditorSuggestionEngineTest {

    private val engine = EditorSuggestionEngine()

    private fun clip(
        id: Long,
        orderIndex: Int,
        startMs: Long,
        endMs: Long,
    ) = TimelineClip(
        id = id,
        clipId = 1L,
        projectId = 1L,
        assetId = 1L,
        orderIndex = orderIndex,
        sourceStartMs = startMs,
        sourceEndMs = endMs,
        origin = ClipOrigin.AI,
    )

    private fun caption(id: Long, startMs: Long, endMs: Long, text: String, positionY: Float = 0.78f) =
        Caption(
            id = id,
            projectId = 1L,
            clipId = 1L,
            index = id.toInt(),
            startMs = startMs,
            endMs = endMs,
            text = text,
            positionY = positionY,
        )

    @Test
    fun `a well formed short produces no pacing complaints`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(
                    clip(1, 0, 0, 3_000),
                    clip(2, 1, 10_000, 16_000),
                    clip(3, 2, 20_000, 27_000),
                ),
                captions = listOf(caption(1, 0, 2_500, "Short and readable", positionY = 0.68f)),
                audio = AudioBed(projectId = 1L, clipId = 1L, originalVolume = 0.9f),
                hookText = "Look at this",
                targetDurationMs = 30_000L,
            ),
        )

        assertThat(suggestions.map { it.category }).doesNotContain(SuggestionCategory.PACING)
        assertThat(suggestions.map { it.category }).doesNotContain(SuggestionCategory.HOOK)
    }

    @Test
    fun `a long opening shot is flagged with a concrete new end time`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 9_000), clip(2, 1, 20_000, 30_000)),
                captions = emptyList(),
                audio = null,
                hookText = null,
                targetDurationMs = 30_000L,
            ),
        )

        val opening = suggestions.first { it.id == "opening_length" }
        val action = opening.action as EditorSuggestionEngine.Action.ShortenClip
        assertThat(action.clipId).isEqualTo(1L)
        assertThat(action.newEndMs).isLessThan(9_000L)
    }

    @Test
    fun `a short that overruns its target is trimmed`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 8_000), clip(2, 1, 20_000, 44_000)),
                captions = emptyList(),
                audio = null,
                hookText = "Hook",
                targetDurationMs = 30_000L,
            ),
        )

        val overrun = suggestions.firstOrNull { it.id == "trailing_overrun" }
        assertThat(overrun).isNotNull()
        val action = overrun!!.action as EditorSuggestionEngine.Action.TrimTrailingClip
        assertThat(action.newEndMs).isLessThan(45_000L)
    }

    @Test
    fun `subtitles sitting too low are raised`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 5_000)),
                captions = listOf(
                    caption(1, 0, 2_000, "Low caption", positionY = 0.85f),
                    caption(2, 2_000, 4_000, "Also low", positionY = 0.83f),
                ),
                audio = null,
                hookText = "Hook",
                targetDurationMs = 30_000L,
            ),
        )

        val position = suggestions.first { it.id == "caption_position" }
        val action = position.action as EditorSuggestionEngine.Action.RaiseCaptions
        assertThat(action.positionY).isWithin(0.001f).of(0.68f)
    }

    @Test
    fun `a missing hook is filled from speech in the first seconds`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 8_000)),
                captions = listOf(
                    caption(1, 100, 2_500, "Wait until you see this view"),
                ),
                audio = null,
                hookText = null,
                targetDurationMs = 30_000L,
            ),
        )

        val hook = suggestions.first { it.id == "missing_hook" }
        val action = hook.action as EditorSuggestionEngine.Action.SetHook
        assertThat(action.text).contains("Wait until you see")
    }

    @Test
    fun `loud original audio is brought down to a safe level`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 5_000)),
                captions = emptyList(),
                audio = AudioBed(projectId = 1L, clipId = 1L, originalVolume = 1.4f),
                hookText = "Hook",
                targetDurationMs = 30_000L,
            ),
        )

        val audio = suggestions.first { it.id == "audio_level" }
        val action = audio.action as EditorSuggestionEngine.Action.AdjustVolume
        assertThat(action.volume).isLessThan(1f)
    }

    @Test
    fun `muted original audio is never adjusted`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 5_000)),
                captions = emptyList(),
                audio = AudioBed(
                    projectId = 1L,
                    clipId = 1L,
                    originalVolume = 1.4f,
                    originalAudioMuted = true,
                ),
                hookText = "Hook",
                targetDurationMs = 30_000L,
            ),
        )

        assertThat(suggestions.map { it.id }).doesNotContain("audio_level")
    }

    @Test
    fun `every suggestion carries a readable explanation`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(
                    clip(1, 0, 0, 12_000),
                    clip(2, 1, 20_000, 21_000),
                    clip(3, 2, 30_000, 50_000),
                ),
                captions = listOf(caption(1, 0, 5_000, "A very long caption that will not fit on a phone screen at all")),
                audio = AudioBed(projectId = 1L, clipId = 1L, originalVolume = 1.3f),
                hookText = null,
                targetDurationMs = 30_000L,
            ),
        )

        assertThat(suggestions).isNotEmpty()
        suggestions.forEach { suggestion ->
            assertThat(suggestion.title).isNotEmpty()
            assertThat(suggestion.detail.length).isGreaterThan(15)
            assertThat(suggestion.category.displayName).isNotEmpty()
        }
    }

    @Test
    fun `static endings are proposed for removal`() {
        val suggestions = engine.suggest(
            EditorSuggestionEngine.Input(
                timeline = listOf(clip(1, 0, 0, 5_000), clip(2, 1, 20_000, 32_000)),
                captions = emptyList(),
                audio = null,
                hookText = "Hook",
                targetDurationMs = 60_000L,
            ),
        )

        assertThat(suggestions.map { it.id }).contains("static_tail")
    }
}
