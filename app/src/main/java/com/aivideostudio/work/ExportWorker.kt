package com.aivideostudio.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aivideostudio.core.common.Constants
import com.aivideostudio.data.local.media.AppFiles
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.media.export.VideoExporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat

/**
 * Renders a single clip in the background.
 *
 * Runs as a foreground service with a low-importance notification so the user can
 * leave the app. Source footage is only read; the output always lands in a new
 * file inside the app's own export directory.
 */
@HiltWorker
class ExportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted parameters: WorkerParameters,
    private val exporter: VideoExporter,
    private val exportRepository: ExportRepository,
    private val clipRepository: ClipRepository,
    private val mediaRepository: MediaRepository,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val appFiles: AppFiles,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val exportId = inputData.getLong(KEY_EXPORT_ID, -1L)
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        val clipId = inputData.getLong(KEY_CLIP_ID, -1L)
        if (exportId <= 0L || projectId <= 0L) return Result.failure()

        val record = exportRepository.getExport(exportId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "export record missing"))

        setForegroundSafely(record.fileName, 0)

        val editState = clipRepository.getEditState(clipId)
            ?: return fail(exportId, clipId, "This clip could not be loaded")

        val assets = mediaRepository.getAssets(projectId).associateBy { it.id }
        val missing = editState.timeline.filter { assets[it.assetId] == null }
        if (missing.isNotEmpty()) {
            return fail(exportId, clipId, "Some original videos are no longer available")
        }

        val project = projectRepository.getProject(projectId)
        val preferences = settingsRepository.snapshot()
        val subtitleStyle = editState.captions.firstOrNull()?.style ?: preferences.defaultSubtitleStyle

        val output = appFiles.newExportFile(record.fileName)

        val progressFlow = MutableStateFlow(0f)
        return try {
            coroutineScope {
                val reporter = launch {
                    progressFlow.collect { fraction ->
                        exportRepository.updateProgress(exportId, fraction, ExportStatus.RUNNING)
                        setForegroundSafely(record.fileName, (fraction * 100).toInt())
                    }
                }
                val outcome = try {
                    exporter.export(
                        request = VideoExporter.Request(
                            projectId = projectId,
                            clipId = clipId,
                            outputFile = output,
                            aspectRatio = project?.aspectRatio ?: editState.clip.aspectRatio,
                            quality = record.quality,
                            frameRate = project?.frameRate ?: FrameRateOption.SOURCE,
                            timeline = editState.timeline,
                            assets = assets,
                            captions = editState.captions,
                            overlays = editState.overlays,
                            audio = editState.audio,
                            hookText = editState.hookText,
                            subtitleStyle = subtitleStyle,
                            hardwareAcceleration = preferences.useHardwareAcceleration,
                        ),
                        onProgress = { fraction -> progressFlow.value = fraction },
                    )
                } finally {
                    reporter.cancel()
                }

                if (outcome.success && outcome.file != null) {
                    exportRepository.complete(
                        exportId = exportId,
                        path = outcome.file.absolutePath,
                        uri = outcome.file.toURI().toString(),
                        sizeBytes = outcome.sizeBytes,
                        durationMs = outcome.durationMs,
                    )
                    clipRepository.updateClipOutput(
                        clipId,
                        outcome.file.absolutePath,
                        com.aivideostudio.domain.model.ClipStatus.EXPORTED,
                    )
                    clipRepository.getClip(clipId)?.let { clip ->
                        clipRepository.updateClip(clip.copy(lastExportId = exportId))
                    }
                    Result.success(workDataOf(KEY_EXPORT_ID to exportId))
                } else {
                    fail(exportId, clipId, outcome.errorMessage ?: "Export failed", outcome.technicalDetail)
                }
            }
        } catch (cancellation: CancellationException) {
            exportRepository.updateProgress(exportId, 0f, ExportStatus.CANCELLED)
            throw cancellation
        } catch (error: Exception) {
            fail(
                exportId,
                clipId,
                "Export failed",
                error.stackTraceToString().take(MAX_TECHNICAL_LENGTH),
            )
        }
    }

    private suspend fun fail(
        exportId: Long,
        clipId: Long,
        message: String,
        technical: String? = null,
    ): Result {
        exportRepository.fail(exportId, message)
        if (clipId > 0L) {
            clipRepository.updateClipOutput(
                clipId,
                null,
                com.aivideostudio.domain.model.ClipStatus.READY,
            )
        }
        return Result.failure(workDataOf(KEY_ERROR to message, KEY_TECHNICAL to technical))
    }

    private suspend fun setForegroundSafely(fileName: String, percent: Int) {
        runCatching {
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_EXPORT)
                        .setSmallIcon(android.R.drawable.stat_sys_upload)
                        .setContentTitle("Exporting $fileName")
                        .setContentText(if (percent > 0) "$percent%" else "Preparing…")
                        .setOngoing(true)
                        .setProgress(100, percent, percent == 0)
                        .setOnlyAlertOnce(true)
                        .build(),
                ),
            )
        }
    }

    companion object {
        const val KEY_EXPORT_ID = "exportId"
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_CLIP_ID = "clipId"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_TECHNICAL = "technical"
        const val NOTIFICATION_ID = 4201
        const val MAX_TECHNICAL_LENGTH = 2_000
    }
}
