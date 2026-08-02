package com.duskript.sunolocal.core.player

import androidx.media3.session.MediaButtonReceiver

/**
 * App-owned media button receiver (v0.1.17).
 *
 * Registers exactly one MEDIA_BUTTON receiver for the app so MediaSessionCompat
 * stops logging "Couldn't find a unique registered media button receiver" on
 * device. Media3's [MediaButtonReceiver] handles the routing: it resolves the
 * app's MediaSessionService ([SunoPlaybackService]) from the manifest, starts
 * it as a foreground service carrying the media-button intent, and the active
 * MediaSession maps headset/lockscreen key events (play/pause, next, previous,
 * seek) onto the process-wide shared ExoPlayer via the player's
 * AvailableCommands.
 *
 * Subclassing instead of manifest-registering the library class directly keeps
 * the registered component app-owned: the manifest entry survives library
 * churn, and the protected hooks (shouldStartForegroundService /
 * onForegroundServiceStartNotAllowedException) stay overridable if Android
 * foreground-service policy changes. onReceive is final in Media3 1.5.1, so
 * the standard routing is inherited verbatim and this class needs no overrides.
 */
class SunoMediaButtonReceiver : MediaButtonReceiver()
