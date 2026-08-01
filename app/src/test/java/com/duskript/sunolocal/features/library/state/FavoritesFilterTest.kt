package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the v0.1.15 track filter chips (Favorites / Not downloaded).
 * Pure JVM — no Android context required (./gradlew testDebugUnitTest).
 */
class FavoritesFilterTest {

    private fun track(
        id: String,
        title: String = "Track $id",
        downloaded: Boolean = false,
        audioUrl: String? = null
    ) = SunoTrack(
        id = id,
        title = title,
        localPath = if (downloaded) "/data/$id.mp3" else null,
        audioUrl = audioUrl
    )

    @Test
    fun `legacy two-arg filterTracks still matches by query only`() {
        val tracks = listOf(
            track("1", title = "Midnight Drive", downloaded = true),
            track("2", title = "Sunrise"),
            track("3", title = "Morning Coffee")
        )
        assertEquals(
            listOf("1"),
            filterTracks(tracks, "midnight").map { it.id }
        )
        // Blank query returns everything, unchanged (legacy contract).
        assertEquals(tracks, filterTracks(tracks, ""))
    }

    @Test
    fun `favorites filter keeps only favorited tracks`() {
        val tracks = listOf(
            track("1", downloaded = true),
            track("2", downloaded = true),
            track("3", downloaded = true)
        )
        val favorites = setOf("1", "3")
        val filtered = filterTracks(tracks, "", favorites, TrackFilter.FAVORITES)
        assertEquals(listOf("1", "3"), filtered.map { it.id })
    }

    @Test
    fun `not downloaded filter keeps only tracks without local files`() {
        val tracks = listOf(
            track("1", downloaded = true),
            track("2", audioUrl = "https://cdn.example/2.mp3"),
            track("3") // no local file and no audio url
        )
        val filtered = filterTracks(tracks, "", emptySet(), TrackFilter.NOT_DOWNLOADED)
        assertEquals(listOf("2", "3"), filtered.map { it.id })
    }

    @Test
    fun `query and filter combine`() {
        val tracks = listOf(
            track("1", title = "Neon Nights", downloaded = true),
            track("2", title = "Neon Dreams"),
            track("3", title = "Neon Sky", downloaded = true)
        )
        val favorites = setOf("1", "2")
        val filtered = filterTracks(tracks, "neon", favorites, TrackFilter.FAVORITES)
        assertEquals(listOf("1", "2"), filtered.map { it.id })

        val notDownloaded = filterTracks(tracks, "neon", favorites, TrackFilter.NOT_DOWNLOADED)
        assertEquals(listOf("2"), notDownloaded.map { it.id })
    }

    @Test
    fun `all filter behaves like the legacy query filter`() {
        val tracks = listOf(
            track("1", title = "Quiet Morning", downloaded = true),
            track("2", title = "Loud Night")
        )
        assertEquals(
            filterTracks(tracks, "quiet"),
            filterTracks(tracks, "quiet", emptySet(), TrackFilter.ALL)
        )
    }
}
