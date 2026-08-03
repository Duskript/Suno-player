package com.duskript.sunolocal.features.library.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duskript.sunolocal.BuildConfig
import com.duskript.sunolocal.SunoLocalApplication
import com.duskript.sunolocal.core.auth.AuthFlowStatus
import com.duskript.sunolocal.core.auth.CookieStore
import com.duskript.sunolocal.core.auth.WebViewCookieBridge
import com.duskript.sunolocal.core.download.SunoDownloadWorker
import com.duskript.sunolocal.core.network.SunoApiClient
import com.duskript.sunolocal.core.network.SunoApiException
import com.duskript.sunolocal.core.player.LocalAudioPlayer
import com.duskript.sunolocal.core.player.PlaybackDiagnostics
import com.duskript.sunolocal.core.player.PlaybackSource
import com.duskript.sunolocal.core.player.PlaybackState
import com.duskript.sunolocal.core.player.PlaybackStateStore
import com.duskript.sunolocal.core.player.ResumePlaybackStatus
import com.duskript.sunolocal.core.storage.FavoritesStore
import com.duskript.sunolocal.core.storage.ImportResult
import com.duskript.sunolocal.core.storage.LibraryBackup
import com.duskript.sunolocal.core.storage.LibraryBackupException
import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.duskript.sunolocal.core.storage.SunoTrackJson
import com.duskript.sunolocal.core.storage.SyncSummaryStore
import com.duskript.sunolocal.core.storage.emptySyncedPlaylists
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
    private val favoritesStore = FavoritesStore(application)
    private val playbackStateStore = PlaybackStateStore(application)
    val audioPlayer = LocalAudioPlayer(application)

    /**
     * Stored (persisted) playlists only — the source of truth for stats and
     * smart-mix derivation. [playlists] additionally merges derived smart mixes
     * on top so they behave like normal playlists in the UI.
     */
    private val _storedPlaylists = MutableStateFlow<List<SunoPlaylist>>(emptyList())
    val storedPlaylists: StateFlow<List<SunoPlaylist>> = _storedPlaylists.asStateFlow()

    private val _playlists = MutableStateFlow<List<SunoPlaylist>>(emptyList())
    val playlists: StateFlow<List<SunoPlaylist>> = _playlists.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<SunoPlaylist?>(null)
    val selectedPlaylist: StateFlow<SunoPlaylist?> = _selectedPlaylist.asStateFlow()

    // v0.1.15 — favorites/starred tracks, persisted app-private as JSON.
    private val _favoriteTrackIds = MutableStateFlow(favoritesStore.loadFavoriteTrackIds())
    val favoriteTrackIds: StateFlow<Set<String>> = _favoriteTrackIds.asStateFlow()

    // v0.1.15 — resume-where-left-off snapshot + hidden-playlist restore count.
    private val _lastPlaybackState = MutableStateFlow(playbackStateStore.load())
    val lastPlaybackState: StateFlow<PlaybackState?> = _lastPlaybackState.asStateFlow()

    private val _hiddenPlaylistCount = MutableStateFlow(libraryStore.loadHiddenPlaylistIds().size)
    val hiddenPlaylistCount: StateFlow<Int> = _hiddenPlaylistCount.asStateFlow()

    // v0.1.18 — bulk cleanup: synced (non-custom, non-smart-mix) playlists with
    // zero tracks that can be hidden from the Settings screen in one tap.
    private val _emptySyncedPlaylistCount = MutableStateFlow(0)
    val emptySyncedPlaylistCount: StateFlow<Int> = _emptySyncedPlaylistCount.asStateFlow()

    // Batch 5 — creator browsing is local-only navigation state: selecting a
    // creator name shows their playlists/tracks from the in-memory library.
    // Nothing here triggers network calls or persistence writes.
    private val _selectedCreator = MutableStateFlow<String?>(null)
    val selectedCreator: StateFlow<String?> = _selectedCreator.asStateFlow()

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

    /** Clear the current heads-up message after the user acknowledges it. */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

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

    /**
     * Capture the in-app WebView cookie jar into CookieStore using the safe
     * no-overwrite-older-cookie rule. Returns true when a WebView __session was
     * captured (whether or not it replaced the stored cookie); the status
     * message distinguishes saved vs. refused-because-stored-is-newer.
     */
    fun captureWebViewCookie(): Boolean {
        val result = WebViewCookieBridge.refreshCookieStore(cookieStore)
        when {
            result.saved -> {
                refreshCookieStatus()
                _connectionTestStatus.value = "Suno WebView cookie captured — tap Test Connection to verify."
                _errorMessage.value = null
            }
            result.captured -> {
                refreshCookieStatus()
                _connectionTestStatus.value =
                    "WebView cookie found but not saved (stored cookie is newer or expiry unknown) — keeping stored cookie."
                _errorMessage.value = null
            }
        }
        return result.saved || result.captured
    }

    /**
     * v0.1.23 — capture the in-app WebView cookie jar and automatically
     * validate it against playlist/me. This replaces the old two-step
     * "Done, then Test Connection": Settings → Login to Suno → Done now shows
     * "Captured, validating…" and then "Valid — Suno playlist/me returned
     * HTTP 200." (or login-required guidance) with no extra tap. Library data
     * is never cleared, and no cookie/JWT material is ever shown.
     */
    fun captureAndValidateWebViewCookie() {
        _connectionTestStatus.value = AuthFlowStatus.STATUS_CAPTURING
        val result = WebViewCookieBridge.refreshCookieStore(cookieStore)

        if (!result.saved && !result.captured) {
            // No WebView __session at all.
            if (!cookieStore.isConfigured()) {
                refreshCookieStatus(valid = false)
                _connectionTestStatus.value = AuthFlowStatus.STATUS_NO_WEBVIEW_NO_STORED
                _errorMessage.value = null
                return
            }
            // A stored cookie exists — validate it; it may still be usable.
            _connectionTestStatus.value = AuthFlowStatus.STATUS_NO_NEW_WEBVIEW_VALIDATING
        } else {
            refreshCookieStatus()
            _connectionTestStatus.value = if (result.saved) {
                AuthFlowStatus.STATUS_CAPTURED_VALIDATING
            } else {
                AuthFlowStatus.STATUS_CAPTURED_KEPT_STORED
            }
            _errorMessage.value = null
        }

        viewModelScope.launch {
            try {
                apiClient.testConnection()
                _connectionTestStatus.value = AuthFlowStatus.STATUS_VALID
                refreshCookieStatus(valid = true)
            } catch (e: SunoApiException) {
                _connectionTestStatus.value = AuthFlowStatus.statusForRejection(e.httpCode)
                refreshCookieStatus(valid = false)
            } catch (e: Exception) {
                _connectionTestStatus.value = AuthFlowStatus.statusForFailure(e.message)
                refreshCookieStatus(valid = false)
            }
        }
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
                _connectionTestStatus.value = AuthFlowStatus.STATUS_VALID
                refreshCookieStatus(valid = true)
            } catch (e: SunoApiException) {
                _connectionTestStatus.value = AuthFlowStatus.statusForRejection(e.httpCode)
                refreshCookieStatus(valid = false)
            } catch (e: Exception) {
                _connectionTestStatus.value = AuthFlowStatus.statusForFailure(e.message)
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

    /**
     * Refresh the Settings cookie status row. When the cookie is configured but
     * not yet validated, append the parsed expiry countdown (or unknown-expiry
     * note) so Settings shows more than "Configured" without any UI changes.
     */
    private fun refreshCookieStatus(valid: Boolean? = null) {
        val configured = cookieStore.isConfigured()
        _cookieConfigured.value = configured
        val freshness = cookieStore.freshness()
        val base = when {
            !configured -> "Missing"
            valid == true -> "Valid"
            valid == false -> "Expired or rejected"
            else -> "Configured — not tested"
        }
        _cookieStatus.value = if (configured && valid == null && freshness.hasSession) {
            "$base (${freshness.statusLabel})"
        } else {
            base
        }
    }

    fun onAddCreatorUrlChanged(url: String) {
        _addCreatorUrl.value = url
    }

    private fun loadLibrary() {
        val hiddenPlaylistIds = libraryStore.loadHiddenPlaylistIds()
        val stored = libraryStore.loadPlaylists()
            .filterNot { it.id in hiddenPlaylistIds && !it.isCustom }
            .map { it.toDomain() }
        _storedPlaylists.value = stored
        _hiddenPlaylistCount.value = hiddenPlaylistIds.size
        // v0.1.18 — bulk cleanup count: synced (non-custom, non-smart-mix)
        // playlists with zero tracks. Smart mixes are never stored, so the
        // isSmartMixId check is defensive only.
        _emptySyncedPlaylistCount.value = stored.count {
            !it.isCustom && !isSmartMixId(it.id) && it.trackCount == 0
        }
        recomputePlaylists()
    }

    /**
     * Rebuilds the displayed playlist list = derived smart mixes (favorites,
     * recently added, streaming-only) on top of the stored library. Smart mixes
     * are never written to suno_library.json; the open selection is re-resolved
     * so details pages survive recomposition.
     */
    private fun recomputePlaylists() {
        val hiddenPlaylistIds = libraryStore.loadHiddenPlaylistIds()
        val smartMixes = buildSmartMixes(_storedPlaylists.value, _favoriteTrackIds.value)
            .filterNot { it.id in hiddenPlaylistIds }
        _playlists.value = smartMixes + _storedPlaylists.value
        _selectedPlaylist.value?.let { current ->
            _selectedPlaylist.value = _playlists.value.find { it.id == current.id }
        }
    }

    // v0.1.15 — favorites/starred tracks.

    /** True when [trackId] is currently favorited. */
    fun isFavoriteTrack(trackId: String): Boolean = trackId in _favoriteTrackIds.value

    /** Toggle the star on [trackId]; persists immediately to FavoritesStore. */
    fun toggleFavoriteTrack(trackId: String) {
        val favorite = trackId !in _favoriteTrackIds.value
        favoritesStore.setFavoriteTrack(trackId, favorite)
        _favoriteTrackIds.value = favoritesStore.loadFavoriteTrackIds()
        // Smart mixes (smart-favorites) depend on the favorites set.
        recomputePlaylists()
    }

    // v0.1.15 — playlist cleanup restore.

    /**
     * Clear every hidden-playlist removal so hidden synced playlists are
     * eligible to come back on the next Resync Library.
     */
    fun restoreHiddenPlaylists() {
        libraryStore.clearHiddenPlaylists()
        _hiddenPlaylistCount.value = 0
        _errorMessage.value = "Hidden playlist removals cleared — tap Resync Library to bring them back."
        loadLibrary()
    }

    // v0.1.18 — bulk cleanup tool.

    /**
     * Hide every synced (non-custom, non-smart-mix) playlist with zero tracks
     * from the current stored library in one pass. Empty synced playlists are
     * usually API/server placeholders Suno reports but that never carry audio.
     * Hiding only affects the local library — Resync respects hidden ids, and
     * the user must tap Resync Library to refresh after restoring. Confirmation
     * dialogs are UI-side; this method just performs the cleanup and reports a
     * one-shot message through [errorMessage].
     */
    fun hideEmptySyncedPlaylists() {
        val candidates = emptySyncedPlaylists(libraryStore.loadPlaylists())
        if (candidates.isEmpty()) {
            _errorMessage.value = "No empty synced playlists to hide."
            return
        }
        val hidden = libraryStore.hideSyncedPlaylists(candidates.map { it.id })
        _errorMessage.value = "Hidden $hidden empty playlist(s) — run Resync Library to refresh."
        loadLibrary()
    }

    // v0.1.15 — resume where left off.

    /**
     * Rebuilds the queue from the saved [PlaybackState]: resolves queue track
     * ids against the current library, starts at the saved track, seeks near
     * the saved position, then plays. Batch E: never a silent no-op — when the
     * saved track/queue cannot be rebuilt, a visible [errorMessage] explains
     * why (track missing from library / queue has no playable tracks / saved
     * track unplayable because its local file is missing and there is no
     * audioUrl). Playback never starts without the user tapping Resume.
     */
    fun resumeLastPlayback() {
        val state = _lastPlaybackState.value
        if (state == null) {
            _errorMessage.value = "Nothing to resume — no saved playback session."
            return
        }
        val allTracks = _playlists.value.asSequence()
            .flatMap { it.tracks.asSequence() }
            .distinctBy { it.id }
            .toList()
        when (val status = ResumePlaybackStatus.evaluate(state, allTracks)) {
            is ResumePlaybackStatus.Ready -> {
                audioPlayer.setQueue(status.queue, startTrackId = status.startTrackId)
                audioPlayer.seekTo(state.positionMs.coerceAtLeast(0L))
                audioPlayer.playPause()
            }
            is ResumePlaybackStatus.SavedTrackUnplayable -> {
                // The saved track's local file is gone and it has no audioUrl,
                // but the rest of the queue is still playable — resume from the
                // first playable track and explain what happened.
                _errorMessage.value = PlaybackSource.missingLocalAudioMessage(status.trackTitle)
                audioPlayer.setQueue(status.queue, startTrackId = status.startTrackId)
                audioPlayer.seekTo(state.positionMs.coerceAtLeast(0L))
                audioPlayer.playPause()
            }
            is ResumePlaybackStatus.SavedTrackMissing -> {
                _errorMessage.value =
                    "The saved track is no longer in your library — resync your playlists to restore it."
            }
            is ResumePlaybackStatus.NoPlayableTracks -> {
                _errorMessage.value =
                    "Saved queue has no playable tracks — local audio is missing and there is no network fallback."
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        _selectedPlaylist.value = _playlists.value.find { it.id == playlistId }
    }

    fun clearSelection() {
        _selectedPlaylist.value = null
    }

    // Batch 5 — creator browsing navigation (local only).

    /** Open the local Creator view for [name]; no network or persistence. */
    fun selectCreator(name: String) {
        _selectedCreator.value = name
    }

    /** Close the Creator view; returns to whichever list was underneath. */
    fun clearCreatorSelection() {
        _selectedCreator.value = null
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

    /** Remove a synced playlist locally and keep it hidden across future Resync Library runs. */
    fun removeSyncedPlaylistFromLibrary(playlistId: String) {
        val playlist = _playlists.value.find { it.id == playlistId && !it.isCustom } ?: return
        libraryStore.hideSyncedPlaylist(playlist.id)
        if (_selectedPlaylist.value?.id == playlistId) {
            _selectedPlaylist.value = null
        }
        _errorMessage.value = "Removed ${playlist.title} from Library. It will stay hidden during Resync Library."
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

    // Batch 6 — export / backup via the Storage Access Framework (SAF).
    //
    // Export serializes the persisted library (playlists + tracks + metadata +
    // local file references) to the portable JSON shape; import parses a backup,
    // merges it with the existing library (existing playlist ids win, duplicate
    // track ids dropped), persists, and reloads. M3U export writes a plain-text
    // playlist for one selected playlist. No storage permissions are used — SAF
    // grants URI access per user action, and no cookies/secrets are exported.

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    /** Dismiss the last export/import result message. */
    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    /** Serialize the current persisted library to the portable backup JSON format. */
    fun libraryBackupJson(): String = LibraryBackup.exportLibraryJson(libraryStore.loadPlaylists())

    /** Write the library backup to a SAF-provided Uri; reports the result via [backupMessage]. */
    fun exportLibraryToUri(uri: Uri) {
        try {
            writeToUri(uri, libraryBackupJson())
            _backupMessage.value = "Export complete — ${libraryStore.playlistCount()} playlists backed up."
        } catch (e: Exception) {
            _backupMessage.value = "Export failed: ${e.message ?: "could not write file"}"
        }
    }

    /**
     * Parse + merge a backup JSON string into the library, persist, and reload.
     * Returns the merge result; throws [LibraryBackupException] on invalid JSON
     * (nothing is persisted on failure).
     */
    fun importLibraryJson(json: String): ImportResult {
        val existing = libraryStore.loadPlaylists()
        val result = LibraryBackup.importLibraryJson(existing, json)
        libraryStore.savePlaylists(result.playlists)
        loadLibrary()
        _backupMessage.value = buildString {
            append("Import complete — ")
            append("${result.importedPlaylists} added, ${result.skippedPlaylists} skipped, ")
            append("${result.importedTracks} tracks added, ${result.skippedTracks} duplicate tracks dropped.")
        }
        return result
    }

    /** Read a backup file from a SAF-provided Uri, merge, persist, reload; reports the result. */
    fun importLibraryFromUri(uri: Uri) {
        try {
            val text = getApplication<Application>().contentResolver
                .openInputStream(uri)?.use { input -> input.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("Could not open the selected file")
            importLibraryJson(text)
        } catch (e: LibraryBackupException) {
            _backupMessage.value = "Import failed: ${e.message}"
        } catch (e: Exception) {
            _backupMessage.value = "Import failed: ${e.message ?: "could not read file"}"
        }
    }

    /** M3U text for a single playlist (localPath, else audioUrl/sourceUrl), or null if not found. */
    fun playlistM3u(playlistId: String): String? {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return null
        return LibraryBackup.exportPlaylistM3u(playlist)
    }

    /** Write a playlist's M3U to a SAF-provided Uri; reports the result via [backupMessage]. */
    fun exportM3uToUri(uri: Uri, playlistId: String) {
        try {
            val m3u = playlistM3u(playlistId)
                ?: throw IllegalStateException("Playlist not found")
            writeToUri(uri, m3u)
            _backupMessage.value = "M3U export complete."
        } catch (e: Exception) {
            _backupMessage.value = "M3U export failed: ${e.message ?: "could not write file"}"
        }
    }

    private fun writeToUri(uri: Uri, content: String) {
        val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open the selected file")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    fun resyncMine() {
        // Try to pick up an in-app WebView login before declaring the app
        // unauthenticated. This prevents the common UX trap where the user logs
        // into Suno, returns to Library, taps sync, and the old cookieConfigured
        // state has not yet been refreshed.
        captureWebViewCookie()

        if (!_cookieConfigured.value) {
            _syncStatus.value = SyncStatus.error(COOKIE_EXPIRED_GUIDANCE)
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

    /**
     * Batch 5 — play a track found anywhere in the library (used by the Creator
     * view and by track details opened outside the selected playlist, e.g. a
     * similar-track). The queue is the library-wide playable track list so
     * playback continues across playlists; dedupe by id because a track can
     * appear in several playlists.
     */
    fun playTrackFromLibrary(trackId: String) {
        val allTracks = _playlists.value.asSequence()
            .flatMap { it.tracks.asSequence() }
            .distinctBy { it.id }
            .toList()
        if (allTracks.none { it.id == trackId }) return
        val playableTracks = allTracks.filter { it.isPlayable }
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
    // v0.1.21 — next/previous command availability for the bottom player.
    val hasPrevious: StateFlow<Boolean> = audioPlayer.hasPrevious
    val hasNext: StateFlow<Boolean> = audioPlayer.hasNext
    // v0.1.20 — playback lifetime diagnostics for Settings (Batch A).
    val playbackDiagnostics: StateFlow<PlaybackDiagnostics> = audioPlayer.playbackDiagnostics

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
    tags = tags,
    mood = mood,
    genre = genre,
    downloadedAtEpochMs = downloadedAtEpochMs
)
