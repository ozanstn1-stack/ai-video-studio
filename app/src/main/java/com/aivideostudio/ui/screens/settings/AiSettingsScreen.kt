package com.aivideostudio.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.ai.provider.AiProviderRegistry
import com.aivideostudio.domain.model.AiConfig
import com.aivideostudio.domain.model.AiProcessingMode
import com.aivideostudio.domain.model.AiProviderType
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.ui.components.OptionChip
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome of a credential/connectivity check, phrased for a human. */
data class ConnectionTestState(
    val success: Boolean,
    val providerName: String,
    val summary: String,
    val capabilities: List<String>,
)

/**
 * Configures who answers AI requests. The screen never reads the API key back
 * out of storage: a boolean drives the placeholder, so a mistyped value can be
 * replaced but a stored one can never be displayed or logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val connection by viewModel.connectionTest.collectAsStateWithLifecycle()

    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(config.baseUrl) { if (baseUrl != config.baseUrl) baseUrl = config.baseUrl }
    LaunchedEffect(config.model) { if (model != config.model) model = config.model }

    Scaffold(
        containerColor = StudioColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("AI Provider") },
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
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(
                    title = "How should AI run?",
                    subtitle = "Choose where the work happens",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AiProviderType.entries.forEach { type ->
                        OptionChip(
                            label = type.displayName,
                            selected = config.providerType == type,
                            onClick = { viewModel.setProviderType(type) },
                        )
                    }
                }
                Text(
                    text = if (config.providerType == AiProviderType.LOCAL) {
                        "Nothing ever leaves your device"
                    } else {
                        "Send audio/frames to your provider"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextTertiary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(
                    title = "Privacy",
                    subtitle = "When may AI leave the device?",
                )
                AiProcessingMode.entries.forEach { mode ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip(
                            label = mode.displayName,
                            selected = config.processingMode == mode,
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

            if (config.providerType != AiProviderType.LOCAL) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(
                        title = "Connection",
                        subtitle = "Credentials are stored encrypted",
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            viewModel.setBaseUrl(it)
                        },
                        label = { Text("Base URL") },
                        placeholder = {
                            Text(
                                if (config.providerType == AiProviderType.GEMINI) {
                                    "https://generativelanguage.googleapis.com/v1beta"
                                } else {
                                    "https://api.openai.com/v1"
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            viewModel.setModel(it)
                        },
                        label = { Text("Model") },
                        placeholder = {
                            Text(
                                if (config.providerType == AiProviderType.GEMINI) {
                                    "gemini-2.0-flash"
                                } else {
                                    "gpt-4o-mini"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { value ->
                            apiKeyInput = value
                            if (value.isNotBlank()) viewModel.setApiKey(value)
                        },
                        label = { Text("API key") },
                        placeholder = {
                            Text(if (config.hasApiKey) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" else "Paste your API key")
                        },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Test connection")
                    }

                    if (connection != null) {
                        val result = connection!!
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StudioBadge(
                                    text = if (result.success) "Ready" else "Not ready",
                                    color = if (result.success) StudioColors.Success else StudioColors.Error,
                                )
                                Text(
                                    text = result.providerName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = result.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.success) StudioColors.TextSecondary else StudioColors.Error,
                            )
                            if (result.capabilities.isNotEmpty()) {
                                Text(
                                    text = "Capabilities: " + result.capabilities.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextTertiary,
                                )
                            }
                        }
                    }

                    Text(
                        text = "Stored securely with the Android Keystore. Keys are never written to logs or backups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextTertiary,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(
                    title = "Features",
                    subtitle = "What the AI is allowed to do",
                )
                AiSwitchRow(
                    title = "Semantic understanding",
                    subtitle = "Subjects, places, activities and mood",
                    checked = config.enableSemanticAnalysis,
                    onCheckedChange = viewModel::setEnableSemanticAnalysis,
                )
                AiSwitchRow(
                    title = "Title & description generation",
                    subtitle = "Write titles, captions and hashtags",
                    checked = config.enableTitleGeneration,
                    onCheckedChange = viewModel::setEnableTitleGeneration,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(
                    title = "Advanced",
                    subtitle = "How long to wait for a cloud reply",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(15, 30, 60, 120).forEach { seconds ->
                        OptionChip(
                            label = "${seconds}s",
                            selected = config.timeoutSeconds == seconds,
                            onClick = { viewModel.setTimeoutSeconds(seconds) },
                        )
                    }
                }
            }

            TextButton(onClick = { showClearDialog = true }) {
                Text(
                    text = "Clear credentials",
                    color = StudioColors.Error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = StudioColors.SurfaceElevated,
            titleContentColor = StudioColors.TextPrimary,
            textContentColor = StudioColors.TextSecondary,
            title = { Text("Clear credentials?") },
            text = {
                Text("The stored API key, base URL and model will be removed from this device.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearCredentials()
                    },
                ) {
                    Text("Clear", color = StudioColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AiSwitchRow(
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

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val registry: AiProviderRegistry,
) : ViewModel() {

    val config: StateFlow<AiConfig> = settingsRepository.preferences
        .map { it.ai }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AiConfig(),
        )

    private val _connectionTest = MutableStateFlow<ConnectionTestState?>(null)
    val connectionTest: StateFlow<ConnectionTestState?> = _connectionTest.asStateFlow()

    fun setProviderType(value: AiProviderType) = launch { settingsRepository.setProviderType(value) }

    fun setProcessingMode(value: AiProcessingMode) = launch { settingsRepository.setProcessingMode(value) }

    fun setBaseUrl(value: String) = launch { settingsRepository.setBaseUrl(value) }

    fun setModel(value: String) = launch { settingsRepository.setModel(value) }

    fun setApiKey(value: String) = launch { settingsRepository.setApiKey(value) }

    fun setTimeoutSeconds(value: Int) = launch { settingsRepository.setTimeoutSeconds(value) }

    fun setEnableSemanticAnalysis(enabled: Boolean) = launch {
        settingsRepository.setEnableSemanticAnalysis(enabled)
    }

    fun setEnableTitleGeneration(enabled: Boolean) = launch {
        settingsRepository.setEnableTitleGeneration(enabled)
    }

    fun clearCredentials() {
        _connectionTest.value = null
        launch { settingsRepository.clearAiConfiguration() }
    }

    /**
     * Asks the registry which provider currently answers and what it claims to
     * support. This is a configuration check, not a network round trip, so it is
     * safe to run without sending anything anywhere.
     */
    fun testConnection() {
        viewModelScope.launch {
            val refreshed = registry.refresh()
            val provider = if (refreshed.providerType == AiProviderType.LOCAL) {
                registry.local()
            } else {
                registry.cloudProvider()
            }

            if (provider == null) {
                _connectionTest.value = ConnectionTestState(
                    success = false,
                    providerName = "Cloud provider",
                    summary = "Choose a provider before testing the connection.",
                    capabilities = emptyList(),
                )
                return@launch
            }

            val capabilities = provider.capabilities()
            val names = capabilities.available
                .map { it.name.lowercase().replace('_', ' ') }
                .sorted()
            val success = names.isNotEmpty()

            _connectionTest.value = ConnectionTestState(
                success = success,
                providerName = provider.displayName,
                summary = if (success) {
                    "Configuration looks good."
                } else {
                    capabilities.reason?.takeIf { it.isNotBlank() }
                        ?: "This provider is not available right now."
                },
                capabilities = names,
            )
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
