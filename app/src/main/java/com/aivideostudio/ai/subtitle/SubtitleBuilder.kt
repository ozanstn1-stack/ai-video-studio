package com.aivideostudio.ai.subtitle

import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.TranscriptSegment
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Builds captions on the *rendered* timeline.
 *
 * Source timestamps are mapped through the timeline clips, so a Short assembled
 * from four different moments still gets correctly synchronised subtitles. Text
 * is then split into readable chunks: the segmenter prefers sentence boundaries,
 * falls back to clause openers, and only splits mid-phrase when a word is too
 * long to fit on a line.
 */
@Singleton
class SubtitleBuilder @Inject constructor() {

    data class Options(
        val style: SubtitleStyle = SubtitleStyle.CREATOR,
        val maxCharsPerLine: Int = 22,
        val maxLines: Int = 2,
        val minCaptionMs: Long = 700L,
        val gapMs: Long = 60L,
        val defaultPositionY: Float = 0.78f,
        /** Position used when a face occupies the lower part of the frame. */
        val raisedPositionY: Float = 0.68f,
    )

    fun build(
        timeline: List<TimelineClip>,
        transcript: List<TranscriptSegment>,
        options: Options = Options(),
    ): List<Caption> {
        if (timeline.isEmpty() || transcript.isEmpty()) return emptyList()

        val captions = ArrayList<Caption>(128)
        var outputCursorMs = 0L
        var index = 0

        timeline.sortedBy { it.orderIndex }.forEach { clip ->
            val clipDurationMs = clip.durationMs
            val overlapping = transcript.filter { segment ->
                segment.assetId == clip.assetId &&
                    segment.startMs < clip.sourceEndMs &&
                    segment.endMs > clip.sourceStartMs &&
                    segment.text.isNotBlank()
            }

            overlapping.forEach { segment ->
                val mappedStart = outputCursorMs +
                    ((segment.startMs - clip.sourceStartMs).coerceAtLeast(0L) / clip.playbackSpeed).toLong()
                val mappedEnd = outputCursorMs +
                    ((segment.endMs - clip.sourceStartMs).coerceAtMost(clip.sourceEndMs - clip.sourceStartMs) /
                        clip.playbackSpeed).toLong()

                val clampedStart = mappedStart.coerceIn(outputCursorMs, outputCursorMs + clipDurationMs)
                val clampedEnd = mappedEnd.coerceIn(clampedStart + 1, outputCursorMs + clipDurationMs)

                chunksOf(segment.text, options).forEach { chunk ->
                    val start = clampedStart + chunk.offsetMs
                    val end = (clampedEnd + chunk.offsetMs).coerceAtLeast(start + options.minCaptionMs)
                    val text = chunk.text.trim()
                    if (text.isNotEmpty()) {
                        captions += Caption(
                            projectId = clip.projectId,
                            clipId = clip.clipId,
                            timelineClipId = clip.id,
                            index = index++,
                            startMs = start,
                            endMs = end,
                            text = text,
                            style = options.style,
                            positionY = options.defaultPositionY,
                            emphasisWords = emphasisIn(text),
                        )
                    }
                }
            }

            outputCursorMs += clipDurationMs
        }

        return dedupeAndSort(captions, options)
    }

    /**
     * Raises the caption band out of the way when a face is in the lower third.
     * Called after face analysis so subtitles never sit on top of somebody.
     */
    fun applyFaceAvoidance(
        captions: List<Caption>,
        faceRegions: List<FaceRegion>,
        options: Options = Options(),
    ): List<Caption> {
        if (faceRegions.isEmpty()) return captions
        return captions.map { caption ->
            val clashes = faceRegions.any { region ->
                region.startMs < caption.endMs &&
                    region.endMs > caption.startMs &&
                    region.centerY > options.defaultPositionY - 0.22f
            }
            if (clashes) caption.copy(positionY = options.raisedPositionY) else caption
        }
    }

