package com.duskript.sunolocal.core.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
 */
class LocalAudioPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val exoPlayer: ExoPlayer = SunoPlaybackEngine.player(appContext)

    private val _currentTrack = MutableStateFlow<SunoTrack?>(null)
    val currentTrack: StateFlow<SunoTrack?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(exoPlayer.isPlaying)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(exoPlayer.shuffleModeEnabled)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _queue = MutableStateFlow<List<SunoTrack>>(emptyList())
    val queue: StateFlow<List<SunoTrack>> = _queue.asStateFlow()

    private val trackMap = mutableMapOf<String, SunoTrack>()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            syncStateFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncStateFromPlayer()
        }
    }

    init {
        SunoPlaybackEngine.mediaSession(appContext)
        syncStateFromPlayer()
        exoPlayer.addListener(listener)
    }

    fun setQueue(tracks: List<SunoTrack>, startTrackId: String? = null) {
        val playableTracks = tracks.filter { it.isPlayable }
        _queue.value = playableTracks
        rebuildTrackMap(playableTracks)

        val mediaItems = playableTracks.map { it.toMediaItem() }
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
        syncStateFromPlayer()
    }

    fun addToQueue(track: SunoTrack) {
        if (!track.isPlayable) return
        val updated = _queue.value + track
        _queue.value = updated
        trackMap[track.id] = track
        exoPlayer.addMediaItem(track.toMediaItem())
        if (exoPlayer.mediaItemCount == 1) {
            exoPlayer.prepare()
            _currentTrack.value = track
        }
        syncStateFromPlayer()
    }

    fun playQueueTrack(trackId: String) {
        syncStateFromPlayer()
        val index = _queue.value.indexOfFirst { it.id == trackId }
        if (index < 0) return
        ensurePlaybackServiceRunning()
        exoPlayer.seekTo(index, 0L)
        exoPlayer.play()
        _currentTrack.value = _queue.value[index]
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
        } else if (_currentTrack.value?.id == trackId) {
            _currentTrack.value = current.getOrNull(exoPlayer.currentMediaItemIndex.coerceIn(0, current.lastIndex))
        }
        syncStateFromPlayer()
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
    }

    fun next() {
        syncStateFromPlayer()
        exoPlayer.seekToNextMediaItem()
    }

    fun previous() {
        syncStateFromPlayer()
        exoPlayer.seekToPreviousMediaItem()
    }

    fun toggleShuffle() {
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = exoPlayer.currentPosition

    fun durationMs(): Long = exoPlayer.duration

    /** Wrapper cleanup only. The foreground media service owns player lifetime. */
    fun release() {
        exoPlayer.removeListener(listener)
        // Intentionally do not release ExoPlayer here; releasing on ViewModel clear
        // kills background playback when the Activity is recreated or backgrounded.
    }

    private fun syncStateFromPlayer() {
        _isPlaying.value = exoPlayer.isPlaying
        _shuffleEnabled.value = exoPlayer.shuffleModeEnabled

        val restoredQueue = (0 until exoPlayer.mediaItemCount).mapNotNull { index ->
            exoPlayer.getMediaItemAt(index).localConfiguration?.tag as? SunoTrack
        }
        if (restoredQueue.isNotEmpty()) {
            _queue.value = restoredQueue
            rebuildTrackMap(restoredQueue)
        }

        val currentTrack = exoPlayer.currentMediaItem?.localConfiguration?.tag as? SunoTrack
            ?: trackMap[exoPlayer.currentMediaItem?.mediaId]
            ?: _queue.value.getOrNull(exoPlayer.currentMediaItemIndex)
        _currentTrack.value = currentTrack
    }

    private fun rebuildTrackMap(tracks: List<SunoTrack>) {
        trackMap.clear()
        tracks.forEach { track -> trackMap[track.id] = track }
    }

    private fun SunoTrack.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(localPath ?: audioUrl)
        .setTag(this)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(creatorName)
                .setArtworkUri(imageUrl?.let { android.net.Uri.parse(it) })
                .build()
        )
        .build()

    private fun ensurePlaybackServiceRunning() {
        // User taps Play while the app is foreground, so a normal startService()
        // avoids the Android O+ "foreground service did not call startForeground"
        // timing trap. Media3 promotes the session service when playback needs it.
        appContext.startService(Intent(appContext, SunoPlaybackService::class.java))
    }
}
