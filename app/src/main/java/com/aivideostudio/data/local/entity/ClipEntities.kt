package com.aivideostudio.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "generated_clips",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("status"), Index("index")],
)
data class GeneratedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val index: Int,
    val title: String,
    val titlesJson: String,
    val description: String,
    val hashtagsJson: String,
    val hookText: String?,
    val hookHighlightId: Long?,
    val label: String,
    val sourceAssetId: Long?,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val durationMs: Long,
    val aspectRatio: String,
    val cropMode: String,
    val storyRole: String,
    val storySummary: String?,
    val thumbnailPath: String?,
    val outputPath: String?,
    val status: String,
    val createdAt: Long,
    val lastExportId: Long?,
)

@Entity(
    tableName = "timeline_clips",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId"), Index("projectId"), Index("assetId")],
)
data class TimelineClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val clipId: Long,
    val projectId: Long,
    val assetId: Long,
    val orderIndex: Int,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val volume: Float,
    val isMuted: Boolean,
    val playbackSpeed: Float,
    val cropMode: String,
    val cropCenterX: Float,
    val cropCenterY: Float,
    val cropScale: Float,
    val storyRole: String,
    val highlightId: Long?,
    val origin: String,
)

@Entity(
    tableName = "captions",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId"), Index("projectId")],
)
data class CaptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val clipId: Long,
    val timelineClipId: Long?,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: String,
    val positionY: Float,
    val isEmphasised: Boolean,
    val emphasisWordsJson: String,
    val isEdited: Boolean,
)

@Entity(
    tableName = "text_overlays",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId")],
)
data class TextOverlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val clipId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val positionX: Float,
    val positionY: Float,
    val fontSizeSp: Float,
    val isTitle: Boolean,
)

@Entity(
    tableName = "audio_beds",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId")],
)
data class AudioBedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val clipId: Long,
    val uri: String?,
    val title: String?,
    val volume: Float,
    val originalAudioMuted: Boolean,
    val originalVolume: Float,
    val startMs: Long,
    val endMs: Long,
)

@Entity(
    tableName = "ai_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId")],
)
data class AiSuggestionEntity(
    @PrimaryKey val id: String,
    val projectId: Long,
    val clipId: Long,
    val category: String,
    val title: String,
    val detail: String,
    val isApplied: Boolean,
    val payloadJson: String,
)
