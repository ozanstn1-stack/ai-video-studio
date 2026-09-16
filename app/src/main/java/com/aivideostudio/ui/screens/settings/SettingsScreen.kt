package com.aivideostudio.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.BuildConfig
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.domain.repository.UserPreferences
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The settings hub. Every row is a doorway to a focused screen so the top level
 * stays scannable and nothing important is more than two taps away.
 */
@Composable
fun SettingsScreen(
    onOpenAi: () -> Unit,
    onOpenDefaults: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val ai = preferences.ai

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = StudioColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "AI", subtitle = "Providers, privacy and processing")
                SettingsRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "AI Provider",
                    subtitle = ai.providerType.displayName,
                    onClick = onOpenAi,
                )
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Cloud processing",
                    subtitle = ai.processingMode.displayName,
                    onClick = onOpenAi,
                )
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    title = "Privacy",
                    subtitle = "What leaves your device",
                    onClick = onOpenPrivacy,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Video", subtitle = "Defaults for new projects")
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    title = "Defaults",
                    subtitle = "${preferences.aspectRatio.label} \u00B7 " +
                        "${preferences.defaultShortCount} Shorts \u00B7 " +
                        "${preferences.defaultShortDurationSec}s",
                    onClick = onOpenDefaults,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Storage")
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = "Storage",
                    subtitle = "Export folder and cache",
                    onClick = onOpenStorage,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "About")
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "About AI Video Studio",
                    subtitle = "Version ${BuildConfig.VERSION_NAME}",
                    onClick = onOpenAbout,
                )
            }
        }

        if (BuildConfig.DEBUG || preferences.debugModeEnabled) {
            item {
                SettingsRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Diagnostics",
                    subtitle = "Logs and pipeline internals",
                    onClick = onOpenDebug,
                )
            }
        }

        item {
            DeveloperModeRow(
                enabled = preferences.debugModeEnabled,
                onToggle = viewModel::setDebugMode,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    StudioCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = StudioColors.PrimaryBright,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextTertiary,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = StudioColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun DeveloperModeRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Enable developer mode",
                style = MaterialTheme.typography.bodyMedium,
                color = StudioColors.TextSecondary,
            )
            Text(
                text = "Shows extra diagnostics and pipeline details",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences(),
        )

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDebugMode(enabled) }
    }
}
