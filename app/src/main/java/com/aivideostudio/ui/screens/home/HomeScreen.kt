package com.aivideostudio.ui.screens.home

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
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.core.common.SizeUtils
import com.aivideostudio.core.common.TimeUtils
import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.model.StorageUsage
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.StorageRepository
import com.aivideostudio.processing.ProjectPipeline
import com.aivideostudio.ui.components.EmptyState
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StatRow
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.components.VideoThumbnail
import com.aivideostudio.ui.components.androidClickable
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val greeting: String = "Hello",
    val recentProjects: List<Project> = emptyList(),
    val activeJobs: List<AiJob> = emptyList(),
    val recentExports: List<ExportRecord> = emptyList(),
    val storage: StorageUsage = StorageUsage(),
    val projectCount: Int = 0,
    val exportCount: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    projectRepository: ProjectRepository,
    jobRepository: JobRepository,
    exportRepository: ExportRepository,
    storageRepository: StorageRepository,
    private val pipeline: ProjectPipeline,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        projectRepository.observeRecentProjects(limit = 8),
        jobRepository.observeActiveJobs(),
        exportRepository.observeRecentExports(limit = 3),
        storageRepository.observeUsage(),
        projectRepository.observeProjectCount(),
        exportRepository.observeCompletedCount(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            greeting = greetingForNow(),
            recentProjects = values[0] as List<Project>,
            activeJobs = values[1] as List<AiJob>,
            recentExports = values[2] as List<ExportRecord>,
            storage = values[3] as StorageUsage,
            projectCount = values[4] as Int,
            exportCount = values[5] as Int,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // Anything left mid-export by a killed process is reported honestly.
        viewModelScope.launch { pipeline.reconcileInterruptedExports() }
    }

    private fun greetingForNow(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..4 -> "Good night"
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
}

@Composable
fun HomeScreen(
    onCreate: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenClips: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenStorage: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column {
                Text(
                    text = state.greeting,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextTertiary,
                )
                Text(
                    text = "What do you want to create?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = StudioColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryAction(
                    title = "Create Short",
                    subtitle = "AI picks the best moments",
                    icon = Icons.Outlined.AutoAwesome,
                    modifier = Modifier.weight(1f),
                    onClick = onCreate,
                )
                SecondaryAction(
                    title = "Analyze",
                    subtitle = "Import footage",
                    icon = Icons.Outlined.Add,
                    modifier = Modifier.weight(1f),
                    onClick = onCreate,
                )
            }
        }

        state.activeJobs.forEach { job ->
            item { ActiveJobCard(job = job, onOpen = { onOpenProject(job.projectId) }) }
        }

        item {
            SectionHeader(
                title = "Recent projects",
                subtitle = if (state.projectCount == 0) {
                    "Nothing here yet"
                } else {
                    "${state.projectCount} project${if (state.projectCount == 1) "" else "s"}"
                },
                action = {
                    if (state.recentProjects.isNotEmpty()) {
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.labelLarge,
                            color = StudioColors.PrimaryBright,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .androidClickable(onOpenLibrary)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                },
            )
        }

        if (state.recentProjects.isEmpty()) {
            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "No projects yet",
                        message = "Turn your camera footage into Shorts with AI.",
                        icon = Icons.Outlined.ContentCut,
                        action = {
                            Text(
                                text = "Create your first video",
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioColors.PrimaryBright,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .androidClickable(onCreate)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        },
                    )
                }
            }
        } else {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(end = 4.dp),
                ) {
                    items(state.recentProjects, key = { it.id }) { project ->
                        ProjectPosterCard(
                            project = project,
                            onClick = { onOpenProject(project.id) },
                        )
                    }
                }
            }
        }

        state.recentExports.takeIf { it.isNotEmpty() }?.let { exports ->
            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(
                            title = "Recent exports",
                            subtitle = "${state.exportCount} finished video" +
                                if (state.exportCount == 1) "" else "s",
                            action = {
                                Text(
                                    text = "Library",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = StudioColors.PrimaryBright,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .androidClickable(onOpenLibrary)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            },
                        )
                        exports.forEach { export ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VideoThumbnail(
                                    source = export.path,
                                    modifier = Modifier
                                        .width(74.dp)
                                        .height(44.dp),
                                    aspectRatio = 16f / 9f,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = export.fileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioColors.TextPrimary,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${export.resolutionLabel} Â· " +
                                            SizeUtils.formatBytes(export.sizeBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioColors.TextTertiary,
                                    )
                                }
                                StudioBadge(
                                    text = "Ready",
                                    color = StudioColors.Success,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            StudioCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenStorage,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = StudioColors.Secondary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Storage",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = SizeUtils.formatBytes(state.storage.totalBytes),
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.Secondary,
                        )
                    }
                    StatRow("Original media", SizeUtils.formatBytes(state.storage.originalMediaBytes))
                    StatRow("Generated clips", SizeUtils.formatBytes(state.storage.generatedClipBytes))
                    StatRow("Cache & temporary", SizeUtils.formatBytes(state.storage.reclaimableBytes))
                    if (state.storage.reclaimableBytes > 0L) {
                        LinearProgressIndicator(
                            progress = {
                                state.storage.reclaimableBytes.toFloat() /
                                    state.storage.totalBytes.coerceAtLeast(1L).toFloat()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = StudioColors.Secondary,
                            trackColor = StudioColors.SurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveJobCard(job: AiJob, onOpen: () -> Unit) {
    StudioCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = StudioColors.PrimaryBright,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (job.status == JobStatus.FAILED) {
                            "Analysis needs attention"
                        } else {
                            "Analyzing your footage"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = job.errorMessage ?: job.stage.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.status == JobStatus.FAILED) {
                            StudioColors.Error
                        } else {
                            StudioColors.TextTertiary
                        },
                    )
                }
                Text(
                    text = "${(job.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.PrimaryBright,
                )
            }
            LinearProgressIndicator(
                progress = { job.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = StudioColors.Primary,
                trackColor = StudioColors.SurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(StudioColors.Primary, StudioColors.Tertiary.copy(alpha = 0.85f)),
                ),
            )
            .androidClickable(onClick)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = StudioColors.OnPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.OnPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.OnPrimary.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun SecondaryAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(StudioColors.SurfaceElevated)
            .androidClickable(onClick)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = StudioColors.Secondary)
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )
        }
    }
}

