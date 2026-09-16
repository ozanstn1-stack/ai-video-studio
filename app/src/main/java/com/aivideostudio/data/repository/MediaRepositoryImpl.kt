package com.aivideostudio.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aivideostudio.core.common.Constants
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.dao.VideoAssetDao
import com.aivideostudio.data.mapper.toDomain
import com.aivideostudio.data.mapper.toEntity
import com.aivideostudio.domain.model.ProbeState
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val context: Context,
    private val assetDao: VideoAssetDao,
    private val dispatchers: DispatcherProvider,
) : MediaRepository {

    override fun observeAssets(projectId: Long): Flow<List<VideoAsset>> =
        assetDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun getAssets(projectId: Long): List<VideoAsset> = withContext(dispatchers.io) {
        assetDao.getByProject(projectId).map { it.toDomain() }
    }

    override suspend fun getAsset(assetId: Long): VideoAsset? = withContext(dispatchers.io) {
        assetDao.getById(assetId)?.toDomain()
    }

    override suspend fun getUnprobedAssets(projectId: Long): List<VideoAsset> =
        withContext(dispatchers.io) { assetDao.getUnprobed(projectId).map { it.toDomain() } }

    override suspend fun addAssets(projectId: Long, uris: List<String>): List<Long> =
        withContext(dispatchers.io) {
            val existing = assetDao.getByProject(projectId).map { it.uri }.toMutableSet()
            val added = mutableListOf<Long>()
            var order = assetDao.getByProject(projectId).size
            uris.forEach { raw ->
                if (raw in existing) return@forEach
                val name = displayName(raw)
                if (isProxyFile(name)) return@forEach
                val asset = VideoAsset(
                    projectId = projectId,
                    uri = raw,
                    displayName = name,
                    durationMs = 0L,
                    sizeBytes = fileSize(raw),
                    isDjiFootage = Constants.DJI_FILE_NAME_REGEX.matches(name),
                    orderIndex = order++,
                    probeState = ProbeState.PENDING,
                )
                val id = assetDao.insert(asset.toEntity())
                if (id > 0) {
                    added += id
                    existing += raw
                }
            }
            added
        }

    override suspend fun updateMetadata(asset: VideoAsset) = withContext(dispatchers.io) {
        assetDao.updateMetadata(
            assetId = asset.id,
            durationMs = asset.durationMs,
            width = asset.width,
            height = asset.height,
            fps = asset.fps,
            bitrate = asset.bitrate,
            videoCodec = asset.videoCodec,
            audioCodec = asset.audioCodec,
            audioChannels = asset.audioChannels,
            audioSampleRate = asset.audioSampleRate,
            sizeBytes = asset.sizeBytes,
            rotation = asset.rotationDegrees,
            hasAudio = asset.hasAudioTrack,
            recordedAt = asset.recordedAtEpochMs,
        )
    }

    override suspend fun updateThumbnail(assetId: Long, path: String?) = withContext(dispatchers.io) {
        assetDao.updateThumbnail(assetId, path)
    }

    override suspend fun updateProbeState(assetId: Long, state: ProbeState, error: String?) =
        withContext(dispatchers.io) {
            assetDao.updateProbeState(assetId, state.name, error)
        }

    override suspend fun removeAsset(assetId: Long) = withContext(dispatchers.io) {
        assetDao.deleteById(assetId)
    }

    override suspend fun totalDurationMs(projectId: Long): Long = withContext(dispatchers.io) {
        assetDao.totalDurationMs(projectId)
    }

    override fun observeTotalSourceBytes(): Flow<Long> = assetDao.observeTotalBytes()

    private fun isProxyFile(name: String): Boolean {
        val base = name.substringBeforeLast('.')
        return Constants.PROXY_SUFFIXES.any { base.endsWith(it, ignoreCase = true) }
    }

    private fun displayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index) ?: "video.mp4"
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    }

    private fun fileSize(uriString: String): Long {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return runCatching {
            java.io.File(uri.path ?: return 0L).length()
        }.getOrDefault(0L)
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                    }
                }
        }
        return 0L
    }
}
