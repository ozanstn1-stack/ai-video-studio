package com.aivideostudio.domain.model

import kotlinx.serialization.Serializable

data class Transcript(
    val id: Long = 0L,
    val projectId: Long,
    val language: String? = null,
    val provider: String,
    val fullText: String = "",
    val createdAt: Long,
    val averageConfidence: Float = 0f,
    val isComplete: Boolean = true,
    val note: String? = null,
) {
    val hasWords: Boolean get() = fullText.isNotBlank()
}

data class TranscriptSegment(
    val id: Long = 0L,
    val transcriptId: Long,
    val projectId: Long,
    val assetId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float = 0f,
    /** Optional per-word timings, used by the dynamic subtitle style. */
    val words: List<TranscriptWord> = emptyList(),
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    val isEmpty: Boolean get() = text.isBlank()
}

@Serializable
data class TranscriptWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val emphasis: Boolean = false,
)
