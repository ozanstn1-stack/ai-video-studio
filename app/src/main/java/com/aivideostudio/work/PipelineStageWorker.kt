package com.aivideostudio.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aivideostudio.core.common.Constants
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.processing.ProjectPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Runs exactly one pipeline stage, then chains to the next.
 *
 * Splitting the pipeline into one worker per stage is what makes it resumable:
 * WorkManager persists the chain, and a stage whose result already exists is
 * skipped rather than repeated. If the process is killed during transcription,
 * the remaining chain continues from the next unfinished stage.
 */
@HiltWorker
class PipelineStageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val pipeline: ProjectPipeline,
    private val jobRepository: JobRepository,
    private val projectRepository: ProjectRepository,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId <= 0L) return Result.failure()
        val stage = PipelineStage.fromName(inputData.getString(KEY_STAGE))

        val job = jobRepository.getLatestJob(projectId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "no job for project"))

        if (stage in job.finishedStages) {
            return continueWith(projectId, stage, job.finishedStages)
        }

        publishProgress(projectId, stage, 0f, job.finishedStages)
        projectRepository.updateStatus(projectId, ProjectStatus.ANALYZING)

        // The stage reports progress from a non-suspending callback, so the
        // reports are funnelled through a conflated flow and persisted off to
        // the side. That keeps the pipeline itself free of database calls.
        val progressFlow = MutableStateFlow(0f)
        val outcome = try {
            coroutineScope {
                val reporter = launch {
                    progressFlow.collect { fraction ->
                        publishProgress(projectId, stage, fraction, job.finishedStages)
                    }
                }
                try {
                    pipeline.runStage(projectId, stage) { fraction -> progressFlow.value = fraction }
                } finally {
                    reporter.cancel()
                }
            }
        } catch (cancellation: CancellationException) {
            // The worker was stopped; leave the job resumable.
            jobRepository.updateProgress(
                jobId = job.id,
                stage = stage,
                status = JobStatus.PAUSED,
                progress = baseWeight(stage),
                attempt = runAttemptCount,
                finishedStages = job.finishedStages,
            )
            throw cancellation
        } catch (error: Exception) {
            jobRepository.updateProgress(
                jobId = job.id,
                stage = stage,
                status = JobStatus.FAILED,
                progress = baseWeight(stage),
                attempt = runAttemptCount + 1,
                finishedStages = job.finishedStages,
                errorMessage = "Something went wrong while processing this video",
                technicalDetail = error.stackTraceToString().take(MAX_TECHNICAL_LENGTH),
            )
            projectRepository.updateStatus(projectId, ProjectStatus.FAILED)
            return Result.failure()
        }

        return if (outcome.succeeded) {
            projectRepository.recordError(projectId, null)
            continueWith(projectId, stage, job.finishedStages + stage, outcome.usedCloud)
        } else {
            val message = outcome.message ?: "Something went wrong"
            projectRepository.recordError(projectId, message)
            jobRepository.updateProgress(
                jobId = job.id,
                stage = stage,
                status = if (runAttemptCount < MAX_ATTEMPTS) JobStatus.QUEUED else JobStatus.FAILED,
                progress = baseWeight(stage),
                attempt = runAttemptCount + 1,
                finishedStages = job.finishedStages,
                errorMessage = message,
                technicalDetail = outcome.technical,
                usedCloud = outcome.usedCloud,
            )
            projectRepository.updateStatus(projectId, ProjectStatus.FAILED)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun publishProgress(
        projectId: Long,
        stage: PipelineStage,
        fraction: Float,
        finished: List<PipelineStage>,
        usedCloud: Boolean = false,
    ) {
        val job = jobRepository.getLatestJob(projectId) ?: return
        // The bar runs from the weight accumulated *before* this stage up to the
        // weight that includes it. Deriving the base from `finished` would pin
        // the very first stage at the completed weight of IMPORT (2%), because
        // its base and ceiling would be identical.
        val base = baseWeight(stage)
        val ceiling = PipelineStage.completedWeight(stage)
        val overall = base + (ceiling - base) * fraction.coerceIn(0f, 1f)
        jobRepository.updateProgress(
            jobId = job.id,
            stage = stage,
            status = JobStatus.RUNNING,
            progress = overall,
            attempt = runAttemptCount,
            finishedStages = finished,
            provider = job.provider,
            usedCloud = job.usedCloud || usedCloud,
        )
        setProgress(workDataOf(KEY_PROGRESS to overall, KEY_STAGE to stage.name))
    }

    private suspend fun continueWith(
        projectId: Long,
        completedStage: PipelineStage,
        finished: List<PipelineStage>,
        usedCloud: Boolean = false,
    ): Result {
        val next = nextStage(completedStage)
        if (next == null) {
            val job = jobRepository.getLatestJob(projectId)
            if (job != null) {
                jobRepository.updateProgress(
                    jobId = job.id,
                    stage = PipelineStage.DONE,
                    status = JobStatus.SUCCEEDED,
                    progress = 1f,
                    attempt = runAttemptCount,
                    finishedStages = finished,
                    provider = job.provider,
                    usedCloud = job.usedCloud || usedCloud,
                )
            }
            projectRepository.updateStatus(projectId, ProjectStatus.GENERATED)
            return Result.success(workDataOf(KEY_STAGE to PipelineStage.DONE.name))
        }

        val job = jobRepository.getLatestJob(projectId)
        if (job != null) {
            jobRepository.updateProgress(
                jobId = job.id,
                stage = next,
                status = JobStatus.QUEUED,
                progress = PipelineStage.completedWeight(completedStage),
                attempt = runAttemptCount,
                finishedStages = finished,
                provider = job.provider,
                usedCloud = job.usedCloud || usedCloud,
            )
        }
        PipelineScheduler.enqueueStage(applicationContext, projectId, next)
        return Result.success(workDataOf(KEY_STAGE to completedStage.name))
    }

    private fun nextStage(stage: PipelineStage): PipelineStage? {
        val order = PipelineScheduler.STAGE_ORDER
        val index = order.indexOf(stage)
        return order.getOrNull(index + 1)
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val MAX_ATTEMPTS = 2
        const val MAX_TECHNICAL_LENGTH = 2_000
        val TAG = Constants.NOTIFICATION_CHANNEL_PROCESSING

        /**
         * Cumulative weight of every stage *before* [stage]: the progress value
         * the bar rests on while [stage] is running. 0% for the first stage.
         */
        fun baseWeight(stage: PipelineStage): Float {
            val order = PipelineScheduler.STAGE_ORDER
            val index = order.indexOf(stage)
            if (index <= 0) return 0f
            return PipelineStage.completedWeight(order[index - 1])
        }
    }
}
