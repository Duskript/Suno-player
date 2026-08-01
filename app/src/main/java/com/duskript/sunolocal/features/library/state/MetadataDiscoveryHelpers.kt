package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * Pure helpers for Batch 5 metadata & discovery (creator browsing, similar
 * tracks). All functions are free of Android dependencies so they can be
 * unit-tested on the JVM (see MetadataDiscoveryHelpersTest). They never touch
 * the network or LibraryStore — discovery is always local-library only.
 */

/** Trim a creator name and collapse internal whitespace; null for blank input. */
fun normalizeCreatorName(name: String?): String? =
    name?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }

/** Case-insensitive matching key for a creator name (null-safe). */
private fun creatorKey(name: String?): String? = normalizeCreatorName(name)?.lowercase()

/**
 * All tracks in [playlists] belonging to [creatorName], matched
 * case-insensitively against the track's own creator name — falling back to
 * the owning playlist's creator name when the track lacks one (common for
 * saved playlists). Deduplicated by track id, stable playlist order.
 */
fun tracksByCreator(playlists: List<SunoPlaylist>, creatorName: String): List<SunoTrack> {
    val key = creatorKey(creatorName) ?: return emptyList()
    return playlists.asSequence()
        .flatMap { playlist ->
            playlist.tracks.asSequence().map { track -> track to (track.creatorName ?: playlist.creatorName) }
        }
        .filter { (_, creator) -> creatorKey(creator) == key }
        .map { (track, _) -> track }
        .distinctBy { it.id }
        .toList()
}

/** All playlists in [playlists] whose creator matches [creatorName]. */
fun playlistsByCreator(playlists: List<SunoPlaylist>, creatorName: String): List<SunoPlaylist> {
    val key = creatorKey(creatorName) ?: return emptyList()
    return playlists.filter { creatorKey(it.creatorName) == key }
}

/**
 * Local "similar tracks" heuristic: the top [limit] tracks from [allTracks]
 * sharing normalized tags, genre, or style-prompt tokens with [target].
 * The target itself is excluded, zero-overlap tracks are dropped, and ties are
 * broken by title then id for stable ordering. No network calls.
 */
fun similarTracks(target: SunoTrack, allTracks: List<SunoTrack>, limit: Int = 5): List<SunoTrack> {
    val targetTags = normalizedTagSet(target.tags)
    val targetGenre = normalizeToken(target.genre)
    val targetStyle = styleTokenSet(target.stylePrompt)

    data class Scored(val track: SunoTrack, val score: Int)

    return allTracks.asSequence()
        .filter { it.id != target.id }
        .mapNotNull { candidate ->
            val score = similarityScore(targetTags, targetGenre, targetStyle, candidate)
            if (score > 0) Scored(candidate, score) else null
        }
        .sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.track.title.lowercase() }
                .thenBy { it.track.id }
        )
        .take(limit)
        .map { it.track }
        .toList()
}

private fun similarityScore(
    targetTags: Set<String>,
    targetGenre: String?,
    targetStyle: Set<String>,
    candidate: SunoTrack
): Int {
    var score = 0
    score += (targetTags intersect normalizedTagSet(candidate.tags)).size
    val candidateGenre = normalizeToken(candidate.genre)
    if (targetGenre != null && candidateGenre != null && targetGenre == candidateGenre) score += 1
    score += (targetStyle intersect styleTokenSet(candidate.stylePrompt)).size
    return score
}

private fun normalizedTagSet(tags: List<String>): Set<String> =
    tags.asSequence().mapNotNull { normalizeToken(it) }.toSet()

/** Split a style prompt into normalized keyword tokens (3+ chars, deduped). */
private fun styleTokenSet(stylePrompt: String?): Set<String> =
    stylePrompt.orEmpty()
        .split(Regex("[^A-Za-z0-9]+"))
        .asSequence()
        .mapNotNull { normalizeToken(it) }
        .filter { it.length >= 3 }
        .toSet()

private fun normalizeToken(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

/**
 * Compact one-line summary of a track's discovery metadata for list rows:
 * "Genre: … • Mood: … • Tags: …". Null when nothing is available.
 */
fun trackMetadataLine(track: SunoTrack): String? {
    val parts = mutableListOf<String>()
    track.genre?.takeIf { it.isNotBlank() }?.let { parts += "Genre: $it" }
    track.mood?.takeIf { it.isNotBlank() }?.let { parts += "Mood: $it" }
    track.tags.asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.let { parts += "Tags: ${it.joinToString(", ")}" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}
