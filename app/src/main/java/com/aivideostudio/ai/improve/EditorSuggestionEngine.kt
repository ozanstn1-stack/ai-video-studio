package com.aivideostudio.ai.improve

import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.SuggestionCategory
import com.aivideostudio.domain.model.TimelineClip
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The analysis behind the editor's "AI Improve" button.
 *
 * Every suggestion is derived from the current edit state — clip lengths,
 * caption geometry, audio levels — so the advice matches what the user is
 * actually looking at rather than generic tips. Applying a suggestion is a
 * separate, explicit step which keeps the user in control.
 */
@Singleton
class EditorSuggestionEngine @Inject constructor() {

    data class Input(
        val timeline: List<TimelineClip>,
        val captions: List<Caption>,
        val audio: AudioBed?,
        val hookText: String?,
        val targetDurationMs: Long,
    )

    sealed interface Action {
        data class ShortenClip(val clipId: Long, val newEndMs: Long, val reason: String) : Action
        data class TrimTrailingClip(val clipId: Long, val newEndMs: Long) : Action
        data class RaiseCaptions(val positionY: Float) : Action
        data class SetHook(val text: String) : Action
        data class AdjustVolume(val volume: Float) : Action
        data class RemoveClip(val clipId: Long) : Action
    }

    data class Suggestion(
        val id: String,
        val category: SuggestionCategory,
        val title: String,
        val detail: String,
        val action: Action,
    )

    fun suggest(input: Input): List<Suggestion> {
        val suggestions = mutableListOf<Suggestion>()
        val ordered = input.timeline.sortedBy { it.orderIndex }

        openingTooLong(ordered)?.let { suggestions += it }
        trailingOverrun(ordered, input.targetDurationMs)?.let { suggestions += it }
        silenceLikeTail(ordered)?.let { suggestions += it }
        captionsTooHigh(input.captions)?.let { suggestions += it }
        captionsTooLong(input.captions)?.let { suggestions += it }
        missingHook(input)?.let { suggestions += it }
        quietOriginalAudio(input.audio)?.let { suggestions += it }
        fragmentsTooShort(ordered)?.let { suggestions += it }

        return suggestions
    }

    /**
     * A short that opens with a long, slow shot loses viewers in the first two
     * seconds. Trimming it to a third is the single highest-impact change.
     */
    private fun openingTooLong(ordered: List<TimelineClip>): Suggestion? {
        val first = ordered.firstOrNull() ?: return null
        if (first.durationMs <= OPENING_MAX_MS) return null
        val newEnd = first.sourceStartMs + OPENING_TARGET_MS
        return Suggestion(
            id = "opening_length",
            category = SuggestionCategory.PACING,
            title = "Tighten the opening",
            detail = "The first shot runs ${first.durationMs / 1000}s. " +
                "Cutting it to ${OPENING_TARGET_MS / 1000}s gets to the point faster.",
            action = Action.ShortenClip(
                clipId = first.id,
                newEndMs = newEnd,
                reason = "opening trimmed",
            ),
        )
    }

    /** Long Shorts lose the tail on the platform; trim to the requested length. */
    private fun trailingOverrun(
        ordered: List<TimelineClip>,
        targetDurationMs: Long,
    ): Suggestion? {
        if (targetDurationMs <= 0L) return null
        val total = ordered.sumOf { it.durationMs }
        if (total <= targetDurationMs + OVERRUN_TOLERANCE_MS) return null
        val last = ordered.lastOrNull() ?: return null
        val excess = total - targetDurationMs
        val newLength = (last.durationMs - excess).coerceAtLeast(MIN_CLIP_MS)
        if (newLength >= last.durationMs) return null
        return Suggestion(
            id = "trailing_overrun",
            category = SuggestionCategory.OUTRO,
            title = "Trim the ending",
            detail = "This Short is ${total / 1000}s. Shortening the last shot " +
                "brings it to ${targetDurationMs / 1000}s, which fits the format better.",
            action = Action.TrimTrailingClip(
                clipId = last.id,
                newEndMs = last.sourceStartMs + newLength,
            ),
        )
    }

