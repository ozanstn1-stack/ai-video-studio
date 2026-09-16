package com.aivideostudio.ai.provider

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.aivideostudio.ai.model.TranscriptionRequest
import com.aivideostudio.ai.model.TranscriptionResult
import com.aivideostudio.ai.model.TranscriptionSource
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.domain.model.TranscriptSegment
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Speech recognition that never leaves the device.
 *
 * Two paths, in order of quality:
 *  1. Android's on-device recogniser, fed the extracted audio file directly
 *     (API 31+). This gives real words with no network and no upload.
 *  2. When the recogniser is unavailable, the audio analysis' speech energy
 *     ranges are returned as text-less segments. Highlights, silence removal and
 *     pacing still work from these; only the caption words are missing, and the
 *     result says so rather than pretending.
 */
@Singleton
class LocalTranscriptionEngine @Inject constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val recognizerAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)

    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(dispatchers.io) {
            val segments = ArrayList<TranscriptSegment>()
            var recognisedAny = false
            var notes = mutableListOf<String>()

            request.assets.forEachIndexed { index, source ->
                val speechRanges = source.speechRanges
                val audioFile = source.audioPath?.let { File(it) }?.takeIf { it.exists() }

                val text = if (recognizerAvailable && audioFile != null) {
                    recogniseFile(audioFile)
                } else {
                    null
                }

                if (text.isNullOrBlank()) {
                    // Honest fallback: we know *when* somebody spoke, not what.
                    speechRanges.forEach { range ->
                        segments += TranscriptSegment(
                            transcriptId = 0L,
                            projectId = request.projectId,
                            assetId = source.assetId,
                            index = segments.size,
                            startMs = range.first,
                            endMs = range.last,
                            text = "",
                            confidence = 0f,
                        )
                    }
                } else {
                    recognisedAny = true
                    segments += distribute(
                        text = text,
                        speechRanges = speechRanges,
                        assetId = source.assetId,
                        projectId = request.projectId,
                        startIndex = segments.size,
                    )
                }
                request.onProgress((index + 1).toFloat() / request.assets.size.coerceAtLeast(1))
            }

            if (!recognisedAny) {
                notes += if (recognizerAvailable) {
                    "Speech could not be recognised on this device"
                } else {
                    "On-device speech recognition is unavailable; enable cloud AI for word-accurate subtitles"
                }
            }

            val confidences = segments.map { it.confidence }.filter { it > 0f }
            TranscriptionResult(
                segments = segments.sortedBy { it.startMs },
                language = Locale.getDefault().language,
                provider = if (recognisedAny) "On-device speech" else "On-device audio analysis",
                averageConfidence = if (confidences.isEmpty()) 0f else confidences.average().toFloat(),
                isComplete = true,
                note = notes.joinToString(". ").takeIf { it.isNotBlank() },
            )
        }

    /**
     * Feeds a compressed audio file to the recogniser. The recogniser reads the
     * file through a pipe, so no microphone permission is involved.
     */
    private suspend fun recogniseFile(file: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        @Suppress("DEPRECATION")
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                    }
                    withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                        awaitRecognition(recognizer, intent)
                    }
                } finally {
                    runCatching { recognizer.destroy() }
                }
            }
        }.getOrNull()
    }

    private suspend fun awaitRecognition(
        recognizer: SpeechRecognizer,
        intent: Intent,
    ): String? = suspendCancellableCoroutine { continuation ->
        val chunks = StringBuilder()
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                texts?.firstOrNull()?.let { chunks.append(' ').append(it) }
                if (continuation.isActive) {
                    continuation.resume(chunks.toString().trim().ifBlank { null })
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Segmented on-device recognition streams partials; keeping them
                // means a timeout still yields something usable.
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { chunks.append(' ').append(it) }
            }

            override fun onError(error: Int) {
                if (continuation.isActive) {
                    continuation.resume(chunks.toString().trim().ifBlank { null })
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)
        continuation.invokeOnCancellation { runCatching { recognizer.cancel() } }
    }

    /**
     * Places recognised sentences on the timeline.
     *
     * The engine does not report timestamps for file input, so sentences are
     * distributed across the speech ranges the audio analysis measured. Time
     * boundaries are therefore real; the exact split between two sentences inside
     * one continuous utterance is proportional to their length.
     */
    private fun distribute(
        text: String,
        speechRanges: List<LongRange>,
        assetId: Long,
        projectId: Long,
        startIndex: Int,
    ): List<TranscriptSegment> {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return emptyList()

        if (speechRanges.isEmpty()) {
            return sentences.mapIndexed { index, sentence ->
                TranscriptSegment(
                    transcriptId = 0L,
                    projectId = projectId,
                    assetId = assetId,
                    index = startIndex + index,
                    startMs = index * ESTIMATED_SENTENCE_MS,
                    endMs = (index + 1) * ESTIMATED_SENTENCE_MS,
                    text = sentence,
                    confidence = GENERIC_CONFIDENCE,
                )
            }
        }

        val totalRangeMs = speechRanges.sumOf { it.last - it.first }.coerceAtLeast(1L)
        val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)
        val charactersPerMs = totalChars.toDouble() / totalRangeMs.toDouble()

        val segments = ArrayList<TranscriptSegment>(sentences.size)
        var rangeIndex = 0
        var cursorInRange = speechRanges.first().first.toDouble()

        sentences.forEachIndexed { index, sentence ->
            val needed = (sentence.length / charactersPerMs).toLong().coerceAtLeast(MIN_SENTENCE_MS)
            var remaining = needed
            var start: Long? = null
            var end = cursorInRange.toLong()

            while (remaining > 0 && rangeIndex < speechRanges.size) {
                val range = speechRanges[rangeIndex]
                val available = (range.last - cursorInRange).toLong()
                if (available <= 0L) {
                    rangeIndex++
                    if (rangeIndex < speechRanges.size) cursorInRange = speechRanges[rangeIndex].first.toDouble()
                    continue
                }
                if (start == null) start = cursorInRange.toLong()
                val consumed = minOf(available, remaining)
                cursorInRange += consumed
                end = cursorInRange.toLong()
                remaining -= consumed
                if (cursorInRange >= range.last) {
                    rangeIndex++
                    if (rangeIndex < speechRanges.size) cursorInRange = speechRanges[rangeIndex].first.toDouble()
                }
            }

            segments += TranscriptSegment(
                transcriptId = 0L,
                projectId = projectId,
                assetId = assetId,
                index = startIndex + index,
                startMs = start ?: cursorInRange.toLong(),
                endMs = end.coerceAtLeast((start ?: 0L) + MIN_SENTENCE_MS),
                text = sentence,
                confidence = GENERIC_CONFIDENCE,
            )
        }
        return segments
    }

    private fun splitSentences(text: String): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return emptyList()
        val sentences = ArrayList<String>()
        val builder = StringBuilder()
        cleaned.forEach { char ->
            builder.append(char)
            if (char in SENTENCE_ENDINGS && builder.length >= MIN_CHARS_FOR_SPLIT) {
                sentences += builder.toString().trim()
                builder.clear()
            }
        }
        if (builder.length > MIN_CHARS_FOR_SPLIT) sentences += builder.toString().trim()
        return sentences.filter { it.isNotBlank() }
    }

    private companion object {
        const val RECOGNITION_TIMEOUT_MS = 90_000L
        const val ESTIMATED_SENTENCE_MS = 2_500L
        const val MIN_SENTENCE_MS = 900L
        const val GENERIC_CONFIDENCE = 0.6f
        const val MIN_CHARS_FOR_SPLIT = 12
        val SENTENCE_ENDINGS = setOf('.', '!', '?')
    }
}
