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
import com.duskript.sunolocal.core.network.SunoApiException
import com.duskript.sunolocal.core.storage.SyncSummaryStore
import com.duskript.sunolocal.domain.model.COOKIE_EXPIRED_GUIDANCE
import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import com.duskript.sunolocal.domain.model.SyncSummary
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
    private val syncSummaryStore = SyncSummaryStore(context)

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
                syncSummaryStore.save(
                    SyncSummary(
                        finishedAtEpochMs = System.currentTimeMillis(),
                        mode = mode,
                        source = playlistUrl,
                        success = false,
                        message = "Auto-sync skipped: login required",
                        error = COOKIE_EXPIRED_GUIDANCE
                    )
                )
                return Result.success(workDataOf("message" to "Auto-sync skipped: login required"))
            }

            setForegroundIfAllowed(if (autoSync) "Auto-syncing Suno playlists…" else "Starting sync…")

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
            val errorText = if (isAuthFailure(e)) {
                COOKIE_EXPIRED_GUIDANCE
            } else {
                e.message ?: "Unknown error"
            }
            syncSummaryStore.save(
                SyncSummary(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    mode = mode,
                    source = playlistUrl,
                    success = false,
                    message = "Sync failed",
                    error = errorText
                )
            )
            if (isTransientFailure(e)) {
                // Likely network timeout/connection loss or HTTP 5xx — let WorkManager
                // retry with its built-in exponential backoff policy.
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to errorText))
            }
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
        val hiddenPlaylistIds = libraryStore.loadHiddenPlaylistIds()
        val playlists = apiClient.fetchMyPlaylists()
            .filterNot { playlist -> playlist.id in hiddenPlaylistIds }
            .map { playlist -> playlist.mergeExistingDownloads(existingTracks) }
        Log.i(TAG, "Fetched ${playlists.size} playlist(s) with ${playlists.sumOf { it.tracks.size }} track(s)")
        if (playlists.isEmpty()) {
            setProgress(workDataOf("progress" to 1f, "message" to "No playlists found"))
            syncSummaryStore.save(
                SyncSummary(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    mode = MODE_MY_LIBRARY,
                    success = true,
                    message = "No playlists found"
                )
            )
            return Result.success()
        }

        var totalTracks = 0
        var completedTracks = 0
        var downloadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var authError: String? = null

        playlists.forEach { playlist ->
            libraryStore.upsertPlaylist(playlist)
            totalTracks += playlist.tracks.size
        }

        if (totalTracks == 0) {
            val message = "No tracks found in ${playlists.size} playlist(s)"
            Log.w(TAG, message)
            setProgress(workDataOf("progress" to 1f, "message" to message))
            syncSummaryStore.save(
                SyncSummary(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    mode = MODE_MY_LIBRARY,
                    success = false,
                    totalTracks = 0,
                    downloadedCount = 0,
                    skippedCount = 0,
                    failedCount = 0,
                    message = message,
                    error = "Suno returned playlists but no tracks. Re-test connection, then try adding a specific playlist URL so we can isolate whether /playlist/me summaries or playlist detail endpoints changed."
                )
            )
            return Result.failure(workDataOf("error" to message))
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
                    setForegroundIfAllowed("Downloading ${track.title} ($completedTracks/$totalTracks)")

                    val downloadedFile = try {
                        apiClient.downloadTrack(track, musicDir)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download track ${track.id} (${track.title}) from ${track.audioUrl ?: "cdn fallback"}: ${e.message}")
                        failedCount++
                        if (authError == null && isAuthFailure(e)) {
                            authError = COOKIE_EXPIRED_GUIDANCE
                        }
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

        val syncMessage = if (authError != null) {
            "Sync finished with $failedCount failed download(s)"
        } else {
            "Sync complete: $downloadedCount new, $skippedCount unchanged"
        }
        syncSummaryStore.save(
            SyncSummary(
                finishedAtEpochMs = System.currentTimeMillis(),
                mode = MODE_MY_LIBRARY,
                success = true,
                totalTracks = totalTracks,
                downloadedCount = downloadedCount,
                skippedCount = skippedCount,
                failedCount = failedCount,
                message = syncMessage,
                error = authError
            )
        )

        return Result.success()
    }

    /** Sync a single playlist from a public URL. */
    private suspend fun syncPlaylistUrl(url: String): Result {
        setProgress(workDataOf("progress" to 0f, "message" to "Fetching playlist\u2026"))

        val existingTracks = loadExistingTracksById()
        val playlist = apiClient.fetchPlaylistFromUrl(url).mergeExistingDownloads(existingTracks)
        libraryStore.unhidePlaylist(playlist.id)
        val totalTracks = playlist.tracks.size

        if (totalTracks == 0) {
            libraryStore.upsertPlaylist(playlist)
            setProgress(workDataOf("progress" to 1f, "message" to "No tracks found"))
            syncSummaryStore.save(
                SyncSummary(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    mode = MODE_PLAYLIST_URL,
                    source = url,
                    success = true,
                    message = "No tracks found"
                )
            )
            return Result.success()
        }

        var downloadedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var authError: String? = null

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
                setForegroundIfAllowed("Downloading ${track.title} (${index + 1}/$totalTracks)")

                val downloadedFile = try {
                    apiClient.downloadTrack(track, musicDir)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download track ${track.id}: ${e.message}")
                    failedCount++
                    if (authError == null && isAuthFailure(e)) {
                        authError = COOKIE_EXPIRED_GUIDANCE
                    }
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

        val syncMessage = if (authError != null) {
            "Playlist sync finished with $failedCount failed download(s)"
        } else {
            "Playlist sync complete: $downloadedCount new, $skippedCount unchanged"
        }
        syncSummaryStore.save(
            SyncSummary(
                finishedAtEpochMs = System.currentTimeMillis(),
                mode = MODE_PLAYLIST_URL,
                source = url,
                success = true,
                totalTracks = totalTracks,
                downloadedCount = downloadedCount,
                skippedCount = skippedCount,
                failedCount = failedCount,
                message = syncMessage,
                error = authError
            )
        )

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
                        tags = track.tags,
                        mood = track.mood,
                        genre = track.genre,
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
                        tags = remoteTrack.tags.ifEmpty { existing.tags },
                        mood = remoteTrack.mood ?: existing.mood,
                        genre = remoteTrack.genre ?: existing.genre,
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

    /** True when the exception means the Suno session cookie was rejected (HTTP 401/403). */
    private fun isAuthFailure(e: Exception): Boolean =
        e is SunoApiException && (e.httpCode == 401 || e.httpCode == 403)

    /**
     * True when the failure looks transient (network timeout/connection loss or
     * HTTP 429/5xx) and WorkManager should retry with backoff. Auth failures
     * (401/403), validation errors, and parse failures are not transient.
     */
    private fun isTransientFailure(e: Exception): Boolean {
        if (e is SunoApiException) {
            if (e.httpCode == 429 || e.httpCode >= 500) return true
            // httpCode 0 wraps a low-level network error (e.g. timeout) as cause.
            return e.httpCode == 0 && e.cause is java.io.IOException
        }
        return e is java.io.IOException
    }

    /**
     * Promote to a foreground Worker only when Android allows it. Android 12+
     * can reject WorkManager's SystemForegroundService with
     * ForegroundServiceStartNotAllowedException / mAllowStartForeground=false
     * depending on app/background state. That notification is nice-to-have;
     * failing it must not abort playlist fetches or downloads.
     */
    private suspend fun setForegroundIfAllowed(message: String) {
        try {
            setForeground(createForegroundInfo(message))
        } catch (e: Exception) {
            Log.w(TAG, "Foreground sync notification unavailable; continuing without it: ${e.message}")
        }
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
