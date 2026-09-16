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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Walks the whole pipeline in one worker invocation.
 *
 * Earlier versions chained one WorkManager request per stage through the
 * unique-work APPEND policy. That left a silent failure mode: the job row
 * stayed RUNNING/QUEUED in the database while the chained request behind it
 * was gone, and the progress bar froze forever. The loop avoids the problem
 * entirely — resumability comes from the persisted `finished` stages, so a
 * process death mid-run simply re-runs this worker and picks up the first
 * unfinished stage.
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

        var stage = PipelineStage.fromName(inputData.getString(KEY_STAGE))
        val job = jobRepository.getLatestJob(projectId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "no job for project"))
        val finished = job.finishedStages.toMutableList()

        while (stage != PipelineStage.DONE) {
            // Already-completed stages are skipped, never repeated.
            if (stage in finished) {
                stage = nextStage(stage) ?: break
                continue
            }

            publishProgress(projectId, stage, 0f, finished)
            projectRepository.updateStatus(projectId, ProjectStatus.ANALYZING)

            // The stage reports progress from a non-suspending callback, so the
            // reports are funnelled through a conflated flow and persisted off to
            // the side. That keeps the pipeline itself free of database calls.
            val progressFlow = MutableStateFlow(0f)
            val outcome = try {
                coroutineScope {
                    val reporter = launch {
                        progressFlow.collect { fraction ->
                            publishProgress(projectId, stage, fraction, finished)
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
                    finishedStages = finished,
                )
                throw cancellation
            } catch (error: Exception) {
                jobRepository.updateProgress(
                    jobId = job.id,
                    stage = stage,
                    status = JobStatus.FAILED,
                    progress = baseWeight(stage),
                    attempt = runAttemptCount + 1,
                    finishedStages = finished,
                    errorMessage = "Something went wrong while processing this video",
                    technicalDetail = error.stackTraceToString().take(MAX_TECHNICAL_LENGTH),
                )
                projectRepository.updateStatus(projectId, ProjectStatus.FAILED)
                return Result.failure()
            }

            if (!outcome.succeeded) {
                val message = outcome.message ?: "Something went wrong"
                projectRepository.recordError(projectId, message)
                jobRepository.updateProgress(
                    jobId = job.id,
                    stage = stage,
                    status = if (runAttemptCount < MAX_ATTEMPTS) JobStatus.QUEUED else JobStatus.FAILED,
                    progress = baseWeight(stage),
                    attempt = runAttemptCount + 1,
                    finishedStages = finished,
                    errorMessage = message,
                    technicalDetail = outcome.technical,
                    usedCloud = outcome.usedCloud,
                )
                projectRepository.updateStatus(projectId, ProjectStatus.FAILED)
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }

            projectRepository.recordError(projectId, null)
            finished += stage
            val next = nextStage(stage)
            val latest = jobRepository.getLatestJob(projectId)
            if (latest != null) {
                jobRepository.updateProgress(
                    jobId = latest.id,
                    stage = next ?: PipelineStage.DONE,
                    status = if (next == null) JobStatus.SUCCEEDED else JobStatus.QUEUED,
                    progress = if (next == null) 1f else PipelineStage.completedWeight(stage),
                    attempt = runAttemptCount,
                    finishedStages = finished,
                    provider = latest.provider,
                    usedCloud = latest.usedCloud || outcome.usedCloud,
                )
            }
            stage = next ?: break
            // Yield to cancellation between stages so a stopped worker never
            // starts another stage after being told to stop.
            coroutineContext.ensureActive()
        }

        projectRepository.updateStatus(projectId, ProjectStatus.GENERATED)
        return Result.success(workDataOf(KEY_STAGE to PipelineStage.DONE.name))
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
