package com.aivideostudio.ai.generation

import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.HighlightLabel
import com.aivideostudio.domain.model.StoryRole
import com.aivideostudio.domain.model.TimelineClip
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a ranked list of moments into coherent Shorts.
 *
 * The brief is explicit that this must not just grab random 30 second chunks,
 * so every plan is assembled from four roles — hook, context, climax, payoff —
 * taking the best available moment for each role and respecting the target
 * length. When there is only one usable moment it degrades to a single trimmed
 * take rather than inventing structure that is not there.
 */
@Singleton
class StoryBuilder @Inject constructor() {

    data class PlannedSegment(
        val assetId: Long,
        val highlightId: Long?,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val role: StoryRole,
        val highlight: Highlight? = null,
    ) {
        val durationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)
    }

    data class ShortPlan(
        val index: Int,
        val segments: List<PlannedSegment>,
        val label: HighlightLabel,
        val hookText: String?,
        val storySummary: String,
        val anchorHighlight: Highlight?,
    ) {
        val durationMs: Long get() = segments.sumOf { it.durationMs }
        val primaryAssetId: Long? get() = segments.firstOrNull()?.assetId
    }

    /**
     * @param highlights ranked candidates, highest value first
     * @param targets target length per Short in milliseconds
     */
    fun build(
        highlights: List<Highlight>,
        shortCount: Int,
        targetDurationMs: Long,
    ): List<ShortPlan> {
        val pool = highlights
            .filter { !it.isRejected && it.durationMs >= MIN_SEGMENT_MS }
            .sortedByDescending { it.overallScore }
        if (pool.isEmpty() || shortCount <= 0) return emptyList()

        val plans = ArrayList<ShortPlan>(shortCount)
        val used = HashSet<Long>()

        repeat(shortCount) { index ->
            // Every Short needs its own anchor. When the footage does not hold
            // enough distinct moments we return fewer Shorts instead of padding
            // the list with near-identical copies of the same one.
            val remaining = pool.filter { it.id !in used }
            if (remaining.isEmpty()) return@repeat

            val anchor = remaining.first()
            val plan = assemblePlan(
                index = index,
                anchor = anchor,
                pool = pool,
                used = used,
                targetDurationMs = targetDurationMs,
            ) ?: return@repeat

            plan.segments.forEach { segment ->
                segment.highlight?.id?.let { id -> used += id }
            }
            used += anchor.id
            plans += plan
        }

        return plans
    }

    private fun assemblePlan(
        index: Int,
        anchor: Highlight,
        pool: List<Highlight>,
        used: Set<Long>,
        targetDurationMs: Long,
    ): ShortPlan? {
        val budget = Allocator(targetDurationMs)

        val hook = pick(pool, used, anchor.id) { it.hookScore }
            ?.takeIf { it.overlaps(anchor).not() }
        val payoff = pick(pool, used, anchor.id, hook?.id) { it.payoffScore }
            ?.takeIf { it.overlaps(anchor).not() }
        val context = pickOrderedBefore(anchor, pool, used, hook?.id, payoff?.id)

        val segments = mutableListOf<PlannedSegment>()

        hook?.let { segments += it.toSegment(StoryRole.HOOK, budget.take(hookShare(budget.total))) }
        context?.let { segments += it.toSegment(StoryRole.CONTEXT, budget.take(contextShare(budget.total))) }
        segments += anchor.toSegment(StoryRole.CLIMAX, budget.take(budget.remaining))
        payoff?.let { segments += it.toSegment(StoryRole.PAYOFF, budget.take(payoffShare(budget.total))) }

        val withContent = segments.filter { it.durationMs >= MIN_SEGMENT_MS }
        if (withContent.isEmpty()) {
            val fallback = anchor.trimTo(targetDurationMs, StoryRole.CLIMAX)
            return ShortPlan(
                index = index,
                segments = listOf(fallback),
                label = anchor.label,
                hookText = null,
                storySummary = summaryFor(anchor, listOf(fallback)),
                anchorHighlight = anchor,
            )
        }

        return ShortPlan(
            index = index,
            segments = withContent,
            label = pickPlanLabel(anchor, hook, payoff),
            hookText = hook?.transcriptText?.firstSentence()?.takeIf { it.isNotBlank() },
            storySummary = summaryFor(anchor, withContent),
            anchorHighlight = anchor,
        )
    }

    /** Prefers a moment that sits just before the climax in the source timeline. */
    private fun pickOrderedBefore(
        anchor: Highlight,
        pool: List<Highlight>,
        used: Set<Long>,
        vararg excluded: Long?,
    ): Highlight? {
        val candidates = pool.filter { candidate ->
            candidate.id != anchor.id &&
                candidate.id !in used &&
                excluded.filterNotNull().none { it == candidate.id } &&
                candidate.assetId == anchor.assetId &&
                candidate.endMs <= anchor.startMs &&
                anchor.startMs - candidate.endMs <= CONTEXT_SEARCH_WINDOW_MS
        }
        return candidates.minByOrNull { anchor.startMs - it.endMs }
            ?: pool.filter { it.id != anchor.id && it.id !in used }
                .minByOrNull { min(kotlin.math.abs(it.startMs - anchor.startMs), it.durationMs) }
    }

    private fun pick(
        pool: List<Highlight>,
        used: Set<Long>,
        anchorId: Long,
        excluded: Long? = null,
        selector: (Highlight) -> Float,
    ): Highlight? = pool
        .filter { it.id != anchorId && it.id !in used && it.id != excluded }
        .maxByOrNull(selector)

    private fun Highlight.toSegment(role: StoryRole, budgetMs: Long): PlannedSegment {
        val length = min(durationMs, max(budgetMs, MIN_SEGMENT_MS))
        // The payoff keeps the tail of the moment, everything else keeps the head.
        val adjusted = if (role == StoryRole.PAYOFF) {
            (endMs - length).coerceAtLeast(startMs)
        } else {
            startMs
        }
        val end = (adjusted + length).coerceAtMost(endMs).coerceAtLeast(adjusted + 1)
        return PlannedSegment(
            assetId = assetId,
            highlightId = id,
            sourceStartMs = adjusted,
            sourceEndMs = end,
            role = role,
            highlight = this,
        )
    }

    private fun Highlight.trimTo(lengthMs: Long, role: StoryRole): PlannedSegment {
        val length = min(durationMs, lengthMs).coerceAtLeast(MIN_SEGMENT_MS)
        // Keep the middle of the moment: the extremes are the least representative.
        val padding = (durationMs - length) / 2
        return PlannedSegment(
            assetId = assetId,
            highlightId = id,
            sourceStartMs = (startMs + padding).coerceAtLeast(startMs),
            sourceEndMs = (startMs + padding + length).coerceAtMost(endMs),
            role = role,
            highlight = this,
        )
    }

    private fun Highlight.overlaps(other: Highlight): Boolean =
        assetId == other.assetId && startMs < other.endMs && endMs > other.startMs

    private fun pickPlanLabel(
        anchor: Highlight,
        hook: Highlight?,
        payoff: Highlight?,
    ): HighlightLabel = when {
        hook != null && hook.hookScore >= HOOK_PLAN_THRESHOLD -> HighlightLabel.HOOK
        payoff != null && payoff.payoffScore >= PAYOFF_PLAN_THRESHOLD -> HighlightLabel.PAYOFF
        anchor.label == HighlightLabel.MOMENT && anchor.speechScore >= 0.6f -> HighlightLabel.STORY
        else -> anchor.label
    }

    private fun summaryFor(anchor: Highlight, segments: List<PlannedSegment>): String {
        val roles = segments.map { it.role.ordinalLabel }.distinct()
        val seconds = segments.sumOf { it.durationMs } / 1000
        return "${roles.joinToString(" \u2192 ")} \u00B7 ${seconds}s"
    }

    private fun String.firstSentence(): String {
        val trimmed = trim()
        val end = trimmed.indexOfFirst { it in SENTENCE_ENDINGS }
        return if (end in 0..MAX_HOOK_CHARS) trimmed.substring(0, end + 1) else ""
    }

    /** Hands out a fixed time budget across the four story roles. */
    private class Allocator(total: Long) {
        val total: Long = total
        var remaining: Long = total
            private set

        fun take(requested: Long): Long {
            val granted = requested.coerceAtMost(remaining).coerceAtLeast(0L)
            remaining -= granted
            return granted
        }
    }

    /** Returns the plans reshaped into editor-ready timeline rows. */
    fun toTimeline(plan: ShortPlan, clipId: Long, projectId: Long): List<TimelineClip> =
        plan.segments.mapIndexed { index, segment ->
            TimelineClip(
                clipId = clipId,
                projectId = projectId,
                assetId = segment.assetId,
                orderIndex = index,
                sourceStartMs = segment.sourceStartMs,
                sourceEndMs = segment.sourceEndMs,
                storyRole = segment.role,
                highlightId = segment.highlightId,
            )
        }

    /** Convenience for the preview card shown before the user approves. */
    fun describe(plan: ShortPlan): String = plan.segments.joinToString("  \u00B7  ") { segment ->
        "${segment.role.ordinalLabel} ${segment.sourceStartMs / 1000}s\u2013${segment.sourceEndMs / 1000}s"
    }

    fun toClip(
        plan: ShortPlan,
        projectId: Long,
        aspectRatio: com.aivideostudio.domain.model.AspectRatio,
        createdAt: Long,
    ): GeneratedClip = GeneratedClip(
        projectId = projectId,
        index = plan.index,
        title = "",
        label = plan.label,
        hookText = plan.hookText,
        hookHighlightId = plan.segments.firstOrNull { it.role == StoryRole.HOOK }?.highlightId,
        sourceAssetId = plan.primaryAssetId,
        sourceStartMs = plan.segments.firstOrNull()?.sourceStartMs ?: 0L,
        sourceEndMs = plan.segments.lastOrNull()?.sourceEndMs ?: 0L,
        durationMs = plan.durationMs,
        aspectRatio = aspectRatio,
        storyRole = StoryRole.CLIMAX,
        storySummary = plan.storySummary,
        createdAt = createdAt,
    )

    private fun hookShare(total: Long) = (total * HOOK_SHARE).toLong()
    private fun contextShare(total: Long) = (total * CONTEXT_SHARE).toLong()
    private fun payoffShare(total: Long) = (total * PAYOFF_SHARE).toLong()

    private companion object {
        const val MIN_SEGMENT_MS = 1_200L
        const val CONTEXT_SEARCH_WINDOW_MS = 120_000L
        const val HOOK_SHARE = 0.18f
        const val CONTEXT_SHARE = 0.24f
        const val PAYOFF_SHARE = 0.16f
        const val HOOK_PLAN_THRESHOLD = 0.6f
        const val PAYOFF_PLAN_THRESHOLD = 0.65f
        const val MAX_HOOK_CHARS = 90
        val SENTENCE_ENDINGS = setOf('.', '!', '?', '\u2026')
    }
}
