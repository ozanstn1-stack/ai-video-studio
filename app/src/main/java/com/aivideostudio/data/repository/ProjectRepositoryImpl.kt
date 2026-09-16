package com.aivideostudio.data.repository

import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.dao.ProjectDao
import com.aivideostudio.data.mapper.toDomain
import com.aivideostudio.data.mapper.toEntity
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.model.VideoQuality
import com.aivideostudio.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val dispatchers: DispatcherProvider,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<Project>> =
        projectDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeRecentProjects(limit: Int): Flow<List<Project>> =
        projectDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeActiveProjects(): Flow<List<Project>> =
        projectDao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeProjectCount(): Flow<Int> = projectDao.observeCount()

    override fun observeProject(projectId: Long): Flow<Project?> =
        projectDao.observeAll().map { list -> list.firstOrNull { it.id == projectId }?.toDomain() }

    override suspend fun getProject(projectId: Long): Project? = withContext(dispatchers.io) {
        projectDao.getById(projectId)?.toDomain()
    }

    override suspend fun createProject(
        name: String,
        mode: CreationMode,
        shortCount: Int,
        shortDurationSec: Int,
        aspectRatio: AspectRatio,
        quality: VideoQuality,
        frameRate: FrameRateOption,
        tag: String?,
    ): Long = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val project = Project(
            name = name,
            createdAt = now,
            updatedAt = now,
            status = ProjectStatus.DRAFT,
            mode = mode,
            targetShortCount = shortCount,
            targetShortDurationSec = shortDurationSec,
            aspectRatio = aspectRatio,
            quality = quality,
            frameRate = frameRate,
            tag = tag,
        )
        projectDao.insert(project.toEntity())
    }

    override suspend fun updateOptions(
        projectId: Long,
        mode: CreationMode,
        shortCount: Int,
        shortDurationSec: Int,
        aspectRatio: AspectRatio,
        quality: VideoQuality,
        frameRate: FrameRateOption,
    ) = withContext(dispatchers.io) {
        val existing = projectDao.getById(projectId) ?: return@withContext
        projectDao.update(
            existing.copy(
                mode = mode.name,
                targetShortCount = shortCount,
                targetShortDurationSec = shortDurationSec,
                aspectRatio = aspectRatio.name,
                quality = quality.name,
                frameRate = frameRate.name,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun updateStatus(projectId: Long, status: ProjectStatus) =
        withContext(dispatchers.io) {
            projectDao.updateStatus(projectId, status.name, System.currentTimeMillis())
        }

    override suspend fun updateCover(projectId: Long, path: String?) = withContext(dispatchers.io) {
        projectDao.updateCover(projectId, path, System.currentTimeMillis())
    }

    override suspend fun updateSummary(projectId: Long, summary: String?) =
        withContext(dispatchers.io) {
            projectDao.updateSummary(projectId, summary, System.currentTimeMillis())
        }

    override suspend fun recordError(projectId: Long, message: String?) =
        withContext(dispatchers.io) {
            projectDao.updateError(projectId, message, System.currentTimeMillis())
        }

    override suspend fun rename(projectId: Long, name: String) = withContext(dispatchers.io) {
        projectDao.rename(projectId, name, System.currentTimeMillis())
    }

    override suspend fun deleteProject(projectId: Long) = withContext(dispatchers.io) {
        projectDao.deleteById(projectId)
    }

    override suspend fun touch(projectId: Long) = withContext(dispatchers.io) {
        projectDao.touch(projectId, System.currentTimeMillis())
    }
}
