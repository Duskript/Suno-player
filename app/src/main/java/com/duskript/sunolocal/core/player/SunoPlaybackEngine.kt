package com.duskript.sunolocal.core.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.duskript.sunolocal.MainActivity

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
    // v0.1.27 — the single shared session is now a MediaLibrarySession (it IS
    // a MediaSession) so Android Auto can browse the saved library while the
    // notification/lockscreen/Bluetooth/widget paths keep working unchanged.
    private var mediaLibrarySessionInstance: MediaLibraryService.MediaLibrarySession? = null

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
            .also {
                playerInstance = it
                // v0.1.20 — one-time instrumentation proving the durable audio
                // config is applied: USAGE_MEDIA/CONTENT_TYPE_MUSIC audio
                // attributes (audio focus) + WAKE_MODE_LOCAL (CPU kept alive
                // during screen-off playback).
                Log.i(
                    TAG,
                    "Created shared ExoPlayer: audioAttributes=USAGE_MEDIA/CONTENT_TYPE_MUSIC, " +
                        "wakeMode=C.WAKE_MODE_LOCAL"
                )
            }
    }

    /**
     * The process-wide player only when one already exists, or null.
     *
     * Used by lightweight receivers (e.g. AudioNoisyReceiver on headset
     * unplug) that must react to an existing playback session without ever
     * constructing a player from scratch.
     */
    fun currentPlayerOrNull(): ExoPlayer? = playerInstance

    /**
     * The shared session as a MediaSession (compatibility accessor).
     *
     * v0.1.27 — the shared session is a MediaLibrarySession, which is a
     * MediaSession, so MediaSession-typed callers keep working unchanged.
     * Callers that need Android Auto browsing should use
     * [mediaLibrarySession] with the service's browse callback instead.
     */
    fun mediaSession(context: Context): MediaSession {
        return mediaLibrarySession(context, FallbackLibraryCallback)
    }

    /**
     * The single shared MediaLibrarySession, created exactly once with the
     * given browse callback and the process-wide [player].
     *
     * SunoPlaybackService calls this from onCreate so the Android Auto browse
     * callback is installed before any session exists; LocalAudioPlayer no
     * longer creates the session eagerly (v0.1.27) so the service-owned
     * callback is never frozen out by a UI-created plain session.
     */
    fun mediaLibrarySession(
        context: Context,
        callback: MediaLibraryService.MediaLibrarySession.Callback
    ): MediaLibraryService.MediaLibrarySession {
        val existing = mediaLibrarySessionInstance
        if (existing != null) return existing
        val appContext = context.applicationContext
        return MediaLibraryService.MediaLibrarySession.Builder(appContext, player(appContext), callback)
            .setId("suno-local-playback")
            // v0.1.17: tapping the media notification / lockscreen artwork opens
            // the app. Play/pause/next/previous/seek stay exposed automatically:
            // Media3 derives the session commands from the player's
            // AvailableCommands, and media-button key events arrive through
            // SunoMediaButtonReceiver (see AndroidManifest.xml).
            .setSessionActivity(sessionActivityPendingIntent(appContext))
            .build()
            .also {
                mediaLibrarySessionInstance = it
                // v0.1.20 — one-time instrumentation so logcat can prove the
                // session exists for dumpsys media_session checks.
                Log.i(TAG, "Created MediaLibrarySession id=suno-local-playback")
            }
    }

    /** PendingIntent that opens MainActivity; used for notification/lockscreen taps. */
    private fun sessionActivityPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
        mediaLibrarySessionInstance?.release()
        mediaLibrarySessionInstance = null
        playerInstance?.release()
        playerInstance = null
    }

    /**
     * Defensive all-default browse callback used only if a MediaSession-typed
     * caller requests the session before SunoPlaybackService installed its
     * browse callback (does not happen in the normal service-first flow). All
     * MediaLibrarySession.Callback methods have defaults, so this object
     * compiles with no overrides and simply returns "not supported" errors
     * for browse requests.
     */
    private object FallbackLibraryCallback : MediaLibraryService.MediaLibrarySession.Callback

    private const val TAG = "SunoPlaybackEngine"
}
