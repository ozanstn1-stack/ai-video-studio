package com.aivideostudio.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aivideostudio.core.common.Constants
import com.aivideostudio.domain.model.PipelineStage
import kotlinx.coroutines.flow.first

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WorkManager chains.
 *
 * A normal analysis runs entirely offline; the only stage that may need the
 * network is transcription against a cloud provider, so the chain does not
 * require connectivity and instead fails that one stage with a clear message the
 * user can retry.
 */
@Singleton
class PipelineScheduler @Inject constructor(
    private val context: Context,
) {

    suspend fun startAnalysis(projectId: Long) {
        cancel(projectId)
        enqueueStage(context, projectId, STAGE_ORDER.first())
    }

    suspend fun resumeAnalysis(projectId: Long, from: PipelineStage) {
        cancel(projectId)
        enqueueStage(context, projectId, from)
    }

    /**
     * Cancels every live request of the project and *waits* until no active
     * request remains. The sweep is asynchronous internally; an immediate
     * re-enqueue without awaiting it can race the sweep and have the fresh
     * request cancelled along with the old chain, which stranded the job at a
     * frozen progress value with nothing running behind it.
     */
    suspend fun cancel(projectId: Long) {
        workManager().cancelAllWorkByTag(Constants.pipelineTag(projectId))
        observeIsRunning(projectId).first { infos -> infos.none { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        } }
    }

    fun observeIsRunning(projectId: Long) =
        workManager().getWorkInfosByTagFlow(Constants.pipelineTag(projectId))

    /**
     * True while WorkManager still holds a live request (running, queued or
     * blocked in the chain) for this project. A job row can be RUNNING in the
     * database while the chain behind it is gone — the app died mid-stage or
     * the worker was stopped without returning a result — and the caller uses
     * this to decide whether the pipeline has to be re-enqueued.
     */
    suspend fun hasActiveWork(projectId: Long): Boolean =
        observeIsRunning(projectId).first().any { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }

    fun startExport(exportId: Long, projectId: Long, clipId: Long) {
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(ExportWorker.KEY_EXPORT_ID, exportId)
                    .putLong(ExportWorker.KEY_PROJECT_ID, projectId)
                    .putLong(ExportWorker.KEY_CLIP_ID, clipId)
                    .build(),
            )
            .addTag(EXPORT_TAG)
            .setConstraints(Constraints.NONE)
            .build()
        workManager().enqueueUniqueWork(
            "export_$exportId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelExport(exportId: Long) {
        workManager().cancelUniqueWork("export_$exportId")
    }

    private fun workManager(): WorkManager = WorkManager.getInstance(context)

    companion object {
        const val EXPORT_TAG = "export"

        /**
         * The canonical order of the pipeline. Kept here so the worker, the
         * progress model and the analysis screen all agree.
         */
        val STAGE_ORDER: List<PipelineStage> = listOf(
            PipelineStage.IMPORT,
            PipelineStage.PROBE_VIDEO,
            PipelineStage.GENERATE_THUMBNAILS,
            PipelineStage.SCENE_DETECTION,
            PipelineStage.AUDIO_ANALYSIS,
            PipelineStage.TRANSCRIPTION,
            PipelineStage.SEMANTIC_ANALYSIS,
            PipelineStage.HIGHLIGHT_DETECTION,
            PipelineStage.SHORT_GENERATION,
            PipelineStage.RENDER,
        )

        fun enqueueStage(context: Context, projectId: Long, stage: PipelineStage) {
            val request = buildRequest(projectId, stage)
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(projectId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun buildRequest(projectId: Long, stage: PipelineStage): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PipelineStageWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(PipelineStageWorker.KEY_PROJECT_ID, projectId)
                        .putString(PipelineStageWorker.KEY_STAGE, stage.name)
                        .build(),
                )
                .addTag(Constants.pipelineTag(projectId))
                .addTag(STAGE_TAG_PREFIX + stage.name)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(false)
                        .build(),
                )
                .build()

        private fun workName(projectId: Long) = "pipeline_$projectId"

        private const val STAGE_TAG_PREFIX = "stage_"
    }
}

/** Small helper so callers do not depend on WorkManager's state enum directly. */
fun WorkInfo.isActiveState(): Boolean =
    state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
