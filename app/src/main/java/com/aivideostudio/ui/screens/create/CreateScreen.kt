package com.aivideostudio.ui.screens.create

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.core.common.Constants
import com.aivideostudio.core.common.SizeUtils
import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.model.VideoQuality
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.ui.components.OptionChip
import com.aivideostudio.ui.components.OptionRow
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.components.VideoThumbnail
import com.aivideostudio.ui.theme.StudioColors
import com.aivideostudio.ui.components.androidClickable
import com.aivideostudio.work.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SelectedVideo(val uri: String, val name: String, val sizeBytes: Long)

data class CreateUiState(
    val selected: List<SelectedVideo> = emptyList(),
    val mode: CreationMode = CreationMode.AUTO,
    val shortCount: Int = 3,
    val shortDurationSec: Int = 30,
    val aspectRatio: AspectRatio = AspectRatio.VERTICAL_9_16,
    val quality: VideoQuality = VideoQuality.HIGH,
    val frameRate: FrameRateOption = FrameRateOption.SOURCE,
    val showAdvanced: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
) {
    val totalBytes: Long get() = selected.sumOf { it.sizeBytes }
    val canCreate: Boolean get() = selected.isNotEmpty() && !isCreating
}

@HiltViewModel
class CreateViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val mediaRepository: MediaRepository,
    private val jobRepository: JobRepository,
    private val settingsRepository: SettingsRepository,
    private val scheduler: PipelineScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateUiState())
    val state: StateFlow<CreateUiState> = _state.asStateFlow()

    init {
        // Seed the form with the user's saved defaults.
        viewModelScope.launch {
            val preferences = settingsRepository.snapshot()
            _state.update {
                it.copy(
                    shortCount = preferences.defaultShortCount,
                    shortDurationSec = preferences.defaultShortDurationSec,
                    aspectRatio = preferences.defaultAspectRatio,
                    quality = preferences.defaultQuality,
                    frameRate = preferences.defaultFrameRate,
                )
            }
        }
    }

    fun addUris(videos: List<SelectedVideo>) {
        _state.update { current ->
            val existing = current.selected.map { it.uri }.toSet()
            current.copy(
                selected = current.selected + videos.filter { it.uri !in existing },
                error = null,
            )
        }
    }

    fun remove(uri: String) {
        _state.update { it.copy(selected = it.selected.filterNot { video -> video.uri == uri }) }
    }

    fun clear() = _state.update { it.copy(selected = emptyList(), error = null) }

    fun setMode(value: CreationMode) = _state.update { it.copy(mode = value) }
    fun setShortCount(value: Int) = _state.update { it.copy(shortCount = value) }
    fun setDuration(value: Int) = _state.update { it.copy(shortDurationSec = value) }
    fun setAspectRatio(value: AspectRatio) = _state.update { it.copy(aspectRatio = value) }
    fun setQuality(value: VideoQuality) = _state.update { it.copy(quality = value) }
    fun setFrameRate(value: FrameRateOption) = _state.update { it.copy(frameRate = value) }
    fun toggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun createProject(onStarted: (Long) -> Unit) {
        val snapshot = _state.value
        if (!snapshot.canCreate) return
        _state.update { it.copy(isCreating = true, error = null) }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val name = buildProjectName(snapshot)
                val projectId = projectRepository.createProject(
                    name = name,
                    mode = snapshot.mode,
                    shortCount = snapshot.shortCount,
                    shortDurationSec = snapshot.shortDurationSec,
                    aspectRatio = snapshot.aspectRatio,
                    quality = snapshot.quality,
                    frameRate = snapshot.frameRate,
                )

                val added = mediaRepository.addAssets(
                    projectId,
                    snapshot.selected.map { it.uri },
                )
                if (added.isEmpty()) {
                    projectRepository.deleteProject(projectId)
                    _state.update {
                        it.copy(isCreating = false, error = "These videos could not be added")
                    }
                    return@launch
                }

                projectRepository.updateStatus(projectId, ProjectStatus.IMPORTING)
                jobRepository.upsertJob(
                    AiJob(
                        projectId = projectId,
                        stage = PipelineStage.IMPORT,
                        status = JobStatus.QUEUED,
                        progress = 0f,
                        startedAt = now,
                        updatedAt = now,
                    ),
                )
                scheduler.startAnalysis(projectId)
                _state.update { it.copy(isCreating = false) }
                onStarted(projectId)
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        isCreating = false,
                        error = "The project could not be created. Please try again.",
                    )
                }
            }
        }
    }

    private fun buildProjectName(snapshot: CreateUiState): String {
        val first = snapshot.selected.firstOrNull()?.name?.substringBeforeLast('.')
        val base = first?.takeIf { it.isNotBlank() } ?: "New project"
        val stamp = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "$base \u00B7 $stamp"
    }
}

