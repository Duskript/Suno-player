package com.duskript.sunolocal.domain.model

/**
 * SunoTrack — represents a single audio track from a Suno playlist.
 *
 * The app keeps Suno's useful creative metadata with the offline audio: cover
 * art URL, generated lyrics, style tags/prompt, and description prompt. Custom
 * playlists reuse these immutable track records while changing playlistId.
 */
data class SunoTrack(
    val id: String,
    val title: String,
    val audioUrl: String? = null,
    val localPath: String? = null,
    val imageUrl: String? = null,
    val durationMs: Long? = null,
    val playlistId: String? = null,
    val creatorName: String? = null,
    val sourceUrl: String? = null,
    val lyrics: String? = null,
    val stylePrompt: String? = null,
    val descriptionPrompt: String? = null,
    val downloadedAtEpochMs: Long = 0L
) {
    /** Whether this track's audio file has been downloaded to local storage. */
    val isDownloaded: Boolean get() = localPath != null

    /** Whether this track has all required metadata for playback (at minimum an id and title). */
    val isPlayable: Boolean get() = localPath != null || audioUrl != null

    /** Compact metadata line for list/detail UI. */
    val metadataSummary: String
        get() = listOfNotNull(
            creatorName,
            stylePrompt?.takeIf { it.isNotBlank() }?.let { "Style: ${it.take(80)}" },
            durationMs?.let { "${it / 1000}s" }
        ).joinToString(" • ")
}
