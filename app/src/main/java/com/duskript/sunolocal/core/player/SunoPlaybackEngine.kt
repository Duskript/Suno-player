package com.duskript.sunolocal.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

    fun mediaSession(context: Context): MediaSession {
        val appContext = context.applicationContext
        return mediaSessionInstance ?: MediaSession.Builder(appContext, player(appContext))
            .setId("suno-local-playback")
            .build()
            .also { mediaSessionInstance = it }
    }

    fun release() {
        mediaSessionInstance?.release()
        mediaSessionInstance = null
        playerInstance?.release()
        playerInstance = null
    }
}