@Composable
fun CreateScreen(
    onAnalysisStarted: (Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: CreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingFolderUri by remember { mutableStateOf<Uri?>(null) }

    val pickVideos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addUris(uris.map { toSelectedVideo(context, it) })
    }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        pendingFolderUri = treeUri
        if (treeUri != null) {
            val found = collectVideosFromTree(context, treeUri)
            if (found.isEmpty()) {
                viewModel.addUris(emptyList())
            } else {
                viewModel.addUris(found)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                Column {
                    Text(
                        text = "Create",
                        style = MaterialTheme.typography.headlineMedium,
                        color = StudioColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Turn your footage into great Shorts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextTertiary,
                    )
                }
            }

            item { AddVideosCard(
                onPickVideos = {
                    pickVideos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onPickFolder = {
                    pickFolder.launch(null)
                },
            ) }

            state.error?.let { message ->
                item {
                    StudioCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Something went wrong",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioColors.Error,
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            if (state.selected.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(
                            title = "Selected videos",
                            subtitle = "${state.selected.size} video" +
                                (if (state.selected.size == 1) "" else "s") +
                                " \u00B7 ${SizeUtils.formatBytes(state.totalBytes)}",
                            action = {
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = StudioColors.TextTertiary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .androidClickable(viewModel::clear)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            },
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.selected, key = { it.uri }) { video ->
                                SelectedVideoTile(
                                    video = video,
                                    onRemove = { viewModel.remove(video.uri) },
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionRow("What do you want to create?")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 3, 5, 10).forEach { count ->
                                OptionChip(
                                    label = if (count == 1) "1 Short" else "$count Shorts",
                                    selected = state.shortCount == count,
                                    onClick = { viewModel.setShortCount(count) },
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionRow("Duration")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60).forEach { seconds ->
                                OptionChip(
                                    label = "${seconds}s",
                                    selected = state.shortDurationSec == seconds,
                                    onClick = { viewModel.setDuration(seconds) },
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionRow("Format")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                AspectRatio.VERTICAL_9_16,
                                AspectRatio.LANDSCAPE_16_9,
                                AspectRatio.SQUARE_1_1,
                            ).forEach { ratio ->
                                OptionChip(
                                    label = ratio.label,
                                    selected = state.aspectRatio == ratio,
                                    onClick = { viewModel.setAspectRatio(ratio) },
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionRow("Style")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(CreationMode.entries.toList()) { mode ->
                                OptionChip(
                                    label = mode.displayName,
                                    selected = state.mode == mode,
                                    onClick = { viewModel.setMode(mode) },
                                )
                            }
                        }
                        Text(
                            text = state.mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                        )
                    }
                }

                item {
                    StudioCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::toggleAdvanced,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = StudioColors.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Advanced",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                StudioBadge(
                                    text = "${state.quality.label} \u00B7 ${state.frameRate.label}",
                                    color = StudioColors.Secondary,
                                )
                            }
                            AnimatedVisibility(visible = state.showAdvanced) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OptionRow("Video quality")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            VideoQuality.entries.forEach { quality ->
                                                OptionChip(
                                                    label = quality.label,
                                                    selected = state.quality == quality,
                                                    onClick = { viewModel.setQuality(quality) },
                                                )
                                            }
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OptionRow("Frame rate")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FrameRateOption.entries.forEach { option ->
                                                OptionChip(
                                                    label = option.label,
                                                    selected = state.frameRate == option,
                                                    onClick = { viewModel.setFrameRate(option) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            StudioColors.Background.copy(alpha = 0f),
                            StudioColors.Background,
                        ),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Button(
                onClick = { viewModel.createProject(onAnalysisStarted) },
                enabled = state.canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Primary,
                    contentColor = StudioColors.OnPrimary,
                    disabledContainerColor = StudioColors.SurfaceVariant,
                    disabledContentColor = StudioColors.TextTertiary,
                ),
            ) {
                if (state.isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = StudioColors.OnPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Preparing\u2026")
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Analyze & Create",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddVideosCard(
    onPickVideos: () -> Unit,
    onPickFolder: () -> Unit,
) {
    StudioCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                StudioColors.Primary.copy(alpha = 0.30f),
                                StudioColors.Secondary.copy(alpha = 0.16f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = StudioColors.PrimaryBright,
                )
            }
            Text(
                text = "Add videos",
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Select clips from your camera roll, or import a whole DJI folder.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPickVideos,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Primary,
                        contentColor = StudioColors.OnPrimary,
                    ),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose videos")
                }
                Button(
                    onClick = onPickFolder,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.SurfaceVariant,
                        contentColor = StudioColors.TextPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("DJI folder")
                }
            }
        }
    }
}

@Composable
private fun SelectedVideoTile(
    video: SelectedVideo,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.width(128.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoThumbnail(
                source = video.uri,
                modifier = Modifier.width(128.dp),
                aspectRatio = 9f / 14f,
            )
            Text(
                text = video.name,
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
                maxLines = 1,
            )
            Text(
                text = SizeUtils.formatBytes(video.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextTertiary,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioColors.Scrim),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove",
                tint = StudioColors.TextPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Photo Picker returns a stable content URI, so the name and size are read once
 * here and kept in memory until the project row is created.
 */
private fun toSelectedVideo(context: Context, uri: Uri): SelectedVideo {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    var size = 0L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    return SelectedVideo(uri.toString(), name, size)
}

/**
 * Walks a folder the user granted access to and picks out video files. DJI
 * writes low-resolution `_LRV` proxies next to the originals; those are skipped
 * because analysing them would produce worse results than the real footage.
 */
private fun collectVideosFromTree(context: Context, treeUri: Uri): List<SelectedVideo> {
    val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        ?: return emptyList()
    val result = mutableListOf<SelectedVideo>()

    fun walk(directory: DocumentFile, depth: Int) {
        if (depth > MAX_FOLDER_DEPTH || result.size >= MAX_FOLDER_FILES) return
        directory.listFiles().forEach { file ->
            if (result.size >= MAX_FOLDER_FILES) return
            if (file.isDirectory) {
                walk(file, depth + 1)
            } else {
                val mime = file.type
                val name = file.name ?: return@forEach
                val extension = name.substringAfterLast('.', "").lowercase()
                val isProxy = name.substringBeforeLast('.').endsWith("_lrv", ignoreCase = true)
                val isVideo = mime in Constants.SUPPORTED_VIDEO_MIME_TYPES ||
                    extension in Constants.SUPPORTED_VIDEO_EXTENSIONS
                if (isVideo && !isProxy) {
                    result += SelectedVideo(file.uri.toString(), name, file.length())
                }
            }
        }
    }

    walk(root, 0)
    return result
}

private const val MAX_PICK = 50
private const val MAX_FOLDER_DEPTH = 4
private const val MAX_FOLDER_FILES = 200
