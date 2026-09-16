package com.aivideostudio.ui.screens.analysis

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.processing.ProjectPipeline
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.theme.StudioColors
import com.aivideostudio.work.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnalysisUiState(
    val project: Project? = null,
    val job: AiJob? = null,
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
    val canContinueWithoutAi: Boolean = false,
) {
    val progress: Float get() = job?.progress?.coerceIn(0f, 1f) ?: 0f
    val currentStage: PipelineStage get() = job?.stage ?: PipelineStage.IMPORT
    val finishedStages: List<PipelineStage> get() = job?.finishedStages.orEmpty()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalysisViewModel @Inject constructor(    private val projectRepository: ProjectRepository,
    private val jobRepository: JobRepository,
    private val pipeline: ProjectPipeline,
    private val scheduler: PipelineScheduler,
) : ViewModel() {

    private val activeProjectId = MutableStateFlow(0L)
    private val extraState = MutableStateFlow(AnalysisUiState())

    val state: StateFlow<AnalysisUiState> = combine(
        activeProjectId.flatMapLatest { id -> projectRepository.observeProject(id) },
        activeProjectId.flatMapLatest { id -> jobRepository.observeLatestJob(id) },
        extraState,
    ) { project, job, extra ->
        extra.copy(
            project = project,
            job = job,
            isRunning = job?.status == JobStatus.RUNNING || job?.status == JobStatus.QUEUED,
            isFinished = project?.status == ProjectStatus.GENERATED ||
                project?.status == ProjectStatus.EXPORTED,
            errorMessage = job?.displayError ?: project?.lastError,
            canContinueWithoutAi = job?.stage in SKIPPABLE_STAGES &&
                job?.status == JobStatus.FAILED,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisUiState())

    fun attach(projectId: Long) {
        activeProjectId.value = projectId
        extraState.value = AnalysisUiState()
        viewModelScope.launch {
            // A project whose chain was interrupted (app killed, device restarted)
            // resumes from the first stage that has not completed.
            val job = jobRepository.getLatestJob(projectId) ?: return@launch
            when (job.status) {
                JobStatus.PAUSED -> scheduler.resumeAnalysis(projectId, job.stage)
                // RUNNING/QUEUED rows whose worker chain no longer exists are
                // dead weight: without re-enqueuing, the progress bar would sit
                // frozen forever with nothing behind it.
                JobStatus.RUNNING, JobStatus.QUEUED ->
                    if (!scheduler.hasActiveWork(projectId)) {
                        scheduler.resumeAnalysis(projectId, resumeStage(job))
                    }

                else -> Unit
            }
            pipeline.reconcileInterruptedExports()
        }
    }

    /**
     * The first stage after the last finished one; falls back to the recorded
     * stage when nothing has completed yet. Stages are idempotent, so
     * re-enqueuing a stage that actually did run is always safe.
     */
    private fun resumeStage(job: AiJob): PipelineStage {
        val finished = job.finishedStages
        if (finished.isEmpty()) return job.stage
        val index = PipelineScheduler.STAGE_ORDER.indexOf(finished.last())
        return PipelineScheduler.STAGE_ORDER.getOrNull(index + 1) ?: job.stage
    }

    fun retry() {
        val projectId = activeProjectId.value
        val job = state.value.job ?: return
        viewModelScope.launch {
            projectRepository.recordError(projectId, null)
            scheduler.resumeAnalysis(projectId, job.stage)
        }
    }

    /**
     * Skips the stage that failed and carries on. Only the AI stages can be
     * skipped: a project without a transcript still produces Shorts, but one
     * without scene detection cannot produce anything at all.
     */
    fun continueWithoutAi() {
        val projectId = activeProjectId.value
        val job = state.value.job ?: return
        val nextIndex = PipelineScheduler.STAGE_ORDER.indexOf(job.stage) + 1
        val next = PipelineScheduler.STAGE_ORDER.getOrNull(nextIndex) ?: return
        viewModelScope.launch {
            jobRepository.updateProgress(
                jobId = job.id,
                stage = next,
                status = JobStatus.QUEUED,
                progress = PipelineStage.completedWeight(job.stage),
                attempt = job.attempt,
                finishedStages = job.finishedStages + job.stage,
                errorMessage = null,
                technicalDetail = null,
            )
            projectRepository.recordError(projectId, null)
            scheduler.resumeAnalysis(projectId, next)
        }
    }

    fun cancel() {
        scheduler.cancel(activeProjectId.value)
    }

    private companion object {
        val SKIPPABLE_STAGES = setOf(
            PipelineStage.TRANSCRIPTION,
            PipelineStage.SEMANTIC_ANALYSIS,
            PipelineStage.HIGHLIGHT_DETECTION,
        )
    }
}

@Composable
fun AnalysisScreen(
    projectId: Long,
    onFinished: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.attach(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isFinished) {
        if (state.isFinished) {
            // Give the finishing animation a moment before moving on.
            delay(650)
            onFinished(projectId)
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
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
                    Text(
                        text = "Analyzing",
                        style = MaterialTheme.typography.headlineSmall,
                        color = StudioColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                StudioColors.Primary.copy(alpha = 0.32f),
                                                StudioColors.Secondary.copy(alpha = 0.18f),
                                            ),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = StudioColors.PrimaryBright,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = StudioColors.PrimaryBright,
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (state.isFinished) {
                                        "Analysis complete"
                                    } else {
                                        "Analyzing your footage\u2026"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = state.project?.name ?: "Preparing project",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextTertiary,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioColors.PrimaryBright,
                            )
                        }

                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = StudioColors.Primary,
                            trackColor = StudioColors.SurfaceVariant,
                        )

                        Text(
                            text = state.currentStage.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }
            }

            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PipelineScheduler.STAGE_ORDER.forEach { stage ->
                            StageRow(
                                stage = stage,
                                isDone = stage in state.finishedStages,
                                isCurrent = stage == state.currentStage && !state.isFinished,
                            )
                        }
                    }
                }
            }

            state.errorMessage?.let { message ->
                item {
                    StudioCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = StudioColors.Warning,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Something went wrong",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.Warning,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioColors.TextSecondary,
                            )
                            Text(
                                text = "Your original videos are untouched.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextTertiary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = viewModel::retry,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = StudioColors.Primary,
                                        contentColor = StudioColors.OnPrimary,
                                    ),
                                ) {
                                    Text("Retry")
                                }
                                AnimatedVisibility(visible = state.canContinueWithoutAi) {
                                    TextButton(onClick = viewModel::continueWithoutAi) {
                                        Text(
                                            text = "Continue without AI",
                                            color = StudioColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.isRunning) {
                item {
                    TextButton(onClick = viewModel::cancel) {
                        Text("Cancel analysis", color = StudioColors.TextTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StageRow(
    stage: PipelineStage,
    isDone: Boolean,
    isCurrent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        isDone -> StudioColors.Success.copy(alpha = 0.2f)
                        isCurrent -> StudioColors.Primary.copy(alpha = 0.2f)
                        else -> StudioColors.SurfaceVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isDone -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = StudioColors.Success,
                    modifier = Modifier.size(13.dp),
                )

                isCurrent -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = StudioColors.PrimaryBright,
                    strokeWidth = 2.dp,
                )

                else -> Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(StudioColors.TextTertiary.copy(alpha = 0.5f)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stage.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isDone -> StudioColors.TextSecondary
                isCurrent -> StudioColors.TextPrimary
                else -> StudioColors.TextTertiary
            },
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isDone) {
            StudioBadge(text = "Done", color = StudioColors.Success)
        }
    }
}
