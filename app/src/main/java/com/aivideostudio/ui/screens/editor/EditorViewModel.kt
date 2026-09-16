package com.aivideostudio.ui.screens.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aivideostudio.ai.improve.EditorSuggestionEngine
import com.aivideostudio.ai.subtitle.SubtitleBuilder
import com.aivideostudio.core.common.Constants
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.ClipEditState
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TextOverlay
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.repository.AnalysisRepository
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.work.PipelineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class EditorUiState(
    val clipId: Long = 0L,
    val projectId: Long = 0L,
    val title: String = "",
    val description: String = "",
    val aspectRatio: AspectRatio = AspectRatio.VERTICAL_9_16,
    val timeline: List<TimelineClip> = emptyList(),
    val captions: List<Caption> = emptyList(),
    val overlays: List<TextOverlay> = emptyList(),
    val audio: AudioBed? = null,
    val hookText: String? = null,
    val selectedIndex: Int = 0,
    val assets: Map<Long, VideoAsset> = emptyMap(),
    val suggestions: List<EditorSuggestionEngine.Suggestion> = emptyList(),
    val suggestionsApplied: Set<String> = emptySet(),
    val exportStatus: ExportStatus? = null,
    val exportProgress: Float = 0f,
    val exportLabel: String? = null,
    val lastExportPath: String? = null,
    val captionsSpeculative: Boolean = false,
    val isDirty: Boolean = false,
    val isLoading: Boolean = true,
    val message: String? = null,
    val subtitleStyle: SubtitleStyle = SubtitleStyle.CREATOR,
    val captionPositionY: Float = 0.78f,
) {
    val selectedSegment: TimelineClip? get() = timeline.getOrNull(selectedIndex)
    val totalDurationMs: Long get() = timeline.sumOf { it.durationMs }
    val canMoveLeft: Boolean get() = selectedIndex > 0
    val canMoveRight: Boolean get() = selectedIndex in 0 until timeline.lastIndex
    val originalMuted: Boolean get() = audio?.originalAudioMuted == true
    val originalVolume: Float get() = audio?.originalVolume ?: 1f
    val musicTitle: String? get() = audio?.title
    val musicVolume: Float get() = audio?.volume ?: 0.35f

    val previewUri: String? get() = selectedSegment
        ?.let { segment -> assets[segment.assetId]?.uri }
        ?: timeline.firstOrNull()?.let { segment -> assets[segment.assetId]?.uri }

    val previewStartMs: Long
        get() = selectedSegment?.sourceStartMs ?: timeline.firstOrNull()?.sourceStartMs ?: 0L
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val clipRepository: ClipRepository,
    private val mediaRepository: MediaRepository,
    private val analysisRepository: AnalysisRepository,
    private val projectRepository: ProjectRepository,
    private val exportRepository: ExportRepository,
    private val settingsRepository: SettingsRepository,
    private val scheduler: PipelineScheduler,
    private val suggestionEngine: EditorSuggestionEngine,
    private val subtitleBuilder: SubtitleBuilder,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var currentClip: GeneratedClip? = null
    private var transcriptSegments: List<com.aivideostudio.domain.model.TranscriptSegment> = emptyList()
    private var exportWatcher: kotlinx.coroutines.Job? = null

    fun attach(clipId: Long) {
        if (_state.value.clipId == clipId && !_state.value.isLoading) return
        _state.update { it.copy(clipId = clipId, isLoading = true) }
        viewModelScope.launch {
            val clip = clipRepository.getClip(clipId)
            if (clip == null) {
                _state.update { it.copy(isLoading = false, message = "This Short could not be loaded") }
                return@launch
            }
            currentClip = clip
            val edit = clipRepository.getEditState(clipId)
            val assets = mediaRepository.getAssets(clip.projectId).associateBy { it.id }
            transcriptSegments = analysisRepository.getSegments(clip.projectId)
            val preferences = settingsRepository.snapshot()
            val style = edit?.captions?.firstOrNull()?.style ?: preferences.defaultSubtitleStyle

            _state.update {
                EditorUiState(
                    clipId = clipId,
                    projectId = clip.projectId,
                    title = clip.title,
                    description = clip.description,
                    aspectRatio = clip.aspectRatio,
                    timeline = edit?.timeline.orEmpty(),
                    captions = edit?.captions.orEmpty(),
                    overlays = edit?.overlays.orEmpty(),
                    audio = edit?.audio,
                    hookText = edit?.hookText,
                    assets = assets,
                    subtitleStyle = style,
                    captionPositionY = edit?.captions?.firstOrNull()?.positionY ?: 0.78f,
                    captionsSpeculative = (edit?.captions.isNullOrEmpty()) &&
                        transcriptSegments.isEmpty() && edit?.timeline.orEmpty().isNotEmpty(),
                    isLoading = false,
                    lastExportPath = clip.outputPath,
                )
            }
            watchLatestExport(clipId)
        }
    }

    private fun watchLatestExport(clipId: Long) {
        exportWatcher?.cancel()
        exportWatcher = viewModelScope.launch {
            exportRepository.observeProjectExports(_state.value.projectId).collect { exports ->
                val latest = exports.filter { it.clipId == clipId }.maxByOrNull { it.createdAt } ?: return@collect
                _state.update {
                    it.copy(
                        exportStatus = latest.status,
                        exportProgress = latest.progress,
                        lastExportPath = latest.path ?: it.lastExportPath,
                        exportLabel = when (latest.status) {
                            ExportStatus.COMPLETED -> "Exported \u00B7 ${latest.resolutionLabel}"
                            ExportStatus.FAILED -> latest.errorMessage ?: "Export failed"
                            ExportStatus.RUNNING -> null
                            ExportStatus.CANCELLED -> "Export cancelled"
                            ExportStatus.QUEUED -> null
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ editing

    fun selectSegment(index: Int) {
        if (index !in _state.value.timeline.indices) return
        _state.update { it.copy(selectedIndex = index) }
    }

    fun deleteSelected() {
        val index = _state.value.selectedIndex
        val segment = _state.value.timeline.getOrNull(index) ?: return
        if (_state.value.timeline.size <= 1) {
            _state.update { it.copy(message = "A Short needs at least one clip") }
            return
        }
        val updated = _state.value.timeline.filterNot { it.id == segment.id }
        commitTimeline(updated, selected = index.coerceAtMost(updated.lastIndex))
    }

    fun splitSelected() {
        val index = _state.value.selectedIndex
        val segment = _state.value.timeline.getOrNull(index) ?: return
        if (segment.durationMs < MIN_SPLIT_MS) {
            _state.update { it.copy(message = "This clip is too short to split") }
            return
        }
        val midpoint = (segment.sourceStartMs + segment.sourceEndMs) / 2
        val first = segment.copy(
            id = 0L,
            sourceEndMs = midpoint,
            origin = com.aivideostudio.domain.model.ClipOrigin.USER_SPLIT,
        )
        val second = segment.copy(
            id = 0L,
            sourceStartMs = midpoint,
            origin = com.aivideostudio.domain.model.ClipOrigin.USER_SPLIT,
        )
        val updated = _state.value.timeline.toMutableList().apply {
            removeAt(index)
            addAll(index, listOf(first, second))
        }
        commitTimeline(updated, selected = index)
    }

    fun moveSelectedLeft() {
        val index = _state.value.selectedIndex
        if (index <= 0) return
        val updated = _state.value.timeline.toMutableList()
        val item = updated.removeAt(index)
        updated.add(index - 1, item)
        commitTimeline(updated, selected = index - 1)
    }

    fun moveSelectedRight() {
        val index = _state.value.selectedIndex
        if (index >= _state.value.timeline.lastIndex) return
        val updated = _state.value.timeline.toMutableList()
        val item = updated.removeAt(index)
        updated.add(index + 1, item)
        commitTimeline(updated, selected = index + 1)
    }

    fun trimSelected(startMs: Long, endMs: Long) {
        val index = _state.value.selectedIndex
        val segment = _state.value.timeline.getOrNull(index) ?: return
        val asset = _state.value.assets[segment.assetId]
        val maxEnd = asset?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val safeStart = startMs.coerceIn(0L, (endMs - MIN_CLIP_MS).coerceAtLeast(0L))
        val safeEnd = endMs.coerceIn(safeStart + MIN_CLIP_MS, maxEnd)
        val updated = _state.value.timeline.toMutableList().apply {
            this[index] = segment.copy(
                sourceStartMs = safeStart,
                sourceEndMs = safeEnd,
                origin = com.aivideostudio.domain.model.ClipOrigin.USER_TRIM,
            )
        }
        commitTimeline(updated, selected = index)
    }

    fun setSpeed(speed: Float) {
        updateSelectedSegment { it.copy(playbackSpeed = speed.coerceIn(0.25f, 4f)) }
    }

    fun setCropMode(mode: CropMode) {
        updateSelectedSegment { it.copy(cropMode = mode) }
    }

    fun setZoom(value: Float) {
        updateSelectedSegment { it.copy(cropScale = value.coerceIn(1f, 3f)) }
    }

    fun setCropCenter(x: Float, y: Float) {
        updateSelectedSegment {
            it.copy(
                cropCenterX = x.coerceIn(0f, 1f),
                cropCenterY = y.coerceIn(0f, 1f),
                cropMode = CropMode.SMART,
            )
        }
    }

    private fun updateSelectedSegment(transform: (TimelineClip) -> TimelineClip) {
        val index = _state.value.selectedIndex
        val segment = _state.value.timeline.getOrNull(index) ?: return
        val updated = _state.value.timeline.toMutableList().apply { this[index] = transform(segment) }
        commitTimeline(updated, selected = index)
    }

    fun setAspectRatio(ratio: AspectRatio) {
        _state.update { it.copy(aspectRatio = ratio, isDirty = true) }
        persistClipOptions()
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        val clipId = _state.value.clipId
        _state.update { it.copy(subtitleStyle = style, isDirty = true) }
        viewModelScope.launch {
            clipRepository.updateCaptionStyle(clipId, style)
            _state.update { it.copy(captions = it.captions.map { c -> c.copy(style = style) }) }
        }
    }

    fun setCaptionPosition(positionY: Float) {
        val clipId = _state.value.clipId
        _state.update {
            it.copy(
                captionPositionY = positionY,
                captions = it.captions.map { caption -> caption.copy(positionY = positionY) },
                isDirty = true,
            )
        }
        viewModelScope.launch { clipRepository.updateCaptionPosition(clipId, positionY) }
    }

    fun setCaptionText(captionId: Long, text: String) {
        viewModelScope.launch {
            clipRepository.updateCaptionText(captionId, text)
            _state.update {
                it.copy(
                    captions = it.captions.map { caption ->
                        if (caption.id == captionId) {
                            caption.copy(text = text, isEdited = true)
                        } else {
                            caption
                        }
                    },
                    isDirty = true,
                )
            }
        }
    }

    fun addOverlay(text: String) {
        if (text.isBlank()) return
        val state = _state.value
        val overlay = TextOverlay(
            projectId = state.projectId,
            clipId = state.clipId,
            startMs = 0L,
            endMs = state.totalDurationMs.coerceAtLeast(2_000L),
            text = text,
            positionX = 0.5f,
            positionY = 0.22f,
            isTitle = true,
        )
        viewModelScope.launch {
            val id = clipRepository.upsertOverlay(overlay)
            _state.update {
                it.copy(overlays = it.overlays + overlay.copy(id = id), isDirty = true)
            }
        }
    }

    fun deleteOverlay(overlayId: Long) {
        viewModelScope.launch {
            clipRepository.deleteOverlay(overlayId)
            _state.update {
                it.copy(
                    overlays = it.overlays.filterNot { overlay -> overlay.id == overlayId },
                    isDirty = true,
                )
            }
        }
    }

    fun setHook(text: String) {
        if (text.isBlank()) return
        val clipId = _state.value.clipId
        _state.update { it.copy(hookText = text, isDirty = true) }
        viewModelScope.launch { clipRepository.updateClipHook(clipId, text) }
    }

    fun setOriginalMuted(muted: Boolean) {
        val state = _state.value
        val bed = (state.audio ?: AudioBed(
            projectId = state.projectId,
            clipId = state.clipId,
        )).copy(originalAudioMuted = muted)
        persistAudioBed(bed)
    }

    fun setOriginalVolume(volume: Float) {
        val state = _state.value
        val bed = (state.audio ?: AudioBed(
            projectId = state.projectId,
            clipId = state.clipId,
        )).copy(originalVolume = volume.coerceIn(0f, 1.5f))
        persistAudioBed(bed)
    }

    fun setMusic(uri: Uri) {
        val state = _state.value
        val title = runCatching {
            uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
        }.getOrNull() ?: "Music"
        val bed = (state.audio ?: AudioBed(
            projectId = state.projectId,
            clipId = state.clipId,
        )).copy(
            uri = uri.toString(),
            title = title,
            startMs = 0L,
            endMs = state.totalDurationMs,
        )
        persistAudioBed(bed)
    }

    fun setMusicVolume(volume: Float) {
        val state = _state.value
        val bed = state.audio?.copy(volume = volume.coerceIn(0f, 1f)) ?: return
        persistAudioBed(bed)
    }

    fun clearMusic() {
        val state = _state.value
        val bed = state.audio?.copy(uri = null, title = null) ?: return
        persistAudioBed(bed)
    }

    private fun persistAudioBed(bed: AudioBed) {
        viewModelScope.launch {
            clipRepository.upsertAudioBed(bed)
            _state.update { it.copy(audio = bed, isDirty = true) }
        }
    }

    // ------------------------------------------------------------------ improve

    fun applySuggestion(suggestion: EditorSuggestionEngine.Suggestion) {
        val state = _state.value
        when (val action = suggestion.action) {
            is EditorSuggestionEngine.Action.ShortenClip -> trimByClipId(action.clipId, action.newEndMs)

            is EditorSuggestionEngine.Action.TrimTrailingClip ->
                trimByClipId(action.clipId, action.newEndMs)

            is EditorSuggestionEngine.Action.RemoveClip -> {
                val updated = state.timeline.filterNot { it.id == action.clipId }
                if (updated.isNotEmpty()) {
                    commitTimeline(updated, selected = state.selectedIndex.coerceAtMost(updated.lastIndex))
                }
            }

            is EditorSuggestionEngine.Action.RaiseCaptions -> {
                if (action.positionY > 0f) {
                    setCaptionPosition(action.positionY)
                } else {
                    splitLongCaptions()
                }
            }

            is EditorSuggestionEngine.Action.SetHook -> setHook(action.text)
            is EditorSuggestionEngine.Action.AdjustVolume -> setOriginalVolume(action.volume)
        }
        _state.update { it.copy(suggestionsApplied = it.suggestionsApplied + suggestion.id) }
    }

    fun applyAllSuggestions() {
        _state.value.suggestions
            .filterNot { it.id in _state.value.suggestionsApplied }
            .forEach(::applySuggestion)
    }

    fun refreshSuggestions() {
        val state = _state.value
        val suggestions = suggestionEngine.suggest(
            EditorSuggestionEngine.Input(
                timeline = state.timeline,
                captions = state.captions,
                audio = state.audio,
                hookText = state.hookText,
                targetDurationMs = currentClip?.let { it.durationMs } ?: state.totalDurationMs,
            ),
        )
        _state.update { it.copy(suggestions = suggestions) }
    }

    private fun trimByClipId(clipId: Long, newEndMs: Long) {
        val timeline = _state.value.timeline
        val index = timeline.indexOfFirst { it.id == clipId }
        if (index < 0) return
        val segment = timeline[index]
        val safeEnd = newEndMs
            .coerceAtLeast(segment.sourceStartMs + MIN_CLIP_MS)
            .coerceAtMost(segment.sourceEndMs)
        if (safeEnd >= segment.sourceEndMs) return
        val updated = timeline.toMutableList().apply {
            this[index] = segment.copy(
                sourceEndMs = safeEnd,
                origin = com.aivideostudio.domain.model.ClipOrigin.USER_TRIM,
            )
        }
        commitTimeline(updated, selected = _state.value.selectedIndex)
    }

    /** Splits captions that are too long for a phone screen into two readable lines. */
    private fun splitLongCaptions() {
        val state = _state.value
        val rebuilt = state.captions.flatMap { caption ->
            if (caption.text.length <= LONG_CAPTION_CHARS) {
                listOf(caption)
            } else {
                val words = caption.text.split(' ')
                val half = words.size / 2
                val firstText = words.take(half).joinToString(" ")
                val secondText = words.drop(half).joinToString(" ")
                val midpoint = caption.startMs + caption.durationMs / 2
                listOf(
                    caption.copy(id = 0L, endMs = midpoint, text = firstText, isEdited = true),
                    caption.copy(id = 0L, startMs = midpoint, text = secondText, isEdited = true),
                )
            }
        }
        viewModelScope.launch {
            clipRepository.replaceCaptions(state.clipId, rebuilt)
            _state.update { it.copy(captions = rebuilt, isDirty = true) }
        }
    }

    // ------------------------------------------------------------------ export

    fun export() {
        val state = _state.value
        if (state.timeline.isEmpty()) {
            _state.update { it.copy(message = "There is nothing to export yet") }
            return
        }
        viewModelScope.launch {
            val clip = currentClip ?: return@launch
            persistTimeline()
            val record = ExportRecord(
                projectId = state.projectId,
                clipId = state.clipId,
                fileName = buildFileName(clip),
                quality = com.aivideostudio.domain.model.VideoQuality.HIGH,
                createdAt = System.currentTimeMillis(),
                status = ExportStatus.QUEUED,
            )
            val exportId = exportRepository.createExport(record)
            clipRepository.updateClipOutput(clip.id, state.lastExportPath, ClipStatus.RENDERING)
            _state.update { it.copy(exportStatus = ExportStatus.QUEUED, exportProgress = 0f, exportLabel = null) }
            scheduler.startExport(exportId, state.projectId, clip.id)
        }
    }

    fun shareLastExport() {
        val context = shareContext ?: return
        val path = _state.value.lastExportPath
        if (path.isNullOrBlank() || !File(path).exists()) {
            _state.update { it.copy(message = "Export the Short first so it can be shared") }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(path),
            )
            val state = _state.value
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = Constants.EXPORT_MIME_MP4
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, state.title)
                putExtra(Intent.EXTRA_TEXT, state.description)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Short"))
        }.onFailure {
            _state.update { it.copy(message = "Nothing on this device can share the video") }
        }
    }

    /**
     * The application context, assigned from the screen. Holding a Context in a
     * ViewModel is only safe when it outlives configuration changes.
     */
    @SuppressLint("StaticFieldLeak")
    var shareContext: Context? = null

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ internals

    /**
     * Every timeline change goes through here: the order is renumbered, captions
     * are rebuilt against the new timeline, and both are persisted. Captions are
     * regenerated rather than re-timed because a caption's position depends on
     * all the clips before it.
     */
    private fun commitTimeline(updated: List<TimelineClip>, selected: Int) {
        val renumbered = updated.mapIndexed { index, clip -> clip.copy(orderIndex = index) }
        _state.update {
            it.copy(
                timeline = renumbered,
                selectedIndex = selected.coerceIn(0, (renumbered.size - 1).coerceAtLeast(0)),
                isDirty = true,
            )
        }
        viewModelScope.launch {
            persistTimeline(renumbered)
            rebuildCaptions(renumbered)
        }
    }

    private suspend fun persistTimeline(timeline: List<TimelineClip> = _state.value.timeline) {
        val clipId = _state.value.clipId
        clipRepository.replaceTimeline(clipId, timeline)
        _state.update { it.copy(timeline = timeline) }
    }

    private suspend fun rebuildCaptions(timeline: List<TimelineClip>) {
        val state = _state.value
        val options = SubtitleBuilder.Options(
            style = state.subtitleStyle,
            defaultPositionY = state.captionPositionY,
        )
        val captions = subtitleBuilder.build(timeline, transcriptSegments, options)
        clipRepository.replaceCaptions(state.clipId, captions)
        _state.update {
            it.copy(
                captions = captions,
                captionsSpeculative = captions.isEmpty() && timeline.isNotEmpty(),
            )
        }
    }

    private fun persistClipOptions() {
        val clip = currentClip ?: return
        val state = _state.value
        viewModelScope.launch {
            clipRepository.updateClip(
                clip.copy(
                    aspectRatio = state.aspectRatio,
                    durationMs = state.totalDurationMs,
                ),
            )
            projectRepository.touch(state.projectId)
        }
    }

    private fun buildFileName(clip: GeneratedClip): String {
        val base = clip.title.ifBlank { "short_${clip.index + 1}" }
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(' ', '_')
            .take(40)
            .ifBlank { "short" }
        return "${base}_${clip.index + 1}"
    }

    private companion object {
        const val MIN_SPLIT_MS = 1_000L
        const val MIN_CLIP_MS = 600L
        const val LONG_CAPTION_CHARS = 42
    }
}
