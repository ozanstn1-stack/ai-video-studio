package com.aivideostudio.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("status"), Index("stage")],
)
data class AiJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val stage: String,
    val status: String,
    val progress: Float,
    val attempt: Int,
    val provider: String?,
    val startedAt: Long,
    val updatedAt: Long,
    /** JSON array of completed stage names, used to make the pipeline resumable. */
    val finishedStagesJson: String,
    val errorMessage: String?,
    val technicalDetail: String?,
    val usedCloud: Boolean,
)

@Entity(
    tableName = "exports",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("clipId"), Index("status")],
)
data class ExportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val clipId: Long?,
    val fileName: String,
    val path: String?,
    val uri: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val quality: String,
    val bitrate: Int,
    val sizeBytes: Long,
    val durationMs: Long,
    val status: String,
    val progress: Float,
    val createdAt: Long,
    val completedAt: Long,
    val errorMessage: String?,
)