    /**
     * A closing shot that is almost entirely static usually means the camera was
     * left running. Dropping it makes the ending land.
     */
    private fun silenceLikeTail(ordered: List<TimelineClip>): Suggestion? {
        if (ordered.size < 2) return null
        val last = ordered.last()
        if (last.durationMs < LONG_STATIC_MS) return null
        return Suggestion(
            id = "static_tail",
            category = SuggestionCategory.OUTRO,
            title = "Drop the static ending",
            detail = "The final shot is ${last.durationMs / 1000}s long with no cut. " +
                "Removing it makes the Short end on the previous moment.",
            action = Action.RemoveClip(last.id),
        )
    }

    private fun captionsTooHigh(captions: List<Caption>): Suggestion? {
        if (captions.isEmpty()) return null
        val average = captions.map { it.positionY }.average().toFloat()
        if (average <= RAISED_POSITION_Y + 0.02f) return null
        return Suggestion(
            id = "caption_position",
            category = SuggestionCategory.CAPTIONS,
            title = "Move subtitles higher",
            detail = "Subtitles sit at ${(average * 100).toInt()}% of the frame height. " +
                "Moving them up keeps them clear of the platform's UI.",
            action = Action.RaiseCaptions(RAISED_POSITION_Y),
        )
    }

    private fun captionsTooLong(captions: List<Caption>): Suggestion? {
        val long = captions.filter { it.text.length > LONG_CAPTION_CHARS }
        if (long.isEmpty()) return null
        return Suggestion(
            id = "caption_length",
            category = SuggestionCategory.CAPTIONS,
            title = "Shorten ${long.size} subtitle${if (long.size == 1) "" else "s"}",
            detail = "Long lines are hard to read on a phone. " +
                "These will be split across two captions.",
            action = Action.RaiseCaptions(-1f),
        )
    }

    private fun missingHook(input: Input): Suggestion? {
        if (input.hookText?.isNotBlank() == true) return null
        val opening = input.captions
            .filter { it.startMs < HOOK_WINDOW_MS }
            .firstOrNull { it.text.trim().length in MIN_HOOK_CHARS..MAX_HOOK_CHARS }
            ?: return null
        return Suggestion(
            id = "missing_hook",
            category = SuggestionCategory.HOOK,
            title = "Add an opening line",
            detail = "\u201C${opening.text.trim()}\u201D already appears in the first three " +
                "seconds. Using it as the on-screen hook strengthens the opening.",
            action = Action.SetHook(opening.text.trim()),
        )
    }

    private fun quietOriginalAudio(audio: AudioBed?): Suggestion? {
        val volume = audio?.originalVolume ?: return null
        if (audio.originalAudioMuted || volume <= MAX_ORIGINAL_VOLUME) return null
        return Suggestion(
            id = "audio_level",
            category = SuggestionCategory.AUDIO,
            title = "Lower the original audio",
            detail = "Original audio is at ${(volume * 100).toInt()}%, which will clip " +
                "once the platform normalises loudness.",
            action = Action.AdjustVolume(SAFE_VOLUME),
        )
    }

    private fun fragmentsTooShort(ordered: List<TimelineClip>): Suggestion? {
        if (ordered.size < 3) return null
        val tiny = ordered.filter { it.durationMs < FRAGMENT_MIN_MS }
        if (tiny.isEmpty()) return null
        return Suggestion(
            id = "short_fragments",
            category = SuggestionCategory.PACING,
            title = "Remove ${tiny.size} too-short cut${if (tiny.size == 1) "" else "s"}",
            detail = "Cuts under ${FRAGMENT_MIN_MS / 1000}s read as a glitch on social video.",
            action = Action.RemoveClip(tiny.first().id),
        )
    }

    private companion object {
        const val OPENING_MAX_MS = 6_000L
        const val OPENING_TARGET_MS = 3_000L
        const val OVERRUN_TOLERANCE_MS = 1_500L
        const val MIN_CLIP_MS = 1_000L
        const val LONG_STATIC_MS = 8_000L
        const val HOOK_WINDOW_MS = 3_000L
        const val MIN_HOOK_CHARS = 12
        const val MAX_HOOK_CHARS = 70
        const val RAISED_POSITION_Y = 0.68f
        const val LONG_CAPTION_CHARS = 42
        const val MAX_ORIGINAL_VOLUME = 1.0f
        const val SAFE_VOLUME = 0.9f
        const val FRAGMENT_MIN_MS = 1_200L
    }
}
