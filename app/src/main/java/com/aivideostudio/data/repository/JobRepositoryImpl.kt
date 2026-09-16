package com.aivideostudio.data.repository

import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.dao.AiJobDao
import com.aivideostudio.data.local.dao.ExportDao
import com.aivideostudio.data.local.JsonCodec
import com.aivideostudio.data.local.media.AppFiles
import com.aivideostudio.data.mapper.toDomain
import com.aivideostudio.data.mapper.toEntity
import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.JobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobRepositoryImpl @Inject constructor(
    private val jobDao: AiJobDao,
    private val dispatchers: DispatcherProvider,
) : JobRepository {

    override fun observeLatestJob(projectId: Long): Flow<AiJob?> =
        jobDao.observeLatest(projectId).map { it?.toDomain() }

    override fun observeActiveJobs(): Flow<List<AiJob>> =
        jobDao.observeActive().map { list -> list.map { it.toDomain() } }

    override suspend fun getLatestJob(projectId: Long): AiJob? = withContext(dispatchers.io) {
        jobDao.getLatest(projectId)?.toDomain()
    }

    override suspend fun upsertJob(job: AiJob): Long = withContext(dispatchers.io) {
        jobDao.insert(job.toEntity())
    }

    override suspend fun updateProgress(
        jobId: Long,
        stage: PipelineStage,
        status: JobStatus,
        progress: Float,
        attempt: Int,
        finishedStages: List<PipelineStage>,
        errorMessage: String?,
        technicalDetail: String?,
        provider: String?,
        usedCloud: Boolean,
    ) = withContext(dispatchers.io) {
        jobDao.updateProgress(
            jobId = jobId,
            stage = stage.name,
            status = status.name,
            progress = progress.coerceIn(0f, 1f),
            provider = provider,
            updatedAt = System.currentTimeMillis(),
            attempt = attempt,
            finishedStages = JsonCodec.encodeStages(finishedStages),
            error = errorMessage,
            detail = technicalDetail,
            usedCloud = usedCloud,
        )
    }

    override suspend fun deleteJobsForProject(projectId: Long) = withContext(dispatchers.io) {
        jobDao.deleteByProject(projectId)
    }
}

@Singleton
class ExportRepositoryImpl @Inject constructor(
    private val exportDao: ExportDao,
    private val appFiles: AppFiles,
    private val dispatchers: DispatcherProvider,
) : ExportRepository {

    override fun observeExports(): Flow<List<ExportRecord>> =
        exportDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeProjectExports(projectId: Long): Flow<List<ExportRecord>> =
        exportDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    override fun observeRecentExports(limit: Int): Flow<List<ExportRecord>> =
        exportDao.observeRecentCompleted(limit).map { list -> list.map { it.toDomain() } }

    override fun observeCompletedCount(): Flow<Int> = exportDao.observeCompletedCount()

    override fun observeExportBytes(): Flow<Long> = exportDao.observeTotalBytes()

    override suspend fun getExport(exportId: Long): ExportRecord? = withContext(dispatchers.io) {
        exportDao.getById(exportId)?.toDomain()
    }

    override suspend fun getExportsForClip(clipId: Long): List<ExportRecord> =
        withContext(dispatchers.io) { exportDao.getByClip(clipId).map { it.toDomain() } }

    override suspend fun createExport(record: ExportRecord): Long = withContext(dispatchers.io) {
        exportDao.insert(record.toEntity())
    }

    override suspend fun updateProgress(
        exportId: Long,
        progress: Float,
        status: ExportStatus,
    ) = withContext(dispatchers.io) {
        exportDao.updateProgress(exportId, progress.coerceIn(0f, 1f), status.name)
    }

    override suspend fun complete(
        exportId: Long,
        path: String?,
        uri: String?,
        sizeBytes: Long,
        durationMs: Long,
    ) = withContext(dispatchers.io) {
        exportDao.updateResult(
            exportId = exportId,
            status = ExportStatus.COMPLETED.name,
            progress = 1f,
            path = path,
            uri = uri,
            sizeBytes = sizeBytes,
            completedAt = System.currentTimeMillis(),
            error = null,
        )
    }

    override suspend fun fail(exportId: Long, message: String) = withContext(dispatchers.io) {
        exportDao.updateResult(
            exportId = exportId,
            status = ExportStatus.FAILED.name,
            progress = 0f,
            path = null,
            uri = null,
            sizeBytes = 0L,
            completedAt = System.currentTimeMillis(),
            error = message,
        )
    }

    override suspend fun deleteExport(exportId: Long) = withContext(dispatchers.io) {
        val record = exportDao.getById(exportId)
        record?.path?.let { path -> runCatching { File(path).delete() } }
        exportDao.deleteById(exportId)
    }

    override suspend fun deleteAllCompleted() = withContext(dispatchers.io) {
        // Remove the files first so the database never references missing media.
        exportDao.getCompleted().forEach { record ->
            record.path?.let { path -> runCatching { File(path).delete() } }
        }
        exportDao.deleteCompleted()
    }

    override suspend fun reconcileInterrupted(): Int = withContext(dispatchers.io) {
        val running = exportDao.runningCount()
        if (running > 0) {
            exportDao.failStale("Export was interrupted when the app closed")
        }
        running
    }
}
