package com.aivideostudio.data.local.media

import android.content.Context
import android.net.Uri
import com.aivideostudio.core.common.SizeUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every path the app writes to. Nothing here ever points at the user's
 * original media, which is what makes the editing pipeline non-destructive.
 */
@Singleton
class AppFiles @Inject constructor(
    private val context: Context,
) {

    val thumbnailsDir: File by lazy { dir("thumbnails") }
    val exportsDir: File by lazy { dir("exports") }
    val clipWorkDir: File by lazy { dir("clips") }
    val audioCacheDir: File by lazy { dir("audio") }
    val frameCacheDir: File by lazy { dir("frames") }
    val tempDir: File by lazy { File(context.cacheDir, "temp").ensure() }

    fun dir(name: String): File = File(context.filesDir, name).ensure()

    fun projectThumbnailDir(projectId: Long): File = dir("thumbnails/$projectId")

    fun projectFrameDir(projectId: Long): File = File(context.cacheDir, "frames/$projectId").ensure()

    fun projectAudioDir(projectId: Long): File = File(context.cacheDir, "audio/$projectId").ensure()

    fun projectClipDir(projectId: Long): File = File(context.filesDir, "clips/$projectId").ensure()

    fun newExportFile(name: String): File = File(exportsDir, uniqueName(name, "mp4"))

    fun newClipFile(projectId: Long, name: String): File =
        File(projectClipDir(projectId), uniqueName(name, "mp4"))

    fun newAudioFile(projectId: Long, name: String): File =
        File(projectAudioDir(projectId), uniqueName(name, "pcm"))

    fun thumbnailFile(projectId: Long, key: String): File =
        File(projectThumbnailDir(projectId), "$key.jpg")

    fun fileUri(path: String): Uri = Uri.fromFile(File(path))

    private fun uniqueName(name: String, extension: String): String {
        val stamp = System.currentTimeMillis()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "clip" }
        return "${safe}_$stamp.$extension"
    }

    /** Bytes held by our own generated output. */
    fun generatedBytes(): Long = listOf(exportsDir, clipWorkDir).sumOf { it.sizeRecursive() }

    fun cacheBytes(): Long = listOf(context.cacheDir, audioCacheDir, frameCacheDir).sumOf { it.sizeRecursive() }

    fun tempBytes(): Long = tempDir.sizeRecursive()

    fun clearCache(): Long {
        val before = cacheBytes()
        listOf(context.cacheDir, audioCacheDir, frameCacheDir, tempDir).forEach { it.deleteRecursively() }
        audioCacheDir.ensure()
        frameCacheDir.ensure()
        tempDir.ensure()
        return before
    }

    fun deleteGenerated(): Long {
        val before = generatedBytes()
        exportsDir.deleteRecursively()
        exportsDir.ensure()
        clipWorkDir.deleteRecursively()
        clipWorkDir.ensure()
        return before
    }

    fun deleteTemporary(): Long {
        val before = tempBytes()
        tempDir.deleteRecursively()
        tempDir.ensure()
        return before
    }

    fun availableBytes(): Long = context.filesDir.usableSpace

    private fun File.ensure(): File = apply { if (!exists()) mkdirs() }

    private fun File.sizeRecursive(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun describeGenerated(): String = SizeUtils.formatBytes(generatedBytes())
}
