package com.aivideostudio.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.ui.components.EmptyState
import com.aivideostudio.ui.components.OptionChip
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.screens.home.ProjectPosterCard
import com.aivideostudio.ui.screens.home.projectAge
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProjectFilter(val labelRes: Int) {
    ALL(com.aivideostudio.R.string.filter_all),
    IN_PROGRESS(com.aivideostudio.R.string.filter_in_progress),
    READY(com.aivideostudio.R.string.filter_ready),
}

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val filter: ProjectFilter = ProjectFilter.ALL,
    val pendingDeleteId: Long? = null,
) {
    val count: Int get() = projects.size

    /** Filtering happens here so the grid never holds its own duplicate state. */
    val visibleProjects: List<Project>
        get() = when (filter) {
            ProjectFilter.ALL -> projects
            ProjectFilter.IN_PROGRESS -> projects.filter { it.status.isBusy }
            ProjectFilter.READY -> projects.filter { it.status.isFinished }
        }
}

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val scheduler: com.aivideostudio.work.PipelineScheduler,
) : ViewModel() {

    private val filter = MutableStateFlow(ProjectFilter.ALL)
    private val pendingDeleteId = MutableStateFlow<Long?>(null)

    val state: StateFlow<ProjectsUiState> = combine(
        projectRepository.observeProjects(),
        filter,
        pendingDeleteId,
    ) { projects, currentFilter, pending ->
        ProjectsUiState(
            projects = projects,
            filter = currentFilter,
            pendingDeleteId = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    fun setFilter(value: ProjectFilter) {
        filter.value = value
    }

    fun requestDelete(projectId: Long) {
        pendingDeleteId.value = projectId
    }

    fun cancelDelete() {
        pendingDeleteId.value = null
    }

    fun delete(projectId: Long) {
        pendingDeleteId.value = null
        viewModelScope.launch {
            // Kill any live analysis first, otherwise the worker would keep
            // running against rows that no longer exist.
            scheduler.cancel(projectId)
            projectRepository.deleteProject(projectId)
        }
    }
}

@Composable
fun ProjectsScreen(
    onCreate: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenAnalysis: (Long) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
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
                title = stringResource(com.aivideostudio.R.string.projects_title),
                subtitle = "${state.count} project" + if (state.count == 1) "" else "s",
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectFilter.entries.forEach { option ->
                    OptionChip(
                        label = stringResource(option.labelRes),
                        selected = state.filter == option,
                        onClick = { viewModel.setFilter(option) },
                    )
                }
            }
        }

        when {
            state.projects.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = stringResource(com.aivideostudio.R.string.no_projects_yet),
                        message = stringResource(com.aivideostudio.R.string.no_projects_hint),
                        icon = Icons.Outlined.VideoSettings,
                        action = {
                            Text(
                                text = stringResource(com.aivideostudio.R.string.create_first_video),
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

            state.visibleProjects.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No projects match this filter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextTertiary,
                )
            }

            else -> items(state.visibleProjects, key = { it.id }) { project ->
                ProjectCell(
                    project = project,
                    onOpen = { onOpenProject(project.id) },
                    onOpenAnalysis = { onOpenAnalysis(project.id) },
                    onDeleteRequest = { viewModel.requestDelete(project.id) },
                )
            }
        }
    }

    val pendingId = state.pendingDeleteId
    if (pendingId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text(stringResource(com.aivideostudio.R.string.delete_project_title)) },
            text = {
                Text(stringResource(com.aivideostudio.R.string.delete_project_message))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(pendingId) }) {
                    Text(stringResource(com.aivideostudio.R.string.delete), color = StudioColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text(stringResource(com.aivideostudio.R.string.cancel))
                }
            },
            containerColor = StudioColors.SurfaceElevated,
            titleContentColor = StudioColors.TextPrimary,
            textContentColor = StudioColors.TextSecondary,
        )
    }
}

@Composable
private fun ProjectCell(
    project: Project,
    onOpen: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProjectPosterCard(project = project, onClick = onOpen)
        Row(
            modifier = Modifier.width(168.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = projectAge(project.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDeleteRequest, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(com.aivideostudio.R.string.delete_project_action),
                    tint = StudioColors.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (project.status == ProjectStatus.ANALYZING || project.status == ProjectStatus.FAILED) {
            Button(
                onClick = onOpenAnalysis,
                modifier = Modifier.width(168.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Primary,
                    contentColor = StudioColors.OnPrimary,
                ),
            ) {
                Text(
                    stringResource(com.aivideostudio.R.string.continue_analysis),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
