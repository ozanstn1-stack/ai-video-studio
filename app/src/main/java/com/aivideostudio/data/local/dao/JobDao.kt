package com.aivideostudio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aivideostudio.data.local.entity.AiJobEntity
import com.aivideostudio.data.local.entity.ExportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: AiJobEntity): Long

    @Update
    suspend fun update(job: AiJobEntity)

    @Query(
        """
        UPDATE ai_jobs SET
            stage = :stage, status = :status, progress = :progress,
            provider = :provider, updatedAt = :updatedAt, attempt = :attempt,
            finishedStagesJson = :finishedStages, errorMessage = :error,
            technicalDetail = :detail, usedCloud = :usedCloud
        WHERE id = :jobId
        """,
    )
    suspend fun updateProgress(
        jobId: Long,
        stage: String,
        status: String,
        progress: Float,
        provider: String?,
        updatedAt: Long,
        attempt: Int,
        finishedStages: String,
        error: String?,
        detail: String?,
        usedCloud: Boolean,
    )

    @Query("SELECT * FROM ai_jobs WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(projectId: Long): AiJobEntity?

    @Query("SELECT * FROM ai_jobs WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(projectId: Long): Flow<AiJobEntity?>

    @Query("SELECT * FROM ai_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<AiJobEntity>>

    @Query("SELECT * FROM ai_jobs WHERE stage != 'DONE' AND status != 'SUCCEEDED'")
    suspend fun getIncomplete(): List<AiJobEntity>

    @Query("SELECT * FROM ai_jobs WHERE id = :jobId")
    suspend fun getById(jobId: Long): AiJobEntity?

    @Query("DELETE FROM ai_jobs WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)
}

@Dao
interface ExportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(export: ExportEntity): Long

    @Update
    suspend fun update(export: ExportEntity)

    @Query(
        """
        UPDATE exports SET
            status = :status, progress = :progress, path = :path, uri = :uri,
            sizeBytes = :sizeBytes, completedAt = :completedAt, errorMessage = :error
        WHERE id = :exportId
        """,
    )
    suspend fun updateResult(
        exportId: Long,
        status: String,
        progress: Float,
        path: String?,
        uri: String?,
        sizeBytes: Long,
        completedAt: Long,
        error: String?,
    )

    @Query("UPDATE exports SET progress = :progress, status = :status WHERE id = :exportId")
    suspend fun updateProgress(exportId: Long, progress: Float, status: String)

    @Query("SELECT * FROM exports WHERE id = :exportId")
    suspend fun getById(exportId: Long): ExportEntity?

    @Query("SELECT * FROM exports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportEntity>>

    @Query("SELECT * FROM exports WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeByProject(projectId: Long): Flow<List<ExportEntity>>

    @Query("SELECT * FROM exports WHERE status = 'COMPLETED' ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentCompleted(limit: Int): Flow<List<ExportEntity>>

    @Query("SELECT COUNT(*) FROM exports WHERE status = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT * FROM exports WHERE clipId = :clipId ORDER BY createdAt DESC")
    suspend fun getByClip(clipId: Long): List<ExportEntity>

    @Query("SELECT * FROM exports WHERE status = 'COMPLETED'")
    suspend fun getCompleted(): List<ExportEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM exports WHERE status = 'COMPLETED'")
    fun observeTotalBytes(): Flow<Long>

    @Query("DELETE FROM exports WHERE id = :exportId")
    suspend fun deleteById(exportId: Long)

    @Query("DELETE FROM exports WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    /**
     * A process killed mid-export leaves rows in the RUNNING state. On startup
     * they are marked failed so the user sees a clear outcome instead of a
     * progress bar that never moves again.
     */
    @Query("UPDATE exports SET status = 'FAILED', errorMessage = :message WHERE status = 'RUNNING' OR status = 'QUEUED'")
    suspend fun failStale(message: String)

    @Query("SELECT COUNT(*) FROM exports WHERE status = 'RUNNING'")
    suspend fun runningCount(): Int
}
