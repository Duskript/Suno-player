package com.duskript.sunolocal.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

/**
 * Process-wide playback engine shared by the Compose UI and MediaSessionService.
 *
 * Root cause of background-stop bug: playback lived only in a ViewModel-owned
 * ExoPlayer with no MediaSessionService/foreground media notification. When the
 * app lost focus, Android treated playback as app-local foreground activity
 * work instead of durable media playback. This singleton lets the service and UI
 * use the same ExoPlayer instance.
 */
object SunoPlaybackEngine {
    private var playerInstance: ExoPlayer? = null
    private var mediaSessionInstance: MediaSession? = null

    fun player(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        return playerInstance ?: ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { playerInstance = it }
    }

    /**
     * The process-wide player only when one already exists, or null.
     *
     * Used by lightweight receivers (e.g. AudioNoisyReceiver on headset
     * unplug) that must react to an existing playback session without ever
     * constructing a player from scratch.
     */
    fun currentPlayerOrNull(): ExoPlayer? = playerInstance

    fun mediaSession(context: Context): MediaSession {
        val appContext = context.applicationContext
        return mediaSessionInstance ?: MediaSession.Builder(appContext, player(appContext))
            .setId("suno-local-playback")
            .build()
            .also { mediaSessionInstance = it }
    }

    /** True while audio should survive service/activity churn. */
    fun shouldKeepPlaybackAlive(): Boolean {
        val player = playerInstance ?: return false
        return player.isPlaying ||
            player.playWhenReady ||
            player.playbackState == Player.STATE_BUFFERING ||
            player.playbackState == Player.STATE_READY
    }

    /** Release only when the shared player is idle/ended, never during active playback. */
    fun releaseIfIdle() {
        if (shouldKeepPlaybackAlive()) return
        release()
    }

    fun release() {
        mediaSessionInstance?.release()
        mediaSessionInstance = null
        playerInstance?.release()
        playerInstance = null
    }
}
