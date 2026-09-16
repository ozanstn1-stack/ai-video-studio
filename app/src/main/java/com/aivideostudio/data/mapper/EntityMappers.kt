package com.aivideostudio.data.mapper

import com.aivideostudio.data.local.JsonCodec
import com.aivideostudio.data.local.entity.AiJobEntity
import com.aivideostudio.data.local.entity.AiSuggestionEntity
import com.aivideostudio.data.local.entity.AudioBedEntity
import com.aivideostudio.data.local.entity.CaptionEntity
import com.aivideostudio.data.local.entity.ExportEntity
import com.aivideostudio.data.local.entity.GeneratedClipEntity
import com.aivideostudio.data.local.entity.HighlightEntity
import com.aivideostudio.data.local.entity.ProjectEntity
import com.aivideostudio.data.local.entity.SceneEntity
import com.aivideostudio.data.local.entity.TextOverlayEntity
import com.aivideostudio.data.local.entity.TimelineClipEntity
import com.aivideostudio.data.local.entity.TranscriptEntity
import com.aivideostudio.data.local.entity.TranscriptSegmentEntity
import com.aivideostudio.data.local.entity.VideoAssetEntity
import com.aivideostudio.domain.model.AiJob
import com.aivideostudio.domain.model.AiSuggestion
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.ClipOrigin
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.CreationMode
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.domain.model.ExportRecord
import com.aivideostudio.domain.model.ExportStatus
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.Framing
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.domain.model.Highlight
import com.aivideostudio.domain.model.HighlightLabel
import com.aivideostudio.domain.model.JobStatus
import com.aivideostudio.domain.model.PipelineStage
import com.aivideostudio.domain.model.ProbeState
import com.aivideostudio.domain.model.Project
import com.aivideostudio.domain.model.ProjectStatus
import com.aivideostudio.domain.model.Scene
import com.aivideostudio.domain.model.SceneIssue
import com.aivideostudio.domain.model.StoryRole
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.SuggestionCategory
import com.aivideostudio.domain.model.TextOverlay
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.Transcript
import com.aivideostudio.domain.model.TranscriptSegment
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.model.VideoQuality

fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    status = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.DRAFT),
    mode = CreationMode.fromName(mode),
    targetShortCount = targetShortCount,
    targetShortDurationSec = targetShortDurationSec,
    aspectRatio = AspectRatio.fromName(aspectRatio),
    quality = VideoQuality.fromName(quality),
    frameRate = FrameRateOption.fromName(frameRate),
    coverPath = coverPath,
    tag = tag,
    transcriptLanguage = transcriptLanguage,
    aiSummary = aiSummary,
    lastError = lastError,
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    status = status.name,
    mode = mode.name,
    targetShortCount = targetShortCount,
    targetShortDurationSec = targetShortDurationSec,
    aspectRatio = aspectRatio.name,
    quality = quality.name,
    frameRate = frameRate.name,
    coverPath = coverPath,
    tag = tag,
    transcriptLanguage = transcriptLanguage,
    aiSummary = aiSummary,
    lastError = lastError,
)

fun VideoAssetEntity.toDomain(): VideoAsset = VideoAsset(
    id = id,
    projectId = projectId,
    uri = uri,
    displayName = displayName,
    durationMs = durationMs,
    width = width,
    height = height,
    fps = fps,
    bitrate = bitrate,
    videoCodec = videoCodec,
    audioCodec = audioCodec,
    audioChannels = audioChannels,
    audioSampleRate = audioSampleRate,
    sizeBytes = sizeBytes,
    rotationDegrees = rotationDegrees,
    recordedAtEpochMs = recordedAtEpochMs,
    thumbnailPath = thumbnailPath,
    isDjiFootage = isDjiFootage,
    hasAudioTrack = hasAudioTrack,
    probeState = runCatching { ProbeState.valueOf(probeState) }.getOrDefault(ProbeState.PENDING),
    probeError = probeError,
    orderIndex = orderIndex,
)

