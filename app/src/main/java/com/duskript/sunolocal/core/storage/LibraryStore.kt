package com.duskript.sunolocal.core.storage

import android.content.Context
import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LibraryStore — persists the user's local library of Suno playlists and tracks
 * as a JSON file in app-private storage.
 *
 * The JSON schema stores downloaded audio paths plus Suno creative metadata:
 * cover art URL, generated lyrics, style prompt/tags, description prompt, and
 * user-created custom playlist ordering.
 */
class LibraryStore(context: Context) {

    private val libraryFile = File(context.filesDir, FILE_NAME)

    /** Load all saved playlists from local storage. */
    fun loadPlaylists(): List<SunoPlaylistJson> = loadAll().toList()

    /** Replace the entire playlist list with the given list. */
    fun savePlaylists(playlists: List<SunoPlaylistJson>) {
        persistAll(playlists)
    }

    /** Insert or update a single JSON playlist, preserving others. */
    fun upsertPlaylist(playlist: SunoPlaylistJson) {
        val all = loadAll().toMutableList()
        val index = all.indexOfFirst { it.id == playlist.id }
        if (index >= 0) {
            all[index] = playlist
        } else {
            all.add(playlist)
        }
        persistAll(all)
    }

    /** Insert or update a domain playlist returned by the Suno API client. */
    fun upsertPlaylist(playlist: SunoPlaylist) {
        upsertPlaylist(playlist.toJson())
    }

    /**
     * Update the local file path and download timestamp for a specific track.
     * Searches across all playlists for the matching track ID.
     */
    fun updateTrackLocalPath(trackId: String, localPath: String) {
        val all = loadAll().toMutableList()
        var changed = false

        for ((pIndex, playlist) in all.withIndex()) {
            val tracks = playlist.tracks.toMutableList()
            val tIndex = tracks.indexOfFirst { it.id == trackId }
            if (tIndex >= 0) {
                val old = tracks[tIndex]
                tracks[tIndex] = old.copy(
                    localPath = localPath,
                    downloadedAtEpochMs = System.currentTimeMillis()
                )
                all[pIndex] = playlist.copy(tracks = tracks)
                changed = true
            }
        }

        if (changed) persistAll(all)
    }

    /** Retrieve a specific playlist by ID. */
    fun getPlaylist(id: String): SunoPlaylistJson? = loadAll().find { it.id == id }

    /** Remove a playlist by ID. */
    fun removePlaylist(id: String) {
        persistAll(loadAll().filter { it.id != id })
    }

    /** Total number of saved playlists. */
    fun playlistCount(): Int = loadAll().size

    private fun loadAll(): MutableList<SunoPlaylistJson> {
        if (!libraryFile.exists()) return mutableListOf()

        return try {
            val json = libraryFile.readText()
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                parsePlaylistJson(array.getJSONObject(i))
            }.toMutableList()
        } catch (e: Exception) {
            // Corrupt file — reset rather than crash app launch.
            libraryFile.delete()
            mutableListOf()
        }
    }

    private fun persistAll(playlists: List<SunoPlaylistJson>) {
        val array = JSONArray()
        playlists.forEach { array.put(serialisePlaylist(it)) }
        libraryFile.writeText(array.toString(2))
    }

    companion object {
        private const val FILE_NAME = "suno_library.json"
    }
}

/** JSON-serialisable mirror of domain models. */
data class SunoTrackJson(
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
    val tags: List<String> = emptyList(),
    val mood: String? = null,
    val genre: String? = null,
    val downloadedAtEpochMs: Long = 0L
)

data class SunoPlaylistJson(
    val id: String,
    val title: String,
    val creatorName: String? = null,
    val sourceUrl: String? = null,
    val tracks: List<SunoTrackJson> = emptyList(),
    val savedFromOtherCreator: Boolean = false,
    val isCustom: Boolean = false,
    val lastSyncedAtEpochMs: Long = 0L
)

private fun SunoPlaylist.toJson(): SunoPlaylistJson = SunoPlaylistJson(
    id = id,
    title = title,
    creatorName = creatorName,
    sourceUrl = sourceUrl,
    tracks = tracks.map { it.toJson() },
    savedFromOtherCreator = savedFromOtherCreator,
    isCustom = isCustom,
    lastSyncedAtEpochMs = lastSyncedAtEpochMs
)

