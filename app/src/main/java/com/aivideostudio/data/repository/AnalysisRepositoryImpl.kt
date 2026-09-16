package com.aivideostudio.data.repository

import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.JsonCodec
import com.aivideostudio.data.local.dao.HighlightDao
import com.aivideostudio.data.local.dao.SceneDao
import com.aivideostudio.data.local.dao.SemanticAnalysisDao
import com.aivideostudio.data.local.dao.TranscriptDao
import com.aivideostudio.data.local.dao.TranscriptSegmentDao
import com.aivideostudio.data.local.entity.SemanticAnalysisEntity
import com.aivideostudio.data.mapper.toDomain
import com.aivideostudio.data.mapper.toEntity
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.SceneIssue
import com.aivideostudio.domain.model.Transcript
import com.aivideostudio.domain.model.TranscriptSegment
import com.aivideostudio.domain.repository.AnalysisRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalysisRepositoryImpl @Inject constructor(
    private val sceneDao: SceneDao,
    private val transcriptDao: TranscriptDao,
    private val segmentDao: TranscriptSegmentDao,
    private val highlightDao: HighlightDao,
    private val semanticDao: SemanticAnalysisDao,
    private val dispatchers: DispatcherProvider,
) : AnalysisRepository {

    override suspend fun replaceScenes(assetId: Long, scenes: List<Scene>) =
        withContext(dispatchers.io) {
            sceneDao.deleteByAsset(assetId)
            if (scenes.isNotEmpty()) sceneDao.insertAll(scenes.map { it.toEntity() })
        }

    override suspend fun getScenesForProject(projectId: Long): List<Scene> = withContext(dispatchers.io) {
        sceneDao.getByProject(projectId).map { it.toDomain() }
    }

    override suspend fun getScenesForAsset(assetId: Long): List<Scene> = withContext(dispatchers.io) {
        sceneDao.getByAsset(assetId).map { it.toDomain() }
    }

    override fun observeScenes(assetId: Long): Flow<List<Scene>> =
        sceneDao.observeByAsset(assetId).map { list -> list.map { it.toDomain() } }

    override suspend fun updateSceneIssue(sceneId: Long, issue: SceneIssue, duplicateOf: Long?) =
        withContext(dispatchers.io) { sceneDao.updateIssue(sceneId, issue.name, duplicateOf) }

    override suspend fun updateSceneAudio(sceneId: Long, speechRatio: Float, loudness: Float) =
        withContext(dispatchers.io) { sceneDao.updateAudio(sceneId, speechRatio, loudness) }

    override suspend fun updateSceneFace(
        sceneId: Long,
        coverage: Float,
        centerX: Float,
        centerY: Float,
        framing: com.aivideostudio.domain.model.Framing,
    ) = withContext(dispatchers.io) {
        sceneDao.updateFace(sceneId, coverage, centerX, centerY, framing.name)
    }

    override suspend fun sceneCount(projectId: Long): Int = withContext(dispatchers.io) {
        sceneDao.countByProject(projectId)
    }

    override suspend fun saveTranscript(
        transcript: Transcript,
        segments: List<TranscriptSegment>,
    ): Long = withContext(dispatchers.io) {
        transcriptDao.deleteByProject(transcript.projectId)
        val transcriptId = transcriptDao.insert(transcript.copy(id = 0L).toEntity())
        if (segments.isNotEmpty()) {
            segmentDao.insertAll(segments.map { it.copy(id = 0L, transcriptId = transcriptId).toEntity() })
        }
        transcriptId
    }

    override suspend fun getTranscript(projectId: Long): Transcript? = withContext(dispatchers.io) {
        transcriptDao.getLatest(projectId)?.toDomain()
    }

    override fun observeTranscript(projectId: Long): Flow<Transcript?> =
        transcriptDao.observeLatest(projectId).map { it?.toDomain() }

    override suspend fun getSegments(projectId: Long): List<TranscriptSegment> =
        withContext(dispatchers.io) { segmentDao.getByProject(projectId).map { it.toDomain() } }

    override fun observeSegments(projectId: Long): Flow<List<TranscriptSegment>> =
        segmentDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun segmentCount(projectId: Long): Int = withContext(dispatchers.io) {
        segmentDao.countByProject(projectId)
    }

    override suspend fun saveSemanticResult(
        projectId: Long,
        result: com.aivideostudio.ai.model.SceneAnalysisResult,
    ) = withContext(dispatchers.io) {
        semanticDao.upsert(
            SemanticAnalysisEntity(
                projectId = projectId,
                subjectsJson = JsonCodec.encodeStrings(result.subjects),
                locationsJson = JsonCodec.encodeStrings(result.locations),
                activitiesJson = JsonCodec.encodeStrings(result.activities),
                tagsJson = JsonCodec.encodeStrings(result.tags),
                mood = result.mood,
                storySummary = result.storySummary,
                provider = result.provider,
                confidence = result.confidence,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun getSemanticResult(
        projectId: Long,
    ): com.aivideostudio.ai.model.SceneAnalysisResult = withContext(dispatchers.io) {
        semanticDao.get(projectId)?.let { entity ->
            com.aivideostudio.ai.model.SceneAnalysisResult(
                subjects = JsonCodec.decodeStrings(entity.subjectsJson),
                locations = JsonCodec.decodeStrings(entity.locationsJson),
                activities = JsonCodec.decodeStrings(entity.activitiesJson),
                tags = JsonCodec.decodeStrings(entity.tagsJson),
                mood = entity.mood,
                storySummary = entity.storySummary,
                provider = entity.provider,
                confidence = entity.confidence,
            )
        } ?: com.aivideostudio.ai.model.SceneAnalysisResult.EMPTY
    }

    override suspend fun replaceHighlights(projectId: Long, highlights: List<Highlight>) =
        withContext(dispatchers.io) {
            highlightDao.replaceAll(projectId, highlights.map { it.toEntity() })
        }

    override suspend fun getHighlights(projectId: Long): List<Highlight> = withContext(dispatchers.io) {
        highlightDao.getByProject(projectId).map { it.toDomain() }
    }

    override fun observeHighlights(projectId: Long): Flow<List<Highlight>> =
        highlightDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    override suspend fun setHighlightSelection(id: Long, selected: Boolean, rejected: Boolean) =
        withContext(dispatchers.io) { highlightDao.updateSelection(id, selected, rejected) }

    override suspend fun highlightCount(projectId: Long): Int = withContext(dispatchers.io) {
        highlightDao.countByProject(projectId)
    }
}