fun VideoAsset.toEntity(): VideoAssetEntity = VideoAssetEntity(
    id = id,
    projectId = projectId,
    uri = uri,
    displayName = displayName,
    durationMs = durationMs,
    width = width,
    height = height,
    fps = fps,
    bitrate = bitrate,
    videoCodec = videoCodec,
    audioCodec = audioCodec,
    audioChannels = audioChannels,
    audioSampleRate = audioSampleRate,
    sizeBytes = sizeBytes,
    rotationDegrees = rotationDegrees,
    recordedAtEpochMs = recordedAtEpochMs,
    thumbnailPath = thumbnailPath,
    isDjiFootage = isDjiFootage,
    hasAudioTrack = hasAudioTrack,
    probeState = probeState.name,
    probeError = probeError,
    orderIndex = orderIndex,
)

fun SceneEntity.toDomain(): Scene = Scene(
    id = id,
    projectId = projectId,
    assetId = assetId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    brightness = brightness,
    sharpness = sharpness,
    motion = motion,
    stability = stability,
    saturation = saturation,
    quality = quality,
    faceCoverage = faceCoverage,
    faceCenterX = faceCenterX,
    faceCenterY = faceCenterY,
    dominantHue = dominantHue,
    framing = Framing.fromName(framing),
    issue = SceneIssue.entries.firstOrNull { it.name == issue } ?: SceneIssue.NONE,
    duplicateOfSceneId = duplicateOfSceneId,
    speechRatio = speechRatio,
    audioLoudness = audioLoudness,
    label = label,
)

fun Scene.toEntity(): SceneEntity = SceneEntity(
    id = id,
    projectId = projectId,
    assetId = assetId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    brightness = brightness,
    sharpness = sharpness,
    motion = motion,
    stability = stability,
    saturation = saturation,
    quality = quality,
    faceCoverage = faceCoverage,
    faceCenterX = faceCenterX,
    faceCenterY = faceCenterY,
    dominantHue = dominantHue,
    framing = framing.name,
    issue = issue.name,
    duplicateOfSceneId = duplicateOfSceneId,
    speechRatio = speechRatio,
    audioLoudness = audioLoudness,
    label = label,
)

fun TranscriptEntity.toDomain(): Transcript = Transcript(
    id = id,
    projectId = projectId,
    language = language,
    provider = provider,
    fullText = fullText,
    createdAt = createdAt,
    averageConfidence = averageConfidence,
    isComplete = isComplete,
    note = note,
)

fun Transcript.toEntity(): TranscriptEntity = TranscriptEntity(
    id = id,
    projectId = projectId,
    language = language,
    provider = provider,
    fullText = fullText,
    createdAt = createdAt,
    averageConfidence = averageConfidence,
    isComplete = isComplete,
    note = note,
)

fun TranscriptSegmentEntity.toDomain(): TranscriptSegment = TranscriptSegment(
    id = id,
    transcriptId = transcriptId,
    projectId = projectId,
    assetId = assetId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    text = text,
    confidence = confidence,
    words = JsonCodec.decodeWords(wordsJson),
)

fun TranscriptSegment.toEntity(): TranscriptSegmentEntity = TranscriptSegmentEntity(
    id = id,
    transcriptId = transcriptId,
    projectId = projectId,
    assetId = assetId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    text = text,
    confidence = confidence,
    wordsJson = JsonCodec.encodeWords(words),
)

fun HighlightEntity.toDomain(): Highlight = Highlight(
    id = id,
    projectId = projectId,
    assetId = assetId,
    startMs = startMs,
    endMs = endMs,
    visualScore = visualScore,
    audioScore = audioScore,
    speechScore = speechScore,
    motionScore = motionScore,
    interestScore = interestScore,
    uniquenessScore = uniquenessScore,
    storyScore = storyScore,
    overallScore = overallScore,
    hookScore = hookScore,
    payoffScore = payoffScore,
    speechCoverage = speechCoverage,
    label = HighlightLabel.fromName(label),
    reasons = JsonCodec.decodeReasons(reasonsJson),
    transcriptText = transcriptText,
    orderIndex = orderIndex,
    isSelected = isSelected,
    isRejected = isRejected,
    isManual = isManual,
)

