package com.aivideostudio.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aivideostudio.core.security.SecretStore
import com.aivideostudio.domain.model.AiConfig
import com.aivideostudio.domain.model.AiProcessingMode
import com.aivideostudio.domain.model.AiProviderType
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.VideoQuality
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.domain.repository.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val context: Context,
    private val secretStore: SecretStore,
) : SettingsRepository {

    override val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            onboardingCompleted = prefs[Keys.ONBOARDING] ?: false,
            defaultAspectRatio = AspectRatio.fromName(prefs[Keys.ASPECT_RATIO]),
            defaultShortCount = prefs[Keys.SHORT_COUNT] ?: 3,
            defaultShortDurationSec = prefs[Keys.SHORT_DURATION] ?: 30,
            defaultSubtitleStyle = SubtitleStyle.fromName(prefs[Keys.SUBTITLE_STYLE]),
            defaultQuality = VideoQuality.fromName(prefs[Keys.QUALITY]),
            defaultFrameRate = FrameRateOption.fromName(prefs[Keys.FRAME_RATE]),
            ai = AiConfig(
                providerType = AiProviderType.fromName(prefs[Keys.PROVIDER_TYPE]),
                processingMode = AiProcessingMode.fromName(prefs[Keys.PROCESSING_MODE]),
                baseUrl = prefs[Keys.BASE_URL].orEmpty(),
                model = prefs[Keys.MODEL].orEmpty(),
                hasApiKey = !prefs[Keys.API_KEY].isNullOrBlank(),
                timeoutSeconds = prefs[Keys.TIMEOUT] ?: 60,
                cloudConsentGranted = prefs[Keys.CLOUD_CONSENT] ?: false,
                enableSemanticAnalysis = prefs[Keys.SEMANTIC] ?: true,
                enableTitleGeneration = prefs[Keys.TITLES] ?: true,
            ),
            exportFolderUri = prefs[Keys.EXPORT_FOLDER],
            debugModeEnabled = prefs[Keys.DEBUG_MODE] ?: false,
            useHardwareAcceleration = prefs[Keys.HW_ACCEL] ?: true,
            keepSourceAudio = prefs[Keys.KEEP_AUDIO] ?: true,
        )
    }

    override suspend fun snapshot(): UserPreferences = preferences.first()

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        put(Keys.ONBOARDING, completed)

    override suspend fun setDefaultAspectRatio(value: AspectRatio) =
        put(Keys.ASPECT_RATIO, value.name)

    override suspend fun setDefaultShortCount(value: Int) = put(Keys.SHORT_COUNT, value)

    override suspend fun setDefaultShortDuration(value: Int) =
        put(Keys.SHORT_DURATION, value.coerceIn(10, 180))

    override suspend fun setDefaultSubtitleStyle(value: SubtitleStyle) =
        put(Keys.SUBTITLE_STYLE, value.name)

    override suspend fun setDefaultQuality(value: VideoQuality) = put(Keys.QUALITY, value.name)

    override suspend fun setDefaultFrameRate(value: FrameRateOption) =
        put(Keys.FRAME_RATE, value.name)

    override suspend fun setExportFolder(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.EXPORT_FOLDER) else prefs[Keys.EXPORT_FOLDER] = uri
        }
    }

    override suspend fun setDebugMode(enabled: Boolean) = put(Keys.DEBUG_MODE, enabled)

    override suspend fun setHardwareAcceleration(enabled: Boolean) = put(Keys.HW_ACCEL, enabled)

    override suspend fun setKeepSourceAudio(enabled: Boolean) = put(Keys.KEEP_AUDIO, enabled)

    override suspend fun setProviderType(value: AiProviderType) =
        put(Keys.PROVIDER_TYPE, value.name)

    override suspend fun setProcessingMode(value: AiProcessingMode) =
        put(Keys.PROCESSING_MODE, value.name)

    override suspend fun setBaseUrl(value: String) = put(Keys.BASE_URL, value.trim())

    override suspend fun setModel(value: String) = put(Keys.MODEL, value.trim())

    override suspend fun setTimeoutSeconds(value: Int) =
        put(Keys.TIMEOUT, value.coerceIn(10, 300))

    override suspend fun setEnableSemanticAnalysis(enabled: Boolean) = put(Keys.SEMANTIC, enabled)

    override suspend fun setEnableTitleGeneration(enabled: Boolean) = put(Keys.TITLES, enabled)

    override suspend fun setCloudConsent(granted: Boolean) = put(Keys.CLOUD_CONSENT, granted)

    override suspend fun setApiKey(key: String) = put(Keys.API_KEY, secretStore.encrypt(key.trim()))

    override suspend fun apiKey(): String? {
        val stored = context.dataStore.data.first()[Keys.API_KEY] ?: return null
        return secretStore.decrypt(stored)
    }

    override suspend fun clearAiConfiguration() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.BASE_URL)
            prefs.remove(Keys.MODEL)
            prefs.remove(Keys.API_KEY)
        }
        secretStore.clear()
    }

    override suspend fun applyBuildDefaults(
        baseUrl: String,
        apiKey: String,
        model: String,
    ): Boolean {
        if (baseUrl.isBlank() && apiKey.isBlank() && model.isBlank()) return false
        val current = snapshot()
        if (current.ai.baseUrl.isNotBlank() || current.ai.hasApiKey) return false
        context.dataStore.edit { prefs ->
            if (baseUrl.isNotBlank()) prefs[Keys.BASE_URL] = baseUrl.trim()
            if (model.isNotBlank()) prefs[Keys.MODEL] = model.trim()
            if (apiKey.isNotBlank()) prefs[Keys.API_KEY] = secretStore.encrypt(apiKey.trim())
        }
        return true
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val ASPECT_RATIO = stringPreferencesKey("default_aspect_ratio")
        val SHORT_COUNT = intPreferencesKey("default_short_count")
        val SHORT_DURATION = intPreferencesKey("default_short_duration")
        val SUBTITLE_STYLE = stringPreferencesKey("default_subtitle_style")
        val QUALITY = stringPreferencesKey("default_quality")
        val FRAME_RATE = stringPreferencesKey("default_frame_rate")
        val PROVIDER_TYPE = stringPreferencesKey("ai_provider_type")
        val PROCESSING_MODE = stringPreferencesKey("ai_processing_mode")
        val BASE_URL = stringPreferencesKey("ai_base_url")
        val MODEL = stringPreferencesKey("ai_model")
        val API_KEY = stringPreferencesKey("ai_api_key_encrypted")
        val TIMEOUT = intPreferencesKey("ai_timeout_seconds")
        val CLOUD_CONSENT = booleanPreferencesKey("ai_cloud_consent")
        val SEMANTIC = booleanPreferencesKey("ai_semantic_enabled")
        val TITLES = booleanPreferencesKey("ai_titles_enabled")
        val EXPORT_FOLDER = stringPreferencesKey("export_folder_uri")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode_enabled")
        val HW_ACCEL = booleanPreferencesKey("hw_acceleration")
        val KEEP_AUDIO = booleanPreferencesKey("keep_source_audio")
    }
}
