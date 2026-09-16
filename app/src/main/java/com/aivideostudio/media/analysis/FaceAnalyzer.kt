package com.aivideostudio.media.analysis

import android.graphics.Bitmap
import com.aivideostudio.core.common.DispatcherProvider
import com.aivideostudio.media.model.FaceSample
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device face detection used for two things the brief cares about:
 * subject-aware cropping and telling the difference between a wide establishing
 * shot and a close-up on a person.
 *
 * ML Kit's bundled model runs fully offline, so this never uploads a frame.
 */
@Singleton
class FaceAnalyzer @Inject constructor(
    private val dispatchers: DispatcherProvider,
) {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.06f)
                .enableTracking()
                .build(),
        )
    }

    suspend fun analyse(timeMs: Long, bitmap: Bitmap): FaceSample = withContext(dispatchers.default) {
        val safe = bitmap.toArgb8888()
        try {
            val faces = detect(safe)
            if (faces.isEmpty()) {
                return@withContext FaceSample(timeMs, 0f, 0.5f, 0.5f, 0, false)
            }
            val frameArea = safe.width.toFloat() * safe.height.toFloat()
            var coverage = 0f
            var weightedX = 0f
            var weightedY = 0f
            var weight = 0f
            var smiling = false

            faces.forEach { face ->
                val box = face.boundingBox
                val area = (box.width().coerceAtLeast(0) * box.height().coerceAtLeast(0)).toFloat()
                coverage += area / frameArea
                val faceWeight = area.coerceAtLeast(1f)
                weightedX += ((box.left + box.right) / 2f / safe.width) * faceWeight
                weightedY += ((box.top + box.bottom) / 2f / safe.height) * faceWeight
                weight += faceWeight
                if (face.smilingProbability ?: 0f > 0.6f) smiling = true
            }

            FaceSample(
                timeMs = timeMs,
                coverage = coverage.coerceIn(0f, 1f),
                centerX = if (weight <= 0f) 0.5f else (weightedX / weight).coerceIn(0f, 1f),
                centerY = if (weight <= 0f) 0.5f else (weightedY / weight).coerceIn(0f, 1f),
                faceCount = faces.size,
                isSmiling = smiling,
            )
        } catch (error: Exception) {
            FaceSample(timeMs, 0f, 0.5f, 0.5f, 0, false)
        } finally {
            if (safe !== bitmap) safe.recycle()
        }
    }

    fun close() {
        runCatching { detector.close() }
    }

    private suspend fun detect(bitmap: Bitmap) = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces -> if (continuation.isActive) continuation.resume(faces) }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        continuation.invokeOnCancellation { }
    }

    /** ML Kit requires a software bitmap; the retriever normally already gives us one. */
    private fun Bitmap.toArgb8888(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
}