fun Highlight.toEntity(): HighlightEntity = HighlightEntity(
    id = id,
    projectId = projectId,
    assetId = assetId,
    startMs = startMs,
    endMs = endMs,
    visualScore = visualScore,
    audioScore = audioScore,
    speechScore = speechScore,
    motionScore = motionScore,
    interestScore = interestScore,
    uniquenessScore = uniquenessScore,
    storyScore = storyScore,
    overallScore = overallScore,
    hookScore = hookScore,
    payoffScore = payoffScore,
    speechCoverage = speechCoverage,
    label = label.name,
    reasonsJson = JsonCodec.encodeReasons(reasons),
    transcriptText = transcriptText,
    orderIndex = orderIndex,
    isSelected = isSelected,
    isRejected = isRejected,
    isManual = isManual,
)

fun GeneratedClipEntity.toDomain(): GeneratedClip = GeneratedClip(
    id = id,
    projectId = projectId,
    index = index,
    title = title,
    titles = JsonCodec.decodeStrings(titlesJson),
    description = description,
    hashtags = JsonCodec.decodeStrings(hashtagsJson),
    hookText = hookText,
    hookHighlightId = hookHighlightId,
    label = HighlightLabel.fromName(label),
    sourceAssetId = sourceAssetId,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    durationMs = durationMs,
    aspectRatio = AspectRatio.fromName(aspectRatio),
    cropMode = CropMode.fromName(cropMode),
    storyRole = StoryRole.entries.firstOrNull { it.name == storyRole } ?: StoryRole.CLIMAX,
    storySummary = storySummary,
    thumbnailPath = thumbnailPath,
    outputPath = outputPath,
    status = runCatching { ClipStatus.valueOf(status) }.getOrDefault(ClipStatus.DRAFT),
    createdAt = createdAt,
    lastExportId = lastExportId,
)

fun GeneratedClip.toEntity(): GeneratedClipEntity = GeneratedClipEntity(
    id = id,
    projectId = projectId,
    index = index,
    title = title,
    titlesJson = JsonCodec.encodeStrings(titles),
    description = description,
    hashtagsJson = JsonCodec.encodeStrings(hashtags),
    hookText = hookText,
    hookHighlightId = hookHighlightId,
    label = label.name,
    sourceAssetId = sourceAssetId,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    durationMs = durationMs,
    aspectRatio = aspectRatio.name,
    cropMode = cropMode.name,
    storyRole = storyRole.name,
    storySummary = storySummary,
    thumbnailPath = thumbnailPath,
    outputPath = outputPath,
    status = status.name,
    createdAt = createdAt,
    lastExportId = lastExportId,
)

fun TimelineClipEntity.toDomain(): TimelineClip = TimelineClip(
    id = id,
    clipId = clipId,
    projectId = projectId,
    assetId = assetId,
    orderIndex = orderIndex,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    volume = volume,
    isMuted = isMuted,
    playbackSpeed = playbackSpeed,
    cropMode = CropMode.fromName(cropMode),
    cropCenterX = cropCenterX,
    cropCenterY = cropCenterY,
    cropScale = cropScale,
    storyRole = StoryRole.entries.firstOrNull { it.name == storyRole } ?: StoryRole.CLIMAX,
    highlightId = highlightId,
    origin = runCatching { ClipOrigin.valueOf(origin) }.getOrDefault(ClipOrigin.AI),
)

fun TimelineClip.toEntity(): TimelineClipEntity = TimelineClipEntity(
    id = id,
    clipId = clipId,
    projectId = projectId,
    assetId = assetId,
    orderIndex = orderIndex,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    volume = volume,
    isMuted = isMuted,
    playbackSpeed = playbackSpeed,
    cropMode = cropMode.name,
    cropCenterX = cropCenterX,
    cropCenterY = cropCenterY,
    cropScale = cropScale,
    storyRole = storyRole.name,
    highlightId = highlightId,
    origin = origin.name,
)

fun CaptionEntity.toDomain(): Caption = Caption(
    id = id,
    projectId = projectId,
    clipId = clipId,
    timelineClipId = timelineClipId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    text = text,
    style = SubtitleStyle.fromName(style),
    positionY = positionY,
    isEmphasised = isEmphasised,
    emphasisWords = JsonCodec.decodeStrings(emphasisWordsJson),
    isEdited = isEdited,
)

