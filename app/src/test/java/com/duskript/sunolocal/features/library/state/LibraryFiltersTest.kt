package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Batch 3 search/filter pure helpers.
 * Pure JVM — no Android context required (./gradlew testDebugUnitTest).
 */
class LibraryFiltersTest {

    private fun playlist(
        id: String,
        title: String,
        creator: String? = null,
        isCustom: Boolean = false,
        tracks: List<SunoTrack> = emptyList()
    ) = SunoPlaylist(
        id = id,
        title = title,
        creatorName = creator,
        tracks = tracks,
        isCustom = isCustom
    )

    private fun track(
        id: String,
        title: String = "Track $id",
        creator: String? = null,
        lyrics: String? = null,
        stylePrompt: String? = null,
        descriptionPrompt: String? = null,
        downloaded: Boolean = false
    ) = SunoTrack(
        id = id,
        title = title,
        creatorName = creator,
        lyrics = lyrics,
        stylePrompt = stylePrompt,
        descriptionPrompt = descriptionPrompt,
        localPath = if (downloaded) "/data/$id.mp3" else null
    )

    @Test
    fun `playlist query matches title case-insensitively`() {
        val playlists = listOf(
            playlist("1", "Roadtrip Anthems", creator = "Ada"),
            playlist("2", "Late Night Coding", creator = "Grace"),
            playlist("3", "workout mix", creator = null)
        )
        assertEquals(
            listOf("1"),
            filterPlaylists(playlists, "ROADTRIP", LibraryPlaylistFilter.ALL).map { it.id }
        )
        assertEquals(
            listOf("3"),
            filterPlaylists(playlists, "Workout", LibraryPlaylistFilter.ALL).map { it.id }
        )
    }

    @Test
    fun `playlist query matches creator case-insensitively`() {
        val playlists = listOf(
            playlist("1", "First Mix", creator = "Ada Lovelace"),
            playlist("2", "Second Mix", creator = "Grace Hopper"),
            playlist("3", "Third Mix", creator = null)
        )
        assertEquals(
            listOf("2"),
            filterPlaylists(playlists, "grace hopper", LibraryPlaylistFilter.ALL).map { it.id }
        )
        assertEquals(
            listOf("1", "2"),
            filterPlaylists(playlists, "a", LibraryPlaylistFilter.ALL).map { it.id }
        )
    }

    @Test
    fun `custom mixes filter keeps only custom playlists`() {
        val playlists = listOf(
            playlist("1", "My Roadtrip Mix", isCustom = true),
            playlist("2", "Suno Library", isCustom = false)
        )
        assertEquals(
            listOf("1"),
            filterPlaylists(playlists, "", LibraryPlaylistFilter.CUSTOM_MIXES).map { it.id }
        )
        // Query and filter combine: "roadtrip" only matches the custom mix.
        assertEquals(
            listOf("1"),
            filterPlaylists(playlists, "roadtrip", LibraryPlaylistFilter.CUSTOM_MIXES).map { it.id }
        )
        // "suno" matches only the non-custom playlist, so the custom filter hides it.
        assertTrue(
            filterPlaylists(playlists, "suno", LibraryPlaylistFilter.CUSTOM_MIXES).isEmpty()
        )
    }

    @Test
    fun `downloaded only filter includes fully and partially downloaded playlists`() {
        val playlists = listOf(
            playlist(
                "full",
                "Fully Downloaded",
                tracks = listOf(track("a", downloaded = true), track("b", downloaded = true))
            ),
            playlist(
                "partial",
                "Partially Downloaded",
                tracks = listOf(track("c", downloaded = true), track("d", downloaded = false))
            ),
            playlist(
                "none",
                "Nothing Downloaded",
                tracks = listOf(track("e", downloaded = false), track("f", downloaded = false))
            ),
            playlist("empty", "Empty Mix", isCustom = true)
        )
        assertEquals(
            listOf("full", "partial"),
            filterPlaylists(playlists, "", LibraryPlaylistFilter.DOWNLOADED_ONLY).map { it.id }
        )
    }

    @Test
    fun `track query matches title creator lyrics style and description`() {
        val tracks = listOf(
            track("t1", title = "Neon Sunrise", creator = "Kira"),
            track("t2", title = "Anything", creator = "Alex", lyrics = "running through the RAIN"),
            track("t3", title = "Anything 2", stylePrompt = "synthwave, dreamy pads"),
            track("t4", title = "Anything 3", descriptionPrompt = "A song about deep space travel"),
            track("t5", title = "Anything 4", creator = "Zed")
        )
        assertEquals(listOf("t1"), filterTracks(tracks, "neon").map { it.id })
        assertEquals(listOf("t2"), filterTracks(tracks, "RAIN").map { it.id })
        assertEquals(listOf("t3"), filterTracks(tracks, "Synthwave").map { it.id })
        assertEquals(listOf("t4"), filterTracks(tracks, "deep space").map { it.id })
        assertEquals(listOf("t5"), filterTracks(tracks, "zed").map { it.id })
        // No match → empty result.
        assertTrue(filterTracks(tracks, "zzzz-not-here").isEmpty())
    }

    @Test
    fun `blank query returns input filtered only by selected filter`() {
        val playlists = listOf(
            playlist("1", "My Mix", isCustom = true),
            playlist("2", "Suno Library", tracks = listOf(track("a", downloaded = true)))
        )
        assertEquals(playlists, filterPlaylists(playlists, "   ", LibraryPlaylistFilter.ALL))
        assertEquals(
            listOf("1"),
            filterPlaylists(playlists, "", LibraryPlaylistFilter.CUSTOM_MIXES).map { it.id }
        )
        assertEquals(
            listOf("2"),
            filterPlaylists(playlists, " ", LibraryPlaylistFilter.DOWNLOADED_ONLY).map { it.id }
        )
    }

    @Test
    fun `null metadata fields are handled without crashing`() {
        val playlists = listOf(playlist("1", "No Creator", creator = null))
        assertEquals(
            listOf("1"),
            filterPlaylists(playlists, "no creator", LibraryPlaylistFilter.ALL).map { it.id }
        )
        val tracks = listOf(
            track("t1", title = "Plain", creator = null, lyrics = null, stylePrompt = null, descriptionPrompt = null)
        )
        assertEquals(listOf("t1"), filterTracks(tracks, "plain").map { it.id })
        assertTrue(filterTracks(tracks, "missing").isEmpty())
    }
}
