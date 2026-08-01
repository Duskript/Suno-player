package com.duskript.sunolocal.core.player

import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground-capable Media3 session service for background music playback.
 *
 * Android keeps media playback alive through a MediaSessionService + media
 * notification instead of tying audio to Activity focus. The UI starts this
 * service before playback and both service/UI share SunoPlaybackEngine.player().
 */
class SunoPlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        SunoPlaybackEngine.mediaSession(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        SunoPlaybackEngine.mediaSession(this)

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the task away should not kill music that is actively playing.
        // Media apps are expected to keep playback/session state alive until the
        // user pauses/stops, so leave the shared engine alone here.
        Log.i(TAG, "Task removed; keeping shared playback engine alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Android can tear down/recreate MediaSessionService while the process
        // and player are still valid. Releasing the process-wide ExoPlayer here
        // causes the reported bug: playback stops a little while after the app
        // backgrounds. Only release when the player is truly idle.
        if (SunoPlaybackEngine.shouldKeepPlaybackAlive()) {
            Log.i(TAG, "Service destroyed while playback active; preserving shared player")
        } else {
            Log.i(TAG, "Service destroyed while idle; releasing shared player")
            SunoPlaybackEngine.releaseIfIdle()
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SunoPlaybackService"
    }
}
