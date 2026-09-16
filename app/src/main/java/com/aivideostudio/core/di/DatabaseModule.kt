package com.aivideostudio.core.di

import android.content.Context
import androidx.room.Room
import com.aivideostudio.data.local.AiVideoStudioDatabase
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AiVideoStudioDatabase =
        Room.databaseBuilder(context, AiVideoStudioDatabase::class.java, AiVideoStudioDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideProjectDao(db: AiVideoStudioDatabase): ProjectDao = db.projectDao()

    @Provides
    fun provideVideoAssetDao(db: AiVideoStudioDatabase): VideoAssetDao = db.videoAssetDao()

    @Provides
    fun provideSceneDao(db: AiVideoStudioDatabase): SceneDao = db.sceneDao()

    @Provides
    fun provideSemanticAnalysisDao(db: AiVideoStudioDatabase): SemanticAnalysisDao =
        db.semanticAnalysisDao()

    @Provides
    fun provideTranscriptDao(db: AiVideoStudioDatabase): TranscriptDao = db.transcriptDao()

    @Provides
    fun provideTranscriptSegmentDao(db: AiVideoStudioDatabase): TranscriptSegmentDao =
        db.transcriptSegmentDao()

    @Provides
    fun provideHighlightDao(db: AiVideoStudioDatabase): HighlightDao = db.highlightDao()

    @Provides
    fun provideGeneratedClipDao(db: AiVideoStudioDatabase): GeneratedClipDao =
        db.generatedClipDao()

    @Provides
    fun provideTimelineClipDao(db: AiVideoStudioDatabase): TimelineClipDao = db.timelineClipDao()

    @Provides
    fun provideCaptionDao(db: AiVideoStudioDatabase): CaptionDao = db.captionDao()

    @Provides
    fun provideTextOverlayDao(db: AiVideoStudioDatabase): TextOverlayDao = db.textOverlayDao()

    @Provides
    fun provideAudioBedDao(db: AiVideoStudioDatabase): AudioBedDao = db.audioBedDao()

    @Provides
    fun provideAiSuggestionDao(db: AiVideoStudioDatabase): AiSuggestionDao = db.aiSuggestionDao()

    @Provides
    fun provideAiJobDao(db: AiVideoStudioDatabase): AiJobDao = db.aiJobDao()

    @Provides
    fun provideExportDao(db: AiVideoStudioDatabase): ExportDao = db.exportDao()
}
