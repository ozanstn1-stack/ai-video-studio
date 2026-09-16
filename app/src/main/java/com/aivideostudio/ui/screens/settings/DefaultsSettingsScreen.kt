package com.aivideostudio.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.BuildConfig
import com.aivideostudio.domain.model.AiProcessingMode
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.VideoQuality
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.domain.repository.UserPreferences
import com.aivideostudio.ui.components.OptionChip
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultsSettingsScreen(
    onBack: () -> Unit,
    viewModel: DefaultsViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = StudioColors.Background,
        topBar = { SettingsTopBar(title = "Defaults", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Shorts per project", subtitle = "How many clips to cut")
                OptionChips(
                    options = listOf(1, 3, 5, 10),
                    selected = preferences.defaultShortCount,
                    label = { it.toString() },
                    onSelect = viewModel::setShortCount,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Short duration", subtitle = "Target length of each clip")
                OptionChips(
                    options = listOf(15, 30, 60),
                    selected = preferences.defaultShortDurationSec,
                    label = { "${it}s" },
                    onSelect = viewModel::setShortDuration,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Aspect ratio", subtitle = "Shape of the exported video")
                OptionChips(
                    options = AspectRatio.entries.toList(),
                    selected = preferences.defaultAspectRatio,
                    label = { it.label },
                    onSelect = viewModel::setAspectRatio,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Video quality", subtitle = "File size against detail")
                OptionChips(
                    options = VideoQuality.entries.toList(),
                    selected = preferences.defaultQuality,
                    label = { it.label },
                    onSelect = viewModel::setQuality,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Frame rate", subtitle = "Keep the source or force a rate")
                OptionChips(
                    options = FrameRateOption.entries.toList(),
                    selected = preferences.defaultFrameRate,
                    label = { it.label },
                    onSelect = viewModel::setFrameRate,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Subtitle style", subtitle = "How captions are drawn")
                OptionChips(
                    options = SubtitleStyle.entries.toList(),
                    selected = preferences.defaultSubtitleStyle,
                    label = { it.displayName },
                    onSelect = viewModel::setSubtitleStyle,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Performance")
                SettingsSwitchRow(
                    title = "Hardware acceleration",
                    subtitle = "Use the device encoder when exporting",
                    checked = preferences.useHardwareAcceleration,
                    onCheckedChange = viewModel::setHardwareAcceleration,
                )
                SettingsSwitchRow(
                    title = "Keep original audio",
                    subtitle = "Carry source sound into the clips",
                    checked = preferences.keepSourceAudio,
                    onCheckedChange = viewModel::setKeepSourceAudio,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val ai = preferences.ai

    Scaffold(
        containerColor = StudioColors.Background,
        topBar = { SettingsTopBar(title = "Privacy", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Your original videos remain on your device unless you explicitly enable cloud AI processing.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = StudioColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "AI processing", subtitle = "When may AI leave the device?")
                AiProcessingMode.entries.forEach { mode ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip(
                            label = mode.displayName,
                            selected = ai.processingMode == mode,
                            onClick = { viewModel.setProcessingMode(mode) },
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(
                    title = "What would be uploaded",
                    subtitle = "Only ever with cloud processing enabled",
                )
                PrivacyBullet("A compressed audio-only file for speech transcription")
                PrivacyBullet("A few still frames for scene understanding")
                PrivacyBullet("Only text summaries for titles and descriptions")
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSwitchRow(
                    title = "Cloud consent granted",
                    subtitle = "Allow the provider you chose to process this footage",
                    checked = ai.cloudConsentGranted,
                    onCheckedChange = viewModel::setCloudConsent,
                )
                TextButton(onClick = { viewModel.setCloudConsent(false) }) {
                    Text(
                        text = "Revoke consent",
                        color = StudioColors.Error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(onBack: () -> Unit) {
    Scaffold(
        containerColor = StudioColors.Background,
        topBar = { SettingsTopBar(title = "About", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "AI Video Studio",
                    style = MaterialTheme.typography.headlineMedium,
                    color = StudioColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextTertiary,
                )
                Text(
                    text = "Turn long recordings into short, captioned videos. Everything is " +
                        "analysed on your device, and cloud AI is something you opt into.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Built with")
                AboutTechLine("Media3", "Playback, decoding and export")
                AboutTechLine("ML Kit face detection", "Keeps subjects in frame while cropping")
                AboutTechLine("Room", "Stores projects, clips and analysis locally")
                AboutTechLine("WorkManager", "Runs long analysis and export jobs in the background")
                AboutTechLine("Compose", "The entire interface")
            }

            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "All local analysis runs on your device. Cloud AI is optional and off by default.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextPrimary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Licences")
                Text(
                    text = "This app is built on open-source software. Media3, Room, " +
                        "WorkManager, Kotlin, Coroutines and Jetpack Compose are used under " +
                        "the Apache License 2.0. On-device machine learning is provided by " +
                        "Google ML Kit. Full licence texts are included with the application " +
                        "package and in the project repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun PrivacyBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.PrimaryBright,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.TextSecondary,
        )
    }
}

@Composable
private fun AboutTechLine(name: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(170.dp),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextTertiary,
        )
    }
}

@Composable
private fun <T> OptionChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            OptionChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = StudioColors.Background,
            titleContentColor = StudioColors.TextPrimary,
            navigationIconContentColor = StudioColors.TextPrimary,
        ),
    )
}

@HiltViewModel
class DefaultsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    fun setShortCount(value: Int) {
        viewModelScope.launch { settingsRepository.setDefaultShortCount(value) }
    }

    fun setShortDuration(value: Int) {
        viewModelScope.launch { settingsRepository.setDefaultShortDuration(value) }
    }

    fun setAspectRatio(value: AspectRatio) {
        viewModelScope.launch { settingsRepository.setDefaultAspectRatio(value) }
    }

    fun setQuality(value: VideoQuality) {
        viewModelScope.launch { settingsRepository.setDefaultQuality(value) }
    }

    fun setFrameRate(value: FrameRateOption) {
        viewModelScope.launch { settingsRepository.setDefaultFrameRate(value) }
    }

    fun setSubtitleStyle(value: SubtitleStyle) {
        viewModelScope.launch { settingsRepository.setDefaultSubtitleStyle(value) }
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHardwareAcceleration(enabled) }
    }

    fun setKeepSourceAudio(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepSourceAudio(enabled) }
    }
}

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    fun setProcessingMode(value: AiProcessingMode) {
        viewModelScope.launch { settingsRepository.setProcessingMode(value) }
    }

    fun setCloudConsent(granted: Boolean) {
        viewModelScope.launch { settingsRepository.setCloudConsent(granted) }
    }
}
