package com.aivideostudio.data.repository

import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.dao.VideoAssetDao
import com.aivideostudio.data.local.media.AppFiles
import com.aivideostudio.domain.model.StorageUsage
import com.aivideostudio.domain.repository.StorageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    private val assetDao: VideoAssetDao,
    private val exportDao: com.aivideostudio.data.local.dao.ExportDao,
    private val appFiles: AppFiles,
    private val dispatchers: DispatcherProvider,
) : StorageRepository {

    private val refreshTrigger = MutableStateFlow(0L)

    override fun observeUsage(): Flow<StorageUsage> = combine(
        assetDao.observeTotalBytes(),
        exportDao.observeTotalBytes(),
        refreshTrigger,
    ) { sourceBytes, exportBytes, _ ->
        StorageUsage(
            originalMediaBytes = sourceBytes,
            generatedClipBytes = appFiles.generatedBytes() + exportBytes,
            cacheBytes = appFiles.cacheBytes(),
            tempBytes = appFiles.tempBytes(),
        )
    }.flowOn(dispatchers.io)

    override suspend fun refresh() = withContext(dispatchers.io) {
        refreshTrigger.value = System.currentTimeMillis()
    }

    override suspend fun clearCache(): Long = withContext(dispatchers.io) {
        val freed = appFiles.clearCache()
        refreshTrigger.value = System.currentTimeMillis()
        freed
    }

    override suspend fun deleteGeneratedFiles(): Long = withContext(dispatchers.io) {
        val freed = appFiles.deleteGenerated()
        refreshTrigger.value = System.currentTimeMillis()
        freed
    }

    override suspend fun deleteTemporaryFiles(): Long = withContext(dispatchers.io) {
        val freed = appFiles.deleteTemporary()
        refreshTrigger.value = System.currentTimeMillis()
        freed
    }

    override suspend fun availableBytes(): Long = withContext(dispatchers.io) {
        appFiles.availableBytes()
    }
}
