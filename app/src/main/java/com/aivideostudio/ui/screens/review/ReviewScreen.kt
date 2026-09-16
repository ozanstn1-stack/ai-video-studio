package com.aivideostudio.ui.screens.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.core.common.TimeUtils
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.repository.AnalysisRepository
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.ui.components.EmptyState
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.components.StudioVideoPlayer
import com.aivideostudio.ui.components.androidClickable
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
import javax.inject.Inject

data class ReviewUiState(
    val project: Project? = null,
    val assets: List<VideoAsset> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val clips: List<GeneratedClip> = emptyList(),
    val isGenerating: Boolean = false,
) {
    val selected: List<Highlight> get() = highlights.filter { it.isSelected && !it.isRejected }
    val previewUri: String? get() = selected.firstOrNull()
        ?.let { highlight -> assets.firstOrNull { it.id == highlight.assetId }?.uri }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val mediaRepository: MediaRepository,
    private val analysisRepository: AnalysisRepository,
    private val clipRepository: ClipRepository,
    private val jobRepository: JobRepository,
    private val scheduler: PipelineScheduler,
) : ViewModel() {

    private val projectId = MutableStateFlow(0L)
    private val generating = MutableStateFlow(false)

    val state: StateFlow<ReviewUiState> = combine(
        projectId.flatMapLatest { id -> projectRepository.observeProject(id) },
        projectId.flatMapLatest { id -> mediaRepository.observeAssets(id) },
        projectId.flatMapLatest { id -> analysisRepository.observeHighlights(id) },
        projectId.flatMapLatest { id -> clipRepository.observeClips(id) },
        generating,
    ) { project, assets, highlights, clips, isGenerating ->
        ReviewUiState(
            project = project,
            assets = assets,
            highlights = highlights.sortedByDescending { it.overallScore },
            clips = clips,
            isGenerating = isGenerating,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun attach(id: Long) {
        projectId.value = id
    }

    fun setSelected(highlightId: Long, selected: Boolean) {
        viewModelScope.launch {
            analysisRepository.setHighlightSelection(highlightId, selected, rejected = !selected)
        }
    }

    fun generateShorts() {
        val id = projectId.value
        if (id <= 0L) return
        generating.value = true
        viewModelScope.launch {
            val job = jobRepository.getLatestJob(id)
            if (job != null) {
                jobRepository.updateProgress(
                    jobId = job.id,
                    stage = PipelineStage.SHORT_GENERATION,
                    status = JobStatus.QUEUED,
                    progress = PipelineStage.completedWeight(PipelineStage.HIGHLIGHT_DETECTION),
                    attempt = 0,
                    finishedStages = job.finishedStages
                        .filter { it.ordinal < PipelineStage.SHORT_GENERATION.ordinal } +
                        listOf(PipelineStage.HIGHLIGHT_DETECTION),
                )
            }
            scheduler.resumeAnalysis(id, PipelineStage.SHORT_GENERATION)
            projectRepository.touch(id)
            generating.value = false
        }
    }

    fun stopGeneration() {
        generating.value = false
    }
}

@Composable
fun ReviewScreen(
    projectId: Long,
    onBack: () -> Unit,
    onOpenClips: (Long) -> Unit,
    onRegenerate: (Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.attach(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
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
                        text = state.project?.name ?: "Review",
                        style = MaterialTheme.typography.headlineSmall,
                        color = StudioColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = "${state.selected.size} of ${state.highlights.size} moments selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextTertiary,
                    )
                }
            }
        }

        state.project?.aiSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "What the AI understood",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }
            }
        }

        if (state.selected.isNotEmpty()) {
            item {
                val highlight = state.selected.first()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(
                        title = "Preview",
                        subtitle = "${TimeUtils.formatDuration(highlight.startMs)} \u2013 " +
                            TimeUtils.formatDuration(highlight.endMs),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(18.dp)),
                    ) {
                        StudioVideoPlayer(
                            mediaUri = state.previewUri,
                            startPositionMs = highlight.startMs,
                            playWhenReady = false,
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "AI selected these moments",
                subtitle = "Turn off anything you do not want to use",
            )
        }

        if (state.highlights.isEmpty()) {
            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "No moments yet",
                        message = "The analysis did not find usable moments in this footage.",
                        icon = Icons.Outlined.ContentCut,
                    )
                }
            }
        } else {
            items(state.highlights, key = { it.id }) { highlight ->
                HighlightRow(
                    highlight = highlight,
                    index = state.highlights.indexOf(highlight) + 1,
                    expanded = expandedId == highlight.id,
                    onToggleExpand = {
                        expandedId = if (expandedId == highlight.id) null else highlight.id
                    },
                    onSelectedChange = { selected -> viewModel.setSelected(highlight.id, selected) },
                )
            }
        }

        if (state.clips.isNotEmpty()) {
            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "${state.clips.size} Short${if (state.clips.size == 1) "" else "s"} ready",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioColors.TextPrimary,
                        )
                        Text(
                            text = "Open them to edit, caption and export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                        )
                        Button(
                            onClick = { onOpenClips(projectId) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Primary,
                                contentColor = StudioColors.OnPrimary,
                            ),
                        ) {
                            Text("View Shorts")
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::generateShorts,
                    enabled = state.selected.isNotEmpty() && !state.isGenerating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Primary,
                        contentColor = StudioColors.OnPrimary,
                        disabledContainerColor = StudioColors.SurfaceVariant,
                        disabledContentColor = StudioColors.TextTertiary,
                    ),
                ) {
                    if (state.isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = StudioColors.OnPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = if (state.clips.isEmpty()) {
                            "Create ${state.project?.targetShortCount ?: 3} Shorts"
                        } else {
                            "Regenerate Shorts"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = { onRegenerate(projectId) }) {
                    Text("Run the analysis again", color = StudioColors.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun HighlightRow(
    highlight: Highlight,
    index: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectedChange: (Boolean) -> Unit,
) {
    StudioCard(modifier = Modifier.fillMaxWidth(), onClick = onToggleExpand) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = index.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextTertiary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = highlight.label.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${TimeUtils.formatDuration(highlight.startMs)} \u2013 " +
                            TimeUtils.formatDuration(highlight.endMs) +
                            "  (${highlight.durationMs / 1000}s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextTertiary,
                    )
                }
                Switch(
                    checked = highlight.isSelected && !highlight.isRejected,
                    onCheckedChange = onSelectedChange,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                highlight.reasons.take(3).forEach { reason ->
                    StudioBadge(text = "${reason.badge} ${reason.text}", color = StudioColors.Secondary)
                }
            }

            if (highlight.transcriptText != null) {
                Text(
                    text = "\u201C${highlight.transcriptText.take(140)}\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                    maxLines = if (expanded) 6 else 2,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .androidClickable(onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Why this clip?",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.PrimaryBright,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = StudioColors.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScoreRow("Visual quality", highlight.visualScore)
                    ScoreRow("Speech clarity", highlight.speechScore)
                    ScoreRow("Audio quality", highlight.audioScore)
                    ScoreRow("Motion", highlight.motionScore)
                    ScoreRow("Interestingness", highlight.interestScore)
                    ScoreRow("Uniqueness", highlight.uniquenessScore)
                    ScoreRow("Story relevance", highlight.storyScore)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Opening strength ${(highlight.hookScore * 100).toInt()}% \u00B7 " +
                            "Ending strength ${(highlight.payoffScore * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreRow(label: String, score: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextTertiary,
            modifier = Modifier.width(120.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(StudioColors.SurfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(score.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            score >= 0.75f -> StudioColors.Success
                            score >= 0.5f -> StudioColors.Secondary
                            else -> StudioColors.TextTertiary
                        },
                    ),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${(score * 100).toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = StudioColors.TextSecondary,
            modifier = Modifier.width(28.dp),
        )
    }
}
