package com.aivideostudio.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.aivideostudio.data.local.entity.AiSuggestionEntity
import com.aivideostudio.data.local.entity.AudioBedEntity
import com.aivideostudio.data.local.entity.CaptionEntity
import com.aivideostudio.data.local.entity.GeneratedClipEntity
import com.aivideostudio.data.local.entity.TextOverlayEntity
import com.aivideostudio.data.local.entity.TimelineClipEntity
import kotlinx.coroutines.flow.Flow

/** A generated Short together with everything the editor needs. */
data class ClipWithChildren(
    @Embedded val clip: GeneratedClipEntity,
    @Relation(parentColumn = "id", entityColumn = "clipId") val timeline: List<TimelineClipEntity>,
    @Relation(parentColumn = "id", entityColumn = "clipId") val captions: List<CaptionEntity>,
    @Relation(parentColumn = "id", entityColumn = "clipId") val overlays: List<TextOverlayEntity>,
    @Relation(parentColumn = "id", entityColumn = "clipId") val audioBeds: List<AudioBedEntity>,
    @Relation(parentColumn = "id", entityColumn = "clipId") val suggestions: List<AiSuggestionEntity>,
)

@Dao
interface GeneratedClipDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(clip: GeneratedClipEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(clips: List<GeneratedClipEntity>): List<Long>

    @Update
    suspend fun update(clip: GeneratedClipEntity)

    @Query("UPDATE generated_clips SET title = :title WHERE id = :clipId")
    suspend fun updateTitle(clipId: Long, title: String)

    @Query("UPDATE generated_clips SET description = :description, hashtagsJson = :hashtags WHERE id = :clipId")
    suspend fun updateDescription(clipId: Long, description: String, hashtags: String)

    @Query("UPDATE generated_clips SET hookText = :hook WHERE id = :clipId")
    suspend fun updateHook(clipId: Long, hook: String?)

    @Query("UPDATE generated_clips SET thumbnailPath = :path WHERE id = :clipId")
    suspend fun updateThumbnail(clipId: Long, path: String?)

    @Query("UPDATE generated_clips SET outputPath = :path, status = :status WHERE id = :clipId")
    suspend fun updateOutput(clipId: Long, path: String?, status: String)

    @Query("UPDATE generated_clips SET status = :status WHERE id = :clipId")
    suspend fun updateStatus(clipId: Long, status: String)

    @Query("UPDATE generated_clips SET durationMs = :durationMs, sourceStartMs = :startMs, sourceEndMs = :endMs WHERE id = :clipId")
    suspend fun updateTiming(clipId: Long, durationMs: Long, startMs: Long, endMs: Long)

    @Query("UPDATE generated_clips SET lastExportId = :exportId WHERE id = :clipId")
    suspend fun updateLastExport(clipId: Long, exportId: Long?)

    @Query("SELECT * FROM generated_clips WHERE projectId = :projectId ORDER BY \"index\" ASC")
    suspend fun getByProject(projectId: Long): List<GeneratedClipEntity>

    @Query("SELECT * FROM generated_clips WHERE projectId = :projectId ORDER BY \"index\" ASC")
    fun observeByProject(projectId: Long): Flow<List<GeneratedClipEntity>>

    @Query("SELECT * FROM generated_clips ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GeneratedClipEntity>>

    @Query("SELECT * FROM generated_clips WHERE id = :clipId")
    suspend fun getById(clipId: Long): GeneratedClipEntity?

    @Transaction
    @Query("SELECT * FROM generated_clips WHERE id = :clipId")
    suspend fun getWithChildren(clipId: Long): ClipWithChildren?

    @Transaction
    @Query("SELECT * FROM generated_clips WHERE projectId = :projectId ORDER BY \"index\" ASC")
    fun observeWithChildren(projectId: Long): Flow<List<ClipWithChildren>>

    @Query("SELECT * FROM generated_clips WHERE status = 'READY' OR status = 'EXPORTED' ORDER BY createdAt DESC")
    fun observeFinished(): Flow<List<GeneratedClipEntity>>

    @Query("SELECT COUNT(*) FROM generated_clips WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM generated_clips WHERE projectId = :projectId")
    suspend fun totalDurationMs(projectId: Long): Long

    @Query("DELETE FROM generated_clips WHERE id = :clipId")
    suspend fun deleteById(clipId: Long)

    @Query("DELETE FROM generated_clips WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)
}

@Dao
interface TimelineClipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clips: List<TimelineClipEntity>): List<Long>

    @Upsert
    suspend fun upsert(clip: TimelineClipEntity): Long

    @Update
    suspend fun update(clip: TimelineClipEntity)

    @Query("SELECT * FROM timeline_clips WHERE clipId = :clipId ORDER BY orderIndex ASC")
    suspend fun getByClip(clipId: Long): List<TimelineClipEntity>

    @Query("UPDATE timeline_clips SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun updateOrder(id: Long, orderIndex: Int)

    @Query("DELETE FROM timeline_clips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM timeline_clips WHERE clipId = :clipId")
    suspend fun deleteByClip(clipId: Long)

    @Transaction
    suspend fun replaceForClip(clipId: Long, clips: List<TimelineClipEntity>) {
        deleteByClip(clipId)
        insertAll(clips)
    }
}

@Dao
interface CaptionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(captions: List<CaptionEntity>)

    @Upsert
    suspend fun upsert(caption: CaptionEntity)

    @Query("SELECT * FROM captions WHERE clipId = :clipId ORDER BY startMs ASC")
    suspend fun getByClip(clipId: Long): List<CaptionEntity>

    @Query("SELECT * FROM captions WHERE clipId = :clipId ORDER BY startMs ASC")
    fun observeByClip(clipId: Long): Flow<List<CaptionEntity>>

    @Query("UPDATE captions SET text = :text, isEdited = 1 WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("UPDATE captions SET style = :style WHERE clipId = :clipId")
    suspend fun updateStyleForClip(clipId: Long, style: String)

    @Query("UPDATE captions SET positionY = :positionY WHERE clipId = :clipId")
    suspend fun updatePositionForClip(clipId: Long, positionY: Float)

    @Query("DELETE FROM captions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM captions WHERE clipId = :clipId")
    suspend fun deleteByClip(clipId: Long)

    @Transaction
    suspend fun replaceForClip(clipId: Long, captions: List<CaptionEntity>) {
        deleteByClip(clipId)
        insertAll(captions)
    }
}

@Dao
interface TextOverlayDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(overlays: List<TextOverlayEntity>)

    @Upsert
    suspend fun upsert(overlay: TextOverlayEntity): Long

    @Query("SELECT * FROM text_overlays WHERE clipId = :clipId ORDER BY startMs ASC")
    suspend fun getByClip(clipId: Long): List<TextOverlayEntity>

    @Query("SELECT * FROM text_overlays WHERE clipId = :clipId ORDER BY startMs ASC")
    fun observeByClip(clipId: Long): Flow<List<TextOverlayEntity>>

    @Query("DELETE FROM text_overlays WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM text_overlays WHERE clipId = :clipId")
    suspend fun deleteByClip(clipId: Long)
}

