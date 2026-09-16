package com.aivideostudio.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Language models are asked for JSON, but they still wrap it in prose, fences or
 * a "sure!" preamble often enough that parsing has to be forgiving. These helpers
 * pull the first well-formed object or array out of a response and expose typed
 * accessors that degrade to defaults instead of throwing.
 */
internal object CloudJson {

    val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun firstObject(text: String): JsonObject? = firstElement(text, '{', '}')?.jsonObjectOrNull()

    fun firstArray(text: String): JsonArray? = firstElement(text, '[', ']')?.let {
        runCatching { parser.parseToJsonElement(it).jsonArray }.getOrNull()
    }

    private fun firstElement(text: String, open: Char, close: Char): String? {
        val start = text.indexOf(open)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == open -> depth++
                char == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun String.jsonObjectOrNull(): JsonObject? =
        runCatching { parser.parseToJsonElement(this).jsonObject }.getOrNull()
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.stringList(key: String): List<String> {
    val element: JsonElement = this[key] ?: return emptyList()
    val array = runCatching { element.jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { item ->
        runCatching { item.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? =
    (this[key] as? JsonObject)

internal fun JsonObject.arrayOrNull(key: String): JsonArray? =
    (this[key] as? JsonArray)
