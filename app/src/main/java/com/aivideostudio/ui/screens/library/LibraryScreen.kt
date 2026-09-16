package com.aivideostudio.ui.screens.library

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.ui.components.ClipCard
import com.aivideostudio.ui.components.EmptyState
import com.aivideostudio.ui.components.OptionChip
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.components.StudioVideoPlayer
import com.aivideostudio.ui.theme.StudioColors
import com.aivideostudio.work.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class LibraryFilter(val label: String) {
    ALL("All clips"),
    EXPORTED("Exported"),
}

data class LibraryUiState(
    val clips: List<GeneratedClip> = emptyList(),
    val exportedClipIds: Set<Long> = emptySet(),
    val exportCount: Int = 0,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val previewClipId: Long? = null,
    val pendingDeleteAll: Boolean = false,
) {
    val visibleClips: List<GeneratedClip>
        get() = when (filter) {
            LibraryFilter.ALL -> clips
            LibraryFilter.EXPORTED -> clips.filter {
                it.status == ClipStatus.EXPORTED || it.id in exportedClipIds
            }
        }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val clipRepository: ClipRepository,
    private val exportRepository: ExportRepository,
    private val scheduler: PipelineScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private data class Controls(
        val filter: LibraryFilter,
        val previewClipId: Long?,
        val pendingDeleteAll: Boolean,
    )

    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val previewClipId = MutableStateFlow<Long?>(null)
    private val pendingDeleteAll = MutableStateFlow(false)

    val state: StateFlow<LibraryUiState> = combine(
        clipRepository.observeFinishedClips(),
        exportRepository.observeExports(),
        combine(filter, previewClipId, pendingDeleteAll) { current, preview, deleteAll ->
            Controls(filter = current, previewClipId = preview, pendingDeleteAll = deleteAll)
        },
    ) { clips, exports, controls ->
        LibraryUiState(
            clips = clips,
            exportedClipIds = exports.mapNotNull { it.clipId }.toSet(),
            exportCount = exports.count { it.isCompleted },
            filter = controls.filter,
            previewClipId = controls.previewClipId,
            pendingDeleteAll = controls.pendingDeleteAll,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }

    /** The clip's rendered file, or null when the user has not exported it yet. */
    fun previewUri(clipId: Long): String? =
        state.value.clips.firstOrNull { it.id == clipId }?.outputPath

    fun openPreview(clipId: Long) {
        previewClipId.value = clipId
    }

    fun closePreview() {
        previewClipId.value = null
    }

    fun export(clipId: Long) {
        viewModelScope.launch {
            val clip = clipRepository.getClip(clipId) ?: return@launch
            val record = ExportRecord(
                projectId = clip.projectId,
                clipId = clip.id,
                fileName = clip.title.ifBlank { "short_${clip.id}" },
            )
            val exportId = exportRepository.createExport(record)
            scheduler.startExport(exportId, clip.projectId, clip.id)
        }
    }

    fun delete(clipId: Long) {
        viewModelScope.launch { clipRepository.deleteClip(clipId) }
    }

    fun requestDeleteAllExports() {
        pendingDeleteAll.value = true
    }

    fun cancelDeleteAllExports() {
        pendingDeleteAll.value = false
    }

    fun deleteAllExports() {
        pendingDeleteAll.value = false
        viewModelScope.launch { exportRepository.deleteAllCompleted() }
    }

    /**
     * Shares the rendered file when there is one, falling back to the generated
     * thumbnail. Source footage is never part of a share.
     */
    fun share(clip: GeneratedClip) {
        val path = clip.outputPath ?: clip.thumbnailPath
        if (path.isNullOrBlank()) {
            Toast.makeText(context, "Export the clip first to share it", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "This clip cannot be shared", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (clip.outputPath != null) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share clip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun LibraryScreen(
    onEditClip: (Long) -> Unit,
    onCreate: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = "Library",
                subtitle = "${state.clips.size} clip" + if (state.clips.size == 1) "" else "s",
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { option ->
                    OptionChip(
                        label = option.label,
                        selected = state.filter == option,
                        onClick = { viewModel.setFilter(option) },
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.exportCount} exported",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.requestDeleteAllExports() }) {
                    Text("Delete all exported", color = StudioColors.Error)
                }
            }
        }

        when {
            state.clips.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "No clips yet",
                        message = "Generated Shorts appear here once analysis finishes.",
                        icon = Icons.Outlined.VideoLibrary,
                        action = {
                            Text(
                                text = "Create a project",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioColors.PrimaryBright,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClick = onCreate)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        },
                    )
                }
            }

            state.visibleClips.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No exported clips yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextTertiary,
                )
            }

            else -> items(state.visibleClips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    onOpen = { viewModel.openPreview(clip.id) },
                    showActions = true,
                    onEdit = { onEditClip(clip.id) },
                    onExport = { viewModel.export(clip.id) },
                    onDelete = { viewModel.delete(clip.id) },
                    onShare = { viewModel.share(clip) },
                )
            }
        }
    }

    if (state.pendingDeleteAll) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteAllExports() },
            title = { Text("Delete exported videos?") },
            text = {
                Text(
                    "This removes every exported file from the library. " +
                        "Your projects and original videos are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAllExports() }) {
                    Text("Delete all", color = StudioColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeleteAllExports() }) { Text("Cancel") }
            },
            containerColor = StudioColors.SurfaceElevated,
            titleContentColor = StudioColors.TextPrimary,
            textContentColor = StudioColors.TextSecondary,
        )
    }

    val previewId = state.previewClipId
    if (previewId != null) {
        ClipPreviewDialog(
            uri = viewModel.previewUri(previewId),
            onClose = { viewModel.closePreview() },
        )
    }
}

@Composable
private fun ClipPreviewDialog(uri: String?, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (uri != null) {
                StudioVideoPlayer(
                    mediaUri = uri,
                    modifier = Modifier.fillMaxSize(),
                    playWhenReady = true,
                    showControls = true,
                )
            } else {
                Text(
                    text = "Export to preview",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}
