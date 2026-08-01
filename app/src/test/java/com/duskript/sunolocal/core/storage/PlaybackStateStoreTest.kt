package com.duskript.sunolocal.core.storage

import com.duskript.sunolocal.core.player.PlaybackState
import com.duskript.sunolocal.core.player.parsePlaybackStateJson
import com.duskript.sunolocal.core.player.playbackStateToJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the v0.1.15 PlaybackStateStore JSON serialisation.
 *
 * The store itself needs an Android Context for app-private storage; the
 * serialisation helpers are pure top-level functions so they are tested here
 * on a plain JVM (./gradlew testDebugUnitTest) using the org.json test dep.
 */
class PlaybackStateStoreTest {

    @Test
    fun `round-trip preserves every field`() {
        val state = PlaybackState(
            trackId = "track-42",
            playlistId = "suno-pl-7",
            positionMs = 12345L,
            queueIds = listOf("track-42", "track-9", "track-3"),
            updatedAtEpochMs = 1700000000000L
        )
        val parsed = parsePlaybackStateJson(playbackStateToJson(state))
        assertEquals(state, parsed)
    }

    @Test
    fun `null playlist id and empty queue survive the round-trip`() {
        val state = PlaybackState(
            trackId = "track-1",
            playlistId = null,
            positionMs = 0L,
            queueIds = emptyList(),
            updatedAtEpochMs = 0L
        )
        val json = playbackStateToJson(state)
        assertEquals("track-1", json.getString("track_id"))
        assertNull("playlist_id must stay null", json.optString("playlist_id").takeIf { it != "null" && it.isNotBlank() })
        val parsed = parsePlaybackStateJson(json)
        assertEquals(state, parsed)
    }

    @Test
    fun `negative or missing position clamps to zero`() {
        val json = JSONObject().apply {
            put("track_id", "track-5")
            put("position_ms", -50L)
        }
        val parsed = parsePlaybackStateJson(json)
        assertEquals(0L, parsed?.positionMs)
    }

    @Test
    fun `missing track id yields null`() {
        val json = JSONObject().apply {
            put("position_ms", 100L)
            put("queue_ids", emptyList<Any>())
        }
        assertNull(parsePlaybackStateJson(json))
    }

    @Test
    fun `queue ids ignore blank and null entries`() {
        val json = JSONObject().apply {
            put("track_id", "track-1")
            put("queue_ids", listOf("track-1", "null", "  ", "track-2"))
        }
        val parsed = parsePlaybackStateJson(json)
        assertEquals(listOf("track-1", "track-2"), parsed?.queueIds)
    }

    @Test
    fun `missing queue ids default to empty list`() {
        val json = JSONObject().apply { put("track_id", "track-1") }
        assertEquals(emptyList<String>(), parsePlaybackStateJson(json)?.queueIds)
    }
}
