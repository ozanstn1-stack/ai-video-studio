package com.aivideostudio.media.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import com.aivideostudio.ai.crop.SmartCropper
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.domain.model.AspectRatio
import com.aivideostudio.domain.model.AudioBed
import com.aivideostudio.domain.model.Caption
import com.aivideostudio.domain.model.CropMode
import com.aivideostudio.domain.model.FrameRateOption
import com.aivideostudio.domain.model.SubtitleStyle
import com.aivideostudio.domain.model.TextOverlay
import com.aivideostudio.domain.model.TimelineClip
import com.aivideostudio.domain.model.VideoAsset
import com.aivideostudio.domain.model.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders a clip with Media3's Transformer.
 *
 * The source files are only ever read: a clip is a list of `[start, end)` ranges
 * pointing back at the originals, plus effects applied at render time. That is
 * what makes the whole app non-destructive.
 *
 * Captions, titles and text overlays are drawn by a [CanvasOverlay] that receives
 * the presentation timestamp of every frame, so timed captions work without
 * baking anything into the source.
 */
@Singleton
@OptIn(UnstableApi::class)
class VideoExporter @Inject constructor(
    private val context: Context,
    private val smartCropper: SmartCropper,
    private val dispatchers: DispatcherProvider,
) {

    data class Request(
        val projectId: Long,
        val clipId: Long?,
        val outputFile: File,
        val aspectRatio: AspectRatio,
        val quality: VideoQuality,
        val frameRate: FrameRateOption,
        val timeline: List<TimelineClip>,
        val assets: Map<Long, VideoAsset>,
        val captions: List<Caption> = emptyList(),
        val overlays: List<TextOverlay> = emptyList(),
        val audio: AudioBed? = null,
        val hookText: String? = null,
        val subtitleStyle: SubtitleStyle = SubtitleStyle.CREATOR,
        val hardwareAcceleration: Boolean = true,
    )

    data class Outcome(
        val success: Boolean,
        val file: File? = null,
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val errorMessage: String? = null,
        val technicalDetail: String? = null,
        val hardwareAccelerated: Boolean = false,
    )

    suspend fun export(
        request: Request,
        onProgress: (Float) -> Unit,
    ): Outcome {
        if (request.timeline.isEmpty()) {
            return Outcome(success = false, errorMessage = "There is nothing to export yet")
        }
        val missing = request.timeline.filter { request.assets[it.assetId] == null }
        if (missing.isNotEmpty()) {
            return Outcome(
                success = false,
                errorMessage = "Some original videos could not be found",
                technicalDetail = "missing assets: ${missing.map { it.assetId }}",
            )
        }
        request.outputFile.parentFile?.mkdirs()
        if (request.outputFile.exists()) request.outputFile.delete()

        // Transformer must be created and driven from a thread with a Looper.
        return withContext(dispatchers.main) {
            runExport(request, onProgress)
        }
    }

    private suspend fun runExport(
        request: Request,
        onProgress: (Float) -> Unit,
    ): Outcome {
        val (width, height) = renderSize(request)
        val composition = buildComposition(request, width, height)
            ?: return Outcome(success = false, errorMessage = "These clips cannot be combined")

        val deferred = CompletableDeferred<Outcome>()
        val transformer = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory(request, width, height))
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val file = request.outputFile
                        deferred.complete(
                            Outcome(
                                success = true,
                                file = file,
                                durationMs = exportResult.durationMs,
                                sizeBytes = if (file.exists()) file.length() else exportResult.fileSizeBytes,
                                hardwareAccelerated = request.hardwareAcceleration,
                            ),
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException,
                    ) {
                        deferred.complete(
                            Outcome(
                                success = false,
                                errorMessage = friendlyError(exception),
                                technicalDetail = "code=${exception.errorCodeName} " +
                                    "message=${exception.message}",
                            ),
                        )
                    }
                },
            )
            .build()

        return try {
            transformer.start(composition, request.outputFile.absolutePath)
            val holder = ProgressHolder()
            val outcome = coroutineScope {
                val poller = launch {
                    while (isActive && !deferred.isCompleted) {
                        val state = runCatching { transformer.getProgress(holder) }.getOrDefault(
                            Transformer.PROGRESS_STATE_NOT_STARTED,
                        )
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                        }
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    deferred.await()
                } finally {
                    poller.cancel()
                }
            }
            if (!outcome.success) runCatching { request.outputFile.delete() }
            outcome
        } catch (cancellation: CancellationException) {
            runCatching { transformer.cancel() }
            runCatching { request.outputFile.delete() }
            throw cancellation
        } catch (error: Exception) {
            runCatching { transformer.cancel() }
            runCatching { request.outputFile.delete() }
            Outcome(
                success = false,
                errorMessage = "Export failed",
                technicalDetail = error.message,
            )
        }
    }

    private fun buildComposition(
        request: Request,
        width: Int,
        height: Int,
    ): Composition? {
        val items = request.timeline
            .sortedBy { it.orderIndex }
            .mapNotNull { clip -> buildEditedItem(request, clip, width, height) }
        if (items.isEmpty()) return null

        val captionOverlay = CaptionOverlay(
            captions = request.captions,
            overlays = request.overlays,
            hookText = request.hookText,
            style = request.subtitleStyle,
            totalDurationMs = request.timeline.sumOf { it.durationMs },
        )

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.Builder(items)
            .experimentalSetForceAudioTrack(true)
            .build()

        // Optional music bed: a second sequence is mixed with the first by the
        // Transformer's audio mixer, so the original audio stays intact.
        val musicUri = request.audio?.uri
        if (!musicUri.isNullOrBlank()) {
            val musicItem = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(musicUri).build(),
            )
                .setRemoveVideo(true)
                .setEffects(
                    Effects(
                        listOf(gainProcessor(request.audio?.volume ?: MUSIC_DEFAULT_VOLUME)),
                        emptyList(),
                    ),
                )
                .build()
            sequences += EditedMediaItemSequence.Builder(listOf(musicItem))
                .setIsLooping(true)
                .build()
        }

        return Composition.Builder(sequences)
            // Composition-level effects run on the composited frame, so captions
            // are drawn once over the final image rather than per source clip.
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(captionOverlay)))))
            .build()
    }

    private fun buildEditedItem(
        request: Request,
        clip: TimelineClip,
        width: Int,
        height: Int,
    ): EditedMediaItem? {
        val asset = request.assets[clip.assetId] ?: return null

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.sourceStartMs.coerceAtLeast(0L))
            .setEndPositionMs(clip.sourceEndMs.coerceAtLeast(clip.sourceStartMs + 1))
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(asset.uri)
            .setClippingConfiguration(clipping)
            .build()

        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        // Letterbox into the target frame first, then crop inside it.
        videoEffects += Presentation.createForWidthAndHeight(
            width,
            height,
            Presentation.LAYOUT_SCALE_TO_FIT,
        )
        cropEffect(clip, asset, request.aspectRatio)?.let { videoEffects += it }

        val audioProcessors = mutableListOf<AudioProcessor>()
        val muted = clip.isMuted || (request.audio?.originalAudioMuted == true)
        if (!muted && clip.volume != 1f) {
            audioProcessors += gainProcessor(clip.volume)
        }

        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(muted || !asset.hasAudioTrack)
            .setEffects(Effects(audioProcessors, videoEffects))

        request.frameRate.fps?.let { fps -> builder.setFrameRate(fps) }
        if (clip.playbackSpeed != 1f && clip.playbackSpeed > 0f) {
            builder.setSpeed(ConstantSpeedProvider(clip.playbackSpeed))
        }
        return builder.build()
    }

    private fun cropEffect(
        clip: TimelineClip,
        asset: VideoAsset,
        aspectRatio: AspectRatio,
    ): Crop? {
        val useSmart = clip.cropMode == CropMode.SMART || clip.cropMode == CropMode.CENTER
        if (!useSmart) return null
        val rect = smartCropper.toCropRect(
            decision = com.aivideostudio.media.model.CropDecision(
                centerX = clip.cropCenterX,
                centerY = clip.cropCenterY,
                scale = clip.cropScale.coerceAtLeast(1f),
            ),
            sourceWidth = asset.width,
            sourceHeight = asset.height,
            rotationDegrees = asset.rotationDegrees,
            targetAspect = aspectRatio,
        )
        // A full-frame crop is a no-op and only costs GPU time.
        if (rect.width >= 0.995f && rect.height >= 0.995f) return null
        return Crop(rect.left, rect.right, rect.bottom, rect.top)
    }

    private fun encoderFactory(
        request: Request,
        width: Int,
        height: Int,
    ): DefaultEncoderFactory {
        val fps = request.frameRate.fps ?: DEFAULT_FPS
        val bitrate = bitrateFor(width, height, fps, request.quality)
        return DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
            )
            .setEnableFallback(true)
            .build()
    }

    private fun renderSize(request: Request): Pair<Int, Int> {
        val (width, height) = request.aspectRatio.renderSize()
        val scale = request.quality.scale
        return alignToEven((width * scale).toInt()) to alignToEven((height * scale).toInt())
    }

    /**
     * H.264 requires even dimensions and the platforms' encoders dislike very
     * large frames, so the short edge is capped.
     */
    private fun alignToEven(value: Int): Int = (value / 2) * 2

    private fun bitrateFor(width: Int, height: Int, fps: Int, quality: VideoQuality): Int {
        val pixels = width.toLong() * height.toLong()
        val bitsPerPixel = BASE_BITS_PER_PIXEL * quality.bitrateMultiplier
        val raw = pixels * fps * bitsPerPixel
        return raw.toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    private fun gainProcessor(volume: Float): GainProcessor =
        GainProcessor(ConstantGainProvider(volume.coerceIn(0f, 4f)))

    private fun friendlyError(exception: ExportException): String = when {
        exception.errorCodeName.contains("DECODING", ignoreCase = true) ->
            "One of the source videos could not be decoded"

        exception.errorCodeName.contains("ENCODING", ignoreCase = true) ->
            "This device could not encode the video at these settings"

        exception.message?.contains("space", ignoreCase = true) == true ->
            "There is not enough free storage for this export"

        else -> "Export failed"
    }

    /** Constant playback speed, used for slow motion and time-lapse. */
    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /** Fixed gain applied to a whole clip. */
    private class ConstantGainProvider(private val gain: Float) : GainProcessor.GainProvider {
        override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float = gain
        override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long = samplePosition
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
        const val DEFAULT_FPS = 30
        const val BASE_BITS_PER_PIXEL = 0.085
        const val MIN_BITRATE = 2_000_000
        const val MAX_BITRATE = 60_000_000
        const val MUSIC_DEFAULT_VOLUME = 0.35f
    }
}
