package com.duskript.sunolocal.domain.model

/**
 * SunoPlaylist — represents a collection of Suno tracks, either from the
 * authenticated user's library, a saved creator playlist, or a user-built custom
 * playlist assembled from existing downloaded/synced tracks.
 */
data class SunoPlaylist(
    val id: String,
    val title: String,
    val creatorName: String? = null,
    val sourceUrl: String? = null,
    val tracks: List<SunoTrack> = emptyList(),
    val savedFromOtherCreator: Boolean = false,
    val isCustom: Boolean = false,
    val lastSyncedAtEpochMs: Long = 0L
) {
    /** Number of tracks in this playlist. */
    val trackCount: Int get() = tracks.size

    /** Number of tracks that have been downloaded to local storage. */
    val downloadedTrackCount: Int get() = tracks.count { it.isDownloaded }

    /** True if all tracks in this playlist have been downloaded. */
    val isFullyDownloaded: Boolean get() = tracks.isNotEmpty() && tracks.all { it.isDownloaded }
}
