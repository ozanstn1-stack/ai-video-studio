package com.aivideostudio.ai.text

import com.aivideostudio.ai.model.DescriptionResult
import com.aivideostudio.ai.model.SceneAnalysisResult
import com.aivideostudio.ai.model.TitleSuggestion
import com.aivideostudio.ai.model.VideoContext
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.HighlightLabel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts the handful of words that describe what the footage is about. Used to
 * seed titles, descriptions and hashtags when the cloud is not available.
 */
@Singleton
class KeywordExtractor @Inject constructor() {

    fun extract(text: String, limit: Int = 6): List<String> {
        if (text.isBlank()) return emptyList()
        val counts = HashMap<String, Int>()
        val display = HashMap<String, String>()

        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .forEach { word ->
                val key = singular(word)
                counts[key] = (counts[key] ?: 0) + 1
                display.putIfAbsent(key, word)
            }

        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { entry -> display[entry.key]?.replaceFirstChar { it.uppercase() } ?: entry.key }
    }

    private fun singular(word: String): String =
        if (word.length > 4 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

    private companion object {
        const val MIN_WORD_LENGTH = 4
        val STOP_WORDS = setOf(
            "this", "that", "with", "have", "here", "there", "what", "when", "where", "just",
            "like", "very", "really", "about", "going", "gonna", "want", "then", "than", "them",
            "they", "your", "youre", "from", "into", "over", "some", "look", "looks", "looking",
            "yeah", "okay", "well", "know", "make", "made", "much", "many", "cant", "dont",
            "didnt", "thats", "theres", "its", "for", "and", "but", "not", "are", "was", "were",
            "been", "will", "would", "could", "should", "because", "thing", "things", "time",
            "today", "right", "left", "back", "down", "still", "even", "also", "next", "first",
        )
    }
}

/**
 * Produces titles without any cloud call.
 *
 * Titles are template driven rather than random: each template is paired with the
 * signal that justifies it (a strong hook, a beautiful shot, a clear story), so
 * the suggestions read as though somebody watched the clip.
 */
@Singleton
class LocalTitleGenerator @Inject constructor(
    private val keywordExtractor: KeywordExtractor,
) {

    fun generate(context: VideoContext, highlight: Highlight?): List<TitleSuggestion> {
        val keywords = keywordExtractor.extract(context.transcript, limit = 5)
        val subject = keywords.firstOrNull()
        val place = keywords.getOrNull(1) ?: subject

        val titles = linkedSetOf<TitleSuggestion>()
        val hookText = highlight?.transcriptText
            ?.trim()
            ?.split('.', '!', '?')
            ?.firstOrNull { it.trim().length in 12..70 }
            ?.trim()

        hookText?.let { titles += TitleSuggestion(it.trimEnd('.', ',') + " \uD83D\uDE33", "Hook") }

        if (highlight?.label == HighlightLabel.HOOK || (highlight?.hookScore ?: 0f) > 0.5f) {
            titles += TitleSuggestion("Wait until you see this \uD83E\uDD2F", "Hook")
        }
        subject?.let {
            titles += TitleSuggestion("The $it you have to see", "Curiosity")
            titles += TitleSuggestion("POV: you discover $it by accident", "POV")
        }
        place?.let {
            titles += TitleSuggestion("Didn't expect this in $it \uD83D\uDE0D", "Discovery")
        }
        if (highlight?.label == HighlightLabel.CINEMATIC || (highlight?.visualScore ?: 0f) > 0.65f) {
            titles += TitleSuggestion("The most beautiful place I've filmed \u2728", "Cinematic")
        }
        if (highlight?.label == HighlightLabel.HIGH_ENERGY) {
            titles += TitleSuggestion("This got intense \uD83D\uDD25", "Energy")
        }
        if (highlight?.label == HighlightLabel.STORY) {
            titles += TitleSuggestion("The story behind this moment", "Story")
        }

        titles += TitleSuggestion(defaultTitleFor(context.mode), "Default")

        return titles.toList().take(MAX_TITLES)
    }

    private fun defaultTitleFor(mode: CreationMode): String = when (mode) {
        CreationMode.TRAVEL -> "A hidden gem worth the trip \uD83C\uDFD4"
        CreationMode.ACTION -> "Pure action, no filler \uD83D\uDD25"
        CreationMode.VLOG -> "A moment worth remembering"
        CreationMode.FOOD -> "You can almost taste it \uD83E\uDD24"
        CreationMode.CINEMATIC -> "Slow it down and look around \uD83C\uDFAC"
        CreationMode.SPORTS -> "Watch that again \u26A1"
        CreationMode.AUTO -> "You need to see this \u2728"
    }

    private companion object {
        const val MAX_TITLES = 7
    }
}

@Singleton
class LocalDescriptionGenerator @Inject constructor(
    private val keywordExtractor: KeywordExtractor,
) {

    fun generate(
        context: VideoContext,
        analysis: SceneAnalysisResult,
        highlight: Highlight?,
    ): DescriptionResult {
        val keywords = keywordExtractor.extract(
            listOf(context.transcript, analysis.storySummary.orEmpty()).joinToString(" "),
            limit = 8,
        )
        val subjects = (analysis.subjects + analysis.locations + analysis.activities).distinct()
        val topic = (subjects + keywords).distinct().take(4)

        val opener = when {
            !highlight?.transcriptText.isNullOrBlank() ->
                highlight.transcriptText.trim().split('.', '!', '?').firstOrNull()
                    ?.trim()?.takeIf { it.length > 12 }

            analysis.storySummary?.isNotBlank() == true -> analysis.storySummary
            context.mode == CreationMode.TRAVEL -> "A clip from a trip worth remembering."
            else -> null
        }

        val body = buildString {
            opener?.let { append(it.trimEnd('.', ',')).append(". ") }
            if (topic.isNotEmpty()) {
                append("Filmed with a ")
                append(if (context.isDjiFootage) "DJI action camera" else "compact camera")
                append(" \u2014 captured around ")
                append(topic.joinToString(", ").lowercase())
                append(".")
            } else {
                append("Shot handheld, edited with AI Video Studio.")
            }
        }.trim()

        val hashtags = buildHashtags(context, topic, analysis)

        return DescriptionResult(
            description = body,
            hashtags = hashtags,
            keywords = keywords,
        )
    }

    private fun buildHashtags(
        context: VideoContext,
        topic: List<String>,
        analysis: SceneAnalysisResult,
    ): List<String> {
        val tags = linkedSetOf<String>()
        tags += topic.mapNotNull { it.toHashtag() }
        tags += analysis.tags.mapNotNull { it.toHashtag() }
        when (context.mode) {
            CreationMode.TRAVEL -> tags += listOf("Travel", "TravelShorts", "HiddenGem")
            CreationMode.ACTION -> tags += listOf("ActionCam", "Adrenaline", "POV")
            CreationMode.VLOG -> tags += listOf("Vlog", "DailyVlog", "StoryTime")
            CreationMode.FOOD -> tags += listOf("FoodTok", "Foodie", "Tasty")
            CreationMode.CINEMATIC -> tags += listOf("Cinematic", "Filmmaking", "Moody")
            CreationMode.SPORTS -> tags += listOf("Sports", "Highlights", "Skills")
            CreationMode.AUTO -> tags += listOf("Shorts", "Reels")
        }
        if (context.isDjiFootage) tags += "DJI"
        tags += "AIVideoStudio"
        return tags.take(MAX_HASHTAGS)
    }

    private fun String.toHashtag(): String? {
        val cleaned = filter { it.isLetterOrDigit() }
        if (cleaned.length < 3) return null
        return cleaned.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val MAX_HASHTAGS = 12
    }
}
