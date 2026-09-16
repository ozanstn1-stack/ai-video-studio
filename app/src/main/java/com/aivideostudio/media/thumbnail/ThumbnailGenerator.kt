package com.aivideostudio.media.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.data.local.media.AppFiles
import com.aivideostudio.media.frames.FrameSampler
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes small JPEG posters next to nothing else: thumbnails live in the app's
 * private directory and are always derived from, never replacing, the source.
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    private val frameSampler: FrameSampler,
    private val appFiles: AppFiles,
    private val dispatchers: DispatcherProvider,
) {

    private val cache = mutableMapOf<String, Bitmap?>()

    suspend fun generate(
        projectId: Long,
        assetId: Long,
        uri: String,
        durationMs: Long,
        timeMs: Long = -1L,
    ): String? = withContext(dispatchers.io) {
        val at = if (timeMs >= 0) timeMs else pickPosterTime(durationMs)
        val bitmap = frameSampler.frameAt(uri, at, POSTER_WIDTH) ?: return@withContext null
        val file = appFiles.thumbnailFile(projectId, "asset_$assetId")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, out)
            }
            file.absolutePath
        } catch (error: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun generateForClip(
        projectId: Long,
        clipId: Long,
        uri: String,
        timeMs: Long,
    ): String? = withContext(dispatchers.io) {
        val bitmap = frameSampler.frameAt(uri, timeMs.coerceAtLeast(0L), POSTER_WIDTH)
            ?: return@withContext null
        val file = appFiles.thumbnailFile(projectId, "clip_$clipId")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, out)
            }
            file.absolutePath
        } catch (error: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Loads a thumbnail for the timeline; results are memoised per process. */
    fun load(path: String, maxWidth: Int = 320): Bitmap? {
        cache[path]?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxWidth) sampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
        cache[path] = bitmap
        return bitmap
    }

    fun clearCache() = cache.clear()

    /**
     * Posters land a little way in — the first frame of a camera clip is often
     * a hand or the ground.
     */
    private fun pickPosterTime(durationMs: Long): Long = when {
        durationMs <= 0L -> 0L
        durationMs < 4_000L -> durationMs / 3
        else -> (durationMs * 0.15).toLong()
    }

    private companion object {
        const val POSTER_WIDTH = 640
        const val POSTER_QUALITY = 88
    }
}
