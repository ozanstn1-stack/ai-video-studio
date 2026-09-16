package com.aivideostudio.ai.provider

import com.aivideostudio.ai.model.SceneSummary
import com.aivideostudio.ai.model.VideoContext

/**
 * Prompts live in one place so the wording can be tuned without touching the
 * transport code, and so every request asks for the same strict JSON shape.
 */
internal object Prompts {

    const val ASSISTANT_SYSTEM =
        "You are a professional short-form video editor. You analyze raw camera " +
            "footage and describe it precisely and briefly. You always answer with " +
            "valid JSON only, with no markdown fences and no commentary."

    fun semantic(context: VideoContext, scenes: List<SceneSummary>): String = buildString {
        appendLine("Analyze this footage and describe what it contains.")
        appendLine()
        appendLine("Project: ${context.projectName}")
        appendLine("Intent: ${context.mode.displayName} (${context.mode.description})")
        appendLine("Clips: ${context.assetNames.joinToString(", ")}")
        appendLine("Total length: ${context.totalDurationMs / 1000} seconds")
        if (context.isDjiFootage) appendLine("Camera: DJI action camera footage")
        if (context.transcript.isNotBlank()) {
            appendLine()
            appendLine("Speech transcript (may be partial):")
            appendLine(context.transcript.take(TRANSCRIPT_BUDGET))
        }
        if (scenes.isNotEmpty()) {
            appendLine()
            appendLine("Detected shots (start-end ms, framing, issue):")
            scenes.take(SCENE_BUDGET).forEach { scene ->
                appendLine(
                    "- ${scene.startMs}-${scene.endMs} ${scene.framing} " +
                        "brightness=${scene.brightness.round()} motion=${scene.motion.round()}" +
                        if (scene.issue.isNotBlank()) " issue=${scene.issue}" else "",
                )
            }
        }
        appendLine()
        appendLine(
            """
            Answer with this JSON object:
            {
              "subjects": ["what or who is on screen"],
              "locations": ["recognisable places, if any"],
              "activities": ["what is happening"],
              "mood": "one or two words",
              "tags": ["short topical tags"],
              "storySummary": "one sentence describing the footage",
              "highlightWindows": [
                {"startMs": 0, "endMs": 0, "label": "Hook|Cinematic|HighEnergy|Story|Payoff|Scenic", "reason": "why"}
              ]
            }
            """.trimIndent(),
        )
    }

    fun titlesAndDescription(context: VideoContext, clipSummary: String): String = buildString {
        appendLine("Write short-form video metadata for this clip.")
        appendLine()
        appendLine("Original footage: ${context.assetNames.joinToString(", ")}")
        appendLine("Clip content: $clipSummary")
        if (context.transcript.isNotBlank()) {
            appendLine("Speech: ${context.transcript.take(TRANSCRIPT_BUDGET)}")
        }
        appendLine()
        appendLine("Audience: TikTok, Instagram Reels, YouTube Shorts. Tone: natural, curious, never clickbait-spam.")
        appendLine()
        appendLine(
            """
            Answer with this JSON object:
            {
              "titles": [{"text": "...", "style": "Hook|Curiosity|POV|Descriptive"}],
              "description": "one or two sentences",
              "hashtags": ["#tag without the hash also accepted"],
              "keywords": ["search keywords"]
            }
            """.trimIndent(),
        )
    }

    fun rerank(context: VideoContext, candidates: List<String>): String = buildString {
        appendLine("You are ranking candidate moments from one recording.")
        appendLine("Goal: pick moments that make a coherent ${context.targetShortDurationSec}s")
        appendLine("${context.mode.displayName} short with a strong opening and a satisfying ending.")
        appendLine()
        appendLine("Candidates (index, start ms, end ms, transcript):")
        candidates.forEachIndexed { index, line -> appendLine("$index. $line") }
        appendLine()
        appendLine(
            """
            Answer with this JSON object:
            {
              "ranking": [{"index": 0, "score": 0.0, "reason": "why this works"}]
            }
            Scores are 0..1. Rank every candidate you were given.
            """.trimIndent(),
        )
    }

    fun transcriptionLanguageHint(language: String?): String =
        language?.let { "The recording is in $it." } ?: ""

    private fun Float.round(): String = ((this * 100).toInt() / 100f).toString()

    private const val TRANSCRIPT_BUDGET = 4_000
    private const val SCENE_BUDGET = 60
}
