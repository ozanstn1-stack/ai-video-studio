package com.aivideostudio.data.local

import com.aivideostudio.domain.model.HighlightReason
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.TranscriptWord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
/**
 * Entities keep every collection in a primitive column. This object owns the
 * (de)serialisation so the mapping code stays free of JSON details and a
 * corrupted value can never crash a query — it degrades to an empty list.
 */
object JsonCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val stringList = ListSerializer(String.serializer())
    private val wordList = ListSerializer(TranscriptWord.serializer())

    fun encodeStrings(value: List<String>): String =
        if (value.isEmpty()) "" else json.encodeToString(stringList, value)

    fun decodeStrings(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(stringList, it) }.getOrNull()
        }.orEmpty()

    /** `badge|text` pairs keep the column human readable in a DB browser. */
    fun encodeReasons(value: List<HighlightReason>): String =
        value.joinToString(SEPARATOR) { "${it.badge}$FIELD_SEPARATOR${it.text}" }

    fun decodeReasons(value: String?): List<HighlightReason> =
        value?.takeIf { it.isNotBlank() }?.split(SEPARATOR)?.mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR, limit = 2)
            if (parts.size == 2) HighlightReason(parts[0], parts[1]) else null
        }.orEmpty()

    fun encodeWords(value: List<TranscriptWord>): String =
        if (value.isEmpty()) "" else json.encodeToString(wordList, value)

    fun decodeWords(value: String?): List<TranscriptWord> =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(wordList, it) }.getOrNull()
        }.orEmpty()

    fun encodeStages(value: List<PipelineStage>): String = encodeStrings(value.map { it.name })

    /** Unknown names are dropped rather than silently becoming the first stage. */
    fun decodeStages(value: String?): List<PipelineStage> =
        decodeStrings(value).mapNotNull { name ->
            PipelineStage.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

    private const val SEPARATOR = "\u001F"
    private const val FIELD_SEPARATOR = "|"
}
