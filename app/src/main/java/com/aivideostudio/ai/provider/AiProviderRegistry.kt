package com.aivideostudio.ai.provider

import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.domain.model.AiCapability
import com.aivideostudio.domain.model.AiConfig
import com.aivideostudio.domain.model.AiProcessingMode
import com.aivideostudio.domain.model.AiProviderType
import com.aivideostudio.domain.model.CloudConsentRequest
import com.aivideostudio.domain.repository.SettingsRepository
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides who answers each request.
 *
 * The privacy model lives here: a cloud provider is only reachable when the user
 * chose one, configured credentials, selected a processing mode that permits it,
 * and — in "Always ask" mode — explicitly consented for this session. In every
 * other case the on-device provider answers, so AI features never simply break.
 */
@Singleton
class AiProviderRegistry @Inject constructor(
    private val localProvider: LocalAIProvider,
    private val openAiProvider: OpenAiCompatibleProvider,
    private val geminiProvider: GeminiProvider,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) {

    @Volatile
    private var cachedConfig: AiConfig = AiConfig()

    /** Reloads credentials from encrypted storage. Called before each pipeline run. */
    suspend fun refresh(): AiConfig = withContext(dispatchers.io) {
        val preferences = settings.snapshot()
        val key = settings.apiKey()
        val config = preferences.ai
        cachedConfig = config
        openAiProvider.configure(config, key)
        geminiProvider.configure(config, key)
        config
    }

    fun currentConfig(): AiConfig = cachedConfig

    fun local(): AIProvider = localProvider

    fun cloudProvider(): AIProvider? = when (cachedConfig.providerType) {
        AiProviderType.LOCAL -> null
        AiProviderType.OPENAI_COMPATIBLE -> openAiProvider
        AiProviderType.GEMINI -> geminiProvider
    }

    /**
     * Whether a cloud call is permitted right now. Headless callers (workers) get
     * the session consent granted from the confirmation dialog; interactive
     * callers leave it unset and the UI asks first.
     */
    fun cloudAllowed(): Boolean {
        val config = cachedConfig
        if (config.providerType == AiProviderType.LOCAL) return false
        if (!config.isCloudConfigured) return false
        return when (config.processingMode) {
            AiProcessingMode.LOCAL_ONLY -> false
            AiProcessingMode.CLOUD_WHEN_NECESSARY -> true
            AiProcessingMode.ALWAYS_ASK -> config.cloudConsentGranted
        }
    }

    /** The provider that will actually handle [capability], with a safe fallback. */
    fun resolve(capability: AiCapability): AIProvider {
        if (!cloudAllowed()) return localProvider
        val cloud = cloudProvider() ?: return localProvider
        return if (capability in cloud.capabilities().available) cloud else localProvider
    }

    fun usedCloud(capability: AiCapability): Boolean = resolve(capability) !== localProvider

    /** What the user has to approve before anything is uploaded. */
    fun describeConsent(
        stageNames: List<String>,
        frameCount: Int,
        audioSeconds: Int,
    ): CloudConsentRequest? {
        val cloud = cloudProvider() ?: return null
        if (cachedConfig.processingMode == AiProcessingMode.LOCAL_ONLY) return null

        val items = buildList {
            if (audioSeconds > 0) {
                add("A compressed audio-only file (no video) for ${audioSeconds / 60 + 1} min of speech")
            }
            if (frameCount > 0) {
                add("$frameCount still frame${if (frameCount == 1) "" else "s"} for scene understanding")
            }
            if (stageNames.isNotEmpty()) {
                add("A text summary of the analyzed footage")
            }
        }.ifEmpty { listOf("A text summary of your footage") }

        return CloudConsentRequest(
            providerName = cloud.displayName,
            items = items,
            frameCount = frameCount,
            audioSeconds = audioSeconds,
        )
    }

    fun allProviders(): List<AIProvider> = listOf(localProvider, openAiProvider, geminiProvider)

    fun providerById(id: String): AIProvider? = allProviders().firstOrNull { it.id == id }
}
