package com.aivideostudio.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.aivideostudio.data.local.entity.ProjectEntity
import com.aivideostudio.data.local.entity.VideoAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("UPDATE projects SET updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun touch(projectId: Long, updatedAt: Long)

    @Query("UPDATE projects SET status = :status, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateStatus(projectId: Long, status: String, updatedAt: Long)

    @Query("UPDATE projects SET lastError = :message, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateError(projectId: Long, message: String?, updatedAt: Long)

    @Query("UPDATE projects SET coverPath = :path, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateCover(projectId: Long, path: String?, updatedAt: Long)

    @Query("UPDATE projects SET aiSummary = :summary, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun updateSummary(projectId: Long, summary: String?, updatedAt: Long)

    @Query("UPDATE projects SET name = :name, updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun rename(projectId: Long, name: String, updatedAt: Long)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteById(projectId: Long)

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getById(projectId: Long): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE status IN ('IMPORTING', 'ANALYZING') ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT COUNT(*) FROM projects")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM projects WHERE status IN ('ANALYZING', 'DRAFT', 'READY')")
    suspend fun getUnfinished(): List<ProjectEntity>
}

@Dao
interface VideoAssetDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(asset: VideoAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(assets: List<VideoAssetEntity>): List<Long>

    @Upsert
    suspend fun upsert(asset: VideoAssetEntity)

    @Query("UPDATE video_assets SET probeState = :state, probeError = :error WHERE id = :assetId")
    suspend fun updateProbeState(assetId: Long, state: String, error: String?)

    @Query(
        """
        UPDATE video_assets SET
            durationMs = :durationMs, width = :width, height = :height, fps = :fps,
            bitrate = :bitrate, videoCodec = :videoCodec, audioCodec = :audioCodec,
            audioChannels = :audioChannels, audioSampleRate = :audioSampleRate,
            sizeBytes = :sizeBytes, rotationDegrees = :rotation, hasAudioTrack = :hasAudio,
            recordedAtEpochMs = :recordedAt, probeState = 'READY', probeError = NULL
        WHERE id = :assetId
        """,
    )
    suspend fun updateMetadata(
        assetId: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        fps: Float,
        bitrate: Int,
        videoCodec: String?,
        audioCodec: String?,
        audioChannels: Int,
        audioSampleRate: Int,
        sizeBytes: Long,
        rotation: Int,
        hasAudio: Boolean,
        recordedAt: Long,
    )

    @Query("UPDATE video_assets SET thumbnailPath = :path WHERE id = :assetId")
    suspend fun updateThumbnail(assetId: Long, path: String?)

    @Query("SELECT * FROM video_assets WHERE projectId = :projectId ORDER BY orderIndex ASC")
    fun observeByProject(projectId: Long): Flow<List<VideoAssetEntity>>

    @Query("SELECT * FROM video_assets WHERE projectId = :projectId ORDER BY orderIndex ASC")
    suspend fun getByProject(projectId: Long): List<VideoAssetEntity>

    @Query("SELECT * FROM video_assets WHERE id = :assetId")
    suspend fun getById(assetId: Long): VideoAssetEntity?

    @Query("SELECT * FROM video_assets WHERE projectId = :projectId AND probeState != 'READY'")
    suspend fun getUnprobed(projectId: Long): List<VideoAssetEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM video_assets")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM video_assets WHERE projectId = :projectId")
    suspend fun totalDurationMs(projectId: Long): Long

    @Query("DELETE FROM video_assets WHERE id = :assetId")
    suspend fun deleteById(assetId: Long)

    @Query("DELETE FROM video_assets WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)

    @Transaction
    @Query("SELECT COUNT(*) FROM video_assets WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int
}
