package com.aivideostudio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.aivideostudio.core.common.Constants
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Provides two pieces of app-wide wiring:
 *  - WorkManager is created through Hilt so pipeline and export workers can be
 *    injected with repositories;
 *  - Coil understands video files, so thumbnails are requested like any image.
 */
@HiltAndroidApp
class AiVideoStudioApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN,
            )
            .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /**
     * Processing and export run in foreground services, so the channels are
     * created once at startup rather than lazily from a background thread.
     */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val processing = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_PROCESSING,
            getString(R.string.channel_processing_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_processing_description)
            setShowBadge(false)
        }

        val exports = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_EXPORT,
            getString(R.string.channel_export_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_export_description)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(processing, exports))
    }
}