@Dao
interface AudioBedDao {

    @Upsert
    suspend fun upsert(bed: AudioBedEntity): Long

    @Query("SELECT * FROM audio_beds WHERE clipId = :clipId LIMIT 1")
    suspend fun getByClip(clipId: Long): AudioBedEntity?

    @Query("SELECT * FROM audio_beds WHERE clipId = :clipId LIMIT 1")
    fun observeByClip(clipId: Long): Flow<AudioBedEntity?>

    @Query("UPDATE audio_beds SET volume = :volume WHERE clipId = :clipId")
    suspend fun updateVolume(clipId: Long, volume: Float)

    @Query("UPDATE audio_beds SET originalAudioMuted = :muted WHERE clipId = :clipId")
    suspend fun updateOriginalMuted(clipId: Long, muted: Boolean)

    @Query("DELETE FROM audio_beds WHERE clipId = :clipId")
    suspend fun deleteByClip(clipId: Long)
}

@Dao
interface AiSuggestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suggestions: List<AiSuggestionEntity>)

    @Upsert
    suspend fun upsert(suggestion: AiSuggestionEntity)

    @Query("SELECT * FROM ai_suggestions WHERE clipId = :clipId")
    suspend fun getByClip(clipId: Long): List<AiSuggestionEntity>

    @Query("SELECT * FROM ai_suggestions WHERE clipId = :clipId")
    fun observeByClip(clipId: Long): Flow<List<AiSuggestionEntity>>

    @Query("UPDATE ai_suggestions SET isApplied = 1 WHERE id = :id")
    suspend fun markApplied(id: String)

    @Query("DELETE FROM ai_suggestions WHERE clipId = :clipId")
    suspend fun deleteByClip(clipId: Long)
}
