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
    // Batch 5 — optional discovery metadata. Suno does not always provide these
    // (and older stored JSON predates them), so all three default to absent and
    // are only populated when the API response actually carries them.
    val tags: List<String> = emptyList(),
    val mood: String? = null,
    val genre: String? = null,
    val downloadedAtEpochMs: Long = 0L
) {
    /** Whether this track's audio file has been downloaded to local storage. */
    val isDownloaded: Boolean get() = localPath != null

    /**
     * Whether this track declares a playback source (localPath or audioUrl).
     * UI enable states use this cheap check; the real runtime check that also
     * verifies a local file exists and is non-empty lives in
     * PlaybackSource.resolve (Batch E) and is used for queue building.
     */
    val isPlayable: Boolean get() = localPath != null || audioUrl != null

    /** Compact metadata line for list/detail UI. */
    val metadataSummary: String
        get() = listOfNotNull(
            creatorName,
            stylePrompt?.takeIf { it.isNotBlank() }?.let { "Style: ${it.take(80)}" },
            durationMs?.let { "${it / 1000}s" }
        ).joinToString(" • ")
}
