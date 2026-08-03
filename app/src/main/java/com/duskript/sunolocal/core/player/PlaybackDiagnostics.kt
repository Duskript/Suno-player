package com.duskript.sunolocal.core.player

import androidx.media3.common.Player

/**
 * Playback lifetime diagnostics snapshot (Batch A, v0.1.20).
 *
 * Pure data holder derived from the shared ExoPlayer + MediaSession state so
 * Settings can answer "why did it stop?" without exposing engine internals.
 * Lives in its own file because it is shared by LocalAudioPlayer (producer),
 * LibraryViewModel (passthrough) and SettingsScreen (consumer), and keeps those
 * files free of label-formatting logic. Carries no secrets, URIs, or cookies —
 * only playback state that is safe to show on the Settings screen.
 */
data class PlaybackDiagnostics(
    /** Current track title, or null when nothing is loaded ("none"). */
    val trackTitle: String? = null,
    /** Human label for [Player.playbackState]: Idle / Buffering / Ready / Ended / Unknown. */
    val playerStateLabel: String = "Idle",
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val queueLength: Int = 0,
    /** Zero-based index of the current media item, or -1 when the queue is empty. */
    val currentIndex: Int = -1,
    /** Human label for [Player.repeatMode]: Off / All / One / Unknown. */
    val repeatModeLabel: String = "Off",
    val shuffleEnabled: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    // v0.1.21 — next/previous command availability, derived from
    // ExoPlayer.hasPreviousMediaItem()/hasNextMediaItem(). Lets Settings show
    // why an outside-app next/previous is currently unavailable.
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** Last user-facing playback error message, if any (see LocalAudioPlayer.playbackErrorMessage). */
    val lastError: String? = null,
    /**
     * True when SunoPlaybackEngine.shouldKeepPlaybackAlive() says the shared
     * player must survive service/activity churn (playing, playWhenReady, or
     * buffering/ready). Shown as "keep alive" vs "idle" in Settings.
     */
    val keepAlive: Boolean = false,
) {
    companion object {
        fun playerStateLabel(state: Int): String = when (state) {
            Player.STATE_IDLE -> "Idle"
            Player.STATE_BUFFERING -> "Buffering"
            Player.STATE_READY -> "Ready"
            Player.STATE_ENDED -> "Ended"
            else -> "Unknown"
        }

        fun repeatModeLabel(mode: Int): String = when (mode) {
            Player.REPEAT_MODE_OFF -> "Off"
            Player.REPEAT_MODE_ALL -> "All"
            Player.REPEAT_MODE_ONE -> "One"
            else -> "Unknown"
        }
    }
}
