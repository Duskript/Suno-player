package com.duskript.sunolocal.core.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * FavoritesStore — persists the user's starred/favorite track IDs as a JSON
 * array of strings in app-private storage (suno_favorites.json), mirroring
 * LibraryStore's hidden-playlists style.
 *
 * Favorites are app-local state only: Suno API data is never mutated, and the
 * favorites file is not part of library exports. Load errors never crash the
 * app — a corrupt/missing file yields an empty set and is reset.
 */
class FavoritesStore(context: Context) {

    private val favoritesFile = File(context.filesDir, FILE_NAME)

    /** Load all favorited track IDs; empty set when none exist / file is corrupt. */
    fun loadFavoriteTrackIds(): Set<String> {
        if (!favoritesFile.exists()) return emptySet()
        return try {
            val array = JSONArray(favoritesFile.readText())
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }
            }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $FILE_NAME — resetting", e)
            favoritesFile.delete()
            emptySet()
        }
    }

    /** Add or remove [trackId] from the favorites set, persisting immediately. */
    fun setFavoriteTrack(trackId: String, favorite: Boolean) {
        val current = loadFavoriteTrackIds().toMutableSet()
        if (favorite) {
            current.add(trackId)
        } else {
            current.remove(trackId)
        }
        persistFavoriteTrackIds(current)
    }

    /** True when [trackId] is currently favorited. */
    fun isFavoriteTrack(trackId: String): Boolean = trackId in loadFavoriteTrackIds()

    private fun persistFavoriteTrackIds(ids: Set<String>) {
        val array = JSONArray()
        ids.sorted().forEach { array.put(it) }
        favoritesFile.writeText(array.toString(2))
    }

    companion object {
        private const val TAG = "FavoritesStore"
        private const val FILE_NAME = "suno_favorites.json"
    }
}
