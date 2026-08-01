package com.duskript.sunolocal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.duskript.sunolocal.core.download.SunoAutoSyncScheduler
import com.duskript.sunolocal.core.storage.LibraryStore

/**
 * SunoLocalApplication — Application subclass for Suno Local Player.
 *
 * Initialises:
 * - WorkManager (background download/sync)
 * - LibraryStore (local persistence)
 * - Notification channels for playback and download progress
 */
class SunoLocalApplication : Application(), Configuration.Provider {

    /** App-private LibraryStore instance — created eagerly so workers can access it. */
    lateinit var libraryStore: LibraryStore
        private set

    override fun onCreate() {
        super.onCreate()

        instance = this

        // Initialise local persistence
        libraryStore = LibraryStore(this)

        // Create notification channels for Android 13+
        createNotificationChannels()

        // Eagerly initialise WorkManager (ensures it's ready for background workers)
        WorkManager.getInstance(this)

        // Keep Suno playlists fresh with Android's minimum periodic cadence (15 minutes).
        SunoAutoSyncScheduler.schedule(this)
    }

    // ---- Configuration.Provider (WorkManager custom config) ----

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    // ---- Notification Channels ----

    private fun createNotificationChannels() {
        val channels = listOf(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for Suno tracks"
            },
            NotificationChannel(
                CHANNEL_PLAYBACK,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing track"
            }
        )

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channels.forEach { manager.createNotificationChannel(it) }
        }
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "suno_local_download"
        const val CHANNEL_PLAYBACK = "suno_local_playback"

        /** Global application instance — use sparingly, prefer dependency injection patterns. */
        @Volatile
        lateinit var instance: SunoLocalApplication
            private set
    }
}
