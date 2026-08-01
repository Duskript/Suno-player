package com.duskript.sunolocal.core.storage

import com.duskript.sunolocal.domain.model.SunoPlaylist
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Thrown when a backup file cannot be parsed. The message is user-presentable
 * and is surfaced by the ViewModel instead of crashing.
 */
class LibraryBackupException(message: String) : Exception(message)

/**
 * Result of merging a backup file into the existing library.
 *
 * @property playlists the merged library (existing playlists first, then
 *   imported ones) — ready to persist via [LibraryStore.savePlaylists].
 * @property importedPlaylists number of new playlists added.
 * @property skippedPlaylists number of incoming playlists skipped because a
 *   playlist with the same id already exists (existing wins).
 * @property importedTracks total tracks added across imported playlists.
 * @property skippedTracks duplicate track ids dropped inside imported
 *   playlists (first occurrence wins). Tracks inside a skipped playlist are
 *   not counted here — the whole playlist was skipped.
 */
data class ImportResult(
    val playlists: List<SunoPlaylistJson>,
    val importedPlaylists: Int,
    val skippedPlaylists: Int,
    val importedTracks: Int,
    val skippedTracks: Int
)

/**
 * LibraryBackup — pure JVM helpers for exporting/importing the local library
 * as a portable JSON file and for generating plain-text M3U playlist files.
 *
 * No Android dependencies: exports use the exact JSON shape [LibraryStore]
 * persists in app-private storage (see docs/EXPORT_FORMAT.md), so a backup can
 * be re-imported by the app itself or inspected by hand. Import never deletes
 * existing library content. Conflict rules:
 *  - an incoming playlist whose id already exists is skipped (existing wins),
 *  - duplicate track ids within an imported playlist are dropped (first wins),
 *  - nothing is written for a skipped playlist (its tracks are not merged).
 *
 * No cookies, credentials, or secrets are ever written by these helpers — the
 * JSON only carries library metadata, local file references, and Suno media
 * URLs, exactly like app-private storage.
 */
object LibraryBackup {

    /**
     * Serialize the library to a JSON string. The top level is a raw JSON
     * array of playlist objects — the same shape as suno_library.json.
     */
    fun exportLibraryJson(playlists: List<SunoPlaylistJson>): String {
        val array = JSONArray()
        playlists.forEach { array.put(serialisePlaylist(it)) }
        return array.toString(2)
    }

    /**
     * Merge a backup JSON string into [existing] and return the merged library
     * plus import counts. Throws [LibraryBackupException] when the input is
     * not valid backup JSON; nothing is mutated on failure.
     */
    fun importLibraryJson(existing: List<SunoPlaylistJson>, backupJson: String): ImportResult {
        val incoming = parseLibraryJson(backupJson)

        val merged = existing.toMutableList()
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }

        var importedPlaylists = 0
        var skippedPlaylists = 0
        var importedTracks = 0
        var skippedTracks = 0

        for (playlist in incoming) {
            if (playlist.id in existingIds) {
                skippedPlaylists++
                continue
            }
            val deduped = dedupeTracks(playlist.tracks)
            skippedTracks += playlist.tracks.size - deduped.size
            importedTracks += deduped.size
            importedPlaylists++
            merged.add(playlist.copy(tracks = deduped))
            existingIds.add(playlist.id)
        }

        return ImportResult(
            playlists = merged,
            importedPlaylists = importedPlaylists,
            skippedPlaylists = skippedPlaylists,
            importedTracks = importedTracks,
            skippedTracks = skippedTracks
        )
    }

    /**
     * Generate a plain-text M3U playlist. Each entry writes
     * `#EXTINF:<seconds>,<title>` followed by the track location: local file
     * path when downloaded, otherwise the Suno audio URL, otherwise the source
     * URL. Tracks with no location at all are omitted (no dangling line).
     */
    fun exportPlaylistM3u(playlist: SunoPlaylist): String = buildString {
        appendLine("#EXTM3U")
        playlist.tracks.forEach { track ->
            val durationSeconds = track.durationMs?.let { (it / 1000).coerceAtLeast(0) } ?: -1
            appendLine("#EXTINF:$durationSeconds,${track.title}")
            val location = track.localPath ?: track.audioUrl ?: track.sourceUrl
            if (location != null) appendLine(location)
        }
    }

    /**
     * Parse a backup JSON string into playlists. Accepts both the raw array
     * form (identical to app-private storage) and a future-proof wrapper
     * object `{"version": N, "playlists": [...]}`. Throws
     * [LibraryBackupException] on invalid JSON, non-object elements, or a
     * playlist without a usable id.
     */
    fun parseLibraryJson(json: String): List<SunoPlaylistJson> {
        val trimmed = json.trim()
        val array: JSONArray = try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("playlists")
                        ?: throw LibraryBackupException("Backup file has no \"playlists\" array")
                }
                else -> throw LibraryBackupException("Backup file is not a JSON array or object")
            }
        } catch (e: LibraryBackupException) {
            throw e
        } catch (e: JSONException) {
            throw LibraryBackupException("Backup file is not valid JSON (${e.message})")
        }

        val playlists = mutableListOf<SunoPlaylistJson>()
        for (i in 0 until array.length()) {
            val element = try {
                array.getJSONObject(i)
            } catch (e: JSONException) {
                throw LibraryBackupException("Backup playlist #${i + 1} is not a valid object")
            }
            val playlist = parsePlaylistJson(element)
                ?: throw LibraryBackupException("Backup playlist #${i + 1} has no valid id")
            playlists.add(playlist)
        }
        return playlists
    }

    private fun dedupeTracks(tracks: List<SunoTrackJson>): List<SunoTrackJson> {
        val seen = mutableSetOf<String>()
        return tracks.filter { seen.add(it.id) }
    }
}
