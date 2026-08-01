package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * Smart local mixes (v0.1.15) — read-only, derived playlists computed from the
 * user's stored library + favorites.
 *
 * These are pure helpers so they can be unit-tested on the JVM (see
 * SmartMixesTest). Smart mixes are NEVER persisted into suno_library.json or
 * exported as normal playlists; the ViewModel merges them into the displayed
 * playlist list on top of the stored library. IDs are stable and prefixed
 * `smart-` so the UI can recognise them and so they never collide with stored
 * playlist ids.
 */

/** Common prefix for every derived smart mix id. */
const val SMART_MIX_PREFIX = "smart-"

/** Smart mix ids (stable across sessions). */
const val SMART_MIX_FAVORITES_ID = "smart-favorites"
const val SMART_MIX_RECENT_ID = "smart-recent"
const val SMART_MIX_STREAMING_ID = "smart-failed-or-streaming"

/**
 * Builds the smart mix playlist list for the current library.
 *
 * Track lists are deduped by track id across all stored playlists so a track
 * that appears in several playlists shows up once. Empty mixes are dropped so
 * the Library page does not show dead rows.
 *
 * - `smart-favorites`: every favorited track ([favoriteTrackIds]) that is playable.
 * - `smart-recent`: recently downloaded/added tracks (downloadedAtEpochMs > 0),
 *   newest first.
 * - `smart-failed-or-streaming`: tracks that are NOT downloaded yet but are
 *   still playable over the network via their remote audioUrl. Per-track
 *   download-failure detail is not tracked by the sync pipeline, so this mix
 *   is labelled honestly as "Not downloaded (streaming only)".
 *
 * @param playlists stored library playlists (the smart mixes are derived from these).
 * @param favoriteTrackIds currently favorited track ids.
 * @param nowEpochMs clock value used for the mixes' lastSyncedAtEpochMs stamp.
 */
fun buildSmartMixes(
    playlists: List<SunoPlaylist>,
    favoriteTrackIds: Set<String>,
    nowEpochMs: Long = System.currentTimeMillis()
): List<SunoPlaylist> {
    val allTracks = playlists.flatMap { it.tracks }.distinctBy { it.id }

    val favorites = allTracks.filter { it.id in favoriteTrackIds && it.isPlayable }
    val recent = allTracks
        .filter { it.downloadedAtEpochMs > 0L }
        .sortedByDescending { it.downloadedAtEpochMs }
    val streamingOnly = allTracks.filter { !it.isDownloaded && it.isPlayable }

    return listOf(
        smartMix(SMART_MIX_FAVORITES_ID, "Favorites", favorites, nowEpochMs),
        smartMix(SMART_MIX_RECENT_ID, "Recently added", recent, nowEpochMs),
        smartMix(
            SMART_MIX_STREAMING_ID,
            "Not downloaded (streaming only)",
            streamingOnly,
            nowEpochMs
        )
    ).filter { it.tracks.isNotEmpty() }
}

/** True when [playlistId] is a derived smart mix id (prefix `smart-`). */
fun isSmartMixId(playlistId: String): Boolean = playlistId.startsWith(SMART_MIX_PREFIX)

private fun smartMix(
    id: String,
    title: String,
    tracks: List<SunoTrack>,
    nowEpochMs: Long
): SunoPlaylist = SunoPlaylist(
    id = id,
    title = title,
    creatorName = "Smart mix",
    sourceUrl = null,
    tracks = tracks,
    savedFromOtherCreator = false,
    isCustom = false,
    lastSyncedAtEpochMs = nowEpochMs
)
