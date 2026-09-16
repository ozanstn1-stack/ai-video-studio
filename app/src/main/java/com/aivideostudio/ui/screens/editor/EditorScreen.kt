package com.aivideostudio.ui.screens.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aivideostudio.ai.improve.EditorSuggestionEngine
import com.aivideostudio.core.common.TimeUtils
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.ClipEditState
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.ui.components.OptionChip
import com.aivideostudio.ui.components.OptionRow
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StudioBadge
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.components.StudioVideoPlayer
import com.aivideostudio.ui.theme.StudioColors
import kotlinx.coroutines.flow.StateFlow

/**
 * The edit surface.
 *
 * The screen is deliberately organised around four questions a non-editor can
 * answer: what is in the Short (Clip), what it says (Text), how it sounds
 * (Audio) and how it is framed (Crop). "AI Improve" and "Export" stay visible
 * the whole time at the bottom.
 */
@Composable
fun EditorScreen(
    clipId: Long,
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(clipId) { viewModel.attach(clipId) }
    LaunchedEffect(context) { viewModel.shareContext = context.applicationContext }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showCaptionEditor by remember { mutableStateOf<Long?>(null) }
    var showImprove by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showTitleDialog by remember { mutableStateOf(false) }

    val pickMusic = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::setMusic) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 170.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = StudioColors.TextSecondary,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.title.ifBlank { "Edit Short" },
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "${state.totalDurationMs / 1000}s \u00B7 " +
                                "${state.aspectRatio.label} \u00B7 ${state.timeline.size} clips",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                        )
                    }
                    if (state.isDirty) {
                        StudioBadge(text = "Unsaved edits", color = StudioColors.Warning)
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .aspectRatio(state.aspectRatio.aspectValue.coerceIn(0.5f, 1.9f))
                        .clip(RoundedCornerShape(18.dp)),
                ) {
                    StudioVideoPlayer(
                        mediaUri = state.previewUri,
                        startPositionMs = state.previewStartMs,
                        playWhenReady = false,
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionHeader(
                        title = "Timeline",
                        subtitle = "Tap a clip to select it",
                    )
                    TimelineStrip(
                        timeline = state.timeline,
                        captions = state.captions.map { it.startMs to it.endMs },
                        selectedIndex = state.selectedIndex,
                        onSelect = viewModel::selectSegment,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimelineAction(
                            icon = Icons.Outlined.ContentCut,
                            label = "Split",
                            enabled = state.selectedSegment != null,
                            onClick = viewModel::splitSelected,
                        )
                        TimelineAction(
                            icon = Icons.Outlined.Delete,
                            label = "Delete",
                            enabled = state.selectedSegment != null,
                            onClick = viewModel::deleteSelected,
                        )
                        TimelineAction(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            label = "Move left",
                            enabled = state.canMoveLeft,
                            onClick = viewModel::moveSelectedLeft,
                        )
                        TimelineAction(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            label = "Move right",
                            enabled = state.canMoveRight,
                            flip = true,
                            onClick = viewModel::moveSelectedRight,
                        )
                    }
                }
            }

            item {
                StudioCard(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.selectedSegment?.let { segment ->
                            Text(
                                text = "Clip ${segment.orderIndex + 1} \u00B7 " +
                                    "${segment.durationMs / 1000}s \u00B7 " +
                                    segment.storyRole.ordinalLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioColors.TextPrimary,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OptionRow("Trim")
                                Text(
                                    text = "${TimeUtils.formatDuration(segment.sourceStartMs)} \u2013 " +
                                        TimeUtils.formatDuration(segment.sourceEndMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextTertiary,
                                )
                                RangeSliderRow(
                                    start = segment.sourceStartMs,
                                    end = segment.sourceEndMs,
                                    onChange = { start, end ->
                                        viewModel.trimSelected(start, end)
                                    },
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OptionRow("Speed")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0.5f, 0.75f, 1f, 1.5f, 2f).forEach { speed ->
                                        OptionChip(
                                            label = if (speed == 1f) "1x" else "${speed}x",
                                            selected = segment.playbackSpeed == speed,
                                            onClick = { viewModel.setSpeed(speed) },
                                        )
                                    }
                                }
                            }
                        } ?: Text(
                            text = "Select a clip on the timeline to trim it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextTertiary,
                        )
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionHeader(title = "Format")
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
                StudioCard(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SectionHeader(
                            title = "Crop",
                            subtitle = "Keep the subject inside the frame",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                CropMode.SMART to "AI crop",
                                CropMode.CENTER to "Centre",
                                CropMode.FIT to "Fit",
                            ).forEach { (mode, label) ->
                                OptionChip(
                                    label = label,
                                    selected = state.selectedSegment?.cropMode == mode,
                                    enabled = state.selectedSegment != null,
                                    onClick = { viewModel.setCropMode(mode) },
                                )
                            }
                        }
                        state.selectedSegment?.let { segment ->
                            SliderRow(
                                label = "Zoom",
                                value = segment.cropScale,
                                range = 1f..3f,
                                onChange = { viewModel.setZoom(it) },
                            )
                            SliderRow(
                                label = "Horizontal",
                                value = segment.cropCenterX,
                                range = 0f..1f,
                                onChange = { viewModel.setCropCenter(it, segment.cropCenterY) },
                            )
                            SliderRow(
                                label = "Vertical",
                                value = segment.cropCenterY,
                                range = 0f..1f,
                                onChange = { viewModel.setCropCenter(segment.cropCenterX, it) },
                            )
                        }
                    }
                }
            }

            item {
                StudioCard(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SectionHeader(
                            title = "Captions",
                            subtitle = "${state.captions.size} lines",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OptionRow("Style")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(SubtitleStyle.entries.toList()) { style ->
                                    OptionChip(
                                        label = style.displayName,
                                        selected = state.subtitleStyle == style,
                                        onClick = { viewModel.setSubtitleStyle(style) },
                                    )
                                }
                            }
                        }
                        SliderRow(
                            label = "Position",
                            value = state.captionPositionY,
                            range = 0.45f..0.90f,
                            onChange = viewModel::setCaptionPosition,
                        )
                        state.captions.take(4).forEach { caption ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(StudioColors.SurfaceVariant)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = TimeUtils.formatDuration(caption.startMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextTertiary,
                                    modifier = Modifier.width(52.dp),
                                )
                                Text(
                                    text = caption.text.ifBlank { "(no speech)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextPrimary,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { showCaptionEditor = caption.id },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Subtitles,
                                        contentDescription = "Edit caption",
                                        tint = StudioColors.TextSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        if (state.captionsSpeculative) {
                            Text(
                                text = "Captions were generated from timing data only, so the " +
                                    "words may be missing. Enable cloud AI for word-accurate " +
                                    "subtitles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.Warning,
                            )
                        }
                    }
                }
            }

            item {
                StudioCard(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SectionHeader(title = "Text & audio")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimelineAction(
                                icon = Icons.Outlined.TextFields,
                                label = "Add text",
                                enabled = true,
                                onClick = { showTextDialog = true },
                            )
                            TimelineAction(
                                icon = Icons.Outlined.AutoAwesome,
                                label = "Set hook",
                                enabled = state.hookText.isNullOrBlank(),
                                onClick = { showTitleDialog = true },
                            )
                            TimelineAction(
                                icon = Icons.Outlined.MusicNote,
                                label = if (state.musicTitle == null) "Add music" else "Change music",
                                enabled = true,
                                onClick = { pickMusic.launch(arrayOf("audio/*")) },
                            )
                        }
                        state.overlays.forEach { overlay ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = overlay.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { viewModel.deleteOverlay(overlay.id) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Remove text",
                                        tint = StudioColors.Error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (state.originalMuted) {
                                    Icons.AutoMirrored.Outlined.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Outlined.VolumeUp
                                },
                                contentDescription = null,
                                tint = StudioColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Mute original audio",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.originalMuted,
                                onCheckedChange = viewModel::setOriginalMuted,
                            )
                        }
                        if (!state.originalMuted) {
                            SliderRow(
                                label = "Original volume",
                                value = state.originalVolume,
                                range = 0f..1.5f,
                                onChange = viewModel::setOriginalVolume,
                            )
                        }
                        if (state.musicTitle != null) {
                            SliderRow(
                                label = "Music volume",
                                value = state.musicVolume,
                                range = 0f..1f,
                                onChange = viewModel::setMusicVolume,
                            )
                            TextButton(onClick = viewModel::clearMusic) {
                                Text("Remove music", color = StudioColors.Error)
                            }
                        }
                    }
                }
            }
        }

        EditorBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            isExporting = state.exportStatus == ExportStatus.RUNNING,
            exportProgress = state.exportProgress,
            exportLabel = state.exportLabel,
            onImprove = {
                viewModel.refreshSuggestions()
                showImprove = true
            },
            onExport = viewModel::export,
            onShare = viewModel::shareLastExport,
        )
    }

    showCaptionEditor?.let { captionId ->
        val caption = state.captions.firstOrNull { it.id == captionId }
        if (caption != null) {
            CaptionEditDialog(
                initial = caption.text,
                onDismiss = { showCaptionEditor = null },
                onConfirm = { text ->
                    viewModel.setCaptionText(captionId, text)
                    showCaptionEditor = null
                },
            )
        }
    }

    if (showTextDialog) {
        CaptionEditDialog(
            initial = "",
            title = "Add text",
            onDismiss = { showTextDialog = false },
            onConfirm = { text ->
                viewModel.addOverlay(text)
                showTextDialog = false
            },
        )
    }

    if (showTitleDialog) {
        CaptionEditDialog(
            initial = state.title,
            title = "Opening hook",
            onDismiss = { showTitleDialog = false },
            onConfirm = { text ->
                viewModel.setHook(text)
                showTitleDialog = false
            },
        )
    }

    if (showImprove) {
        ImproveSheet(
            suggestions = state.suggestions,
            isApplied = state.suggestionsApplied,
            onApply = { viewModel.applySuggestion(it) },
            onApplyAll = viewModel::applyAllSuggestions,
            onDismiss = { showImprove = false },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            containerColor = StudioColors.SurfaceElevated,
            titleContentColor = StudioColors.TextPrimary,
            textContentColor = StudioColors.TextSecondary,
            title = { Text("Heads up") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            },
        )
    }
}

