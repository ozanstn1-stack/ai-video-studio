package com.aivideostudio.ai.detection

import com.aivideostudio.core.common.Constants
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.HighlightLabel
import com.aivideostudio.domain.model.HighlightReason
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.SceneIssue
import com.aivideostudio.domain.model.TranscriptSegment
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The scoring core of the app.
 *
 * Given local measurements (scene features, audio energy, transcript) it decides
 * which stretches of footage are worth keeping and *why*. Nothing here touches
 * Android APIs, the network or the database, which keeps it fully unit testable
 * and means the same numbers drive the UI badges and the ordering.
 */
@Singleton
class HighlightScorer @Inject constructor() {

    data class Config(
        val mode: CreationMode = CreationMode.AUTO,
        val targetDurationMs: Long = Constants.DEFAULT_SHORT_DURATION_SEC * 1000L,
        val maxHighlights: Int = 40,
        val minDurationMs: Long = Constants.MIN_HIGHLIGHT_DURATION_MS,
        val maxDurationMs: Long = Constants.MAX_HIGHLIGHT_DURATION_MS,
    )

    /** The intermediate window the scorer reasons about. */
    private data class Window(
        val assetId: Long,
        val scenes: List<Scene>,
        val startMs: Long,
        val endMs: Long,
        val scores: Scores,
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    private data class Scores(
        val visual: Float,
        val audio: Float,
        val speech: Float,
        val motion: Float,
        val interest: Float,
        val story: Float,
        val hook: Float,
        val payoff: Float,
        val speechCoverage: Float,
        val uniqueness: Float = 1f,
    ) {
        fun overall(weights: Weights): Float =
            (visual * weights.visual +
                audio * weights.audio +
                speech * weights.speech +
                motion * weights.motion +
                interest * weights.interest +
                story * weights.story +
                uniqueness * weights.uniqueness)
                .coerceIn(0f, 1f)
    }

    /** Per-mode importance of each signal. Rows always sum to 1. */
    private data class Weights(
        val visual: Float,
        val audio: Float,
        val speech: Float,
        val motion: Float,
        val interest: Float,
        val story: Float,
        val uniqueness: Float,
    ) {
        fun normalised(): Weights {
            val total = visual + audio + speech + motion + interest + story + uniqueness
            if (total <= 0f) return AUTO
            return Weights(
                visual / total,
                audio / total,
                speech / total,
                motion / total,
                interest / total,
                story / total,
                uniqueness / total,
            )
        }

        companion object {
            val AUTO = Weights(0.24f, 0.10f, 0.18f, 0.12f, 0.16f, 0.12f, 0.08f)
            val TRAVEL = Weights(0.26f, 0.08f, 0.14f, 0.14f, 0.20f, 0.10f, 0.08f)
            val ACTION = Weights(0.18f, 0.16f, 0.08f, 0.26f, 0.20f, 0.04f, 0.08f)
            val SPORTS = Weights(0.16f, 0.18f, 0.06f, 0.28f, 0.20f, 0.04f, 0.08f)
            val VLOG = Weights(0.16f, 0.12f, 0.34f, 0.06f, 0.10f, 0.16f, 0.06f)
            val FOOD = Weights(0.34f, 0.14f, 0.10f, 0.06f, 0.18f, 0.10f, 0.08f)
            val CINEMATIC = Weights(0.38f, 0.10f, 0.06f, 0.06f, 0.22f, 0.06f, 0.12f)
        }
    }

    fun score(
        scenes: List<Scene>,
        transcript: List<TranscriptSegment>,
        config: Config = Config(),
    ): List<Highlight> {
        if (scenes.isEmpty()) return emptyList()

        val usable = scenes.filter { !it.isDiscardable }
        if (usable.isEmpty()) return emptyList()

        val weights = weightsFor(config.mode)
        val windows = buildWindows(usable, transcript, config, weights)
        if (windows.isEmpty()) return emptyList()

        // Uniqueness is only meaningful once the obviously weak candidates are
        // gone; computing it pairwise over every window would be quadratic on
        // hour-long footage for no benefit.
        val shortlist = windows
            .sortedByDescending { it.scores.overall(weights) }
            .take(UNIQUENESS_SHORTLIST)
            .map { it.copy(scores = it.scores.copy(uniqueness = 1f)) }

        val withUniqueness = applyUniqueness(shortlist)

        val selected = selectDiverse(withUniqueness, weights, config.maxHighlights)

        return selected
            .sortedBy { it.startMs }
            .mapIndexed { index, window -> window.toHighlight(index, weights, transcript) }
    }

    private fun weightsFor(mode: CreationMode): Weights = when (mode) {
        CreationMode.AUTO -> Weights.AUTO
        CreationMode.TRAVEL -> Weights.TRAVEL
        CreationMode.ACTION -> Weights.ACTION
        CreationMode.SPORTS -> Weights.SPORTS
        CreationMode.VLOG -> Weights.VLOG
        CreationMode.FOOD -> Weights.FOOD
        CreationMode.CINEMATIC -> Weights.CINEMATIC
    }.normalised()

    private fun buildWindows(
        scenes: List<Scene>,
        transcript: List<TranscriptSegment>,
        config: Config,
        weights: Weights,
    ): List<Window> {
        val byAsset = scenes.groupBy { it.assetId }
        val result = ArrayList<Window>(256)

        byAsset.values.forEach { assetScenes ->
            val ordered = assetScenes.sortedBy { it.startMs }
            ordered.forEachIndexed { startIndex, _ ->
                val accumulated = ArrayList<Scene>(8)
                var cursor = startIndex
                var duration = 0L
                while (cursor < ordered.size && duration < config.targetDurationMs) {
                    val scene = ordered[cursor]
                    // Never bridge a gap in the source: highlights are contiguous.
                    if (accumulated.isNotEmpty() &&
                        scene.startMs - accumulated.last().endMs > MAX_GAP_MS
                    ) {
                        break
                    }
                    accumulated += scene
                    duration += scene.durationMs
                    cursor++
                }
                if (duration < config.minDurationMs || accumulated.isEmpty()) return@forEachIndexed
                if (duration > config.maxDurationMs) return@forEachIndexed

                val window = Window(
                    assetId = accumulated.first().assetId,
                    scenes = accumulated,
                    startMs = accumulated.first().startMs,
                    endMs = accumulated.last().endMs,
                    scores = computeScores(accumulated, transcript),
                )
                result += window
            }
        }
        return result
    }

    private fun computeScores(scenes: List<Scene>, transcript: List<TranscriptSegment>): Scores {
        val totalMs = scenes.sumOf { it.durationMs }.coerceAtLeast(1L).toFloat()
        fun weight(scene: Scene) = scene.durationMs.toFloat() / totalMs

        val visual = scenes.sumOf { (weight(it) * scoreVisual(it)).toDouble() }.toFloat()
        val audio = scenes.sumOf { (weight(it) * scoreAudio(it)).toDouble() }.toFloat()
        val motion = scenes.sumOf { (weight(it) * scoreMotion(it)).toDouble() }.toFloat()
        val interest = scoreInterest(scenes)
        val speechCoverage = scenes.sumOf { (weight(it) * it.speechRatio.coerceIn(0f, 1f)).toDouble() }
            .toFloat()
        val speech = scoreSpeech(scenes, transcript, speechCoverage)
        val story = scoreStory(scenes, transcript)

        val start = scenes.first().startMs
        val end = scenes.last().endMs
        val hook = scoreHook(scenes.first(), transcript, start)
        val payoff = scorePayoff(scenes.last(), transcript, end)

        return Scores(
            visual = visual,
            audio = audio,
            speech = speech,
            motion = motion,
            interest = interest,
            story = story,
            hook = hook,
            payoff = payoff,
            speechCoverage = speechCoverage,
        )
    }

    private fun scoreVisual(scene: Scene): Float {
        val exposure = 1f - (abs(scene.brightness - IDEAL_BRIGHTNESS) / IDEAL_BRIGHTNESS)
            .coerceIn(0f, 1f)
        val faceBonus = (scene.faceCoverage * FACE_BONUS_WEIGHT).coerceAtMost(FACE_BONUS_MAX)
        val framingBonus = when (scene.framing) {
            com.aivideostudio.domain.model.Framing.CLOSE_UP -> 0.06f
            com.aivideostudio.domain.model.Framing.MEDIUM -> 0.08f
            com.aivideostudio.domain.model.Framing.WIDE -> 0.04f
            else -> 0f
        }
        return (scene.quality * 0.42f +
            scene.sharpness * 0.26f +
            exposure * 0.20f +
            scene.saturation * 0.12f +
            faceBonus +
            framingBonus)
            .coerceIn(0f, 1f)
    }

    private fun scoreAudio(scene: Scene): Float {
        val loudness = scene.audioLoudness.coerceIn(0f, 1f)
        // Penalise both ends: near-silent tracks and clipping are equally bad.
        val balance = 1f - (abs(loudness - IDEAL_LOUDNESS) / IDEAL_LOUDNESS).coerceIn(0f, 1f)
        val silencePenalty = scene.speechRatio.coerceIn(0f, 1f) * 0.5f + 0.5f
        return (balance * 0.6f + silencePenalty * 0.4f).coerceIn(0f, 1f)
    }

    private fun scoreMotion(scene: Scene): Float {
        val sweetSpot = 1f - (abs(scene.motion - IDEAL_MOTION) / IDEAL_MOTION).coerceIn(0f, 1f)
        return (sweetSpot * 0.7f + scene.stability * 0.3f).coerceIn(0f, 1f)
    }

    private fun scoreInterest(scenes: List<Scene>): Float {
        if (scenes.isEmpty()) return 0f
        val durationSeconds = (scenes.sumOf { it.durationMs }.coerceAtLeast(1L)) / 1000f
        // More distinct scenes per second means more happens in this window.
        val changeRate = (scenes.size / durationSeconds / CHANGE_RATE_TARGET).coerceIn(0f, 1f)
        val faceCoverage = scenes.map { it.faceCoverage }.average().toFloat().coerceIn(0f, 1f)
        val brightnessVariety = standardDeviation(scenes.map { it.brightness }).coerceIn(0f, 1f)
        val hueVariety = standardDeviation(scenes.map { it.dominantHue }).coerceIn(0f, 1f)
        val saturation = scenes.map { it.saturation }.average().toFloat().coerceIn(0f, 1f)

        return (changeRate * 0.30f +
            faceCoverage * 0.22f +
            brightnessVariety * 0.16f +
            hueVariety * 0.16f +
            saturation * 0.16f)
            .coerceIn(0f, 1f)
    }

    private fun scoreSpeech(
        scenes: List<Scene>,
        transcript: List<TranscriptSegment>,
        speechCoverage: Float,
    ): Float {
        if (transcript.isEmpty()) {
            // Without a transcript we can still see that somebody was talking.
            return (speechCoverage * 0.7f).coerceIn(0f, 1f)
        }
        val start = scenes.first().startMs
        val end = scenes.last().endMs
        val overlapping = transcript.filter { it.startMs < end && it.endMs > start }
        if (overlapping.isEmpty()) return (speechCoverage * 0.45f).coerceIn(0f, 1f)

        val coverageInWindow = overlapping.sumOf { segment ->
            (minOf(segment.endMs, end) - maxOf(segment.startMs, start)).coerceAtLeast(0L)
        }.toFloat() / (end - start).coerceAtLeast(1L)

        val confidence = overlapping.map { it.confidence.coerceIn(0f, 1f) }.average().toFloat()
        val density = (overlapping.size / ((end - start) / 1000f).coerceAtLeast(1f) / WORDS_PER_SECOND_TARGET)
            .coerceIn(0f, 1f)

        return (coverageInWindow.coerceIn(0f, 1f) * 0.5f +
            confidence * 0.28f +
            density * 0.22f)
            .coerceIn(0f, 1f)
    }

    /** Story relevance: a window that contains whole sentences reads better. */
    private fun scoreStory(scenes: List<Scene>, transcript: List<TranscriptSegment>): Float {
        if (scenes.isEmpty()) return 0f
        val start = scenes.first().startMs
        val end = scenes.last().endMs
        val inside = transcript.filter { it.startMs >= start && it.endMs <= end }
        if (inside.isEmpty()) return 0.25f

        val complete = inside.count { it.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS }
        val completeness = (complete.toFloat() / inside.size).coerceIn(0f, 1f)
        val narrative = inside.count { segment ->
            NARRATIVE_MARKERS.any { marker -> segment.text.contains(marker, ignoreCase = true) }
        }
        val narrativeStrength = (narrative.toFloat() / inside.size).coerceIn(0f, 1f)
        val lengthFit = 1f - (abs(inside.size - IDEAL_SENTENCES) / IDEAL_SENTENCES).coerceIn(0f, 1f)

        return (completeness * 0.42f + narrativeStrength * 0.34f + lengthFit * 0.24f).coerceIn(0f, 1f)
    }

    private fun scoreHook(
        firstScene: Scene,
        transcript: List<TranscriptSegment>,
        windowStartMs: Long,
    ): Float {
        val visualImpact = (firstScene.quality * 0.5f + firstScene.motion * 0.3f +
            firstScene.sharpness * 0.2f).coerceIn(0f, 1f)
        val hookSpeech = transcript
            .filter { it.startMs < windowStartMs + HOOK_WINDOW_MS && it.endMs > windowStartMs }
            .map { segment ->
                val matched = HOOK_WORDS.count { word ->
                    segment.text.contains(word, ignoreCase = true)
                }
                (matched / HOOK_WORD_TARGET).coerceIn(0f, 1f) +
                    (if (segment.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS) 0f else 0.1f)
            }
            .maxOrNull()
            ?.coerceIn(0f, 1f) ?: 0f

        return (visualImpact * 0.55f + firstScene.faceCoverage.coerceAtMost(0.4f) * 0.2f +
            hookSpeech * 0.25f)
            .coerceIn(0f, 1f)
    }

    private fun scorePayoff(
        lastScene: Scene,
        transcript: List<TranscriptSegment>,
        windowEndMs: Long,
    ): Float {
        val visual = (lastScene.quality * 0.55f + lastScene.saturation * 0.2f +
            lastScene.stability * 0.25f).coerceIn(0f, 1f)
        val endingSpeech = transcript
            .filter { it.endMs > windowEndMs - HOOK_WINDOW_MS && it.startMs < windowEndMs }
            .map { segment ->
                val concluded = if (segment.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS) 0.3f else 0f
                val markers = PAYOFF_WORDS.count { segment.text.contains(it, ignoreCase = true) }
                (concluded + (markers / PAYOFF_WORD_TARGET).coerceAtMost(0.7f)).coerceIn(0f, 1f)
            }
            .maxOrNull() ?: 0f

        return (visual * 0.7f + endingSpeech * 0.3f).coerceIn(0f, 1f)
    }

    /**
     * Uniqueness is the inverse of the highest similarity to any other candidate.
     * Without it the generator would happily pick five near-identical shots.
     */
    private fun applyUniqueness(windows: List<Window>): List<Window> {
        val signatures = windows.map { signatureOf(it) }
        return windows.mapIndexed { index, window ->
            var maxSimilarity = 0f
            signatures.forEachIndexed { otherIndex, other ->
                if (otherIndex == index) return@forEachIndexed
                if (windows[otherIndex].assetId != window.assetId) return@forEachIndexed
                val similarity = signatureSimilarity(signatures[index], other)
                if (similarity > maxSimilarity) maxSimilarity = similarity
            }
            window.copy(scores = window.scores.copy(uniqueness = (1f - maxSimilarity).coerceIn(0f, 1f)))
        }
    }

    private fun signatureOf(window: Window): FloatArray {
        val scenes = window.scenes
        val average = { selector: (Scene) -> Float ->
            scenes.map(selector).average().toFloat()
        }
        return floatArrayOf(
            average { it.quality },
            average { it.motion },
            average { it.brightness },
            average { it.dominantHue },
            average { it.saturation },
            average { it.sharpness },
        )
    }

    private fun signatureSimilarity(a: FloatArray, b: FloatArray): Float {
        var distance = 0f
        for (index in a.indices) {
            val delta = a[index] - b[index]
            distance += delta * delta
        }
        val rms = sqrt(distance / a.size)
        return (1f - (rms / SIMILARITY_FULL_SCALE)).coerceIn(0f, 1f)
    }

    /** Greedy pick that never returns two overlapping windows. */
    private fun selectDiverse(
        windows: List<Window>,
        weights: Weights,
        limit: Int,
    ): List<Window> {
        val ordered = windows.sortedByDescending { it.scores.overall(weights) }
        val chosen = ArrayList<Window>(limit)
        ordered.forEach { candidate ->
            if (chosen.size >= limit) return@forEach
            val clashes = chosen.any { existing ->
                existing.assetId == candidate.assetId && existing.overlaps(candidate)
            }
            if (!clashes) chosen += candidate
        }
        return chosen
    }

    private fun Window.overlaps(other: Window): Boolean =
        startMs < other.endMs && endMs > other.startMs

    private fun Window.toHighlight(
        index: Int,
        weights: Weights,
        transcript: List<TranscriptSegment>,
    ): Highlight {
        val overall = scores.overall(weights)
        val label = pickLabel(scores)
        val text = transcript
            .filter { it.startMs < endMs && it.endMs > startMs && it.text.isNotBlank() }
            .joinToString(" ") { it.text }
            .trim()
            .takeIf { it.isNotEmpty() }

        return Highlight(
            projectId = scenes.first().projectId,
            assetId = assetId,
            startMs = startMs,
            endMs = endMs,
            visualScore = scores.visual,
            audioScore = scores.audio,
            speechScore = scores.speech,
            motionScore = scores.motion,
            interestScore = scores.interest,
            uniquenessScore = scores.uniqueness,
            storyScore = scores.story,
            overallScore = overall,
            hookScore = scores.hook,
            payoffScore = scores.payoff,
            speechCoverage = scores.speechCoverage,
            label = label,
            reasons = buildReasons(scores, label, scenes),
            transcriptText = text,
            orderIndex = index,
            isSelected = true,
        )
    }

    private fun pickLabel(scores: Scores): HighlightLabel = when {
        scores.hook >= HOOK_LABEL_THRESHOLD -> HighlightLabel.HOOK
        scores.motion >= HIGH_ENERGY_THRESHOLD -> HighlightLabel.HIGH_ENERGY
        scores.payoff >= PAYOFF_LABEL_THRESHOLD -> HighlightLabel.PAYOFF
        scores.speech >= SPEECH_LABEL_THRESHOLD -> HighlightLabel.STORY
        scores.visual >= CINEMATIC_THRESHOLD -> HighlightLabel.CINEMATIC
        else -> HighlightLabel.MOMENT
    }

    /**
     * Turns the raw numbers into the short, human explanations the review screen
     * shows under "Why this clip?".
     */
    private fun buildReasons(
        scores: Scores,
        label: HighlightLabel,
        scenes: List<Scene>,
    ): List<HighlightReason> {
        val candidates = mutableListOf<Pair<Float, HighlightReason>>()

        if (scenes.any { it.motion >= 0.25f }) {
            candidates += scores.motion to HighlightReason("\uD83C\uDFC3", "Lots of movement")
        }
        if (scenes.any { it.sharpness >= 0.5f }) {
            candidates += scores.visual to HighlightReason("\uD83D\uDD0D", "Sharp, detailed image")
        }
        if (scenes.any { it.faceCoverage > 0.03f }) {
            candidates += 0.7f to HighlightReason("\uD83D\uDC64", "A person is in frame")
        }
        if (scores.speechCoverage >= 0.4f) {
            candidates += scores.speech to HighlightReason("\uD83C\uDFA4", "Clear speech")
        }
        if (scores.story >= 0.5f) {
            candidates += scores.story to HighlightReason("\uD83D\uDCD6", "A complete thought")
        }
        if (scores.hook >= 0.55f) {
            candidates += scores.hook to HighlightReason("\uD83E\uDE9D", "Strong opening")
        }
        if (scores.payoff >= 0.55f) {
            candidates += scores.payoff to HighlightReason("\u2B50", "Good ending")
        }
        if (scenes.all { it.stability >= 0.6f } && scenes.any { it.motion >= 0.1f }) {
            candidates += 0.62f to HighlightReason("\uD83C\uDFA5", "Smooth camera work")
        }
        scenes.firstOrNull { it.issue == SceneIssue.NONE && it.saturation > 0.45f }?.let {
            candidates += 0.5f to HighlightReason("\uD83C\uDF08", "Rich colours")
        }

        val fromLabel = when (label) {
            HighlightLabel.HOOK -> HighlightReason("\uD83E\uDE9D", "Strong hook")
            HighlightLabel.CINEMATIC -> HighlightReason("\uD83C\uDFAC", "Cinematic shot")
            HighlightLabel.HIGH_ENERGY -> HighlightReason("\uD83D\uDD25", "High energy")
            HighlightLabel.STORY -> HighlightReason("\uD83C\uDFA4", "Great story")
            HighlightLabel.PAYOFF -> HighlightReason("\u2B50", "Strong ending")
            HighlightLabel.SCENIC -> HighlightReason("\uD83C\uDFD4", "Beautiful view")
            HighlightLabel.MOMENT -> HighlightReason("\u2728", "Good moment")
        }
        candidates += 0.4f to fromLabel

        return candidates
            .sortedByDescending { it.first }
            .distinctBy { it.second.badge }
            .take(MAX_REASONS)
            .map { it.second }
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).let { d -> d * d } }.average().toFloat()
        return sqrt(variance).pow(0.5f)
    }

    private companion object {
        const val MAX_GAP_MS = 1_500L
        const val UNIQUENESS_SHORTLIST = 220
        const val SIMILARITY_FULL_SCALE = 0.55f

        const val IDEAL_BRIGHTNESS = 0.48f
        const val IDEAL_MOTION = 0.17f
        const val IDEAL_LOUDNESS = 0.16f
        const val FACE_BONUS_WEIGHT = 1.4f
        const val FACE_BONUS_MAX = 0.10f
        const val CHANGE_RATE_TARGET = 0.55f
        const val WORDS_PER_SECOND_TARGET = 4.2f
        const val IDEAL_SENTENCES = 4f

        const val HOOK_WINDOW_MS = 3_500L
        const val HOOK_WORD_TARGET = 3f
        const val PAYOFF_WORD_TARGET = 2.5f
        const val MAX_REASONS = 3

        const val HOOK_LABEL_THRESHOLD = 0.66f
        const val HIGH_ENERGY_THRESHOLD = 0.70f
        const val PAYOFF_LABEL_THRESHOLD = 0.72f
        const val SPEECH_LABEL_THRESHOLD = 0.62f
        const val CINEMATIC_THRESHOLD = 0.66f

        val SENTENCE_ENDINGS = setOf('.', '!', '?', '\u2026')
        val HOOK_WORDS = listOf(
            "look", "watch", "wait", "amazing", "incredible", "unbelievable", "wow",
            "check this", "you won't", "never", "imagine", "secret", "best", "first time",
        )
        val PAYOFF_WORDS = listOf(
            "incredible", "beautiful", "worth it", "finally", "perfect", "unbelievable",
            "so good", "cannot believe", "wow", "thank",
        )
        val NARRATIVE_MARKERS = listOf(
            "because", "then", "first", "next", "after", "finally", "so", "but", "when",
            "while", "here", "we", "i", "you",
        )
    }
}
