package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * Filter modes for the Library page playlist list (Batch 3 — search/filter).
 */
enum class LibraryPlaylistFilter {
    /** Show every playlist matching the search query. */
    ALL,

    /**
     * Show only playlists that have at least one downloaded track
     * ([SunoPlaylist.downloadedTrackCount] > 0). Partial libraries stay
     * visible — a playlist is only hidden when nothing in it is playable
     * offline yet.
     */
    DOWNLOADED_ONLY,

    /** Show only user-created custom mixes ([SunoPlaylist.isCustom]). */
    CUSTOM_MIXES
}

/**
 * Filters [playlists] by [query] (trimmed, case-insensitive match against
 * playlist title and creator name) and then by [filter]. A blank/whitespace
 * query matches every playlist, leaving only the selected filter in effect.
 */
fun filterPlaylists(
    playlists: List<SunoPlaylist>,
    query: String,
    filter: LibraryPlaylistFilter
): List<SunoPlaylist> {
    val needle = query.trim()
    return playlists.filter { playlist ->
        val matchesQuery = needle.isEmpty() ||
            playlist.title.contains(needle, ignoreCase = true) ||
            playlist.creatorName?.contains(needle, ignoreCase = true) == true
        val matchesFilter = when (filter) {
            LibraryPlaylistFilter.ALL -> true
            LibraryPlaylistFilter.DOWNLOADED_ONLY -> playlist.downloadedTrackCount > 0
            LibraryPlaylistFilter.CUSTOM_MIXES -> playlist.isCustom
        }
        matchesQuery && matchesFilter
    }
}

/**
 * Track-level filter chips for a playlist's track list (v0.1.15).
 */
enum class TrackFilter {
    /** Every track matching the search query. */
    ALL,

    /** Only favorited tracks ([SunoTrack.id] in the favorites set). */
    FAVORITES,

    /** Only tracks without a downloaded local file (streaming/not downloaded). */
    NOT_DOWNLOADED
}

/**
 * Filters [tracks] by [query], matching trimmed and case-insensitively against
 * track title, creator name, lyrics, style prompt, and description prompt.
 * Null metadata fields are skipped safely. A blank/whitespace query returns
 * [tracks] unchanged.
 */
fun filterTracks(tracks: List<SunoTrack>, query: String): List<SunoTrack> {
    val needle = query.trim()
    if (needle.isEmpty()) return tracks
    return tracks.filter { track ->
        track.title.contains(needle, ignoreCase = true) ||
            track.creatorName?.contains(needle, ignoreCase = true) == true ||
            track.lyrics?.contains(needle, ignoreCase = true) == true ||
            track.stylePrompt?.contains(needle, ignoreCase = true) == true ||
            track.descriptionPrompt?.contains(needle, ignoreCase = true) == true
    }
}

/**
 * Filters [tracks] by [query] AND [filter] (v0.1.15). Query matching is the
 * same as [filterTracks]; the filter additionally keeps only favorited tracks
 * ([TrackFilter.FAVORITES]) or only not-downloaded tracks
 * ([TrackFilter.NOT_DOWNLOADED]). Defaults preserve the legacy behaviour so
 * existing call sites and tests are unchanged.
 */
fun filterTracks(
    tracks: List<SunoTrack>,
    query: String,
    favoriteTrackIds: Set<String>,
    filter: TrackFilter
): List<SunoTrack> {
    val needle = query.trim()
    return tracks.filter { track ->
        val matchesQuery = needle.isEmpty() ||
            track.title.contains(needle, ignoreCase = true) ||
            track.creatorName?.contains(needle, ignoreCase = true) == true ||
            track.lyrics?.contains(needle, ignoreCase = true) == true ||
            track.stylePrompt?.contains(needle, ignoreCase = true) == true ||
            track.descriptionPrompt?.contains(needle, ignoreCase = true) == true
        val matchesFilter = when (filter) {
            TrackFilter.ALL -> true
            TrackFilter.FAVORITES -> track.id in favoriteTrackIds
            TrackFilter.NOT_DOWNLOADED -> !track.isDownloaded
        }
        matchesQuery && matchesFilter
    }
}