@Composable
private fun TimelineStrip(
    timeline: List<TimelineClip>,
    captions: List<Pair<Long, Long>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val total = timeline.sumOf { it.durationMs }.coerceAtLeast(1L)
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StudioColors.SurfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        itemsIndexed(timeline, key = { _, clip -> clip.id }) { index, clip ->
            val fraction = (clip.durationMs.toFloat() / total).coerceIn(0.06f, 0.65f)
            val selected = index == selectedIndex
            Column(
                modifier = Modifier
                    .width(220.dp * fraction)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) StudioColors.Primary else StudioColors.TimelineClipUser,
                    )
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) StudioColors.PrimaryBright else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                    )
                    .androidClickableEditor { onSelect(index) }
                    .padding(6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = clip.storyRole.ordinalLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.OnPrimary,
                    maxLines = 1,
                )
                Text(
                    text = "${clip.durationMs / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.OnPrimary.copy(alpha = 0.8f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (captions.isEmpty()) {
                                StudioColors.WaveformSilence
                            } else {
                                StudioColors.WaveformSpeech
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun TimelineAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    flip: Boolean = false,
) {
    val tint = if (enabled) StudioColors.TextSecondary else StudioColors.TextTertiary.copy(alpha = 0.5f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StudioColors.SurfaceVariant)
            .androidClickableEditor(enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                .then(
                    if (flip) {
                        Modifier.rotate180()
                    } else {
                        Modifier
                    },
                ),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

/** Two-handle trim control built from two independent sliders. */
@Composable
private fun RangeSliderRow(
    start: Long,
    end: Long,
    onChange: (Long, Long) -> Unit,
) {
    val span = (end - start).coerceAtLeast(1_000L)
    val maxStart = end - 500L
    Slider(
        value = start.toFloat(),
        onValueChange = { onChange(it.toLong(), end) },
        valueRange = 0f..maxStart.coerceAtLeast(1L).toFloat(),
    )
    Slider(
        value = end.toFloat(),
        onValueChange = { onChange(start, it.toLong()) },
        valueRange = (start + 500L).toFloat()..(start + span + 10_000L).toFloat(),
    )
}

@Composable
private fun EditorBottomBar(
    modifier: Modifier = Modifier,
    isExporting: Boolean,
    exportProgress: Float,
    exportLabel: String?,
    onImprove: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioColors.Surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isExporting) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Exporting\u2026 ${(exportProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.Secondary,
                )
                LinearProgressIndicator(
                    progress = { exportProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = StudioColors.Secondary,
                    trackColor = StudioColors.SurfaceVariant,
                )
            }
        } else if (exportLabel != null) {
            Text(
                text = exportLabel,
                style = MaterialTheme.typography.labelMedium,
                color = StudioColors.Success,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onImprove,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.SurfaceVariant,
                    contentColor = StudioColors.PrimaryBright,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI Improve", fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onExport,
                enabled = !isExporting,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Primary,
                    contentColor = StudioColors.OnPrimary,
                    disabledContainerColor = StudioColors.SurfaceVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export", fontWeight = FontWeight.SemiBold)
            }
        }
        if (exportLabel != null && !isExporting) {
            TextButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("Share last export", color = StudioColors.Secondary)
            }
        }
    }
}

