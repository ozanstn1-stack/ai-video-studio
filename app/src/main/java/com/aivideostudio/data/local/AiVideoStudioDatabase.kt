package com.aivideostudio.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aivideostudio.data.local.dao.AiJobDao
import com.aivideostudio.data.local.dao.AiSuggestionDao
import com.aivideostudio.data.local.dao.AudioBedDao
import com.aivideostudio.data.local.dao.CaptionDao
import com.aivideostudio.data.local.dao.ExportDao
import com.aivideostudio.data.local.dao.GeneratedClipDao
import com.aivideostudio.data.local.dao.HighlightDao
import com.aivideostudio.data.local.dao.ProjectDao
import com.aivideostudio.data.local.dao.SceneDao
import com.aivideostudio.data.local.dao.SemanticAnalysisDao
import com.aivideostudio.data.local.dao.TextOverlayDao
import com.aivideostudio.data.local.dao.TimelineClipDao
import com.aivideostudio.data.local.dao.TranscriptDao
import com.aivideostudio.data.local.dao.TranscriptSegmentDao
import com.aivideostudio.data.local.dao.VideoAssetDao
import com.aivideostudio.data.local.entity.AiJobEntity
import com.aivideostudio.data.local.entity.AiSuggestionEntity
import com.aivideostudio.data.local.entity.AudioBedEntity
import com.aivideostudio.data.local.entity.CaptionEntity
import com.aivideostudio.data.local.entity.ExportEntity
import com.aivideostudio.data.local.entity.GeneratedClipEntity
import com.aivideostudio.data.local.entity.HighlightEntity
import com.aivideostudio.data.local.entity.ProjectEntity
import com.aivideostudio.data.local.entity.SceneEntity
import com.aivideostudio.data.local.entity.SemanticAnalysisEntity
import com.aivideostudio.data.local.entity.TextOverlayEntity
import com.aivideostudio.data.local.entity.TimelineClipEntity
import com.aivideostudio.data.local.entity.TranscriptEntity
import com.aivideostudio.data.local.entity.TranscriptSegmentEntity
import com.aivideostudio.data.local.entity.VideoAssetEntity

/**
 * Single source of truth. Every table is project-scoped and cascades on delete,
 * so removing a project cleans up analysis, clips, captions and exports while
 * never touching the original media files on disk.
 */
@Database(
    entities = [
        ProjectEntity::class,
        VideoAssetEntity::class,
        SceneEntity::class,
        SemanticAnalysisEntity::class,
        TranscriptEntity::class,
        TranscriptSegmentEntity::class,
        HighlightEntity::class,
        GeneratedClipEntity::class,
        TimelineClipEntity::class,
        CaptionEntity::class,
        TextOverlayEntity::class,
        AudioBedEntity::class,
        AiSuggestionEntity::class,
        AiJobEntity::class,
        ExportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AiVideoStudioDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun videoAssetDao(): VideoAssetDao
    abstract fun sceneDao(): SceneDao
    abstract fun semanticAnalysisDao(): SemanticAnalysisDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun highlightDao(): HighlightDao
    abstract fun generatedClipDao(): GeneratedClipDao
    abstract fun timelineClipDao(): TimelineClipDao
    abstract fun captionDao(): CaptionDao
    abstract fun textOverlayDao(): TextOverlayDao
    abstract fun audioBedDao(): AudioBedDao
    abstract fun aiSuggestionDao(): AiSuggestionDao
    abstract fun aiJobDao(): AiJobDao
    abstract fun exportDao(): ExportDao

    companion object {
        const val NAME = "ai_video_studio.db"
    }
}
