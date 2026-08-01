package com.duskript.sunolocal.core.player

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PlaybackState — the persisted "resume where left off" snapshot.
 *
 * Written by LocalAudioPlayer on meaningful playback events (queue changes,
 * play/pause, seek, media transitions) and throttled ~every 5s while playing.
 * Read by LibraryViewModel to offer a Resume card after app restart.
 *
 * @property trackId currently loaded track (or last track of the session).
 * @property playlistId source playlist of that track, when known.
 * @property positionMs playback position to resume near.
 * @property queueIds ordered ids of the queue, so resume can rebuild it.
 * @property updatedAtEpochMs last write time (System.currentTimeMillis).
 */
data class PlaybackState(
    val trackId: String,
    val playlistId: String? = null,
    val positionMs: Long = 0L,
    val queueIds: List<String> = emptyList(),
    val updatedAtEpochMs: Long = 0L
)

/**
 * PlaybackStateStore — persists the single most recent [PlaybackState] as JSON
 * in app-private storage (suno_playback_state.json), mirroring
 * SyncSummaryStore's style. Load errors never crash the app; a corrupt or
 * missing file yields null and is reset.
 */
class PlaybackStateStore(context: Context) {

    private val stateFile = File(context.filesDir, FILE_NAME)

    /** Load the last playback state, or null when none exists / the file is corrupt. */
    fun load(): PlaybackState? {
        if (!stateFile.exists()) return null
        return try {
            parsePlaybackStateJson(JSONObject(stateFile.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $FILE_NAME — resetting", e)
            stateFile.delete()
            null
        }
    }

    /** Persist the current playback state. Failures are logged, never thrown. */
    fun save(state: PlaybackState) {
        try {
            stateFile.writeText(playbackStateToJson(state).toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $FILE_NAME", e)
        }
    }

    /** Remove the saved state (used when the queue becomes empty). */
    fun clear() {
        try {
            if (stateFile.exists()) stateFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear $FILE_NAME", e)
        }
    }

    companion object {
        private const val TAG = "PlaybackStateStore"
        private const val FILE_NAME = "suno_playback_state.json"
    }
}

// Serialisation helpers are internal top-level functions so the pure JVM unit
// test (PlaybackStateStoreTest) can round-trip them without an Android context.

internal fun playbackStateToJson(state: PlaybackState): JSONObject = JSONObject().apply {
    put("track_id", state.trackId)
    put("playlist_id", state.playlistId ?: JSONObject.NULL)
    put("position_ms", state.positionMs)
    put("queue_ids", JSONArray(state.queueIds))
    put("updated_at_epoch_ms", state.updatedAtEpochMs)
}

internal fun parsePlaybackStateJson(json: JSONObject): PlaybackState? {
    val trackId = json.optString("track_id").takeIf { it.isNotBlank() } ?: return null
    val queueIds = json.optJSONArray("queue_ids")?.let { array ->
        (0 until array.length()).mapNotNull { i ->
            array.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }
        }
    } ?: emptyList()
    return PlaybackState(
        trackId = trackId,
        playlistId = json.optNullableString("playlist_id"),
        positionMs = json.optLong("position_ms", 0L).coerceAtLeast(0L),
        queueIds = queueIds,
        updatedAtEpochMs = json.optLong("updated_at_epoch_ms", 0L)
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}
