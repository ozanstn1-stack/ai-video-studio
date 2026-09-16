package com.aivideostudio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.aivideostudio.data.local.entity.HighlightEntity
import com.aivideostudio.data.local.entity.SceneEntity
import com.aivideostudio.data.local.entity.TranscriptEntity
import com.aivideostudio.data.local.entity.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SceneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scenes: List<SceneEntity>)

    @Query("SELECT * FROM scenes WHERE projectId = :projectId ORDER BY assetId ASC, startMs ASC")
    suspend fun getByProject(projectId: Long): List<SceneEntity>

    @Query("SELECT * FROM scenes WHERE assetId = :assetId ORDER BY startMs ASC")
    suspend fun getByAsset(assetId: Long): List<SceneEntity>

    @Query("SELECT * FROM scenes WHERE assetId = :assetId ORDER BY startMs ASC")
    fun observeByAsset(assetId: Long): Flow<List<SceneEntity>>

    @Query("SELECT COUNT(*) FROM scenes WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int

    @Query("SELECT COUNT(*) FROM scenes WHERE assetId = :assetId")
    suspend fun countByAsset(assetId: Long): Int

    @Query("UPDATE scenes SET speechRatio = :ratio, audioLoudness = :loudness WHERE id = :sceneId")
    suspend fun updateAudio(sceneId: Long, ratio: Float, loudness: Float)

    @Query("UPDATE scenes SET issue = :issue, duplicateOfSceneId = :duplicateOf WHERE id = :sceneId")
    suspend fun updateIssue(sceneId: Long, issue: String, duplicateOf: Long?)

    @Query(
        "UPDATE scenes SET faceCoverage = :coverage, faceCenterX = :centerX, " +
            "faceCenterY = :centerY, framing = :framing WHERE id = :sceneId",
    )
    suspend fun updateFace(
        sceneId: Long,
        coverage: Float,
        centerX: Float,
        centerY: Float,
        framing: String,
    )

    @Query("DELETE FROM scenes WHERE assetId = :assetId")
    suspend fun deleteByAsset(assetId: Long)

    @Query("DELETE FROM scenes WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)
}

@Dao
interface TranscriptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: TranscriptEntity): Long

    @Update
    suspend fun update(transcript: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(projectId: Long): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(projectId: Long): Flow<TranscriptEntity?>

    @Query("DELETE FROM transcripts WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)
}

@Dao
interface TranscriptSegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_segments WHERE projectId = :projectId ORDER BY startMs ASC")
    suspend fun getByProject(projectId: Long): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_segments WHERE projectId = :projectId ORDER BY startMs ASC")
    fun observeByProject(projectId: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE transcriptId = :transcriptId ORDER BY startMs ASC")
    suspend fun getByTranscript(transcriptId: Long): List<TranscriptSegmentEntity>

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int

    @Query("DELETE FROM transcript_segments WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)

    @Query("DELETE FROM transcript_segments WHERE transcriptId = :transcriptId")
    suspend fun deleteByTranscript(transcriptId: Long)
}

@Dao
interface HighlightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(highlights: List<HighlightEntity>): List<Long>

    @Update
    suspend fun update(highlight: HighlightEntity)

    @Query("UPDATE highlights SET isSelected = :selected WHERE id = :id")
    suspend fun updateSelected(id: Long, selected: Boolean)

    @Query("UPDATE highlights SET isSelected = :selected, isRejected = :rejected, isManual = 1 WHERE id = :id")
    suspend fun updateSelection(id: Long, selected: Boolean, rejected: Boolean)

    @Query("UPDATE highlights SET transcriptText = :text WHERE id = :id")
    suspend fun updateTranscript(id: Long, text: String?)

    @Query("SELECT * FROM highlights WHERE projectId = :projectId ORDER BY overallScore DESC")
    suspend fun getByProject(projectId: Long): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE projectId = :projectId ORDER BY orderIndex ASC")
    fun observeByProject(projectId: Long): Flow<List<HighlightEntity>>

    @Query(
        "SELECT * FROM highlights WHERE projectId = :projectId AND isSelected = 1 " +
            "ORDER BY overallScore DESC LIMIT :limit",
    )
    suspend fun getTop(projectId: Long, limit: Int): List<HighlightEntity>

    @Query("SELECT COUNT(*) FROM highlights WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int

    @Query("DELETE FROM highlights WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)

    @Transaction
    suspend fun replaceAll(projectId: Long, highlights: List<HighlightEntity>) {
        deleteByProject(projectId)
        insertAll(highlights)
    }
}

@Dao
interface SemanticAnalysisDao {

    @Upsert
    suspend fun upsert(entity: com.aivideostudio.data.local.entity.SemanticAnalysisEntity)

    @Query("SELECT * FROM semantic_analysis WHERE projectId = :projectId")
    suspend fun get(projectId: Long): com.aivideostudio.data.local.entity.SemanticAnalysisEntity?

    @Query("SELECT * FROM semantic_analysis WHERE projectId = :projectId")
    fun observe(projectId: Long): Flow<com.aivideostudio.data.local.entity.SemanticAnalysisEntity?>

    @Query("DELETE FROM semantic_analysis WHERE projectId = :projectId")
    suspend fun delete(projectId: Long)
}
