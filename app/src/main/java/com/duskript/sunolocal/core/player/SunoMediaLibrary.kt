package com.duskript.sunolocal.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.duskript.sunolocal.R
import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.duskript.sunolocal.core.storage.SunoTrackJson
import java.io.File

/**
 * SunoMediaLibrary — v0.1.27: driver-safe browse tree for Android Auto.
 *
 * Shallow, stable browse hierarchy served by the shared MediaLibrarySession:
 *
 *   suno_root (browsable folder, not playable)
 *   └── suno_playlists (browsable folder, not playable)
 *       └── playlist:<playlist.id> (browsable folder per saved playlist)
 *           └── track:<track.id> (playable track, URI-filled)
 *
 * Playable track URIs mirror PlaybackSource semantics exactly: a verified
 * local file (exists && length > 0) wins, otherwise the streaming audioUrl is
 * used, otherwise the track is excluded (never a playable item with a null or
 * invalid URI). Playlist folders are browsable-only in this MVP so a tap never
 * triggers ambiguous queue behavior.
 *
 * Functions accept SunoPlaylistJson/SunoTrackJson and avoid Android Context
 * except optional title lookups, so unit tests stay pure JVM.
 */
object SunoMediaLibrary {

    /** Browse id of the browse-tree root. */
    const val ROOT_ID = "suno_root"

    /** Browse id of the "Playlists" folder shown under the root. */
    const val PLAYLISTS_ID = "suno_playlists"

    private const val PLAYLIST_ID_PREFIX = "playlist:"
    private const val TRACK_ID_PREFIX = "track:"

    private const val DEFAULT_ROOT_TITLE = "Suno Local"
    private const val DEFAULT_PLAYLISTS_TITLE = "Playlists"

    /** Stable browse id for a saved-playlist folder. */
    fun playlistBrowseId(playlistId: String): String = "$PLAYLIST_ID_PREFIX$playlistId"

    /** Stable media id for a playable track. */
    fun trackMediaId(trackId: String): String = "$TRACK_ID_PREFIX$trackId"

    /** Extracts the playlist id from a playlist browse id, or null. */
    fun playlistIdFromBrowseId(browseId: String): String? =
        browseId.takeIf { it.startsWith(PLAYLIST_ID_PREFIX) }?.removePrefix(PLAYLIST_ID_PREFIX)

    /** Root item: browsable, not playable. */
    fun rootItem(context: Context? = null): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(context?.getString(R.string.car_root_title) ?: DEFAULT_ROOT_TITLE)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    /** "Playlists" folder item: browsable, not playable. */
    fun playlistsFolderItem(context: Context? = null): MediaItem = MediaItem.Builder()
        .setMediaId(PLAYLISTS_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(
                    context?.getString(R.string.car_playlists_folder_title)
                        ?: DEFAULT_PLAYLISTS_TITLE
                )
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                .build()
        )
        .build()

    /** Saved-playlist folder item: browsable only (MVP — no ambiguous queue on tap). */
    fun playlistItem(playlist: SunoPlaylistJson): MediaItem = MediaItem.Builder()
        .setMediaId(playlistBrowseId(playlist.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playlist.title)
                .setArtist(playlist.creatorName)
                .setAlbumTitle(playlist.creatorName ?: DEFAULT_ROOT_TITLE)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .build()

    /**
     * Playable track item with a URI-filled localConfiguration, or null when
     * the track has no usable playback source (PlaybackSource semantics).
     */
    fun trackItem(track: SunoTrackJson, playlistTitle: String? = null): MediaItem? {
        val uriString = trackUriString(track) ?: return null
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.creatorName)
            .setAlbumTitle(playlistTitle ?: track.creatorName ?: DEFAULT_ROOT_TITLE)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setDurationMs(track.durationMs)
            .setArtworkUri(track.imageUrl?.let { Uri.parse(it) })
            .build()
        val uri = Uri.parse(uriString)
        return MediaItem.Builder()
            .setMediaId(trackMediaId(track.id))
            .setMediaMetadata(metadata)
            .apply { if (uri != null) setUri(uri) }
            .build()
    }

    /**
     * Children of a browse node. Unknown parent ids return an empty list; the
     * service distinguishes "known but empty" from "unknown" via
     * [isKnownParent] when it wants a BAD_VALUE error for unknown ids.
     */
    fun childrenFor(parentId: String, playlists: List<SunoPlaylistJson>): List<MediaItem> {
        return when (parentId) {
            ROOT_ID -> listOf(playlistsFolderItem())
            PLAYLISTS_ID -> playlists.map { playlistItem(it) }
            else -> {
                val playlistId = playlistIdFromBrowseId(parentId) ?: return emptyList()
                val playlist = playlists.firstOrNull { it.id == playlistId } ?: return emptyList()
                playlist.tracks.mapNotNull { trackItem(it, playlist.title) }
            }
        }
    }

    /** True when [parentId] is a valid browse node for the given library. */
    fun isKnownParent(parentId: String, playlists: List<SunoPlaylistJson>): Boolean =
        when (parentId) {
            ROOT_ID, PLAYLISTS_ID -> true
            else -> playlists.any { it.id == playlistIdFromBrowseId(parentId) }
        }

    /** Resolves any browse/track id to its MediaItem, or null when unknown/unplayable. */
    fun itemFor(mediaId: String, playlists: List<SunoPlaylistJson>): MediaItem? {
        return when {
            mediaId == ROOT_ID -> rootItem()
            mediaId == PLAYLISTS_ID -> playlistsFolderItem()
            mediaId.startsWith(PLAYLIST_ID_PREFIX) -> {
                val playlistId = playlistIdFromBrowseId(mediaId) ?: return null
                playlists.firstOrNull { it.id == playlistId }?.let { playlistItem(it) }
            }
            mediaId.startsWith(TRACK_ID_PREFIX) -> {
                val trackId = mediaId.removePrefix(TRACK_ID_PREFIX)
                for (playlist in playlists) {
                    val track = playlist.tracks.firstOrNull { it.id == trackId } ?: continue
                    return trackItem(track, playlist.title)
                }
                null
            }
            else -> null
        }
    }

    /**
     * Pure URI-string decision mirroring PlaybackSource.resolve exactly:
     * verified local file (exists && length > 0) → file URI string; else a
     * nonblank audioUrl → that URL; else null. Pure JVM (java.net URI, no
     * android.net.Uri) so unit tests can assert the local-first /
     * streaming-fallback rules without Robolectric.
     */
    internal fun trackUriString(track: SunoTrackJson): String? {
        val localPath = track.localPath?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        if (localPath != null) {
            val file = File(localPath)
            if (file.isFile && file.length() > 0L) {
                return file.toURI().toString()
            }
        }
        return track.audioUrl?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }
}