@Composable
private fun ImproveSheet(
    suggestions: List<EditorSuggestionEngine.Suggestion>,
    isApplied: Set<String>,
    onApply: (EditorSuggestionEngine.Suggestion) -> Unit,
    onApplyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioColors.SurfaceElevated,
        titleContentColor = StudioColors.TextPrimary,
        textContentColor = StudioColors.TextSecondary,
        title = {
            Column {
                Text("AI Improve")
                Text(
                    text = if (suggestions.isEmpty()) {
                        "Nothing to change \u2014 this Short already looks good."
                    } else {
                        "AI found ${suggestions.size} improvement" +
                            if (suggestions.size == 1) "" else "s"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextTertiary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.forEach { suggestion ->
                    val applied = suggestion.id in isApplied
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioColors.SurfaceVariant)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = suggestion.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioColors.TextPrimary,
                            )
                            Text(
                                text = suggestion.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextTertiary,
                            )
                        }
                        if (applied) {
                            StudioBadge(text = "Applied", color = StudioColors.Success)
                        } else {
                            TextButton(onClick = { onApply(suggestion) }) {
                                Text("Apply", color = StudioColors.PrimaryBright)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (suggestions.isNotEmpty()) {
                TextButton(onClick = onApplyAll) {
                    Text("Apply all", color = StudioColors.PrimaryBright)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = StudioColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun CaptionEditDialog(
    initial: String,
    title: String = "Edit caption",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioColors.SurfaceElevated,
        titleContentColor = StudioColors.TextPrimary,
        textContentColor = StudioColors.TextSecondary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Text") },
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save", color = StudioColors.PrimaryBright)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = StudioColors.TextSecondary)
            }
        },
    )
}

private fun Modifier.rotate180(): Modifier = this.rotate(180f)

private fun Modifier.androidClickableEditor(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun Modifier.androidClickableEditor(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)