fun Caption.toEntity(): CaptionEntity = CaptionEntity(
    id = id,
    projectId = projectId,
    clipId = clipId,
    timelineClipId = timelineClipId,
    index = index,
    startMs = startMs,
    endMs = endMs,
    text = text,
    style = style.name,
    positionY = positionY,
    isEmphasised = isEmphasised,
    emphasisWordsJson = JsonCodec.encodeStrings(emphasisWords),
    isEdited = isEdited,
)

fun TextOverlayEntity.toDomain(): TextOverlay = TextOverlay(
    id = id,
    projectId = projectId,
    clipId = clipId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    positionX = positionX,
    positionY = positionY,
    fontSizeSp = fontSizeSp,
    isTitle = isTitle,
)

fun TextOverlay.toEntity(): TextOverlayEntity = TextOverlayEntity(
    id = id,
    projectId = projectId,
    clipId = clipId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    positionX = positionX,
    positionY = positionY,
    fontSizeSp = fontSizeSp,
    isTitle = isTitle,
)

fun AudioBedEntity.toDomain(): AudioBed = AudioBed(
    id = id,
    projectId = projectId,
    clipId = clipId,
    uri = uri,
    title = title,
    volume = volume,
    originalAudioMuted = originalAudioMuted,
    originalVolume = originalVolume,
    startMs = startMs,
    endMs = endMs,
)

fun AudioBed.toEntity(): AudioBedEntity = AudioBedEntity(
    id = id,
    projectId = projectId,
    clipId = clipId,
    uri = uri,
    title = title,
    volume = volume,
    originalAudioMuted = originalAudioMuted,
    originalVolume = originalVolume,
    startMs = startMs,
    endMs = endMs,
)

fun AiSuggestionEntity.toDomain(): AiSuggestion = AiSuggestion(
    id = id,
    category = SuggestionCategory.entries.firstOrNull { it.name == category }
        ?: SuggestionCategory.PACING,
    title = title,
    detail = detail,
    isApplied = isApplied,
)

fun AiJobEntity.toDomain(): AiJob = AiJob(
    id = id,
    projectId = projectId,
    stage = PipelineStage.fromName(stage),
    status = runCatching { JobStatus.valueOf(status) }.getOrDefault(JobStatus.QUEUED),
    progress = progress,
    attempt = attempt,
    provider = provider,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedStages = JsonCodec.decodeStages(finishedStagesJson),
    errorMessage = errorMessage,
    technicalDetail = technicalDetail,
    usedCloud = usedCloud,
)

fun AiJob.toEntity(): AiJobEntity = AiJobEntity(
    id = id,
    projectId = projectId,
    stage = stage.name,
    status = status.name,
    progress = progress,
    attempt = attempt,
    provider = provider,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedStagesJson = JsonCodec.encodeStages(finishedStages),
    errorMessage = errorMessage,
    technicalDetail = technicalDetail,
    usedCloud = usedCloud,
)

fun ExportEntity.toDomain(): ExportRecord = ExportRecord(
    id = id,
    projectId = projectId,
    clipId = clipId,
    fileName = fileName,
    path = path,
    uri = uri,
    width = width,
    height = height,
    fps = fps,
    quality = VideoQuality.fromName(quality),
    bitrate = bitrate,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    status = runCatching { ExportStatus.valueOf(status) }.getOrDefault(ExportStatus.QUEUED),
    progress = progress,
    createdAt = createdAt,
    completedAt = completedAt,
    errorMessage = errorMessage,
)

fun ExportRecord.toEntity(): ExportEntity = ExportEntity(
    id = id,
    projectId = projectId,
    clipId = clipId,
    fileName = fileName,
    path = path,
    uri = uri,
    width = width,
    height = height,
    fps = fps,
    quality = quality.name,
    bitrate = bitrate,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    status = status.name,
    progress = progress,
    createdAt = createdAt,
    completedAt = completedAt,
    errorMessage = errorMessage,
)
