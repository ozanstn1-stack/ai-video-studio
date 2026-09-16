package com.aivideostudio.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scenes",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VideoAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assetId"), Index("projectId"), Index(value = ["assetId", "index"], unique = true)],
)
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val assetId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val brightness: Float,
    val sharpness: Float,
    val motion: Float,
    val stability: Float,
    val saturation: Float,
    val quality: Float,
    val faceCoverage: Float,
    val faceCenterX: Float,
    val faceCenterY: Float,
    val dominantHue: Float,
    val framing: String,
    val issue: String,
    val duplicateOfSceneId: Long?,
    val speechRatio: Float,
    val audioLoudness: Float,
    val label: String?,
)

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val language: String?,
    val provider: String,
    val fullText: String,
    val createdAt: Long,
    val averageConfidence: Float,
    val isComplete: Boolean,
    val note: String?,
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transcriptId"), Index("projectId"), Index("assetId"), Index("startMs")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transcriptId: Long,
    val projectId: Long,
    val assetId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float,
    /** JSON array of word timings; empty string when the provider gave none. */
    val wordsJson: String,
)

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("assetId"), Index("overallScore"), Index("orderIndex")],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val projectId: Long,
    val assetId: Long,
    val startMs: Long,
    val endMs: Long,
    val visualScore: Float,
    val audioScore: Float,
    val speechScore: Float,
    val motionScore: Float,
    val interestScore: Float,
    val uniquenessScore: Float,
    val storyScore: Float,
    val overallScore: Float,
    val hookScore: Float,
    val payoffScore: Float,
    val speechCoverage: Float,
    val label: String,
    /** JSON array of `badge|text` pairs. */
    val reasonsJson: String,
    val transcriptText: String?,
    val orderIndex: Int,
    val isSelected: Boolean,
    val isRejected: Boolean,
    val isManual: Boolean,
)

/**
 * Semantic understanding of a project, produced by whichever provider answered.
 * Stored as primitives so a provider swap never needs a schema change.
 */
@Entity(
    tableName = "semantic_analysis",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SemanticAnalysisEntity(
    @PrimaryKey val projectId: Long,
    val subjectsJson: String,
    val locationsJson: String,
    val activitiesJson: String,
    val tagsJson: String,
    val mood: String?,
    val storySummary: String?,
    val provider: String,
    val confidence: Float,
    val updatedAt: Long,
)
