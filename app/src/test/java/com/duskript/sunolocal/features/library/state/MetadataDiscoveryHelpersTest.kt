package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Batch 5 metadata & discovery helpers
 * (MetadataDiscoveryHelpers.kt) — creator grouping and the local similar-tracks
 * heuristic. Pure functions over domain models, no Android dependencies.
 */
class MetadataDiscoveryHelpersTest {

    private fun track(
        id: String,
        title: String,
        creatorName: String? = null,
        stylePrompt: String? = null,
        tags: List<String> = emptyList(),
        genre: String? = null,
        mood: String? = null
    ) = SunoTrack(
        id = id,
        title = title,
        creatorName = creatorName,
        stylePrompt = stylePrompt,
        tags = tags,
        genre = genre,
        mood = mood
    )

    private fun playlist(id: String, creatorName: String?, vararg tracks: SunoTrack) =
        SunoPlaylist(id = id, title = "Playlist $id", creatorName = creatorName, tracks = tracks.toList())

    // --- Creator grouping -------------------------------------------------

    @Test
    fun `normalizeCreatorName trims and collapses whitespace, null for blank`() {
        assertEquals("Alice Smith", normalizeCreatorName("  Alice   Smith  "))
        assertEquals("Bob", normalizeCreatorName("Bob"))
        assertNull(normalizeCreatorName(null))
        assertNull(normalizeCreatorName("   "))
    }

    @Test
    fun `tracksByCreator matches case-insensitively and dedupes by id`() {
        val playlists = listOf(
            playlist("p1", "Alice",
                track("t1", "One", creatorName = "alice"),
                track("t2", "Two", creatorName = "Alice"),
                track("t3", "Three", creatorName = "Bob")),
            playlist("p2", "Bob", track("t4", "Four", creatorName = "ALICE"))
        )

        val aliceTracks = tracksByCreator(playlists, "Alice")
        assertEquals(listOf("t1", "t2", "t4"), aliceTracks.map { it.id })
    }

    @Test
    fun `tracksByCreator falls back to the owning playlist creator`() {
        val playlists = listOf(
            playlist("p1", "Alice", track("t1", "One", creatorName = null)),
            playlist("p2", "Bob", track("t2", "Two", creatorName = null))
        )

        val aliceTracks = tracksByCreator(playlists, "alice")
        assertEquals(listOf("t1"), aliceTracks.map { it.id })
    }

    @Test
    fun `tracksByCreator is empty for unknown or blank creators`() {
        val playlists = listOf(playlist("p1", "Alice", track("t1", "One", creatorName = "Alice")))
        assertTrue(tracksByCreator(playlists, "Nobody").isEmpty())
        assertTrue(tracksByCreator(playlists, "   ").isEmpty())
    }

    @Test
    fun `playlistsByCreator matches case-insensitively`() {
        val playlists = listOf(
            playlist("p1", "Alice"),
            playlist("p2", "ALICE"),
            playlist("p3", "Bob")
        )

        val alice = playlistsByCreator(playlists, "alice")
        assertEquals(listOf("p1", "p2"), alice.map { it.id })
        assertTrue(playlistsByCreator(playlists, "Carol").isEmpty())
    }

    // --- Similar tracks heuristic -----------------------------------------

    @Test
    fun `similarTracks scores shared tags, genre and style tokens`() {
        val target = track(
            "t0", "Midnight Drive",
            stylePrompt = "synthwave retrowave night drive",
            tags = listOf("synthwave", "instrumental"),
            genre = "Electronic"
        )
        val library = listOf(
            target,
            // Same genre + shared tag + shared style token "synthwave".
            track("t1", "Neon Highway", stylePrompt = "synthwave dark driving", tags = listOf("synthwave"), genre = "Electronic"),
            // Two shared style tokens ("night", "drive").
            track("t2", "City Lights", stylePrompt = "chill night drive", tags = emptyList(), genre = "Lo-fi"),
            // Nothing shared — must be excluded.
            track("t3", "Acoustic Morning", stylePrompt = "folk acoustic", tags = listOf("acoustic"), genre = "Folk"),
            // Two shared tags, different genre.
            track("t4", "Retro Wave", stylePrompt = "outrun", tags = listOf("instrumental", "synthwave"), genre = "Pop")
        )

        val similar = similarTracks(target, library)

        // t1 scores 3 (genre match + shared tag "synthwave" + shared style token
        // "synthwave"). t2 and t4 tie at 2 (t2: style tokens "night"/"drive";
        // t4: tags "instrumental"/"synthwave"). Ties break by title ascending,
        // so "City Lights" (t2) precedes "Retro Wave" (t4).
        assertEquals(listOf("t1", "t2", "t4"), similar.map { it.id })
        assertTrue("target itself must be excluded", similar.none { it.id == "t0" })
    }

    @Test
    fun `similarTracks excludes zero-overlap tracks and respects limit`() {
        val target = track("t0", "A", tags = listOf("jazz"), genre = "Jazz")
        val library = listOf(
            target,
            track("t1", "B", tags = listOf("jazz")),
            track("t2", "C", tags = listOf("jazz")),
            track("t3", "D", tags = listOf("metal")),
            track("t4", "E", tags = listOf("jazz"))
        )

        val similar = similarTracks(target, library, limit = 2)
        assertEquals(listOf("t1", "t2"), similar.map { it.id })
    }

    @Test
    fun `similarTracks tie-breaks by title then id for stable ordering`() {
        val target = track("t0", "A", tags = listOf("pop"), genre = "Pop")
        val library = listOf(
            target,
            track("z9", "Zulu", tags = listOf("pop"), genre = "Pop"),
            track("a1", "Alpha", tags = listOf("pop"), genre = "Pop"),
            track("m5", "Alpha", tags = listOf("pop"), genre = "Pop")
        )

        // Same score: title asc, then id asc.
        assertEquals(listOf("a1", "m5", "z9"), similarTracks(target, library).map { it.id })
    }

    @Test
    fun `similarTracks returns empty when only the target exists`() {
        val target = track("t0", "Solo", tags = listOf("ambient"))
        assertTrue(similarTracks(target, listOf(target)).isEmpty())
    }

    @Test
    fun `similarTracks matches on style prompt tokens alone`() {
        val target = track("t0", "A", stylePrompt = "epic orchestral cinematic")
        val library = listOf(
            target,
            track("t1", "B", stylePrompt = "epic cinematic trailer"),
            track("t2", "C", stylePrompt = "lofi beats")
        )

        assertEquals(listOf("t1"), similarTracks(target, library).map { it.id })
    }

    // --- Row summary line --------------------------------------------------

    @Test
    fun `trackMetadataLine renders genre mood and deduped tags`() {
        val line = trackMetadataLine(
            track("t1", "X", tags = listOf("pop", "pop", "dance"), genre = "Pop", mood = "Energetic")
        )
        assertEquals("Genre: Pop • Mood: Energetic • Tags: pop, dance", line)
    }

    @Test
    fun `trackMetadataLine is null without discovery metadata`() {
        assertNull(trackMetadataLine(track("t1", "X")))
    }
}
