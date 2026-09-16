package com.aivideostudio.ui.screens.clips

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.VideoQuality
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.ui.components.ClipCard
import com.aivideostudio.ui.components.EmptyState
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.theme.StudioColors
import com.aivideostudio.work.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ClipsUiState(
    val project: Project? = null,
    val clips: List<GeneratedClip> = emptyList(),
    val exports: List<ExportRecord> = emptyList(),
    val message: String? = null,
) {
    fun exportFor(clipId: Long): ExportRecord? =
        exports.firstOrNull { it.clipId == clipId && it.status != ExportStatus.CANCELLED }

    val activeExport: ExportRecord? get() = exports.firstOrNull { it.status == ExportStatus.RUNNING }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClipsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val clipRepository: ClipRepository,
    private val exportRepository: ExportRepository,
    private val scheduler: PipelineScheduler,
) : ViewModel() {

    private val projectId = MutableStateFlow(0L)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ClipsUiState> = combine(
        projectId.flatMapLatest { projectRepository.observeProject(it) },
        projectId.flatMapLatest { clipRepository.observeClips(it) },
        projectId.flatMapLatest { exportRepository.observeProjectExports(it) },
        message,
    ) { project, clips, exports, currentMessage ->
        ClipsUiState(project, clips, exports, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipsUiState())

    fun attach(id: Long) {
        projectId.value = id
    }

    fun dismissMessage() {
        message.value = null
    }

    fun delete(clipId: Long) {
        viewModelScope.launch {
            clipRepository.deleteClip(clipId)
            message.value = "Short deleted. Your original videos are untouched."
        }
    }

    /**
     * Export always writes a new file. If the clip has already been exported the
     * user simply gets another version rather than overwriting anything.
     */
    fun export(clip: GeneratedClip) {
        val id = projectId.value
        if (id <= 0L) return
        viewModelScope.launch {
            val existing = state.value.exportFor(clip.id)
            if (existing?.status == ExportStatus.RUNNING) {
                message.value = "This Short is already exporting"
                return@launch
            }
            val fileName = buildFileName(clip)
            val exportId = exportRepository.createExport(
                ExportRecord(
                    projectId = id,
                    clipId = clip.id,
                    fileName = fileName,
                    quality = state.value.project?.quality ?: VideoQuality.HIGH,
                    createdAt = System.currentTimeMillis(),
                    status = ExportStatus.QUEUED,
                ),
            )
            clipRepository.updateClipOutput(clip.id, clip.outputPath, ClipStatus.RENDERING)
            scheduler.startExport(exportId, id, clip.id)
            message.value = "Export started. You can keep using the app."
        }
    }

    fun share(clip: GeneratedClip) {
        val context = lastContext ?: return
        val path = clip.outputPath
        if (path.isNullOrBlank() || !File(path).exists()) {
            message.value = "Export this Short first so it can be shared"
            return
        }
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(path),
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, clip.title)
                putExtra(Intent.EXTRA_TEXT, clip.description)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Short"))
        }.onFailure {
            message.value = "Nothing on this device can share the video"
        }
    }

    /**
     * Set by the screen. Always the application context: a ViewModel must never
     * hold an Activity, otherwise configuration changes leak the whole view tree.
     */
    @SuppressLint("StaticFieldLeak")
    var lastContext: android.content.Context? = null

    private fun buildFileName(clip: GeneratedClip): String {
        val base = clip.title.ifBlank { "short_${clip.index + 1}" }
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(' ', '_')
            .take(40)
            .ifBlank { "short" }
        return "${base}_${clip.index + 1}"
    }
}

@Composable
fun ClipsScreen(
    projectId: Long,
    onBack: () -> Unit,
    onEditClip: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    viewModel: ClipsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(projectId) { viewModel.attach(projectId) }
    LaunchedEffect(context) { viewModel.lastContext = context.applicationContext }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<GeneratedClip?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { text ->
            snackbarHostState.showSnackbar(text)
            viewModel.dismissMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = StudioColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Your Shorts",
                            style = MaterialTheme.typography.headlineSmall,
                            color = StudioColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.project?.name ?: "Project",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = onOpenLibrary) {
                        Text("Library", color = StudioColors.PrimaryBright)
                    }
                }
            }

            state.activeExport?.let { export ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            title = "Exporting",
                            subtitle = "${export.fileName} \u00B7 ${(export.progress * 100).toInt()}%",
                        )
                        LinearProgressIndicator(
                            progress = { export.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
                            color = StudioColors.Secondary,
                            trackColor = StudioColors.SurfaceVariant,
                        )
                    }
                }
            }

            if (state.clips.isEmpty()) {
                item {
                    EmptyState(
                        title = "No Shorts yet",
                        message = "Go back and create Shorts from the analysed moments.",
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    )
                }
            } else {
                items(state.clips, key = { it.id }) { clip ->
                    val export = state.exportFor(clip.id)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClipCard(
                            clip = clip,
                            onOpen = { onEditClip(clip.id) },
                            showActions = true,
                            onEdit = { onEditClip(clip.id) },
                            onExport = { viewModel.export(clip) },
                            onShare = { viewModel.share(clip) },
                            onDelete = { pendingDelete = clip },
                        )
                        if (export != null && export.status == ExportStatus.RUNNING) {
                            LinearProgressIndicator(
                                progress = { export.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                                color = StudioColors.Secondary,
                                trackColor = StudioColors.SurfaceVariant,
                            )
                        } else if (export?.status == ExportStatus.FAILED) {
                            Text(
                                text = export.errorMessage ?: "Export failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.Error,
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    pendingDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = StudioColors.SurfaceElevated,
            titleContentColor = StudioColors.TextPrimary,
            textContentColor = StudioColors.TextSecondary,
            title = { Text("Delete this Short?") },
            text = {
                Text(
                    "The generated clips and captions are removed. " +
                        "Your original videos are never deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(clip.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete", color = StudioColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep", color = StudioColors.TextSecondary)
                }
            },
        )
    }
}
