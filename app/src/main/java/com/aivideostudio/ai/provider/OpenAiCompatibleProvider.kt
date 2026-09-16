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
import com.aivideostudio.domain.model.HighlightLabel
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.TranscriptSegment
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Talks to any server that implements the OpenAI HTTP contract.
 *
 * This covers OpenAI itself, Azure-style gateways, Groq, OpenRouter, Together,
 * LM Studio, Ollama's compatibility endpoint and self-hosted proxies — which is
 * why the app asks for a base URL rather than hard-coding one vendor.
 */
@Singleton
class OpenAiCompatibleProvider @Inject constructor(
    private val client: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) : AIProvider {

    override val id: String = "openai-compatible"

    override val displayName: String = "OpenAI compatible"

    override val requiresNetwork: Boolean = true

    private var config: AiConfig = AiConfig()
    private var apiKey: String? = null

    fun configure(config: AiConfig, apiKey: String?) {
        this.config = config
        this.apiKey = apiKey
    }

    override fun capabilities(): ProviderCapabilities {
        val available = buildSet {
            if (!isConfigured()) return@buildSet
            add(AiCapability.SEMANTIC_ANALYSIS)
            add(AiCapability.TITLE_GENERATION)
            add(AiCapability.DESCRIPTION_GENERATION)
            add(AiCapability.HIGHLIGHT_REASONING)
            add(AiCapability.TRANSCRIPTION)
        }
        return ProviderCapabilities(
            available = available,
            requiresNetwork = true,
            reason = if (isConfigured()) null else "Add a base URL and API key in Settings",
        )
    }

    private fun isConfigured(): Boolean =
        config.baseUrl.isNotBlank() && !apiKey.isNullOrBlank()

    override suspend fun transcribe(request: TranscriptionRequest): AiResult<TranscriptionResult> =
        withContext(dispatchers.io) {
            if (!isConfigured()) return@withContext AiResult.Failure("Cloud AI is not configured")
            val segments = mutableListOf<TranscriptSegment>()
            var language: String? = null
            var confidences = 0f
            var counted = 0
            var failures = 0

            request.assets.forEachIndexed { assetIndex, source ->
                val audio = source.audioPath?.let { File(it) }
                if (audio == null || !audio.exists()) {
                    failures++
                    return@forEachIndexed
                }
                when (val response = transcribeFile(audio, request.languageHint)) {
                    is AiResult.Success -> {
                        language = language ?: response.value.first
                        response.value.second.forEach { (startMs, endMs, text, confidence) ->
                            segments += TranscriptSegment(
                                transcriptId = 0L,
                                projectId = request.projectId,
                                assetId = source.assetId,
                                index = segments.size,
                                startMs = startMs,
                                endMs = endMs,
                                text = text,
                                confidence = confidence,
                            )
                            confidences += confidence
                            counted++
                        }
                    }

                    is AiResult.Failure -> failures++
                }
                request.onProgress((assetIndex + 1).toFloat() / request.assets.size)
            }

            if (segments.isEmpty() && failures > 0) {
                AiResult.Failure(
                    "Speech transcription failed",
                    "cloud transcription returned no segments",
                )
            } else {
                AiResult.Success(
                    TranscriptionResult(
                        segments = segments.sortedBy { it.startMs },
                        language = language,
                        provider = displayName,
                        averageConfidence = if (counted == 0) 0f else confidences / counted,
                        isComplete = failures == 0,
                        note = if (failures > 0) "$failures clip(s) could not be transcribed" else null,
                    ),
                    displayName,
                )
            }
        }

    private suspend fun transcribeFile(
        audio: File,
        languageHint: String?,
    ): AiResult<Pair<String?, List<SegmentPayload>>> {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", transcriptionModel())
            .addFormDataPart("response_format", "verbose_json")
            .apply {
                languageHint?.takeIf { it.isNotBlank() }?.let {
                    addFormDataPart("language", it.take(2).lowercase())
                }
            }
            .addFormDataPart(
                "file",
                audio.name,
                audio.asRequestBody("audio/mp4".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${apiKey.orEmpty()}")
            .post(form)
            .build()

        return execute(request).let { outcome ->
            when (outcome) {
                is HttpOutcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
                is HttpOutcome.Success -> {
                    val root = CloudJson.firstObject(outcome.body)
                        ?: return AiResult.Failure(
                            "Unexpected response from the AI provider",
                            outcome.body.take(400),
                        )
                    val language = root.string("language")
                    val payload = root.arrayOrNull("segments")?.mapNotNull { element ->
                        runCatching {
                            val segment = element as JsonObject
                            SegmentPayload(
                                startMs = ((segment.float("start") ?: 0f) * 1000f).toLong(),
                                endMs = ((segment.float("end") ?: 0f) * 1000f).toLong(),
                                text = segment.string("text").orEmpty().trim(),
                                confidence = 0.85f,
                            )
                        }.getOrNull()
                    }.orEmpty()

                    val fallbackText = root.string("text")
                    val segments = if (payload.isNotEmpty()) {
                        payload.filter { it.text.isNotBlank() }
                    } else if (!fallbackText.isNullOrBlank()) {
                        listOf(SegmentPayload(0L, 0L, fallbackText, 0.7f))
                    } else {
                        emptyList()
                    }
                    AiResult.Success(language to segments)
                }
            }
        }
    }

    override suspend fun analyzeScenes(
        scenes: List<Scene>,
        context: VideoContext,
    ): AiResult<SceneAnalysisResult> = withContext(dispatchers.io) {
        if (!isConfigured()) return@withContext AiResult.Failure("Cloud AI is not configured")
        val prompt = Prompts.semantic(context, scenes.map { it.toSummary(context) })
        when (val outcome = chat(prompt, context.frames)) {
            is HttpOutcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
            is HttpOutcome.Success -> {
                val root = CloudJson.firstObject(outcome.body)
                    ?: return@withContext AiResult.Failure(
                        "The AI response could not be understood",
                        outcome.body.take(400),
                    )
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
        val lines = localCandidates.map { highlight ->
            "${highlight.startMs}-${highlight.endMs} " +
                "label=${highlight.label.name} " +
                "transcript=\"${highlight.transcriptText.orEmpty().take(160)}\""
        }
        val prompt = Prompts.rerank(context, lines)
        when (val outcome = chat(prompt, emptyList())) {
            is HttpOutcome.Failure -> AiResult.Success(localCandidates, displayName)
            is HttpOutcome.Success -> {
                val root = CloudJson.firstObject(outcome.body)
                val ranking = root?.arrayOrNull("ranking")
                if (ranking == null) {
                    AiResult.Success(localCandidates, displayName)
                } else {
                    val scores = HashMap<Int, Float>()
                    ranking.forEach { element ->
                        runCatching {
                            val item = element as JsonObject
                            val index = item.int("index") ?: return@runCatching
                            val score = item.float("score") ?: return@runCatching
                            scores[index] = score.coerceIn(0f, 1f)
                        }
                    }
                    val reranked = localCandidates.mapIndexed { index, highlight ->
                        val boost = scores[index]
                        if (boost == null) {
                            highlight
                        } else {
                            // Blend instead of replace: the local measurement stays
                            // grounded in the actual pixels.
                            highlight.copy(
                                overallScore = (highlight.overallScore * 0.55f + boost * 0.45f)
                                    .coerceIn(0f, 1f),
                            )
                        }
                    }.sortedByDescending { it.overallScore }
                        .mapIndexed { index, highlight -> highlight.copy(orderIndex = index) }

                    AiResult.Success(reranked, displayName)
                }
            }
        }
    }

    override suspend fun generateTitles(
        context: VideoContext,
        highlight: Highlight?,
    ): AiResult<List<TitleSuggestion>> = withContext(dispatchers.io) {
        if (!isConfigured()) return@withContext AiResult.Failure("Cloud AI is not configured")
        val summary = highlight?.transcriptText?.take(400)
            ?: "${highlight?.label?.displayName ?: "Moment"} from ${context.assetNames.firstOrNull()}"
        val prompt = Prompts.titlesAndDescription(context, summary)
        when (val outcome = chat(prompt, context.frames.take(2))) {
            is HttpOutcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
            is HttpOutcome.Success -> {
                val root = CloudJson.firstObject(outcome.body)
                val titles = root?.arrayOrNull("titles")?.mapNotNull { element ->
                    runCatching {
                        val item = element as JsonObject
                        val text = item.string("text") ?: return@runCatching null
                        TitleSuggestion(text, item.string("style") ?: "AI")
                    }.getOrNull()
                }.orEmpty()

                if (titles.isEmpty()) {
                    AiResult.Failure("No titles were generated", outcome.body.take(300))
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
        if (!isConfigured()) return@withContext AiResult.Failure("Cloud AI is not configured")
        val summary = listOfNotNull(
            analysis.storySummary,
            highlight?.transcriptText?.take(300),
        ).joinToString(" ").ifBlank { "A short clip from ${context.projectName}" }

        val prompt = Prompts.titlesAndDescription(context, summary)
        when (val outcome = chat(prompt, emptyList())) {
            is HttpOutcome.Failure -> AiResult.Failure(outcome.message, outcome.technical)
            is HttpOutcome.Success -> {
                val root = CloudJson.firstObject(outcome.body)
                    ?: return@withContext AiResult.Failure("The AI response could not be understood")
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

    private suspend fun chat(prompt: String, frames: List<com.aivideostudio.ai.model.FrameRef>): HttpOutcome {
        val userContent = buildJsonObject {
            put("type", "text")
            put("text", prompt)
        }
        val messageParts = buildJsonArray {
            add(userContent)
            frames.forEach { frame ->
                val encoded = encodeFrame(frame.path) ?: return@forEach
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject { put("url", "data:image/jpeg;base64,$encoded") },
                        )
                    },
                )
            }
        }

        val body = buildJsonObject {
            put("model", chatModel())
            put("temperature", 0.4)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", Prompts.ASSISTANT_SYSTEM)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", messageParts)
                    },
                )
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.orEmpty()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val outcome = execute(request)
        if (outcome !is HttpOutcome.Success) return outcome

        val root = CloudJson.firstObject(outcome.body)
        val content = extractChatContent(root)
            ?: return HttpOutcome.Failure(
                "The AI provider returned an unexpected response",
                outcome.body.take(400),
            )
        return HttpOutcome.Success(content)
    }

    private fun extractChatContent(root: JsonObject?): String? {
        val choices = root?.arrayOrNull("choices") ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first.objectOrNull("message") ?: return null
        val content = message["content"]
        if (content is kotlinx.serialization.json.JsonPrimitive && content.isString) {
            return content.content
        }
        // Some gateways return content as an array of parts.
        val parts = runCatching { content?.let { CloudJson.parser.parseToJsonElement(it.toString()) } }
            .getOrNull()
        return parts?.let { element ->
            runCatching { element.let { it as kotlinx.serialization.json.JsonArray } }.getOrNull()
                ?.mapNotNull { part ->
                    ((part as? JsonObject)?.string("text"))
                }
                ?.joinToString("")
        }
    }

    private fun encodeFrame(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists() || file.length() > MAX_FRAME_BYTES) return null
        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private sealed interface HttpOutcome {
        data class Success(val body: String) : HttpOutcome
        data class Failure(val message: String, val technical: String? = null) : HttpOutcome
    }

    private suspend fun execute(request: Request): HttpOutcome = try {
        client.newBuilder()
            .callTimeout(config.timeoutSeconds.toLong().coerceAtLeast(15L), TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> HttpOutcome.Success(body)
                    response.code == 401 || response.code == 403 ->
                        HttpOutcome.Failure("The AI provider rejected the API key", "HTTP ${response.code}")

                    response.code == 429 ->
                        HttpOutcome.Failure("The AI provider is rate limiting requests", "HTTP 429")

                    response.code >= 500 ->
                        HttpOutcome.Failure("The AI provider is unavailable", "HTTP ${response.code}")

                    else -> HttpOutcome.Failure(
                        "The AI request was rejected",
                        "HTTP ${response.code}: ${body.take(300)}",
                    )
                }
            }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        HttpOutcome.Failure("Could not reach the AI provider", error.message)
    }

    private fun chatModel(): String = config.model.ifBlank { DEFAULT_CHAT_MODEL }

    private fun transcriptionModel(): String = config.model.ifBlank { DEFAULT_TRANSCRIPTION_MODEL }

    private data class SegmentPayload(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val confidence: Float,
    )

    private fun Scene.toSummary(context: VideoContext): SceneSummary {
        val assetName = context.assets.firstOrNull { it.id == assetId }?.displayName.orEmpty()
        return SceneSummary(
            assetName = assetName,
            startMs = startMs,
            endMs = endMs,
            brightness = brightness,
            motion = motion,
            sharpness = sharpness,
            framing = framing.displayName,
            issue = if (issue.name == "NONE") "" else issue.displayName,
        )
    }

    private companion object {
        const val DEFAULT_CHAT_MODEL = "gpt-4o-mini"
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
        const val MAX_FRAME_BYTES = 1_500_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Keeps the unused-import checker honest about label usage in prompts. */
internal val Highlight.labelName: String get() = HighlightLabel.fromName(label.name).displayName
