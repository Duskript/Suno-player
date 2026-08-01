package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Batch 4 playlist-manager helpers
 * (PlaylistManagerHelpers.kt) — pure functions over domain models, no Android
 * dependencies required.
 */
class PlaylistManagerHelpersTest {

    private fun track(id: String, title: String, playlistId: String? = null) = SunoTrack(
        id = id,
        title = title,
        playlistId = playlistId
    )

    @Test
    fun `duplicate copies tracks into a new custom playlist with order and playlistId rewritten`() {
        val source = SunoPlaylist(
            id = "pl-1",
            title = "Roadtrip Mix",
            creatorName = "You",
            isCustom = true,
            tracks = listOf(
                track("t1", "One", "pl-1"),
                track("t2", "Two", "pl-1"),
                track("t3", "Three", "pl-1")
            )
        )

        val duplicate = buildDuplicatePlaylist(
            source = source,
            newId = "custom-123",
            newTitle = "Roadtrip Mix Copy",
            createdAtEpochMs = 42L
        )

        // Output is always a custom playlist owned by the user.
        assertEquals("custom-123", duplicate.id)
        assertEquals("Roadtrip Mix Copy", duplicate.title)
        assertTrue("duplicate must be custom", duplicate.isCustom)
        assertEquals("You", duplicate.creatorName)
        assertEquals(42L, duplicate.lastSyncedAtEpochMs)
        assertFalse(duplicate.savedFromOtherCreator)
        assertNull(duplicate.sourceUrl)

        // Track list and order are copied exactly.
        assertEquals(listOf("t1", "t2", "t3"), duplicate.tracks.map { it.id })
        assertEquals(listOf("One", "Two", "Three"), duplicate.tracks.map { it.title })
        assertTrue(
            "copied tracks must belong to the new playlist id",
            duplicate.tracks.all { it.playlistId == "custom-123" }
        )

        // The source playlist is not mutated.
        assertEquals("pl-1", source.tracks[0].playlistId)
        assertEquals(3, source.tracks.size)
    }

    @Test
    fun `duplicate of a non-custom playlist yields a custom playlist`() {
        val source = SunoPlaylist(
            id = "suno-pl",
            title = "Chill Beats",
            creatorName = "OtherCreator",
            sourceUrl = "https://suno.com/playlist/abc-123",
            savedFromOtherCreator = true,
            tracks = listOf(track("t1", "One", "suno-pl"))
        )

        val duplicate = buildDuplicatePlaylist(
            source = source,
            newId = "custom-9",
            newTitle = "Chill Beats Copy",
            createdAtEpochMs = 7L
        )

        assertTrue(duplicate.isCustom)
        assertEquals("You", duplicate.creatorName)
        assertFalse(duplicate.savedFromOtherCreator)
        assertNull("custom mix must not inherit the Suno source URL", duplicate.sourceUrl)
        assertEquals("custom-9", duplicate.tracks.single().playlistId)
    }

    @Test
    fun `defaultDuplicateTitle appends Copy and is blank-safe`() {
        assertEquals("My Mix Copy", defaultDuplicateTitle("My Mix"))
        assertEquals("My Mix Copy", defaultDuplicateTitle("  My Mix  "))
        assertEquals("My Suno Mix Copy", defaultDuplicateTitle(""))
        assertEquals("My Suno Mix Copy", defaultDuplicateTitle("   "))
    }

    @Test
    fun `cleanPlaylistTitle trims and falls back when blank`() {
        assertEquals("New Name", cleanPlaylistTitle("  New Name  ", fallback = "Fallback"))
        assertEquals("Fallback", cleanPlaylistTitle("   ", fallback = "Fallback"))
        assertEquals("Fallback", cleanPlaylistTitle("", fallback = "Fallback"))
        assertEquals("Kept", cleanPlaylistTitle("Kept", fallback = "Fallback"))
    }
}
