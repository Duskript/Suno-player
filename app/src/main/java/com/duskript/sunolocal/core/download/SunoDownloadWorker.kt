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
import com.duskript.sunolocal.core.auth.PreSyncAuthAction
import com.duskript.sunolocal.core.auth.PreSyncAuthGuard
import com.duskript.sunolocal.core.auth.PreSyncAuthMessages
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

    /**
     * Number of safe WebView adoption + validation retries already consumed by
     * this worker run. Capped at [MAX_AUTH_RETRIES] (1) so a rejected session
     * can never loop.
     */
    private var authRetriesUsed = 0

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
            when (runPreSyncAuthGuard(autoSync)) {
                PreSyncAuthAction.PROCEED -> {
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
                }
                PreSyncAuthAction.LOGIN_REQUIRED -> {
                    // v0.1.23 — pre-sync auth guard outcome. Auto-sync returns
                    // success (a skipped run) so WorkManager never retry-spams
                    // a user who simply has not logged in; manual sync fails
                    // loudly with the same login-required guidance. Library
                    // data and downloaded audio are never touched here.
                    val message = if (autoSync) {
                        PreSyncAuthMessages.AUTO_SYNC_SKIPPED_LOGIN_REQUIRED
                    } else {
                        PreSyncAuthMessages.SYNC_FAILED_LOGIN_REQUIRED
                    }
                    Log.w(TAG, message)
                    syncSummaryStore.save(
                        SyncSummary(
                            finishedAtEpochMs = System.currentTimeMillis(),
                            mode = mode,
                            source = playlistUrl,
                            success = false,
                            message = message,
                            error = COOKIE_EXPIRED_GUIDANCE
                        )
                    )
                    if (autoSync) {
                        Result.success(workDataOf("message" to message))
                    } else {
                        Result.failure(workDataOf("error" to COOKIE_EXPIRED_GUIDANCE))
                    }
                }
                PreSyncAuthAction.RETRY -> {
                    // Pre-sync live validation hit a transient network failure
                    // (timeout / HTTP 429/5xx) — let WorkManager retry with
                    // its built-in exponential backoff policy.
                    Log.w(TAG, "Pre-sync validation transient failure — WorkManager will retry with backoff")
                    Result.retry()
                }
                PreSyncAuthAction.VALIDATE -> {
                    // Unreachable in practice: runPreSyncAuthGuard maps VALIDATE
                    // to PROCEED / LOGIN_REQUIRED / RETRY after running the live
                    // playlist/me probe. Branch kept for exhaustive-when safety.
                    Log.e(TAG, "Unexpected pre-sync guard state: VALIDATE reached doWork")
                    Result.failure(workDataOf("error" to "Unexpected pre-sync guard state"))
                }
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

    /**
     * Pre-sync auth guard (v0.1.23-auth-refresh-flow). Runs before any playlist
     * fetch/download so a sync never starts on a stored cookie that is missing,
     * expired, or provably about to expire:
     *
     * 1. Safe WebView-cookie adoption (no-overwrite-older rule from Batch C).
     * 2. Freshness check on the effective stored cookie.
     * 3. When the cookie is missing/expired/near-expired, live validation with
     *    playlist/me ([PreSyncAuthAction.VALIDATE]).
     *
     * Returns [PreSyncAuthAction.PROCEED] when sync may start,
     * [PreSyncAuthAction.LOGIN_REQUIRED] when the user must log in again, or
     * [PreSyncAuthAction.RETRY] when live validation hit a transient network
     * failure. Library data is never cleared by this guard. Log lines carry
     * booleans/reason/expiry timestamps only — never cookie or JWT values.
     */
    private suspend fun runPreSyncAuthGuard(autoSync: Boolean): PreSyncAuthAction {
        val refreshResult = WebViewCookieBridge.refreshCookieStore(cookieStore)
        val freshness = cookieStore.freshness()
        Log.i(
            TAG,
            "Suno cookie pre-sync guard: autoSync=$autoSync captured=${refreshResult.captured} " +
                "saved=${refreshResult.saved} reason=${refreshResult.reason} " +
                "expiresWithin=${freshness.expiresWithin(PRE_SYNC_VALIDATION_WINDOW_SECONDS)} " +
                "newExpiresAt=${refreshResult.newExpiresAt} oldExpiresAt=${refreshResult.oldExpiresAt}"
        )

        val decision = PreSyncAuthGuard.decide(
            configured = cookieStore.isConfigured(),
            freshness = freshness,
            nearExpiryWindowSeconds = PRE_SYNC_VALIDATION_WINDOW_SECONDS
        )
        Log.i(TAG, "Pre-sync auth guard decision: ${decision.action} — ${decision.reason}")

        if (decision.action != PreSyncAuthAction.VALIDATE) {
            return decision.action
        }

        // Stored cookie is missing a __session, expired, or near expiry: prove
        // it with playlist/me before spending a worker run on a doomed sync.
        return try {
            apiClient.testConnection()
            Log.i(TAG, "Pre-sync live validation passed — playlist/me returned HTTP 200")
            PreSyncAuthAction.PROCEED
        } catch (e: Exception) {
            when {
                isAuthFailure(e) -> {
                    Log.w(
                        TAG,
                        "Pre-sync live validation rejected (HTTP ${(e as? SunoApiException)?.httpCode}) — login required"
                    )
                    PreSyncAuthAction.LOGIN_REQUIRED
                }
                isTransientFailure(e) -> {
                    Log.w(TAG, "Pre-sync live validation transient failure: ${e.message}")
                    PreSyncAuthAction.RETRY
                }
                else -> throw e
            }
        }
    }

    /**
     * One safe WebView adoption + playlist/me validation retry after a
     * mid-sync auth failure (v0.1.23). Never loops: a single retry per worker
     * run at most, shared across playlist-fetch and per-track download failure
     * paths. Returns true only when a freshly adopted cookie validated OK.
     */
    private suspend fun retryAuthOnce(): Boolean {
        if (authRetriesUsed >= MAX_AUTH_RETRIES) return false
        authRetriesUsed++
        val refreshResult = WebViewCookieBridge.refreshCookieStore(cookieStore)
        Log.i(
            TAG,
            "Auth-failure retry $authRetriesUsed/$MAX_AUTH_RETRIES: captured=${refreshResult.captured} " +
                "saved=${refreshResult.saved} reason=${refreshResult.reason}"
        )
        if (!cookieStore.isConfigured()) return false
        return try {
            apiClient.testConnection()
            Log.i(TAG, "Auth-failure retry validation passed")
            true
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Auth-failure retry validation failed: ${if (isAuthFailure(e)) "auth rejected" else "non-auth — ${e.message}"}"
            )
            false
        }
    }

    /** Fetch the user's playlists, retrying exactly once after a fresh WebView adoption on auth failure. */
    private suspend fun fetchMyPlaylistsWithAuthRetry(): List<SunoPlaylist> {
        return try {
            apiClient.fetchMyPlaylists()
        } catch (e: Exception) {
            if (isAuthFailure(e) && retryAuthOnce()) {
                apiClient.fetchMyPlaylists()
            } else {
                throw e
            }
        }
    }

    /** Fetch a playlist URL, retrying exactly once after a fresh WebView adoption on auth failure. */
    private suspend fun fetchPlaylistFromUrlWithAuthRetry(url: String): SunoPlaylist {
        return try {
            apiClient.fetchPlaylistFromUrl(url)
        } catch (e: Exception) {
            if (isAuthFailure(e) && retryAuthOnce()) {
                apiClient.fetchPlaylistFromUrl(url)
            } else {
                throw e
            }
        }
    }

    /**
     * Download one track, retrying exactly once after a fresh WebView adoption
     * when the first attempt was rejected with 401/403. The original exception
     * is rethrown when no retry is possible so the caller can record the
     * failure with auth guidance.
     */
    private suspend fun downloadTrackWithAuthRetry(track: SunoTrack): File? {
        return try {
            apiClient.downloadTrack(track, musicDir)
        } catch (e: Exception) {
            if (isAuthFailure(e) && retryAuthOnce()) {
                try {
                    apiClient.downloadTrack(track, musicDir)
                } catch (e2: Exception) {
                    Log.w(TAG, "Retried download still failed for ${track.id}: ${e2.message}")
                    throw e2
                }
            } else {
                throw e
            }
        }
    }

    /** Sync the authenticated user's entire library. */
    private suspend fun syncMyLibrary(): Result {
        setProgress(workDataOf("progress" to 0f, "message" to "Fetching playlists\u2026"))

        val existingTracks = loadExistingTracksById()
        val hiddenPlaylistIds = libraryStore.loadHiddenPlaylistIds()
        val playlists = fetchMyPlaylistsWithAuthRetry()
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
                        downloadTrackWithAuthRetry(track)
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
        val playlist = fetchPlaylistFromUrlWithAuthRetry(url).mergeExistingDownloads(existingTracks)
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
                    downloadTrackWithAuthRetry(track)
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

        /** Cookies expiring within this window trigger a live pre-sync playlist/me validation. */
        private const val PRE_SYNC_VALIDATION_WINDOW_SECONDS = 5 * 60L

        /** Maximum safe WebView adoption + validation retries per worker run (never loop). */
        private const val MAX_AUTH_RETRIES = 1
        private const val NOTIFICATION_ID = 1001
    }
}