private fun SunoTrack.toJson(): SunoTrackJson = SunoTrackJson(
    id = id,
    title = title,
    audioUrl = audioUrl,
    localPath = localPath,
    imageUrl = imageUrl,
    durationMs = durationMs,
    playlistId = playlistId,
    creatorName = creatorName,
    sourceUrl = sourceUrl,
    lyrics = lyrics,
    stylePrompt = stylePrompt,
    descriptionPrompt = descriptionPrompt,
    tags = tags,
    mood = mood,
    genre = genre,
    downloadedAtEpochMs = downloadedAtEpochMs
)

// Batch 6 — serialisation/parsing helpers are exposed as internal top-level
// functions so the pure LibraryBackup helpers (export/import of the portable
// backup JSON and M3U generation) share the exact same schema as app-private
// storage. Behavior is unchanged; call sites inside LibraryStore are identical.

internal fun serialisePlaylist(playlist: SunoPlaylistJson): JSONObject {
    val tracks = JSONArray()
    playlist.tracks.forEach { track ->
        tracks.put(JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("audio_url", track.audioUrl ?: JSONObject.NULL)
            put("local_path", track.localPath ?: JSONObject.NULL)
            put("image_url", track.imageUrl ?: JSONObject.NULL)
            put("duration_ms", track.durationMs ?: JSONObject.NULL)
            put("playlist_id", track.playlistId ?: JSONObject.NULL)
            put("creator_name", track.creatorName ?: JSONObject.NULL)
            put("source_url", track.sourceUrl ?: JSONObject.NULL)
            put("lyrics", track.lyrics ?: JSONObject.NULL)
            put("style_prompt", track.stylePrompt ?: JSONObject.NULL)
            put("description_prompt", track.descriptionPrompt ?: JSONObject.NULL)
            // Batch 5 — optional discovery metadata. `tags` is always
            // written (possibly empty); `mood`/`genre` follow the same
            // NULL-when-absent convention as the other optional fields.
            put("tags", JSONArray(track.tags))
            put("mood", track.mood ?: JSONObject.NULL)
            put("genre", track.genre ?: JSONObject.NULL)
            put("downloaded_at_epoch_ms", track.downloadedAtEpochMs)
        })
    }

    return JSONObject().apply {
        put("id", playlist.id)
        put("title", playlist.title)
        put("creator_name", playlist.creatorName ?: JSONObject.NULL)
        put("source_url", playlist.sourceUrl ?: JSONObject.NULL)
        put("saved_from_other_creator", playlist.savedFromOtherCreator)
        put("is_custom", playlist.isCustom)
        put("last_synced_at_epoch_ms", playlist.lastSyncedAtEpochMs)
        put("tracks", tracks)
    }
}

internal fun parsePlaylistJson(json: JSONObject): SunoPlaylistJson? {
    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
    val title = json.optString("title").takeIf { it.isNotBlank() } ?: "Untitled"

    val tracksArray = json.optJSONArray("tracks") ?: JSONArray()
    val tracks = (0 until tracksArray.length()).mapNotNull { i ->
        parseTrackJson(tracksArray.getJSONObject(i))
    }

    return SunoPlaylistJson(
        id = id,
        title = title,
        creatorName = json.optNullableString("creator_name"),
        sourceUrl = json.optNullableString("source_url"),
        tracks = tracks,
        savedFromOtherCreator = json.optBoolean("saved_from_other_creator", false),
        isCustom = json.optBoolean("is_custom", false),
        lastSyncedAtEpochMs = json.optLong("last_synced_at_epoch_ms", 0L)
    )
}

internal fun parseTrackJson(json: JSONObject): SunoTrackJson? {
    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
    return SunoTrackJson(
        id = id,
        title = json.optString("title").takeIf { it.isNotBlank() } ?: "Untitled",
        audioUrl = json.optNullableString("audio_url"),
        localPath = json.optNullableString("local_path"),
        imageUrl = json.optNullableString("image_url"),
        durationMs = json.optLong("duration_ms", -1L).takeIf { it >= 0 },
        playlistId = json.optNullableString("playlist_id"),
        creatorName = json.optNullableString("creator_name"),
        sourceUrl = json.optNullableString("source_url"),
        lyrics = json.optNullableString("lyrics"),
        stylePrompt = json.optNullableString("style_prompt"),
        descriptionPrompt = json.optNullableString("description_prompt"),
        // Batch 5 — missing keys in old JSON yield emptyList()/null, so
        // libraries written before this batch load unchanged.
        tags = json.optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }
            }
        } ?: emptyList(),
        mood = json.optNullableString("mood"),
        genre = json.optNullableString("genre"),
        downloadedAtEpochMs = json.optLong("downloaded_at_epoch_ms", 0L)
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}
