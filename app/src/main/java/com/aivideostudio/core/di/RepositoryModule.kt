package com.aivideostudio.core.di

import com.aivideostudio.data.local.prefs.SettingsRepositoryImpl
import com.aivideostudio.data.repository.AnalysisRepositoryImpl
import com.aivideostudio.data.repository.ClipRepositoryImpl
import com.aivideostudio.data.repository.ExportRepositoryImpl
import com.aivideostudio.data.repository.JobRepositoryImpl
import com.aivideostudio.data.repository.MediaRepositoryImpl
import com.aivideostudio.data.repository.ProjectRepositoryImpl
import com.aivideostudio.data.repository.StorageRepositoryImpl
import com.aivideostudio.domain.repository.AnalysisRepository
import com.aivideostudio.domain.repository.ClipRepository
import com.aivideostudio.domain.repository.ExportRepository
import com.aivideostudio.domain.repository.JobRepository
import com.aivideostudio.domain.repository.MediaRepository
import com.aivideostudio.domain.repository.ProjectRepository
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.domain.repository.StorageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    @Singleton
    abstract fun bindAnalysisRepository(impl: AnalysisRepositoryImpl): AnalysisRepository

    @Binds
    @Singleton
    abstract fun bindClipRepository(impl: ClipRepositoryImpl): ClipRepository

    @Binds
    @Singleton
    abstract fun bindJobRepository(impl: JobRepositoryImpl): JobRepository

    @Binds
    @Singleton
    abstract fun bindExportRepository(impl: ExportRepositoryImpl): ExportRepository

    @Binds
    @Singleton
    abstract fun bindStorageRepository(impl: StorageRepositoryImpl): StorageRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
