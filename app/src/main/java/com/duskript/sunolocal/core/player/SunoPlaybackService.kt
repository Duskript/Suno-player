package com.duskript.sunolocal.core.player

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

    override fun onDestroy() {
        SunoPlaybackEngine.release()
        super.onDestroy()
    }
}
