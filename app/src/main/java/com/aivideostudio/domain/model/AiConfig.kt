package com.aivideostudio.domain.model

/**
 * Which engine answers AI requests. `LOCAL` never leaves the device;
 * `OPENAI_COMPATIBLE` covers OpenAI, Groq, OpenRouter, LM Studio, Ollama's
 * OpenAI shim and anything else speaking the same protocol.
 */
enum class AiProviderType(val displayName: String, val requiresNetwork: Boolean) {
    LOCAL("On-device", false),
    OPENAI_COMPATIBLE("OpenAI compatible", true),
    GEMINI("Google Gemini", true),
    ;

    companion object {
        fun fromName(value: String?): AiProviderType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}

/** Mirrors the privacy setting described in the product brief. */
enum class AiProcessingMode(val displayName: String, val description: String) {
    LOCAL_ONLY("Local only", "Nothing ever leaves your device"),
    CLOUD_WHEN_NECESSARY("Cloud when necessary", "Cloud AI is used only for tasks your device cannot do"),
    ALWAYS_ASK("Always ask", "You confirm before anything is uploaded"),
    ;

    companion object {
        fun fromName(value: String?): AiProcessingMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ALWAYS_ASK
    }
}

data class AiConfig(
    val providerType: AiProviderType = AiProviderType.LOCAL,
    val processingMode: AiProcessingMode = AiProcessingMode.ALWAYS_ASK,
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    /** Seconds before a cloud request is abandoned. */
    val timeoutSeconds: Int = 60,
    /** Whether the user has consented to uploading frames for the current session. */
    val cloudConsentGranted: Boolean = false,
    val enableSemanticAnalysis: Boolean = true,
    val enableTitleGeneration: Boolean = true,
) {
    val isCloudConfigured: Boolean
        get() = providerType.requiresNetwork && baseUrl.isNotBlank() && hasApiKey
}

/** What we are allowed to send to a cloud provider, decided before any network call. */
data class CloudConsentRequest(
    val providerName: String,
    val items: List<String>,
    val frameCount: Int = 0,
    val audioSeconds: Int = 0,
)

enum class AiCapability {
    TRANSCRIPTION,
    SEMANTIC_ANALYSIS,
    TITLE_GENERATION,
    DESCRIPTION_GENERATION,
    HIGHLIGHT_REASONING,
}
