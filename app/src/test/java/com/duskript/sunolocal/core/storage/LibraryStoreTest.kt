package com.duskript.sunolocal.core.storage

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Unit tests for LibraryStore's JSON serialisation helpers.
 *
 * These tests validate the JSON domain model (SunoPlaylistJson / SunoTrackJson)
 * used by LibraryStore for persistence. They do NOT require Android context
 * and can run in a standard JVM (./gradlew test).
 *
 * The actual LibraryStore uses Android Context to access app-private storage,
 * so integration tests for read/write require either:
 *   1. Robolectric for Android environment simulation, or
 *   2. An instrumented test running on a device/emulator.
 */
class LibraryStoreTest {

    @Test
    fun `SunoPlaylistJson can be constructed with default values`() {
        val playlist = SunoPlaylistJson(
            id = "test-123",
            title = "My Playlist"
        )

        assertEquals("test-123", playlist.id)
        assertEquals("My Playlist", playlist.title)
        assertNull("creatorName should be null by default", playlist.creatorName)
        assertTrue("tracks should be empty by default", playlist.tracks.isEmpty())
        assertEquals("savedFromOtherCreator should default to false", false, playlist.savedFromOtherCreator)
    }

    @Test
    fun `SunoPlaylistJson can hold tracks`() {
        val track = SunoTrackJson(
            id = "track-1",
            title = "Awesome Song",
            audioUrl = "https://cdn1.suno.ai/track-1.mp3",
            durationMs = 180000L,
            creatorName = "Artist One"
        )

        val playlist = SunoPlaylistJson(
            id = "pl-1",
            title = "Favorites",
            tracks = listOf(track)
        )

        assertEquals(1, playlist.tracks.size)
        assertEquals("Awesome Song", playlist.tracks[0].title)
        assertEquals("Artist One", playlist.tracks[0].creatorName)
        assertEquals(180000L, playlist.tracks[0].durationMs)
    }

    @Test
    fun `SunoTrackJson local path and download timestamp`() {
        val track = SunoTrackJson(
            id = "track-2",
            title = "Downloaded Track",
            localPath = "/data/data/com.duskript.sunolocal/music/track.mp3",
            downloadedAtEpochMs = 1700000000000L
        )

        assertNotNull("localPath should be set", track.localPath)
        assertTrue("downloadedAtEpochMs should be > 0", track.downloadedAtEpochMs > 0)
        assertNull("audioUrl should be null when not provided", track.audioUrl)
    }

    @Test
    fun `SunoTrackJson discovery metadata defaults are absent for old JSON`() {
        val track = SunoTrackJson(id = "track-3", title = "Legacy Track")

        assertTrue("tags should default to empty", track.tags.isEmpty())
        assertNull("mood should default to null", track.mood)
        assertNull("genre should default to null", track.genre)
    }

    @Test
    fun `SunoTrackJson carries discovery metadata for new JSON`() {
        val track = SunoTrackJson(
            id = "track-4",
            title = "Rich Track",
            tags = listOf("synthwave", "instrumental"),
            mood = "Energetic",
            genre = "Electronic"
        )

        assertEquals(listOf("synthwave", "instrumental"), track.tags)
        assertEquals("Energetic", track.mood)
        assertEquals("Electronic", track.genre)
    }

    @Test
    fun `Playlist round-trip serialisation maintains savedFromOtherCreator flag`() {
        val playlist = SunoPlaylistJson(
            id = "pl-external",
            title = "Cool Creator Tracks",
            creatorName = "OtherCreator",
            sourceUrl = "https://suno.com/playlist/abc-123",
            savedFromOtherCreator = true
        )

        assertEquals(true, playlist.savedFromOtherCreator)
        assertEquals("OtherCreator", playlist.creatorName)
    }
}
