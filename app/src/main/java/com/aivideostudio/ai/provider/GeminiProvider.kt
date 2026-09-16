package com.aivideostudio.ai.provider

import com.aivideostudio.ai.model.AiResult
import com.aivideostudio.ai.model.DescriptionResult
import com.aivideostudio.ai.model.ProviderCapabilities
import com.aivideostudio.ai.model.SceneAnalysisResult
import com.aivideostudio.ai.model.SceneSummary
import com.aivideostudio.ai.model.TitleSuggestion
import com.aivideostudio.ai.model.TranscriptionRequest
import com.aivideostudio.ai.model.TranscriptionResult
import com.aivideostudio.ai.model.VideoContext
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.AiConfig
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.TranscriptSegment
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Google Gemini backend. Gemini accepts audio and images inline, so the same
 * request shape serves transcription and semantic analysis without a separate
 * upload step.
 */
@Singleton
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) : AIProvider {

    override val id: String = "gemini"

    override val displayName: String = "Google Gemini"

    override val requiresNetwork: Boolean = true

    private var config: AiConfig = AiConfig()
    private var apiKey: String? = null

    fun configure(config: AiConfig, apiKey: String?) {
        this.config = config
        this.apiKey = apiKey
    }

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        available = if (isConfigured()) setOf(
            AiCapability.SEMANTIC_ANALYSIS,
            AiCapability.TITLE_GENERATION,
            AiCapability.DESCRIPTION_GENERATION,
            AiCapability.HIGHLIGHT_REASONING,
            AiCapability.TRANSCRIPTION,
        ) else emptySet(),
        requiresNetwork = true,
        reason = if (isConfigured()) null else "Add a Gemini API key in Settings",
    )

    private fun isConfigured(): Boolean = !apiKey.isNullOrBlank()

    private fun baseUrl(): String =
        config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')

    private fun model(): String = config.model.ifBlank { DEFAULT_MODEL }

    override suspend fun transcribe(request: TranscriptionRequest): AiResult<TranscriptionResult> =
        withContext(dispatchers.io) {
            if (!isConfigured()) return@withContext AiResult.Failure("Gemini is not configured")
            val segments = mutableListOf<TranscriptSegment>()
            var failures = 0

            request.assets.forEachIndexed { index, source ->
                val audio = source.audioPath?.let { File(it) }
                if (audio == null || !audio.exists()) {
                    failures++
                    return@forEachIndexed
                }
                val instruction = buildString {
                    appendLine("Transcribe the speech in this audio.")
                    appendLine(Prompts.transcriptionLanguageHint(request.languageHint))
                    appendLine("Answer with JSON only:")
                    appendLine("""{"language":"en","segments":[{"start":0.0,"end":2.4,"text":"..."}]}""")
                    appendLine("Times are seconds from the start of the audio.")
                }
                val parts = buildJsonArray {
                    add(buildJsonObject { put("text", instruction) })
                    add(inlineAudio(audio))
                }
                when (val outcome = generate(parts)) {
                    is Outcome.Failure -> failures++
                    is Outcome.Success -> {
                        val root = CloudJson.firstObject(outcome.text)
                        root?.arrayOrNull("segments")?.forEach { element ->
                            runCatching {
                                val item = element as JsonObject
                                val text = item.string("text")?.trim().orEmpty()
                                if (text.isEmpty()) return@runCatching
                                segments += TranscriptSegment(
                                    transcriptId = 0L,
                                    projectId = request.projectId,
                                    assetId = source.assetId,
                                    index = segments.size,
                                    startMs = ((item.float("start") ?: 0f) * 1000f).toLong(),
                                    endMs = ((item.float("end") ?: 0f) * 1000f).toLong(),
                                    text = text,
                                    confidence = 0.8f,
                                )
                            }
                        }
                    }
                }
                request.onProgress((index + 1).toFloat() / request.assets.size)
            }

            if (segments.isEmpty() && failures > 0) {
                AiResult.Failure("Speech transcription failed", "gemini returned no segments")
            } else {
                AiResult.Success(
                    TranscriptionResult(
                        segments = segments.sortedBy { it.startMs },
                        language = request.languageHint,
                        provider = displayName,
                        averageConfidence = if (segments.isEmpty()) 0f else 0.8f,
                        isComplete = failures == 0,
                        note = if (failures > 0) "$failures clip(s) could not be transcribed" else null,
                    ),
                    displayName,
                )
            }
        }

    override suspend fun analyzeScenes(
        scenes: List<Scene>,
        context: VideoContext,
    ): AiResult<SceneAnalysisResult> = withContext(dispatchers.io) {
        if (!isConfigured()) return@withContext AiResult.Failure("Gemini is not configured")
        val prompt = Prompts.semantic(context, scenes.map { it.toSummary(context) })
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", prompt) })
            context.frames.take(MAX_FRAMES).forEach { frame ->
                inlineImage(frame.path)?.let { add(it) }
            }
        }
        when (val outcome = generate(parts)) {
            is Outcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
            is Outcome.Success -> {
                val root = CloudJson.firstObject(outcome.text)
                    ?: return@withContext AiResult.Failure("The Gemini response could not be understood")
                AiResult.Success(
                    SceneAnalysisResult(
                        subjects = root.stringList("subjects"),
                        locations = root.stringList("locations"),
                        activities = root.stringList("activities"),
                        mood = root.string("mood"),
                        tags = root.stringList("tags"),
                        storySummary = root.string("storySummary"),
                        provider = displayName,
                        confidence = 0.8f,
                    ),
                    displayName,
                )
            }
        }
    }

    override suspend fun detectHighlights(
        scenes: List<Scene>,
        context: VideoContext,
        localCandidates: List<Highlight>,
    ): AiResult<List<Highlight>> = withContext(dispatchers.io) {
        if (!isConfigured() || localCandidates.isEmpty()) {
            return@withContext AiResult.Success(localCandidates, displayName)
        }
        val lines = localCandidates.map {
            "${it.startMs}-${it.endMs} label=${it.label.displayName} " +
                "transcript=\"${it.transcriptText.orEmpty().take(160)}\""
        }
        val parts = buildJsonArray { add(buildJsonObject { put("text", Prompts.rerank(context, lines)) }) }
        when (val outcome = generate(parts)) {
            is Outcome.Failure -> AiResult.Success(localCandidates, displayName)
            is Outcome.Success -> {
                val ranking = CloudJson.firstObject(outcome.text)?.arrayOrNull("ranking")
                    ?: return@withContext AiResult.Success(localCandidates, displayName)
                val scores = HashMap<Int, Float>()
                ranking.forEach { element ->
                    runCatching {
                        val item = element as JsonObject
                        val index = item.int("index") ?: return@runCatching
                        scores[index] = (item.float("score") ?: return@runCatching).coerceIn(0f, 1f)
                    }
                }
                AiResult.Success(
                    localCandidates.mapIndexed { index, highlight ->
                        val boost = scores[index]
                        if (boost == null) {
                            highlight
                        } else {
                            highlight.copy(
                                overallScore = (highlight.overallScore * 0.55f + boost * 0.45f)
                                    .coerceIn(0f, 1f),
                            )
                        }
                    }.sortedByDescending { it.overallScore }
                        .mapIndexed { index, highlight -> highlight.copy(orderIndex = index) },
                    displayName,
                )
            }
        }
    }

    override suspend fun generateTitles(
        context: VideoContext,
        highlight: Highlight?,
    ): AiResult<List<TitleSuggestion>> = withContext(dispatchers.io) {
        if (!isConfigured()) return@withContext AiResult.Failure("Gemini is not configured")
        val summary = highlight?.transcriptText?.take(400)
            ?: "${highlight?.label?.displayName ?: "Moment"} from ${context.assetNames.firstOrNull()}"
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", Prompts.titlesAndDescription(context, summary)) })
        }
        when (val outcome = generate(parts)) {
            is Outcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
            is Outcome.Success -> {
                val titles = CloudJson.firstObject(outcome.text)
                    ?.arrayOrNull("titles")
                    ?.mapNotNull { element ->
                        runCatching {
                            val item = element as JsonObject
                            val text = item.string("text") ?: return@runCatching null
                            TitleSuggestion(text, item.string("style") ?: "AI")
                        }.getOrNull()
                    }.orEmpty()
                if (titles.isEmpty()) {
                    AiResult.Failure("No titles were generated", outcome.text.take(300))
                } else {
                    AiResult.Success(titles, displayName)
                }
            }
        }
    }

    override suspend fun generateDescription(
        context: VideoContext,
        analysis: SceneAnalysisResult,
        highlight: Highlight?,
    ): AiResult<DescriptionResult> = withContext(dispatchers.io) {
        if (!isConfigured()) return@withContext AiResult.Failure("Gemini is not configured")
        val summary = listOfNotNull(analysis.storySummary, highlight?.transcriptText?.take(300))
            .joinToString(" ")
            .ifBlank { "A short clip from ${context.projectName}" }
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", Prompts.titlesAndDescription(context, summary)) })
        }
        when (val outcome = generate(parts)) {
            is Outcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
            is Outcome.Success -> {
                val root = CloudJson.firstObject(outcome.text)
                    ?: return@withContext AiResult.Failure("The Gemini response could not be understood")
                val description = root.string("description")
                    ?: return@withContext AiResult.Failure("No description was generated")
                AiResult.Success(
                    DescriptionResult(
                        description = description,
                        hashtags = root.stringList("hashtags").map { it.removePrefix("#") }.take(14),
                        keywords = root.stringList("keywords"),
                    ),
                    displayName,
                )
            }
        }
    }

    // ------------------------------------------------------------------ transport

    private sealed interface Outcome {
        data class Success(val text: String) : Outcome
        data class Failure(val message: String, val technical: String? = null) : Outcome
    }

    private suspend fun generate(parts: kotlinx.serialization.json.JsonArray): Outcome {
        val body = buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    putJsonArrayText(Prompts.ASSISTANT_SYSTEM)
                },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", parts)
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", 0.4)
                    put("responseMimeType", "application/json")
                },
            )
        }

        val request = Request.Builder()
            .url("${baseUrl()}/models/${model()}:generateContent")
            .addHeader("x-goog-api-key", apiKey.orEmpty())
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newBuilder()
                .callTimeout(config.timeoutSeconds.toLong().coerceAtLeast(15L), TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    val payload = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val text = extractText(payload)
                            if (text.isNullOrBlank()) {
                                Outcome.Failure("Gemini returned an empty answer", payload.take(300))
                            } else {
                                Outcome.Success(text)
                            }
                        }

                        response.code == 400 || response.code == 403 ->
                            Outcome.Failure("Gemini rejected the API key", "HTTP ${response.code}")

                        response.code == 429 ->
                            Outcome.Failure("Gemini is rate limiting requests", "HTTP 429")

                        else -> Outcome.Failure(
                            "The Gemini request failed",
                            "HTTP ${response.code}: ${payload.take(300)}",
                        )
                    }
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Outcome.Failure("Could not reach Gemini", error.message)
        }
    }

    private fun extractText(payload: String): String? {
        val root = CloudJson.firstObject(payload) ?: return null
        val candidate = root.arrayOrNull("candidates")?.firstOrNull() as? JsonObject ?: return null
        val content = candidate.objectOrNull("content") ?: return null
        val parts = content.arrayOrNull("parts") ?: return null
        return parts.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("").ifBlank { null }
    }

    private fun inlineAudio(file: File): JsonObject {
        val encoded = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        return buildJsonObject {
            put(
                "inline_data",
                buildJsonObject {
                    put("mime_type", "audio/mp4")
                    put("data", encoded)
                },
            )
        }
    }

    private fun inlineImage(path: String): JsonObject? = runCatching {
        val file = File(path)
        if (!file.exists() || file.length() > MAX_IMAGE_BYTES) return null
        val encoded = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        buildJsonObject {
            put(
                "inline_data",
                buildJsonObject {
                    put("mime_type", "image/jpeg")
                    put("data", encoded)
                },
            )
        }
    }.getOrNull()

    private fun JsonObjectBuilder.putJsonArrayText(text: String) {
        put(
            "parts",
            buildJsonArray { add(buildJsonObject { put("text", text) }) },
        )
    }

    private fun Scene.toSummary(context: VideoContext): SceneSummary = SceneSummary(
        assetName = context.assets.firstOrNull { it.id == assetId }?.displayName.orEmpty(),
        startMs = startMs,
        endMs = endMs,
        brightness = brightness,
        motion = motion,
        sharpness = sharpness,
        framing = framing.displayName,
        issue = if (issue.name == "NONE") "" else issue.displayName,
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-2.0-flash"
        const val MAX_FRAMES = 8
        const val MAX_IMAGE_BYTES = 1_500_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
