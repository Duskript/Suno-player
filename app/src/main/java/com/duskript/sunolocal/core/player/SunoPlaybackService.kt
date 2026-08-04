package com.duskript.sunolocal.core.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.duskript.sunolocal.R
import com.duskript.sunolocal.SunoLocalApplication
import com.duskript.sunolocal.core.storage.LibraryStore
import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground-capable Media3 library session service for background music
 * playback and Android Auto browsing.
 *
 * v0.1.27 — the service is now a MediaLibraryService: Android keeps media
 * playback alive through a MediaSessionService + media notification instead of
 * tying audio to Activity focus, and the MediaLibrarySession browse tree lets
 * Android Auto render its own driver-safe UI over the saved library. The UI
 * starts this service before playback and both service/UI share
 * SunoPlaybackEngine.player(). The notification/lockscreen/Bluetooth/widget
 * paths are unchanged: they all act on the same shared session.
 */
class SunoPlaybackService : MediaLibraryService() {

    override fun onCreate() {
        super.onCreate()
        // v0.1.25 — root cause of missing notification/lockscreen controls:
        // the Activity created a MediaSession, but the MediaSessionService never
        // registered that session with addSession(), so Media3 had no service
        // session linked to a foreground media notification. Bind the default
        // provider to the app's real playback channel and explicitly add the
        // shared session to this service before playback starts.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID },
                SunoLocalApplication.CHANNEL_PLAYBACK,
                R.string.playback_notification_channel_name
            )
        )
        // v0.1.27 — create the single shared session as a MediaLibrarySession
        // with the browse callback installed up front, so Android Auto sees
        // the library tree even when the UI/notification path created the
        // process first. The same session instance keeps riding the
        // notification provider registered above.
        val session = SunoPlaybackEngine.mediaLibrarySession(
            this,
            SunoLibraryBrowseCallback(applicationContext)
        )
        if (!isSessionAdded(session)) {
            addSession(session)
        }
        Log.i(
            TAG,
            "Registered MediaLibrarySession with service notification provider " +
                "(channel=${SunoLocalApplication.CHANNEL_PLAYBACK})"
        )
        // v0.1.20 — instrumentation: proves the service came up and whether the
        // shared engine already considers playback worth keeping alive.
        Log.i(
            TAG,
            "Service created; keepAlive=${SunoPlaybackEngine.shouldKeepPlaybackAlive()}"
        )
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibraryService.MediaLibrarySession {
        // Controller package can be null (e.g. media key events from the
        // system), so the log stays null-tolerant.
        val controller = controllerInfo.packageName?.takeIf { it.isNotBlank() } ?: "unknown"
        Log.i(
            TAG,
            "Session requested by controller=$controller; " +
                "keepAlive=${SunoPlaybackEngine.shouldKeepPlaybackAlive()}"
        )
        return SunoPlaybackEngine.mediaLibrarySession(
            this,
            SunoLibraryBrowseCallback(applicationContext)
        )
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the task away should not kill music that is actively playing.
        // Media apps are expected to keep playback/session state alive until the
        // user pauses/stops, so leave the shared engine alone here.
        logLifetimeSummary("Task removed")
        Log.i(TAG, "Task removed; keeping shared playback engine alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Android can tear down/recreate MediaSessionService while the process
        // and player are still valid. Releasing the process-wide ExoPlayer here
        // causes the reported bug: playback stops a little while after the app
        // backgrounds. Only release when the player is truly idle.
        logLifetimeSummary("Service destroyed")
        if (SunoPlaybackEngine.shouldKeepPlaybackAlive()) {
            Log.i(TAG, "Service destroyed while playback active; preserving shared player")
        } else {
            Log.i(TAG, "Service destroyed while idle; releasing shared player")
            SunoPlaybackEngine.releaseIfIdle()
        }
        super.onDestroy()
    }

    /** Compact isPlaying/playWhenReady/playbackState/keepAlive snapshot for logcat. */
    private fun logLifetimeSummary(event: String) {
        val player = SunoPlaybackEngine.currentPlayerOrNull()
        val state = player?.playbackState
            ?.let { PlaybackDiagnostics.playerStateLabel(it) }
            ?: "no-player"
        Log.i(
            TAG,
            "$event summary: isPlaying=${player?.isPlaying}, " +
                "playWhenReady=${player?.playWhenReady}, playbackState=$state, " +
                "keepAlive=${SunoPlaybackEngine.shouldKeepPlaybackAlive()}"
        )
    }

    /**
     * v0.1.27 — Android Auto / media-browser browse callback.
     *
     * Read-only browse over the app-private [LibraryStore]: every browse
     * request loads the current playlists, so the car always sees the saved
     * library (custom mixes included). Never mutates the library, never makes
     * network calls, never logs cookies/secrets — only playlist/track titles.
     * The tree is deliberately shallow (root → Playlists → playlist → tracks)
     * to stay driver-safe.
     */
    private class SunoLibraryBrowseCallback(private val context: Context) :
        MediaLibraryService.MediaLibrarySession.Callback {

        private fun loadPlaylists(): List<SunoPlaylistJson> =
            LibraryStore(context.applicationContext).loadPlaylists()

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(SunoMediaLibrary.rootItem(context), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val playlists = loadPlaylists()
            val children = SunoMediaLibrary.childrenFor(parentId, playlists)
            val result: LibraryResult<ImmutableList<MediaItem>> =
                if (children.isEmpty() && !SunoMediaLibrary.isKnownParent(parentId, playlists)) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItemList(children, params)
                }
            return Futures.immediateFuture(result)
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = SunoMediaLibrary.itemFor(mediaId, loadPlaylists())
            val result: LibraryResult<MediaItem> =
                if (item != null) {
                    // onGetItem has no LibraryParams; null matches Media3's
                    // own demos and the session fills in its defaults.
                    LibraryResult.ofItem(item, /* params= */ null)
                } else {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            return Futures.immediateFuture(result)
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // Android Auto requests playback by media id (the browsed item has
            // no URI on the wire), so resolve every URI-less requested item
            // through the library before it reaches the player. Items that
            // already carry a URI pass through untouched.
            val playlists = loadPlaylists()
            val resolved = mediaItems.map { requested ->
                val hasUri = requested.localConfiguration?.uri != null
                if (hasUri) {
                    requested
                } else {
                    SunoMediaLibrary.itemFor(requested.mediaId, playlists) ?: requested
                }
            }
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.singleOrNull()
            val isSingleUriLessTrack = requested != null &&
                requested.localConfiguration?.uri == null &&
                requested.mediaId.startsWith("track:")
            if (isSingleUriLessTrack) {
                val queue = SunoMediaLibrary.playbackQueueFor(requested.mediaId, loadPlaylists())
                if (queue != null) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            queue.mediaItems,
                            queue.startIndex,
                            queue.startPositionMs
                        )
                    )
                }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems,
                    startIndex,
                    startPositionMs
                )
            )
        }
    }

    private companion object {
        const val TAG = "SunoPlaybackService"
    }
}
