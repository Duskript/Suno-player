package com.duskript.sunolocal.core.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.duskript.sunolocal.SunoLocalApplication
import com.duskript.sunolocal.core.auth.CookieStore
import com.duskript.sunolocal.core.auth.WebViewCookieBridge
import com.duskript.sunolocal.core.network.SunoApiClient
import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import java.io.File

/**
 * SunoDownloadWorker — WorkManager CoroutineWorker for background
 * sync/download of Suno playlists and tracks.
 *
 * Sync is intentionally diff-based:
 * - Existing downloaded tracks are matched by stable Suno track ID.
 * - If the stored local file still exists, the worker preserves localPath and
 *   downloadedAtEpochMs and does not call the network download path again.
 * - New or missing-file tracks are the only ones downloaded.
 */
class SunoDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as SunoLocalApplication
    private val cookieStore = CookieStore(context)
    private val apiClient = SunoApiClient(cookieStore = cookieStore)
    private val libraryStore = app.libraryStore

    /** Directory where downloaded audio files are stored. */
    private val musicDir: File by lazy {
        val dir = File(applicationContext.filesDir, "music")
        dir.mkdirs()
        dir
    }

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_MY_LIBRARY
        val playlistUrl = inputData.getString(KEY_PLAYLIST_URL)
        val autoSync = inputData.getBoolean(KEY_AUTO_SYNC, false)

        Log.i(TAG, "SunoDownloadWorker starting: mode=$mode, autoSync=$autoSync, url=$playlistUrl")

        return try {
            refreshStoredWebViewCookieIfAvailable(autoSync)
            if (autoSync && !cookieStore.isConfigured()) {
                Log.i(TAG, "Skipping auto-sync because no Suno cookie is configured")
                return Result.success(workDataOf("message" to "Auto-sync skipped: login required"))
            }

            setForeground(createForegroundInfo(if (autoSync) "Auto-syncing Suno playlists…" else "Starting sync\u2026"))

            when (mode) {
                MODE_MY_LIBRARY -> syncMyLibrary()
                MODE_PLAYLIST_URL -> {
                    if (playlistUrl.isNullOrBlank()) {
                        return Result.failure(workDataOf("error" to "playlist_url required for mode=$mode"))
                    }
                    syncPlaylistUrl(playlistUrl)
                }
                else -> Result.failure(workDataOf("error" to "Unknown mode: $mode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download worker failed", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private fun refreshStoredWebViewCookieIfAvailable(autoSync: Boolean) {
        val expiresSoon = cookieStore.sessionExpiresWithin(FIFTEEN_MINUTES_SECONDS)
        val refreshed = WebViewCookieBridge.refreshCookieStore(cookieStore)
        val expiresAt = cookieStore.sessionExpiresAtEpochSeconds()
        Log.i(
            TAG,
            "Suno cookie pre-sync refresh: autoSync=$autoSync refreshed=$refreshed expiresSoon=$expiresSoon expiresAt=$expiresAt"
        )
    }

    /** Sync the authenticated user's entire library. */
    private suspend fun syncMyLibrary(): Result {
        setProgress(workDataOf("progress" to 0f, "message" to "Fetching playlists\u2026"))

        val existingTracks = loadExistingTracksById()
        val playlists = apiClient.fetchMyPlaylists().map { playlist ->
            playlist.mergeExistingDownloads(existingTracks)
        }
        if (playlists.isEmpty()) {
            setProgress(workDataOf("progress" to 1f, "message" to "No playlists found"))
            return Result.success()
        }

        var totalTracks = 0
        var completedTracks = 0
        var downloadedCount = 0
        var skippedCount = 0

        playlists.forEach { playlist ->
            libraryStore.upsertPlaylist(playlist)
            totalTracks += playlist.tracks.size
        }

        for (playlist in playlists) {
            val syncedTracks = playlist.tracks.map { track ->
                val progress = if (totalTracks > 0) completedTracks.toFloat() / totalTracks else 0f

                val syncedTrack = if (track.hasUsableLocalFile()) {
                    skippedCount++
                    setProgress(workDataOf(
                        "progress" to progress,
                        "message" to "Already downloaded ${track.title} ($completedTracks/$totalTracks)"
                    ))
                    track
                } else {
                    setProgress(workDataOf(
                        "progress" to progress,
                        "message" to "Downloading ${track.title} ($completedTracks/$totalTracks)"
                    ))
                    setForeground(createForegroundInfo("Downloading ${track.title} ($completedTracks/$totalTracks)"))

                    val downloadedFile = try {
                        apiClient.downloadTrack(track, musicDir)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download track ${track.id}: ${e.message}")
                        null
                    }

                    if (downloadedFile != null) {
                        downloadedCount++
                        track.copy(
                            localPath = downloadedFile.absolutePath,
                            downloadedAtEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        track
                    }
                }

                completedTracks++
                syncedTrack
            }

            val updatedPlaylist = playlist.copy(
                tracks = syncedTracks,
                lastSyncedAtEpochMs = System.currentTimeMillis()
            )
            libraryStore.upsertPlaylist(updatedPlaylist)
        }

        setProgress(workDataOf(
            "progress" to 1f,
            "message" to "Sync complete: $downloadedCount new, $skippedCount unchanged"
        ))
        Log.i(TAG, "Sync complete: downloaded=$downloadedCount skipped=$skippedCount total=$totalTracks")

        return Result.success()
    }

    /** Sync a single playlist from a public URL. */
    private suspend fun syncPlaylistUrl(url: String): Result {
        setProgress(workDataOf("progress" to 0f, "message" to "Fetching playlist\u2026"))

        val existingTracks = loadExistingTracksById()
        val playlist = apiClient.fetchPlaylistFromUrl(url).mergeExistingDownloads(existingTracks)
        val totalTracks = playlist.tracks.size

        if (totalTracks == 0) {
            libraryStore.upsertPlaylist(playlist)
            setProgress(workDataOf("progress" to 1f, "message" to "No tracks found"))
            return Result.success()
        }

        var downloadedCount = 0
        var skippedCount = 0

        val syncedTracks = playlist.tracks.mapIndexed { index, track ->
            val progress = index.toFloat() / totalTracks

            if (track.hasUsableLocalFile()) {
                skippedCount++
                setProgress(workDataOf(
                    "progress" to progress,
                    "message" to "Already downloaded ${track.title} (${index + 1}/$totalTracks)"
                ))
                track
            } else {
                setProgress(workDataOf(
                    "progress" to progress,
                    "message" to "Downloading ${track.title} (${index + 1}/$totalTracks)"
                ))
                setForeground(createForegroundInfo("Downloading ${track.title} (${index + 1}/$totalTracks)"))

                val downloadedFile = try {
                    apiClient.downloadTrack(track, musicDir)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download track ${track.id}: ${e.message}")
                    null
                }

                if (downloadedFile != null) {
                    downloadedCount++
                    track.copy(
                        localPath = downloadedFile.absolutePath,
                        downloadedAtEpochMs = System.currentTimeMillis()
                    )
                } else {
                    track
                }
            }
        }

        val updatedPlaylist = playlist.copy(
            tracks = syncedTracks,
            lastSyncedAtEpochMs = System.currentTimeMillis()
        )
        libraryStore.upsertPlaylist(updatedPlaylist)

        setProgress(workDataOf(
            "progress" to 1f,
            "message" to "Playlist sync complete: $downloadedCount new, $skippedCount unchanged"
        ))
        Log.i(TAG, "Playlist sync complete: downloaded=$downloadedCount skipped=$skippedCount total=$totalTracks")

        return Result.success()
    }

    private fun loadExistingTracksById(): Map<String, SunoTrack> {
        return libraryStore.loadPlaylists()
            .flatMap { playlist ->
                playlist.tracks.map { track ->
                    track.id to SunoTrack(
                        id = track.id,
                        title = track.title,
                        audioUrl = track.audioUrl,
                        localPath = track.localPath?.takeIf { it.isNotBlank() && it != "null" },
                        imageUrl = track.imageUrl,
                        durationMs = track.durationMs,
                        playlistId = track.playlistId,
                        creatorName = track.creatorName,
                        sourceUrl = track.sourceUrl,
                        lyrics = track.lyrics,
                        stylePrompt = track.stylePrompt,
                        descriptionPrompt = track.descriptionPrompt,
                        downloadedAtEpochMs = track.downloadedAtEpochMs
                    )
                }
            }
            .toMap()
    }

    private fun SunoPlaylist.mergeExistingDownloads(existingTracks: Map<String, SunoTrack>): SunoPlaylist {
        return copy(
            tracks = tracks.map { remoteTrack ->
                val existing = existingTracks[remoteTrack.id]
                if (existing != null && existing.hasUsableLocalFile()) {
                    remoteTrack.copy(
                        localPath = existing.localPath,
                        lyrics = remoteTrack.lyrics ?: existing.lyrics,
                        stylePrompt = remoteTrack.stylePrompt ?: existing.stylePrompt,
                        descriptionPrompt = remoteTrack.descriptionPrompt ?: existing.descriptionPrompt,
                        downloadedAtEpochMs = existing.downloadedAtEpochMs.takeIf { it > 0L }
                            ?: System.currentTimeMillis()
                    )
                } else {
                    remoteTrack
                }
            }
        )
    }

    private fun SunoTrack.hasUsableLocalFile(): Boolean {
        val path = localPath?.takeIf { it.isNotBlank() && it != "null" } ?: return false
        val file = File(path)
        return file.exists() && file.length() > 0L
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = android.app.Notification.Builder(
            applicationContext,
            SunoLocalApplication.CHANNEL_DOWNLOAD
        )
            .setContentTitle("Suno Local Sync")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SunoDownloadWorker"

        const val KEY_MODE = "mode"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_AUTO_SYNC = "auto_sync"

        const val MODE_MY_LIBRARY = "my_library"
        const val MODE_PLAYLIST_URL = "playlist_url"

        private const val FIFTEEN_MINUTES_SECONDS = 15 * 60L
        private const val NOTIFICATION_ID = 1001
    }
}
