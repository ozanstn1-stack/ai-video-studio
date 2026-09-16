package com.aivideostudio.data.repository

import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.dao.AiSuggestionDao
import com.aivideostudio.data.local.dao.AudioBedDao
import com.aivideostudio.data.local.dao.CaptionDao
import com.aivideostudio.data.local.dao.GeneratedClipDao
import com.aivideostudio.data.local.dao.TextOverlayDao
import com.aivideostudio.data.local.dao.TimelineClipDao
import com.aivideostudio.data.local.entity.AiSuggestionEntity
import com.aivideostudio.data.mapper.toDomain
import com.aivideostudio.data.mapper.toEntity
import com.aivideostudio.domain.model.AiSuggestion
import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.ClipEditState
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TextOverlay
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.repository.ClipRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipRepositoryImpl @Inject constructor(
    private val clipDao: GeneratedClipDao,
    private val timelineDao: TimelineClipDao,
    private val captionDao: CaptionDao,
    private val overlayDao: TextOverlayDao,
    private val audioDao: AudioBedDao,
    private val suggestionDao: AiSuggestionDao,
    private val dispatchers: DispatcherProvider,
) : ClipRepository {

    override fun observeClips(projectId: Long): Flow<List<GeneratedClip>> =
        clipDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    override fun observeFinishedClips(): Flow<List<GeneratedClip>> =
        clipDao.observeFinished().map { list -> list.map { it.toDomain() } }

    override suspend fun getClips(projectId: Long): List<GeneratedClip> = withContext(dispatchers.io) {
        clipDao.getByProject(projectId).map { it.toDomain() }
    }

    override suspend fun getClip(clipId: Long): GeneratedClip? = withContext(dispatchers.io) {
        clipDao.getById(clipId)?.toDomain()
    }

    override suspend fun saveClips(clips: List<GeneratedClip>): List<Long> = withContext(dispatchers.io) {
        clipDao.insertAll(clips.map { it.copy(id = 0L).toEntity() })
    }

    override suspend fun updateClip(clip: GeneratedClip) = withContext(dispatchers.io) {
        clipDao.update(clip.toEntity())
    }

    override suspend fun updateClipTitle(clipId: Long, title: String) = withContext(dispatchers.io) {
        clipDao.updateTitle(clipId, title)
    }

    override suspend fun updateClipDescription(
        clipId: Long,
        description: String,
        hashtags: List<String>,
    ) = withContext(dispatchers.io) {
        clipDao.updateDescription(
            clipId,
            description,
            com.aivideostudio.data.local.JsonCodec.encodeStrings(hashtags),
        )
    }

    override suspend fun updateClipHook(clipId: Long, hook: String?) = withContext(dispatchers.io) {
        clipDao.updateHook(clipId, hook)
    }

    override suspend fun updateClipThumbnail(clipId: Long, path: String?) = withContext(dispatchers.io) {
        clipDao.updateThumbnail(clipId, path)
    }

    override suspend fun updateClipOutput(
        clipId: Long,
        path: String?,
        status: ClipStatus,
    ) = withContext(dispatchers.io) {
        clipDao.updateOutput(clipId, path, status.name)
    }

    override suspend fun deleteClip(clipId: Long) = withContext(dispatchers.io) {
        clipDao.deleteById(clipId)
    }

    override suspend fun deleteClipsForProject(projectId: Long) = withContext(dispatchers.io) {
        clipDao.deleteByProject(projectId)
    }

    override suspend fun getEditState(clipId: Long): ClipEditState? = withContext(dispatchers.io) {
        val children = clipDao.getWithChildren(clipId) ?: return@withContext null
        ClipEditState(
            clip = children.clip.toDomain(),
            timeline = children.timeline.sortedBy { it.orderIndex }.map { it.toDomain() },
            captions = children.captions.sortedBy { it.startMs }.map { it.toDomain() },
            overlays = children.overlays.sortedBy { it.startMs }.map { it.toDomain() },
            audio = children.audioBeds.firstOrNull()?.toDomain(),
            hookText = children.clip.hookText,
        )
    }

    override suspend fun replaceTimeline(clipId: Long, clips: List<TimelineClip>) =
        withContext(dispatchers.io) {
            timelineDao.replaceForClip(
                clipId,
                clips.mapIndexed { index, clip -> clip.copy(id = 0L, orderIndex = index).toEntity() },
            )
        }

    override suspend fun updateTimelineClip(clip: TimelineClip) = withContext(dispatchers.io) {
        timelineDao.update(clip.toEntity())
    }

    override suspend fun replaceCaptions(clipId: Long, captions: List<Caption>) =
        withContext(dispatchers.io) {
            captionDao.replaceForClip(
                clipId,
                captions.mapIndexed { index, caption -> caption.copy(id = 0L, index = index).toEntity() },
            )
        }

    override suspend fun updateCaptionText(captionId: Long, text: String) = withContext(dispatchers.io) {
        captionDao.updateText(captionId, text)
    }

    override suspend fun updateCaptionStyle(clipId: Long, style: SubtitleStyle) =
        withContext(dispatchers.io) { captionDao.updateStyleForClip(clipId, style.name) }

    override suspend fun updateCaptionPosition(clipId: Long, positionY: Float) =
        withContext(dispatchers.io) { captionDao.updatePositionForClip(clipId, positionY) }

    override suspend fun upsertOverlay(overlay: TextOverlay): Long = withContext(dispatchers.io) {
        overlayDao.upsert(overlay.toEntity())
    }

    override suspend fun deleteOverlay(overlayId: Long) = withContext(dispatchers.io) {
        overlayDao.deleteById(overlayId)
    }

    override suspend fun upsertAudioBed(bed: AudioBed) {
        withContext(dispatchers.io) { audioDao.upsert(bed.toEntity()) }
    }

    override suspend fun getAudioBed(clipId: Long): AudioBed? = withContext(dispatchers.io) {
        audioDao.getByClip(clipId)?.toDomain()
    }

    override suspend fun saveSuggestions(clipId: Long, suggestions: List<AiSuggestion>) =
        withContext(dispatchers.io) {
            suggestionDao.deleteByClip(clipId)
            if (suggestions.isNotEmpty()) {
                suggestionDao.insertAll(
                    suggestions.map {
                        AiSuggestionEntity(
                            id = it.id,
                            projectId = 0L,
                            clipId = clipId,
                            category = it.category.name,
                            title = it.title,
                            detail = it.detail,
                            isApplied = it.isApplied,
                            payloadJson = "",
                        )
                    },
                )
            }
        }

    override suspend fun getSuggestions(clipId: Long): List<AiSuggestion> =
        withContext(dispatchers.io) {
            suggestionDao.getByClip(clipId).map { it.toDomain() }
        }

    override suspend fun markSuggestionApplied(id: String) = withContext(dispatchers.io) {
        suggestionDao.markApplied(id)
    }
}