    data class FaceRegion(val startMs: Long, val endMs: Long, val centerY: Float)

    private data class Chunk(val text: String, val offsetMs: Long)

    /**
     * Splits a spoken segment into caption-sized chunks while keeping the text
     * readable. Short segments stay whole; long ones break at punctuation, then
     * at conjunctions, and only then at the character limit.
     */
    private fun chunksOf(text: String, options: Options): List<Chunk> {
        val limit = options.maxCharsPerLine * options.maxLines
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= limit) return listOf(Chunk(cleaned, 0L))

        val parts = splitAtBoundaries(cleaned, limit)
        var offset = 0L
        return parts.map { part ->
            val chunk = Chunk(part, offset)
            offset += estimateDurationMs(part)
            chunk
        }
    }

    private fun splitAtBoundaries(text: String, limit: Int): List<String> {
        val result = ArrayList<String>(4)
        var remaining = text

        while (remaining.length > limit) {
            val window = remaining.substring(0, limit)
            val cut = listOf(
                window.lastIndexOf(SENTENCE_BOUNDARY),
                window.lastIndexOf(CLAUSE_BOUNDARY),
                window.lastIndexOf(CONJUNCTION + " "),
                window.lastIndexOf(' '),
            ).maxOrNull() ?: -1

            if (cut <= 0) {
                // A single very long word: hard split so the caption still fits.
                result += remaining.substring(0, limit)
                remaining = remaining.substring(limit).trimStart()
                continue
            }

            result += remaining.substring(0, cut + 1).trim()
            remaining = remaining.substring(cut + 1).trimStart()
        }
        if (remaining.isNotBlank()) result += remaining
        return result
    }

    /**
     * Words worth emphasising with the Dynamic style: long words, anything the
     * speaker stressed (all caps or trailing punctuation) and emotional markers.
     */
    private fun emphasisIn(text: String): List<String> =
        text.split(' ')
            .map { it.trim().trimEnd('.', ',', ';', ':') }
            .filter { word ->
                word.length >= EMPHASIS_MIN_LENGTH ||
                    word.all { it.isUpperCase() && it.isLetter() } && word.length >= 3 ||
                    EMPHASIS_WORDS.any { it.equals(word, ignoreCase = true) }
            }
            .map { it.uppercase() }
            .distinct()
            .take(MAX_EMPHASIS_WORDS)

    private fun dedupeAndSort(captions: List<Caption>, options: Options): List<Caption> {
        val sorted = captions.sortedBy { it.startMs }
        val result = ArrayList<Caption>(sorted.size)
        sorted.forEach { caption ->
            val previous = result.lastOrNull()
            if (previous != null && previous.text.equals(caption.text, ignoreCase = true) &&
                abs(previous.startMs - caption.startMs) < 400L
            ) {
                return@forEach
            }
            if (previous != null && caption.startMs < previous.endMs - options.gapMs) {
                result[result.lastIndex] = previous.copy(endMs = (caption.startMs - options.gapMs).coerceAtLeast(previous.startMs + 1))
            }
            result += caption
        }
        return result.mapIndexed { index, caption -> caption.copy(index = index) }
    }

    /** Rough speech rate used to keep chunk timings sensible. */
    private fun estimateDurationMs(text: String): Long =
        ((text.length / CHARS_PER_SECOND) * 1000f).toLong().coerceAtLeast(400L)

    private companion object {
        const val SENTENCE_BOUNDARY = '.'
        const val CLAUSE_BOUNDARY = ','
        const val CONJUNCTION = "and"
        const val EMPHASIS_MIN_LENGTH = 8
        const val MAX_EMPHASIS_WORDS = 3
        const val CHARS_PER_SECOND = 14f
        val EMPHASIS_WORDS = listOf(
            "amazing", "incredible", "unbelievable", "wow", "never", "best", "worst",
            "secret", "love", "insane", "perfect", "finally", "look",
        )
    }
}
