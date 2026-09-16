package com.aivideostudio.ui.screens.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.BuildConfig
import com.aivideostudio.core.common.SizeUtils
import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.StorageUsage
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.domain.repository.StorageRepository
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StatRow
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val activeJobs: List<AiJob> = emptyList(),
    val projectCount: Int = 0,
    val clipCount: Int = 0,
    val exportCount: Int = 0,
    val storage: StorageUsage = StorageUsage(),
    val debugModeEnabled: Boolean = false,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    jobRepository: JobRepository,
    projectRepository: ProjectRepository,
    clipRepository: ClipRepository,
    exportRepository: ExportRepository,
    storageRepository: StorageRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private data class Counts(
        val projectCount: Int,
        val clipCount: Int,
        val exportCount: Int,
        val storage: StorageUsage,
    )

    val state: StateFlow<DebugUiState> = combine(
        jobRepository.observeActiveJobs(),
        combine(
            projectRepository.observeProjectCount(),
            clipRepository.observeFinishedClips(),
            exportRepository.observeCompletedCount(),
            storageRepository.observeUsage(),
        ) { projects, clips, exports, storage ->
            Counts(
                projectCount = projects,
                clipCount = clips.size,
                exportCount = exports,
                storage = storage,
            )
        },
        settingsRepository.preferences,
    ) { jobs, counts, preferences ->
        DebugUiState(
            activeJobs = jobs,
            projectCount = counts.projectCount,
            clipCount = counts.clipCount,
            exportCount = counts.exportCount,
            storage = counts.storage,
            debugModeEnabled = preferences.debugModeEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugUiState())

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDebugMode(enabled) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val report = buildReport(state)

    Scaffold(
        containerColor = StudioColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionHeader(
                title = "Runtime",
                subtitle = "Build ${BuildConfig.BUILD_TYPE} - ${BuildConfig.APPLICATION_ID}",
            )

            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow("Manufacturer", Build.MANUFACTURER)
                    StatRow("Model", Build.MODEL)
                    StatRow("Android SDK", Build.VERSION.SDK_INT.toString())
                    StatRow("ABIs", Build.SUPPORTED_ABIS.joinToString())
                    val runtime = Runtime.getRuntime()
                    StatRow("Heap total", "${toMegabytes(runtime.totalMemory())} MB")
                    StatRow("Heap free", "${toMegabytes(runtime.freeMemory())} MB")
                    StatRow("Heap max", "${toMegabytes(runtime.maxMemory())} MB")
                }
            }

            SectionHeader(title = "Counts")

            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow("Projects", state.projectCount.toString())
                    StatRow("Clips", state.clipCount.toString())
                    StatRow("Exports", state.exportCount.toString())
                    StatRow("Storage used", SizeUtils.formatBytes(state.storage.totalBytes))
                    StatRow("Reclaimable", SizeUtils.formatBytes(state.storage.reclaimableBytes))
                }
            }

            SectionHeader(
                title = "Active jobs",
                subtitle = if (state.activeJobs.isEmpty()) {
                    "Nothing running"
                } else {
                    "${state.activeJobs.size} running"
                },
            )

            if (state.activeJobs.isEmpty()) {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No active analysis jobs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextTertiary,
                    )
                }
            } else {
                state.activeJobs.forEach { job -> JobCard(job) }
            }

            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Debug mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                        )
                        Text(
                            text = "Keep pipeline details and technical errors.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                        )
                    }
                    Switch(
                        checked = state.debugModeEnabled,
                        onCheckedChange = { viewModel.setDebugMode(it) },
                    )
                }
            }

            Button(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("AI Video Studio diagnostics", report),
                    )
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Primary,
                    contentColor = StudioColors.OnPrimary,
                ),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text("  Copy diagnostics")
            }
        }
    }
}

@Composable
private fun JobCard(job: AiJob) {
    StudioCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = job.stage.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                StudioBadge(
                    text = job.status.name,
                    color = when (job.status) {
                        JobStatus.FAILED -> StudioColors.Error
                        JobStatus.RUNNING -> StudioColors.Warning
                        JobStatus.SUCCEEDED -> StudioColors.Success
                        else -> StudioColors.Primary
                    },
                )
            }
            StatRow("Progress", "${(job.progress * 100).toInt()}%")
            StatRow("Provider", job.provider ?: "local")
            StatRow("Cloud", if (job.usedCloud) "yes" else "no")
            job.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.Error,
                )
            }
            job.technicalDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextTertiary,
                )
            }
        }
    }
}

private fun buildReport(state: DebugUiState): String {
    val runtime = Runtime.getRuntime()
    return buildString {
        appendLine("AI Video Studio diagnostics")
        appendLine("App id: ${BuildConfig.APPLICATION_ID}")
        appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
        appendLine("Debug build: ${BuildConfig.DEBUG}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Heap total: ${toMegabytes(runtime.totalMemory())} MB")
        appendLine("Heap free: ${toMegabytes(runtime.freeMemory())} MB")
        appendLine("Heap max: ${toMegabytes(runtime.maxMemory())} MB")
        appendLine("Projects: ${state.projectCount}")
        appendLine("Clips: ${state.clipCount}")
        appendLine("Exports: ${state.exportCount}")
        appendLine("Storage used: ${SizeUtils.formatBytes(state.storage.totalBytes)}")
        appendLine("Active jobs: ${state.activeJobs.size}")
        state.activeJobs.forEach { job ->
            appendLine(
                "- ${job.stage.displayName} | ${job.status.name} | " +
                    "${(job.progress * 100).toInt()}% | ${job.provider ?: "local"} | " +
                    "cloud=${job.usedCloud} | ${job.errorMessage ?: "no error"}",
            )
        }
    }
}

private fun toMegabytes(bytes: Long): Long = bytes / (1024L * 1024L)
