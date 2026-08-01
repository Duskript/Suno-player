package com.duskript.sunolocal.features.library.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duskript.sunolocal.BuildConfig
import com.duskript.sunolocal.SunoLocalApplication
import com.duskript.sunolocal.core.auth.CookieStore
import com.duskript.sunolocal.core.auth.WebViewCookieBridge
import com.duskript.sunolocal.core.download.SunoDownloadWorker
import com.duskript.sunolocal.core.network.SunoApiClient
import com.duskript.sunolocal.core.network.SunoApiException
import com.duskript.sunolocal.core.player.LocalAudioPlayer
import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.duskript.sunolocal.core.storage.SunoTrackJson
import com.duskript.sunolocal.core.storage.SyncSummaryStore
import com.duskript.sunolocal.core.update.AppUpdateInfo
import com.duskript.sunolocal.core.update.GitHubUpdateChecker
import com.duskript.sunolocal.domain.model.COOKIE_EXPIRED_GUIDANCE
import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import com.duskript.sunolocal.domain.model.SyncSummary
import com.duskript.sunolocal.domain.model.SyncStatus
import com.duskript.sunolocal.domain.model.isCookieAuthError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LibraryViewModel — main ViewModel for the Suno Local Player app.
 *
 * Manages playlist sync, cookie status, local playback, and user-created custom
 * playlists assembled from existing Suno tracks. Custom playlist mutations write
 * immediately to LibraryStore so order survives app restarts.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SunoLocalApplication
    private val cookieStore = CookieStore(application)
    private val apiClient = SunoApiClient(cookieStore = cookieStore)
    private val libraryStore = app.libraryStore
    private val syncSummaryStore = SyncSummaryStore(application)
    val audioPlayer = LocalAudioPlayer(application)

    private val _playlists = MutableStateFlow<List<SunoPlaylist>>(emptyList())
    val playlists: StateFlow<List<SunoPlaylist>> = _playlists.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<SunoPlaylist?>(null)
    val selectedPlaylist: StateFlow<SunoPlaylist?> = _selectedPlaylist.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow<SyncSummary?>(null)
    val lastSyncSummary: StateFlow<SyncSummary?> = _lastSyncSummary.asStateFlow()

    private val _cookieConfigured = MutableStateFlow(false)
    val cookieConfigured: StateFlow<Boolean> = _cookieConfigured.asStateFlow()

    private val _cookieStatus = MutableStateFlow("Cookie status: Missing")
    val cookieStatus: StateFlow<String> = _cookieStatus.asStateFlow()

    private val _connectionTestStatus = MutableStateFlow("")
    val connectionTestStatus: StateFlow<String> = _connectionTestStatus.asStateFlow()

    private val _addCreatorUrl = MutableStateFlow("")
    val addCreatorUrl: StateFlow<String> = _addCreatorUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateCheckRunning = MutableStateFlow(false)
    val updateCheckRunning: StateFlow<Boolean> = _updateCheckRunning.asStateFlow()

    init {
        refreshCookieStatus()
        loadLibrary()
        refreshLastSyncSummary()
    }

    /** Reload the persisted last-sync summary (called on init and after sync work finishes). */
    fun refreshLastSyncSummary() {
        _lastSyncSummary.value = syncSummaryStore.load()
    }

    fun saveCookie(cookie: String) {
        cookieStore.saveCookie(cookie.trim())
        refreshCookieStatus()
        _connectionTestStatus.value = "Cookie saved — tap Test Connection to verify playlist access."
        _errorMessage.value = null
    }

    fun captureWebViewCookie(): Boolean {
        val captured = WebViewCookieBridge.refreshCookieStore(cookieStore)
        if (captured) {
            refreshCookieStatus()
            _connectionTestStatus.value = "Suno WebView cookie captured — tap Test Connection to verify."
            _errorMessage.value = null
        }
        return captured
    }

    fun testConnection() {
        if (!cookieStore.isConfigured()) {
            refreshCookieStatus()
            _connectionTestStatus.value = "Missing — login or paste a Suno cookie first."
            return
        }

        _connectionTestStatus.value = "Testing playlist/me…"
        viewModelScope.launch {
            try {
                apiClient.testConnection()
                _connectionTestStatus.value = "Valid — Suno playlist/me returned HTTP 200."
                refreshCookieStatus(valid = true)
            } catch (e: SunoApiException) {
                val status = when (e.httpCode) {
                    401 -> "Expired — Suno returned HTTP 401. Tap Login to Suno to refresh."
                    403 -> "Rejected — Suno returned HTTP 403 for this session."
                    else -> "Failed — ${e.message ?: "Suno connection test failed"}"
                }
                _connectionTestStatus.value = status
                refreshCookieStatus(valid = false)
            } catch (e: Exception) {
                _connectionTestStatus.value = "Failed — ${e.message ?: "Suno connection test failed"}"
                refreshCookieStatus(valid = false)
            }
        }
    }

    fun clearCookie() {
        cookieStore.clearCookie()
        refreshCookieStatus()
        _connectionTestStatus.value = "Cookie cleared."
    }

    /**
     * Checks GitHub (Duskript/Suno-player) for a newer release than the installed
     * version. Results land in [updateInfo]; failures are reported as a message
     * instead of crashing.
     */
    fun checkForUpdates() {
        if (_updateCheckRunning.value) return
        _updateCheckRunning.value = true
        _updateInfo.value = AppUpdateInfo.failure(BuildConfig.VERSION_NAME, "Checking for updates…")
        viewModelScope.launch {
            try {
                _updateInfo.value = GitHubUpdateChecker().checkForUpdates(BuildConfig.VERSION_NAME)
            } catch (e: Exception) {
                _updateInfo.value = AppUpdateInfo.failure(
                    BuildConfig.VERSION_NAME,
                    "Update check failed: ${e.message ?: "network error"}"
                )
            } finally {
                _updateCheckRunning.value = false
            }
        }
    }

    /**
     * Opens the GitHub release page in the system browser (or any handler that
     * can open ACTION_VIEW https URLs). No silent APK install — Android forbids
     * normal apps from installing APKs without user consent.
     */
    fun openUpdatePage(context: Context) {
        openUrl(context, _updateInfo.value?.releaseUrl ?: GitHubUpdateChecker.RELEASES_PAGE_URL)
    }

    /** Opens the direct APK download URL from the latest release, if available. */
    fun openUpdateDownload(context: Context) {
        val url = _updateInfo.value?.assetDownloadUrl ?: return
        openUrl(context, url)
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            _errorMessage.value = "No app available to open $url"
        }
    }

    private fun refreshCookieStatus(valid: Boolean? = null) {
        val configured = cookieStore.isConfigured()
        _cookieConfigured.value = configured
        _cookieStatus.value = when {
            !configured -> "Missing"
            valid == true -> "Valid"
            valid == false -> "Expired or rejected"
            else -> "Configured — not tested"
        }
    }

    fun onAddCreatorUrlChanged(url: String) {
        _addCreatorUrl.value = url
    }

    private fun loadLibrary() {
        val stored = libraryStore.loadPlaylists()
        _playlists.value = stored.map { it.toDomain() }
        _selectedPlaylist.value?.let { current ->
            _selectedPlaylist.value = _playlists.value.find { it.id == current.id }
        }
    }

    fun selectPlaylist(playlistId: String) {
        _selectedPlaylist.value = _playlists.value.find { it.id == playlistId }
    }

    fun clearSelection() {
        _selectedPlaylist.value = null
    }

    fun createCustomPlaylist(title: String) {
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: "My Suno Mix"
        val playlist = SunoPlaylist(
            id = "custom-${System.currentTimeMillis()}",
            title = cleanTitle,
            creatorName = "You",
            tracks = emptyList(),
            isCustom = true,
            lastSyncedAtEpochMs = System.currentTimeMillis()
        )
        libraryStore.upsertPlaylist(playlist)
        loadLibrary()
        _errorMessage.value = null
    }

    fun addTrackToCustomPlaylist(trackId: String, targetPlaylistId: String) {
        val target = _playlists.value.find { it.id == targetPlaylistId && it.isCustom }
        if (target == null) {
            _errorMessage.value = "Create a custom playlist first"
            return
        }
        val sourceTrack = _playlists.value.asSequence()
            .flatMap { it.tracks.asSequence() }
            .firstOrNull { it.id == trackId }
        if (sourceTrack == null) {
            _errorMessage.value = "Track not found"
            return
        }
        if (target.tracks.any { it.id == trackId }) {
            _errorMessage.value = "Track is already in ${target.title}"
            return
        }
        val updated = target.copy(
            tracks = target.tracks + sourceTrack.copy(playlistId = target.id),
            lastSyncedAtEpochMs = System.currentTimeMillis()
        )
        libraryStore.upsertPlaylist(updated)
        loadLibrary()
    }

    fun moveTrackInPlaylist(playlistId: String, trackId: String, direction: Int) {
        val playlist = _playlists.value.find { it.id == playlistId && it.isCustom } ?: return
        val tracks = playlist.tracks.toMutableList()
        val from = tracks.indexOfFirst { it.id == trackId }
        if (from < 0) return
        val to = (from + direction).coerceIn(0, tracks.lastIndex)
        if (from == to) return
        val moved = tracks.removeAt(from)
        tracks.add(to, moved)
        libraryStore.upsertPlaylist(
            playlist.copy(tracks = tracks, lastSyncedAtEpochMs = System.currentTimeMillis())
        )
        loadLibrary()
    }

    fun removeTrackFromCustomPlaylist(playlistId: String, trackId: String) {
        val playlist = _playlists.value.find { it.id == playlistId && it.isCustom } ?: return
        libraryStore.upsertPlaylist(
            playlist.copy(
                tracks = playlist.tracks.filterNot { it.id == trackId },
                lastSyncedAtEpochMs = System.currentTimeMillis()
            )
        )
        loadLibrary()
    }

    // Batch 4 — playlist manager: rename/delete custom playlists, duplicate any
    // playlist into a new custom mix, and open/share the source URL of
    // URL-saved playlists. All mutations go through LibraryStore (the single
    // persistence layer); loadLibrary() also re-resolves the open selection so
    // the details page reflects renames/deletes immediately.

    /** Rename a custom playlist. Blank/whitespace input keeps the current title. */
    fun renameCustomPlaylist(playlistId: String, newTitle: String) {
        val playlist = _playlists.value.find { it.id == playlistId && it.isCustom } ?: return
        val cleanTitle = cleanPlaylistTitle(newTitle, fallback = playlist.title)
        if (cleanTitle == playlist.title) return
        libraryStore.upsertPlaylist(
            playlist.copy(title = cleanTitle, lastSyncedAtEpochMs = System.currentTimeMillis())
        )
        loadLibrary()
    }

    /** Delete a custom playlist; closes the details page if it was open. */
    fun deleteCustomPlaylist(playlistId: String) {
        val playlist = _playlists.value.find { it.id == playlistId && it.isCustom } ?: return
        libraryStore.removePlaylist(playlistId)
        if (_selectedPlaylist.value?.id == playlistId) {
            _selectedPlaylist.value = null
        }
        loadLibrary()
    }

    /**
     * Duplicate any playlist into a new custom mix, copying track list/order.
     * [title] defaults to "<original> Copy"; the result is always a custom
     * playlist (isCustom = true, creatorName = "You", unique custom- id).
     */
    fun duplicatePlaylist(playlistId: String, title: String? = null) {
        val source = _playlists.value.find { it.id == playlistId } ?: return
        val newId = "custom-${System.currentTimeMillis()}"
        val defaultTitle = defaultDuplicateTitle(source.title)
        val newTitle = cleanPlaylistTitle(title.orEmpty(), fallback = defaultTitle)
        val duplicate = buildDuplicatePlaylist(
            source = source,
            newId = newId,
            newTitle = newTitle,
            createdAtEpochMs = System.currentTimeMillis()
        )
        libraryStore.upsertPlaylist(duplicate)
        loadLibrary()
    }

    /** Open the Suno source URL of a URL-saved playlist in the system browser. */
    fun openPlaylistSource(playlistId: String) {
        val url = _playlists.value.find { it.id == playlistId }?.sourceUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            _errorMessage.value = "No app available to open $url"
        }
    }

    /** Share the Suno source URL of a URL-saved playlist via the share sheet. */
    fun sharePlaylistSource(playlistId: String) {
        val url = _playlists.value.find { it.id == playlistId }?.sourceUrl ?: return
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            val chooser = Intent.createChooser(sendIntent, "Share playlist")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(chooser)
        } catch (e: Exception) {
            _errorMessage.value = "No app available to share this playlist"
        }
    }

    fun resyncMine() {
        if (!_cookieConfigured.value) {
            _syncStatus.value = SyncStatus.error("Configure your cookie first")
            return
        }

        _syncStatus.value = SyncStatus.RUNNING

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<SunoDownloadWorker>()
            .setInputData(androidx.work.workDataOf(SunoDownloadWorker.KEY_MODE to SunoDownloadWorker.MODE_MY_LIBRARY))
            .addTag("suno_sync")
            .build()

        androidx.work.WorkManager.getInstance(getApplication()).enqueue(workRequest)
        observeSyncWork(workRequest.id, successMessage = "Sync complete")
    }

    fun saveCreatorPlaylist(url: String) {
        if (url.isBlank()) {
            _errorMessage.value = "Enter a playlist URL"
            return
        }

        _syncStatus.value = SyncStatus.RUNNING
        _errorMessage.value = null

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<SunoDownloadWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    SunoDownloadWorker.KEY_MODE to SunoDownloadWorker.MODE_PLAYLIST_URL,
                    SunoDownloadWorker.KEY_PLAYLIST_URL to url.trim()
                )
            )
            .addTag("suno_sync")
            .build()

        androidx.work.WorkManager.getInstance(getApplication()).enqueue(workRequest)
        observeSyncWork(workRequest.id, successMessage = "Playlist saved") {
            _addCreatorUrl.value = ""
        }
    }

    private fun observeSyncWork(
        id: java.util.UUID,
        successMessage: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(id)
                .collect { info ->
                    val workInfo = info ?: return@collect
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress
                            _syncStatus.value = SyncStatus.progress(
                                progress = progress.getFloat("progress", 0f),
                                message = progress.getString("message") ?: "Syncing…"
                            )
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _syncStatus.value = SyncStatus.IDLE.copy(lastMessage = successMessage)
                            onSuccess()
                            loadLibrary()
                            refreshLastSyncSummary()
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            val rawError = workInfo.outputData.getString("error") ?: "Unknown error"
                            // Surface clear re-login guidance when the failure is a
                            // cookie expiry (HTTP 401/403 or expired/unauthorized message).
                            val error = if (isCookieAuthError(rawError)) COOKIE_EXPIRED_GUIDANCE else rawError
                            _syncStatus.value = SyncStatus.error(error)
                            refreshLastSyncSummary()
                        }
                        else -> { /* enqueued, blocked, cancelled */ }
                    }
                }
        }
    }

    fun playPlaylist(playlistId: String) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        val playableTracks = playlist.tracks.filter { it.isPlayable }
        if (playableTracks.isEmpty()) return
        audioPlayer.setQueue(playableTracks)
        audioPlayer.playPause()
    }

    fun playTrack(trackId: String) {
        val playlist = _selectedPlaylist.value ?: return
        if (playlist.tracks.none { it.id == trackId }) return
        val playableTracks = playlist.tracks.filter { it.isPlayable }
        if (playableTracks.isEmpty()) return
        audioPlayer.setQueue(playableTracks, startTrackId = trackId)
        audioPlayer.playPause()
    }

    fun addTrackToQueue(trackId: String) {
        val track = _playlists.value.asSequence()
            .flatMap { it.tracks.asSequence() }
            .firstOrNull { it.id == trackId }
        if (track == null) {
            _errorMessage.value = "Track not found"
            return
        }
        if (!track.isPlayable) {
            _errorMessage.value = "Download this track before adding it to the queue"
            return
        }
        audioPlayer.addToQueue(track)
        _errorMessage.value = null
    }

    fun playQueuedTrack(trackId: String) = audioPlayer.playQueueTrack(trackId)

    fun removeTrackFromQueue(trackId: String) = audioPlayer.removeFromQueue(trackId)

    fun moveQueuedTrack(trackId: String, direction: Int) = audioPlayer.moveQueuedTrack(trackId, direction)

    fun toggleShuffle() = audioPlayer.toggleShuffle()
    fun playPause() = audioPlayer.playPause()
    fun next() = audioPlayer.next()
    fun previous() = audioPlayer.previous()

    // Playback polish (Batch 1): position/duration/seek/repeat/error surface.
    val playbackPositionMs: StateFlow<Long> = audioPlayer.playbackPositionMs
    val playbackDurationMs: StateFlow<Long> = audioPlayer.playbackDurationMs
    val playbackProgress: StateFlow<Float> = audioPlayer.playbackProgress
    val repeatMode: StateFlow<Int> = audioPlayer.repeatMode
    val playbackErrorMessage: StateFlow<String?> = audioPlayer.playbackErrorMessage

    fun seekToProgress(progress: Float) = audioPlayer.seekToProgress(progress)
    fun toggleRepeatMode() = audioPlayer.toggleRepeatMode()
    fun clearPlaybackError() = audioPlayer.clearPlaybackError()

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}

private fun SunoPlaylistJson.toDomain(): SunoPlaylist = SunoPlaylist(
    id = id,
    title = title,
    creatorName = creatorName,
    sourceUrl = sourceUrl,
    tracks = tracks.map { it.toDomain() },
    savedFromOtherCreator = savedFromOtherCreator,
    isCustom = isCustom,
    lastSyncedAtEpochMs = lastSyncedAtEpochMs
)

private fun SunoTrackJson.toDomain(): SunoTrack = SunoTrack(
    id = id,
    title = title,
    audioUrl = audioUrl,
    localPath = localPath,
    imageUrl = imageUrl,
    durationMs = durationMs,
    playlistId = playlistId,
    creatorName = creatorName,
    sourceUrl = sourceUrl,
    lyrics = lyrics,
    stylePrompt = stylePrompt,
    descriptionPrompt = descriptionPrompt,
    downloadedAtEpochMs = downloadedAtEpochMs
)
