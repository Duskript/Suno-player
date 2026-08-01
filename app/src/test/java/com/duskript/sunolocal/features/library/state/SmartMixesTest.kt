package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the v0.1.15 smart local mixes pure helper.
 * Pure JVM — no Android context required (./gradlew testDebugUnitTest).
 */
class SmartMixesTest {

    private fun track(
        id: String,
        downloaded: Boolean = false,
        downloadedAtEpochMs: Long = 0L,
        audioUrl: String? = null
    ) = SunoTrack(
        id = id,
        title = "Track $id",
        creatorName = "Ada",
        localPath = if (downloaded) "/data/$id.mp3" else null,
        audioUrl = audioUrl,
        downloadedAtEpochMs = downloadedAtEpochMs
    )

    private fun playlist(id: String, tracks: List<SunoTrack>): SunoPlaylist =
        SunoPlaylist(id = id, title = "Playlist $id", tracks = tracks)

    @Test
    fun `smart favorites mix contains only favorited playable tracks`() {
        val favorited = track("f1", downloaded = true)
        val favoritedStreaming = track("f2", audioUrl = "https://cdn.example/f2.mp3")
        val notFavorited = track("n1", downloaded = true)
        val playlists = listOf(
            playlist("p1", listOf(favorited, notFavorited)),
            playlist("p2", listOf(favoritedStreaming))
        )
        val mixes = buildSmartMixes(playlists, favoriteTrackIds = setOf("f1", "f2", "missing"))

        val favorites = mixes.first { it.id == SMART_MIX_FAVORITES_ID }
        assertEquals(setOf("f1", "f2"), favorites.tracks.map { it.id }.toSet())
    }

    @Test
    fun `smart recent mix is sorted newest first and only downloaded`() {
        val old = track("old", downloaded = true, downloadedAtEpochMs = 1_000L)
        val middle = track("mid", downloaded = true, downloadedAtEpochMs = 2_000L)
        val newest = track("new", downloaded = true, downloadedAtEpochMs = 3_000L)
        val streamed = track("stream", audioUrl = "https://cdn.example/s.mp3") // not downloaded
        val playlists = listOf(playlist("p1", listOf(old, middle, newest, streamed)))

        val mixes = buildSmartMixes(playlists, favoriteTrackIds = emptySet())

        val recent = mixes.first { it.id == SMART_MIX_RECENT_ID }
        assertEquals(listOf("new", "mid", "old"), recent.tracks.map { it.id })
        assertTrue(recent.tracks.none { it.id == "stream" })
    }

    @Test
    fun `smart streaming mix contains playable tracks without local files`() {
        val downloaded = track("d1", downloaded = true)
        val streamable = track("s1", audioUrl = "https://cdn.example/s1.mp3")
        val noAudio = track("x1") // neither downloaded nor streamable
        val playlists = listOf(playlist("p1", listOf(downloaded, streamable, noAudio)))

        val mixes = buildSmartMixes(playlists, favoriteTrackIds = emptySet())

        val streaming = mixes.first { it.id == SMART_MIX_STREAMING_ID }
        assertEquals(listOf("s1"), streaming.tracks.map { it.id })
    }

    @Test
    fun `smart mixes are stable non-custom playlists with smart prefix ids`() {
        val playlists = listOf(
            playlist("p1", listOf(track("a", downloaded = true, downloadedAtEpochMs = 5L)))
        )
        val mixes = buildSmartMixes(playlists, favoriteTrackIds = setOf("a"))

        assertEquals(SMART_MIX_PREFIX, "smart-")
        mixes.forEach { mix ->
            assertTrue("id ${mix.id} must be prefixed smart-", mix.id.startsWith(SMART_MIX_PREFIX))
            assertEquals("smart mixes are never custom", false, mix.isCustom)
        }
        // Duplicate calls yield the same stable ids.
        val again = buildSmartMixes(playlists, favoriteTrackIds = setOf("a"))
        assertEquals(mixes.map { it.id }, again.map { it.id })
    }

    @Test
    fun `empty mixes are dropped and duplicate track ids are deduped`() {
        val shared = track("dup", downloaded = true, downloadedAtEpochMs = 9L)
        val playlists = listOf(
            playlist("p1", listOf(shared)),
            playlist("p2", listOf(shared))
        )
        val mixes = buildSmartMixes(playlists, favoriteTrackIds = emptySet())

        // No favorites -> no smart-favorites mix.
        assertTrue(mixes.none { it.id == SMART_MIX_FAVORITES_ID })
        // The shared track appears exactly once in smart-recent.
        val recent = mixes.first { it.id == SMART_MIX_RECENT_ID }
        assertEquals(listOf("dup"), recent.tracks.map { it.id })
    }

    @Test
    fun `isSmartMixId recognises only the smart prefix`() {
        assertTrue(isSmartMixId("smart-favorites"))
        assertTrue(isSmartMixId("smart-recent"))
        assertTrue(isSmartMixId("smart-failed-or-streaming"))
        assertTrue(!isSmartMixId("custom-123"))
        assertTrue(!isSmartMixId("suno-playlist-abc"))
        assertTrue(!isSmartMixId("smart"))
    }
}
