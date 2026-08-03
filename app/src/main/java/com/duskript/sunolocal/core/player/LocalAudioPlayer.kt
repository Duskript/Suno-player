package com.duskript.sunolocal.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.duskript.sunolocal.core.widget.SunoPlaybackWidgetUpdater
import com.duskript.sunolocal.domain.model.SunoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LocalAudioPlayer — UI-facing wrapper around a process-wide Media3 ExoPlayer.
 *
 * Playback is backed by SunoPlaybackService/MediaSessionService so music keeps
 * playing when the Activity loses focus, the screen locks, or the user switches
 * apps. The wrapper also exposes queue mutations for the Compose queue sheet.
 *
 * Playback polish (Batch 1): exposes seek/scrub state (position, duration,
 * progress), repeat-mode cycling, and playback-error surfacing with an
 * auto-skip to the next playable item. Position/duration/progress are refreshed
 * on player events, while playing, and after seeks via a main-looper runnable.
 *
 * Resume where left off (v0.1.15): meaningful playback events (queue changes,
 * play/pause, seek, media transitions) plus a throttled ~5s cadence while
 * playing persist a PlaybackState snapshot via PlaybackStateStore so the
 * Library page can offer a Resume card after an app restart.
 */
class LocalAudioPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val exoPlayer: ExoPlayer = SunoPlaybackEngine.player(appContext)
    private val playbackStateStore = PlaybackStateStore(appContext)

    private val _currentTrack = MutableStateFlow<SunoTrack?>(null)
    val currentTrack: StateFlow<SunoTrack?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(exoPlayer.isPlaying)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(exoPlayer.shuffleModeEnabled)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    // v0.1.21 — next/previous command availability for the UI, derived from
    // the shared player's queue position. Buttons in the bottom player and the
    // Media3 notification/lockscreen controls share this truth: no next item
    // means next is unavailable, not just dimmed.
    private val _hasPrevious = MutableStateFlow(exoPlayer.hasPreviousMediaItem())
    val hasPrevious: StateFlow<Boolean> = _hasPrevious.asStateFlow()

    private val _hasNext = MutableStateFlow(exoPlayer.hasNextMediaItem())
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()

    private val _queue = MutableStateFlow<List<SunoTrack>>(emptyList())
    val queue: StateFlow<List<SunoTrack>> = _queue.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _repeatMode = MutableStateFlow(exoPlayer.repeatMode)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _playbackErrorMessage = MutableStateFlow<String?>(null)
    val playbackErrorMessage: StateFlow<String?> = _playbackErrorMessage.asStateFlow()

    // v0.1.20 — playback lifetime diagnostics snapshot for Settings. Derived
    // from player state on every event/position refresh; StateFlow dedupes
    // identical snapshots so composition only recomposes on real changes.
    private val _playbackDiagnostics = MutableStateFlow(PlaybackDiagnostics())
    val playbackDiagnostics: StateFlow<PlaybackDiagnostics> = _playbackDiagnostics.asStateFlow()

    private val trackMap = mutableMapOf<String, SunoTrack>()

    /**
     * Media item ids that already errored this session. Guards against infinite
     * error → skip loops on fully-corrupt queues; cleared once a track loads
     * successfully (see [onPlaybackStateChanged]).
     */
    private val failedMediaItemIds = mutableSetOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionRefreshRunnable = object : Runnable {
        override fun run() {
            updatePlaybackPosition()
            // Throttled resume-state persistence: the tick counter fires every
            // 10th 500ms run (~5s) while the runnable is active, so we never
            // write a JSON file on every position refresh.
            positionTicks++
            if (positionTicks % POSITION_PERSIST_EVERY_N_TICKS == 0) {
                persistPlaybackState()
            }
            mainHandler.postDelayed(this, POSITION_REFRESH_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            syncStateFromPlayer()
            updatePlaybackPosition()
            persistPlaybackState()
            // v0.1.20 — transition-only instrumentation (never on the 500ms tick).
            Log.i(TAG, if (isPlaying) "Playback started" else "Playback paused/stopped")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromPlayer()
            updatePlaybackPosition()
            persistPlaybackState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onPlayerError(error: PlaybackException) {
            // v0.1.20 — log the error code before surfacing it, so logcat has
            // the errorCodeName even when the message is long or truncated.
            Log.e(TAG, "Player error: ${error.errorCodeName} — ${error.message ?: "no detail"}")
            handlePlaybackError(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A successful load (STATE_READY) means the queue is healthy again;
            // reset the failed-item guard so future corrupt tracks each get a
            // fresh auto-skip attempt instead of being muted forever.
            if (playbackState == Player.STATE_READY) {
                failedMediaItemIds.clear()
            }
            // v0.1.20 — transition-only instrumentation; STATE_* changes are
            // rare enough to log without spamming (not the 500ms tick).
            Log.i(
                TAG,
                "Player state -> ${PlaybackDiagnostics.playerStateLabel(playbackState)} " +
                    "(isPlaying=${exoPlayer.isPlaying}, playWhenReady=${exoPlayer.playWhenReady})"
            )
        }

        // v0.1.21 — external-controller sync. Notification/lockscreen/Bluetooth/
        // media-key commands act directly on the shared ExoPlayer (through the
        // MediaSession), so every relevant Player.Listener callback refreshes the
        // UI-facing flows and persists the resume snapshot. Signatures verified
        // against the media3-common 1.5.1 AAR (Player$Listener via javap).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncStateFromPlayer()
            updatePlaybackPosition()
            persistPlaybackState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Queue mutations from any source (in-app or a MediaController)
            // land here; keep availability + queue flows in sync.
            syncStateFromPlayer()
            updatePlaybackPosition()
            persistPlaybackState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // External seek / next / previous changes the current item or
            // position directly on the shared player; persist so the Resume
            // snapshot survives a later app restart.
            syncStateFromPlayer()
            updatePlaybackPosition()
            persistPlaybackState()
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            // next/previous availability tracks queue state; refresh the
            // command-availability flows whenever the player's command set
            // changes (e.g. reaching the end of the queue via external next).
            refreshCommandAvailability()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncStateFromPlayer()
            updatePlaybackPosition()
        }
    }

    /** Tick counter for the ~5s throttled resume-state persistence. */
    private var positionTicks = 0

    init {
        SunoPlaybackEngine.mediaSession(appContext)
        syncStateFromPlayer()
        updatePlaybackPosition()
        exoPlayer.addListener(listener)
        mainHandler.post(positionRefreshRunnable)
    }

    fun setQueue(tracks: List<SunoTrack>, startTrackId: String? = null) {
        // Batch E — filter with the real source check (local file exists &&
        // length > 0, else audioUrl), not just SunoTrack.isPlayable, so stale
        // localPath-only tracks are excluded with clear guidance instead of
        // failing inside ExoPlayer with a mysterious URI error.
        val (playableTracks, droppedTracks) = partitionBySource(tracks)
        _queue.value = playableTracks
        rebuildTrackMap(playableTracks)

        val mediaItems = playableTracks.mapNotNull { it.toMediaItem() }
        exoPlayer.setMediaItems(mediaItems)

        val startIndex = if (startTrackId != null) {
            playableTracks.indexOfFirst { it.id == startTrackId }.coerceAtLeast(0)
        } else {
            0
        }

        if (playableTracks.isNotEmpty()) {
            exoPlayer.seekTo(startIndex, 0L)
            exoPlayer.prepare()
            _currentTrack.value = playableTracks.getOrNull(startIndex)
        } else {
            _currentTrack.value = null
        }
        surfaceMissingLocalAudio(droppedTracks)
        syncStateFromPlayer()
        updatePlaybackPosition()
        persistPlaybackState()
    }

    fun addToQueue(track: SunoTrack) {
        if (!PlaybackSource.resolve(track).isPlayable) {
            surfaceMissingLocalAudio(listOf(track))
            return
        }
        val updated = _queue.value + track
        _queue.value = updated
        trackMap[track.id] = track
        exoPlayer.addMediaItem(track.toMediaItem() ?: return)
        if (exoPlayer.mediaItemCount == 1) {
            exoPlayer.prepare()
            _currentTrack.value = track
        }
        syncStateFromPlayer()
        persistPlaybackState()
    }

    fun playQueueTrack(trackId: String) {
        syncStateFromPlayer()
        val index = _queue.value.indexOfFirst { it.id == trackId }
        if (index < 0) return
        ensurePlaybackServiceRunning()
        exoPlayer.seekTo(index, 0L)
        exoPlayer.play()
        _currentTrack.value = _queue.value[index]
        updatePlaybackPosition()
        persistPlaybackState()
    }

    fun removeFromQueue(trackId: String) {
        syncStateFromPlayer()
        val current = _queue.value.toMutableList()
        val index = current.indexOfFirst { it.id == trackId }
        if (index < 0) return
        current.removeAt(index)
        _queue.value = current
        rebuildTrackMap(current)
        exoPlayer.removeMediaItem(index)
        if (current.isEmpty()) {
            _currentTrack.value = null
            playbackStateStore.clear()
        } else if (_currentTrack.value?.id == trackId) {
            _currentTrack.value = current.getOrNull(exoPlayer.currentMediaItemIndex.coerceIn(0, current.lastIndex))
        }
        syncStateFromPlayer()
        persistPlaybackState()
    }

    fun moveQueuedTrack(trackId: String, direction: Int) {
        syncStateFromPlayer()
        val current = _queue.value.toMutableList()
        val from = current.indexOfFirst { it.id == trackId }
        if (from < 0) return
        val to = (from + direction).coerceIn(0, current.lastIndex)
        if (from == to) return
        val moved = current.removeAt(from)
        current.add(to, moved)
        _queue.value = current
        rebuildTrackMap(current)
        exoPlayer.moveMediaItem(from, to)
        syncStateFromPlayer()
    }

    fun playPause() {
        syncStateFromPlayer()
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else if (exoPlayer.mediaItemCount > 0) {
            ensurePlaybackServiceRunning()
            exoPlayer.play()
        }
        syncStateFromPlayer()
        updatePlaybackPosition()
        persistPlaybackState()
    }

    // v0.1.21 — guarded no-ops: next/previous only step when the queue can
    // actually step (hasNextMediaItem/hasPreviousMediaItem). No-op calls still
    // sync state so UI availability and diagnostics stay current, and an
    // invalid command from an external controller can never crash the player.
    fun next() {
        syncStateFromPlayer()
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        } else {
            Log.i(TAG, "next() ignored: no next media item in queue")
        }
        updatePlaybackPosition()
        persistPlaybackState()
        syncStateFromPlayer()
    }

    fun previous() {
        syncStateFromPlayer()
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
        } else {
            Log.i(TAG, "previous() ignored: no previous media item in queue")
        }
        updatePlaybackPosition()
        persistPlaybackState()
        syncStateFromPlayer()
    }

    fun toggleShuffle() {
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    /**
     * Seeks by normalized progress (0f..1f). No-op while duration is unknown
     * (<= 0 or C.TIME_UNSET) so scrubbing can never seek into the void.
     */
    fun seekToProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val duration = exoPlayer.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        exoPlayer.seekTo((duration * clamped).toLong())
        updatePlaybackPosition()
        persistPlaybackState()
    }

    /** Cycles repeat mode: Off → Repeat All → Repeat One → Off. */
    fun toggleRepeatMode() {
        val next = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        exoPlayer.repeatMode = next
        _repeatMode.value = next
    }

    fun clearPlaybackError() {
        _playbackErrorMessage.value = null
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        updatePlaybackPosition()
        persistPlaybackState()
    }

    fun currentPositionMs(): Long = exoPlayer.currentPosition

    fun durationMs(): Long = exoPlayer.duration

    /** Wrapper cleanup only. The foreground media service owns player lifetime. */
    fun release() {
        exoPlayer.removeListener(listener)
        mainHandler.removeCallbacks(positionRefreshRunnable)
        // Intentionally do not release ExoPlayer here; releasing on ViewModel clear
        // kills background playback when the Activity is recreated or backgrounded.
    }

    /**
     * Persists the resume snapshot (track, source playlist, position, queue
     * ids). No-op while nothing is loaded or the queue is empty, so a stale
     * session is never overwritten by an idle player.
     */
    private fun persistPlaybackState() {
        val current = _currentTrack.value ?: return
        val queueIds = _queue.value.map { it.id }
        if (queueIds.isEmpty()) return
        playbackStateStore.save(
            PlaybackState(
                trackId = current.id,
                playlistId = current.playlistId,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                queueIds = queueIds,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Surfaces a clear user-facing message naming the current track when known,
     * then attempts one skip to the next playable item (re-prepare, resume if
     * playback was active). A per-session set of failed media item ids prevents
     * infinite error → skip loops on fully-corrupt queues; the set is cleared
     * once any track loads successfully.
     */
    private fun handlePlaybackError(error: PlaybackException) {
        val currentItem = exoPlayer.currentMediaItem
        val trackName = _currentTrack.value?.title
            ?: currentItem?.mediaMetadata?.title?.toString()
        val source = _currentTrack.value?.let { PlaybackSource.resolve(it) }

        // Batch E — source-aware wording: a track whose local file vanished
        // mid-queue (and has no audioUrl) gets clear resync guidance instead of
        // a raw error code; a local-file playback failure is labelled as such.
        _playbackErrorMessage.value = buildString {
            when {
                source is PlaybackSource.Unavailable && !trackName.isNullOrBlank() -> {
                    append(PlaybackSource.missingLocalAudioMessage(trackName))
                }
                else -> {
                    append("Playback error")
                    if (!trackName.isNullOrBlank()) append(" on \"$trackName\"")
                    if (source is PlaybackSource.Local) append(" (local file)")
                    append(": ")
                    // PlaybackException.errorCodeName is a plain String property here.
                    append(error.errorCodeName)
                    val detail = error.message
                    if (!detail.isNullOrBlank()) {
                        append(" — ")
                        append(detail)
                    }
                }
            }
        }

        val currentItemId = currentItem?.mediaId
        val alreadyFailed = currentItemId != null && currentItemId in failedMediaItemIds
        currentItemId?.let { failedMediaItemIds.add(it) }
        // Already tried skipping past this item; stop auto-skipping to avoid a
        // loop. The error message is still surfaced for the user to act on.
        if (alreadyFailed) return

        val shouldResume = exoPlayer.playWhenReady || exoPlayer.isPlaying
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.prepare()
            if (shouldResume) {
                exoPlayer.play()
            }
        }
        updatePlaybackPosition()
        persistPlaybackState()
    }

    private fun syncStateFromPlayer() {
        _isPlaying.value = exoPlayer.isPlaying
        _shuffleEnabled.value = exoPlayer.shuffleModeEnabled
        refreshCommandAvailability()

        // v0.1.21 — MediaSession controllers see MediaItems without
        // localConfiguration (tags are session-local), so fall back to the
        // mediaId -> track map when a tag is missing. This keeps the queue and
        // current-track flows correct after external next/previous/play.
        val restoredQueue = (0 until exoPlayer.mediaItemCount).mapNotNull { index ->
            val item = exoPlayer.getMediaItemAt(index)
            (item.localConfiguration?.tag as? SunoTrack) ?: trackMap[item.mediaId]
        }
        if (restoredQueue.isNotEmpty()) {
            _queue.value = restoredQueue
            rebuildTrackMap(restoredQueue)
        }

        val currentTrack = exoPlayer.currentMediaItem?.localConfiguration?.tag as? SunoTrack
            ?: trackMap[exoPlayer.currentMediaItem?.mediaId]
            ?: _queue.value.getOrNull(exoPlayer.currentMediaItemIndex)
        _currentTrack.value = currentTrack
        refreshPlaybackDiagnostics()

        // v0.1.26 — keep the home screen widget on the same state-sync path as
        // the UI/notification/lockscreen: track transitions, play/pause, and
        // command availability all flow through syncStateFromPlayer(). This is
        // never called from the 500ms position tick, and the updater de-dupes
        // identical states, so unchanged renders are no-ops.
        SunoPlaybackWidgetUpdater.updateAll(
            appContext,
            currentTrack,
            exoPlayer.isPlaying,
            exoPlayer.hasPreviousMediaItem(),
            exoPlayer.hasNextMediaItem()
        )
    }

    /** Refreshes next/previous command availability from the shared player. */
    private fun refreshCommandAvailability() {
        _hasPrevious.value = exoPlayer.hasPreviousMediaItem()
        _hasNext.value = exoPlayer.hasNextMediaItem()
    }

    /** Refreshes position/duration/progress flows from the player. */
    private fun updatePlaybackPosition() {
        val duration = exoPlayer.duration
        val durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L
        _playbackDurationMs.value = durationMs

        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        _playbackPositionMs.value = position

        _playbackProgress.value = if (durationMs > 0) {
            (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        refreshPlaybackDiagnostics()
    }

    /**
     * v0.1.20 — rebuilds the Settings diagnostics snapshot from current player
     * state. Called on every event/position refresh; StateFlow dedupes equal
     * values so this is cheap and only emits on real changes.
     */
    private fun refreshPlaybackDiagnostics() {
        _playbackDiagnostics.value = PlaybackDiagnostics(
            trackTitle = _currentTrack.value?.title,
            playerStateLabel = PlaybackDiagnostics.playerStateLabel(exoPlayer.playbackState),
            isPlaying = exoPlayer.isPlaying,
            playWhenReady = exoPlayer.playWhenReady,
            queueLength = exoPlayer.mediaItemCount,
            currentIndex = exoPlayer.currentMediaItemIndex,
            repeatModeLabel = PlaybackDiagnostics.repeatModeLabel(exoPlayer.repeatMode),
            shuffleEnabled = exoPlayer.shuffleModeEnabled,
            durationMs = _playbackDurationMs.value,
            positionMs = _playbackPositionMs.value,
            hasPrevious = _hasPrevious.value,
            hasNext = _hasNext.value,
            lastError = _playbackErrorMessage.value,
            keepAlive = SunoPlaybackEngine.shouldKeepPlaybackAlive()
        )
    }

    private fun rebuildTrackMap(tracks: List<SunoTrack>) {
        trackMap.clear()
        tracks.forEach { track -> trackMap[track.id] = track }
    }

    private fun SunoTrack.toMediaItem(): MediaItem? {
        // Batch E — prefer a verified local file URI (Uri.fromFile) over the
        // raw path string; fall back to the network URL only when the local
        // file is missing/zero-byte; null when neither source exists.
        val uri = when (val source = PlaybackSource.resolve(this)) {
            is PlaybackSource.Local -> Uri.fromFile(source.file)
            is PlaybackSource.Streaming -> Uri.parse(source.url)
            is PlaybackSource.Unavailable -> return null
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setTag(this)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(creatorName)
                    // Album helps lockscreen/notification grouping for a playlist
                    // context; "Suno Local" keeps it stable when creator is unknown.
                    .setAlbumTitle(creatorName ?: "Suno Local")
                    .setArtworkUri(imageUrl?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
    }

    /**
     * Batch E — splits tracks by real playback-source availability. Tracks with
     * a stale local path but a valid audioUrl are kept (streaming fallback) and
     * logged so the fallback is never secret; tracks with neither are dropped
     * for [surfaceMissingLocalAudio] to explain.
     */
    private fun partitionBySource(tracks: List<SunoTrack>): Pair<List<SunoTrack>, List<SunoTrack>> {
        val playable = mutableListOf<SunoTrack>()
        val dropped = mutableListOf<SunoTrack>()
        for (track in tracks) {
            when (val source = PlaybackSource.resolve(track)) {
                is PlaybackSource.Local -> playable.add(track)
                is PlaybackSource.Streaming -> {
                    val stalePath = track.localPath?.trim()
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    if (stalePath != null) {
                        // Honest diagnostics: local file is gone, streaming in use.
                        Log.i(
                            TAG,
                            "Local file missing for \"${track.title}\" ($stalePath) — falling back to network audioUrl"
                        )
                    }
                    playable.add(track)
                }
                is PlaybackSource.Unavailable -> dropped.add(track)
            }
        }
        return playable to dropped
    }

    /**
     * Batch E — surfaces a clear user-facing message when tracks were excluded
     * because they have no usable audio source (stale/missing local path and no
     * audioUrl). A later clean queue build clears a previous missing-file
     * message so the dialog does not linger.
     */
    private fun surfaceMissingLocalAudio(droppedTracks: List<SunoTrack>) {
        val missing = droppedTracks.firstOrNull()
        if (missing == null) {
            if (_playbackErrorMessage.value?.startsWith(PlaybackSource.MISSING_LOCAL_AUDIO_PREFIX) == true) {
                _playbackErrorMessage.value = null
            }
            return
        }
        val extra = droppedTracks.size - 1
        _playbackErrorMessage.value = buildString {
            append(PlaybackSource.missingLocalAudioMessage(missing.title))
            if (extra > 0) append(" (+$extra more)")
        }
        Log.w(
            TAG,
            "Excluded ${droppedTracks.size} track(s) from queue: missing local audio with no audioUrl fallback"
        )
    }

    private fun ensurePlaybackServiceRunning() {
        // User taps Play while the app is foreground, so a normal startService()
        // avoids the Android O+ "foreground service did not call startForeground"
        // timing trap. Media3 promotes the session service when playback needs it.
        appContext.startService(Intent(appContext, SunoPlaybackService::class.java))
    }

    private companion object {
        const val POSITION_REFRESH_INTERVAL_MS = 500L
        const val POSITION_PERSIST_EVERY_N_TICKS = 10
        const val TAG = "LocalAudioPlayer"
    }
}