@Composable
fun ProjectPosterCard(
    project: Project,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(168.dp)
            .androidClickable(onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            VideoThumbnail(
                source = project.coverPath,
                modifier = Modifier.fillMaxWidth(),
                aspectRatio = 3f / 4f,
            )
            StudioBadge(
                text = statusLabel(project.status),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                color = statusColor(project.status),
            )
        }
        Text(
            text = project.name,
            style = MaterialTheme.typography.titleSmall,
            color = StudioColors.TextPrimary,
            maxLines = 1,
        )
        Text(
            text = "${project.targetShortCount} Shorts Â· ${project.targetShortDurationSec}s Â· " +
                project.aspectRatio.label,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextTertiary,
            maxLines = 1,
        )
    }
}

fun statusLabel(status: ProjectStatus): String = when (status) {
    ProjectStatus.DRAFT -> "Draft"
    ProjectStatus.IMPORTING -> "Importing"
    ProjectStatus.READY -> "Ready"
    ProjectStatus.ANALYZING -> "Analyzing"
    ProjectStatus.REVIEW -> "Review"
    ProjectStatus.GENERATED -> "Generated"
    ProjectStatus.EXPORTED -> "Exported"
    ProjectStatus.FAILED -> "Needs attention"
}

fun statusColor(status: ProjectStatus): androidx.compose.ui.graphics.Color = when (status) {
    ProjectStatus.ANALYZING, ProjectStatus.IMPORTING -> StudioColors.Warning
    ProjectStatus.GENERATED, ProjectStatus.EXPORTED -> StudioColors.Success
    ProjectStatus.FAILED -> StudioColors.Error
    else -> StudioColors.Primary
}

/** Formats a project's age for the projects list. */
fun projectAge(updatedAt: Long): String {
    val delta = System.currentTimeMillis() - updatedAt
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000} min ago"
        delta < 86_400_000 -> "${delta / 3_600_000} h ago"
        delta < 604_800_000 -> "${delta / 86_400_000} d ago"
        else -> TimeUtils.formatCompactDuration(delta)
    }
}

/** Small helper so callers do not repeat the age formatting rules. */
fun projectUpdatedLabel(updatedAt: Long): String = projectAge(updatedAt)

