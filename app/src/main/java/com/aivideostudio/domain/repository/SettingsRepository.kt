package com.aivideostudio.domain.repository

import com.aivideostudio.domain.model.AiConfig
import com.aivideostudio.domain.model.AiProcessingMode
import com.aivideostudio.domain.model.AiProviderType
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.VideoQuality
import kotlinx.coroutines.flow.Flow

data class UserPreferences(
    val onboardingCompleted: Boolean = false,
    val defaultAspectRatio: AspectRatio = AspectRatio.VERTICAL_9_16,
    val defaultShortCount: Int = 3,
    val defaultShortDurationSec: Int = 30,
    val defaultSubtitleStyle: SubtitleStyle = SubtitleStyle.CREATOR,
    val defaultQuality: VideoQuality = VideoQuality.HIGH,
    val defaultFrameRate: FrameRateOption = FrameRateOption.SOURCE,
    val ai: AiConfig = AiConfig(),
    val exportFolderUri: String? = null,
    val debugModeEnabled: Boolean = false,
    val useHardwareAcceleration: Boolean = true,
    val keepSourceAudio: Boolean = true,
) {
    val aspectRatio: AspectRatio get() = defaultAspectRatio
}

interface SettingsRepository {
    val preferences: Flow<UserPreferences>

    suspend fun snapshot(): UserPreferences

    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setDefaultAspectRatio(value: AspectRatio)
    suspend fun setDefaultShortCount(value: Int)
    suspend fun setDefaultShortDuration(value: Int)
    suspend fun setDefaultSubtitleStyle(value: SubtitleStyle)
    suspend fun setDefaultQuality(value: VideoQuality)
    suspend fun setDefaultFrameRate(value: FrameRateOption)
    suspend fun setExportFolder(uri: String?)
    suspend fun setDebugMode(enabled: Boolean)
    suspend fun setHardwareAcceleration(enabled: Boolean)
    suspend fun setKeepSourceAudio(enabled: Boolean)

    // --- AI configuration -------------------------------------------------
    suspend fun setProviderType(value: AiProviderType)
    suspend fun setProcessingMode(value: AiProcessingMode)
    suspend fun setBaseUrl(value: String)
    suspend fun setModel(value: String)
    suspend fun setTimeoutSeconds(value: Int)
    suspend fun setEnableSemanticAnalysis(enabled: Boolean)
    suspend fun setEnableTitleGeneration(enabled: Boolean)
    suspend fun setCloudConsent(granted: Boolean)

    /** Stores the API key encrypted with the Android Keystore. Pass blank to clear it. */
    suspend fun setApiKey(key: String)
    suspend fun apiKey(): String?
    suspend fun clearAiConfiguration()

    /** Applies the values compiled in from local.properties, if the user has not configured anything yet. */
    suspend fun applyBuildDefaults(baseUrl: String, apiKey: String, model: String): Boolean
}
