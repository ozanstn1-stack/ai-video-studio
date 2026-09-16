package com.aivideostudio.domain.model

/**
 * A candidate moment in the footage. The individual scores exist so the
 * generation step can reason about *why* something is interesting; the UI only
 * ever shows the derived [HighlightLabel] and [reasons].
 */
data class Highlight(
    val id: Long = 0L,
    val projectId: Long,
    val assetId: Long,
    val startMs: Long,
    val endMs: Long,
    val visualScore: Float,
    val audioScore: Float,
    val speechScore: Float,
    val motionScore: Float,
    val interestScore: Float,
    val uniquenessScore: Float,
    val storyScore: Float,
    val overallScore: Float,
    val hookScore: Float = 0f,
    val payoffScore: Float = 0f,
    val speechCoverage: Float = 0f,
    val label: HighlightLabel = HighlightLabel.MOMENT,
    val reasons: List<HighlightReason> = emptyList(),
    val transcriptText: String? = null,
    val orderIndex: Int = 0,
    val isSelected: Boolean = true,
    val isRejected: Boolean = false,
    /** Set when the user manually swapped this selection. */
    val isManual: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    val startLabel: String get() = formatMs(startMs)
    val endLabel: String get() = formatMs(endMs)

    private fun formatMs(value: Long): String {
        val totalSeconds = value / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}

enum class HighlightLabel(val emoji: String, val displayName: String) {
    HOOK("\uD83E\uDE9D", "Strong hook"),
    CINEMATIC("\uD83C\uDFAC", "Cinematic"),
    HIGH_ENERGY("\uD83D\uDD25", "High energy"),
    STORY("\uD83C\uDFA4", "Great story"),
    PAYOFF("\u2B50", "Strong ending"),
    SCENIC("\uD83C\uDFD4", "Beautiful view"),
    MOMENT("\u2728", "Good moment"),
    ;

    companion object {
        fun fromName(value: String?): HighlightLabel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MOMENT
    }
}

/** Role a highlight plays inside a generated Short. */
enum class StoryRole(val ordinalLabel: String) {
    HOOK("Hook"),
    CONTEXT("Context"),
    CLIMAX("Main moment"),
    PAYOFF("Payoff"),
}
