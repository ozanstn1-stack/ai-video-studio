package com.aivideostudio.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence models intentionally contain only primitives. Enums and lists are
 * mapped in [com.aivideostudio.data.mapper] so that schema changes stay explicit
 * and the domain models remain free of Room annotations.
 */
@Entity(
    tableName = "projects",
    indices = [Index("updatedAt"), Index("status")],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val mode: String,
    val targetShortCount: Int,
    val targetShortDurationSec: Int,
    val aspectRatio: String,
    val quality: String,
    val frameRate: String,
    val coverPath: String?,
    val tag: String?,
    val transcriptLanguage: String?,
    val aiSummary: String?,
    val lastError: String?,
)

@Entity(
    tableName = "video_assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index(value = ["projectId", "uri"], unique = true)],
)
data class VideoAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val bitrate: Int,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val sizeBytes: Long,
    val rotationDegrees: Int,
    val recordedAtEpochMs: Long,
    val thumbnailPath: String?,
    val isDjiFootage: Boolean,
    val hasAudioTrack: Boolean,
    val probeState: String,
    val probeError: String?,
    val orderIndex: Int,
)
