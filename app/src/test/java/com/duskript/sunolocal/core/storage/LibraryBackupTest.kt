package com.duskript.sunolocal.core.storage

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import org.junit.Test

/**
 * Unit tests for the Batch 6 export/backup helpers (LibraryBackup).
 *
 * Pure JVM tests — no Android context required. They cover:
 *  - export -> import round-trip fidelity (ids, order, metadata, custom mixes),
 *  - duplicate conflict rules (playlist id and track id),
 *  - controlled failure on corrupt/invalid backup JSON,
 *  - M3U playlist generation.
 */
class LibraryBackupTest {

    // ---- fixtures ----

    private fun richTrack(id: String, title: String = "Track $id"): SunoTrackJson = SunoTrackJson(
        id = id,
        title = title,
        audioUrl = "https://cdn1.suno.ai/$id.mp3",
        localPath = "/data/user/0/com.duskript.sunolocal/music/$id.mp3",
        imageUrl = "https://cdn1.suno.ai/$id.jpg",
        durationMs = 217000L,
        playlistId = "pl-main",
        creatorName = "Artist One",
        sourceUrl = "https://suno.com/song/$id",
        lyrics = "Verse one\nVerse two",
        stylePrompt = "synthwave, retro drums",
        descriptionPrompt = "A late-night drive",
        tags = listOf("synthwave", "retro"),
        mood = "Energetic",
        genre = "Electronic",
        downloadedAtEpochMs = 1_700_000_000_000L
    )

    private fun playlist(
        id: String,
        title: String,
        tracks: List<SunoTrackJson>,
        isCustom: Boolean = false,
        creatorName: String? = "Artist One"
    ): SunoPlaylistJson = SunoPlaylistJson(
        id = id,
        title = title,
        creatorName = creatorName,
        sourceUrl = "https://suno.com/playlist/$id",
        tracks = tracks,
        isCustom = isCustom,
        lastSyncedAtEpochMs = 1_700_000_000_000L
    )

    // ---- export -> import round-trip ----

    @Test
    fun `export then import round-trip preserves ids order and metadata`() {
        val customTrack = richTrack("t-custom-1").copy(playlistId = "pl-custom")
        val customMix = playlist("pl-custom", "My Mix", listOf(customTrack), isCustom = true, creatorName = "You")
        val main = playlist("pl-main", "Main", listOf(richTrack("t-1"), richTrack("t-2")))

        val backupJson = LibraryBackup.exportLibraryJson(listOf(main, customMix))
        val result = LibraryBackup.importLibraryJson(emptyList(), backupJson)

        assertEquals(2, result.importedPlaylists)
        assertEquals(0, result.skippedPlaylists)
        assertEquals(3, result.importedTracks)
        assertEquals(0, result.skippedTracks)

        val restored = result.playlists
        assertEquals(listOf("pl-main", "pl-custom"), restored.map { it.id })

        // Track order and ids survive.
        assertEquals(listOf("t-1", "t-2"), restored[0].tracks.map { it.id })
        assertEquals(listOf("t-custom-1"), restored[1].tracks.map { it.id })

        // Metadata fields survive (Batch 5 discovery fields included).
        val restoredTrack = restored[0].tracks[0]
        assertEquals(listOf("synthwave", "retro"), restoredTrack.tags)
        assertEquals("Energetic", restoredTrack.mood)
        assertEquals("Electronic", restoredTrack.genre)
        assertEquals("Verse one\nVerse two", restoredTrack.lyrics)
        assertEquals("synthwave, retro drums", restoredTrack.stylePrompt)
        assertEquals("/data/user/0/com.duskript.sunolocal/music/t-1.mp3", restoredTrack.localPath)
        assertEquals("https://cdn1.suno.ai/t-1.mp3", restoredTrack.audioUrl)
        assertEquals(217000L, restoredTrack.durationMs)
        assertEquals(1_700_000_000_000L, restoredTrack.downloadedAtEpochMs)

        // Custom playlist membership survives.
        assertTrue(restored[1].isCustom)
        assertEquals("You", restored[1].creatorName)
        assertEquals("pl-custom", restored[1].tracks[0].playlistId)

        // Export is deterministic and re-parseable (idempotent round-trip).
        val secondJson = LibraryBackup.exportLibraryJson(result.playlists)
        assertEquals(backupJson, secondJson)
    }

    @Test
    fun `wrapper object form imports like the raw array form`() {
        val track = richTrack("t-1")
        val main = playlist("pl-main", "Main", listOf(track))
        val raw = LibraryBackup.exportLibraryJson(listOf(main))
        val wrapped = """{"version":1,"playlists":$raw}"""

        val fromRaw = LibraryBackup.importLibraryJson(emptyList(), raw)
        val fromWrapped = LibraryBackup.importLibraryJson(emptyList(), wrapped)

        assertEquals(fromRaw.playlists, fromWrapped.playlists)
        assertEquals(1, fromWrapped.importedPlaylists)
        assertEquals("t-1", fromWrapped.playlists[0].tracks[0].id)
    }

    // ---- duplicate conflict rules ----

    @Test
    fun `import skips duplicate playlist ids and keeps the existing one`() {
        val existingTrack = richTrack("t-old").copy(title = "Original title")
        val existing = playlist("pl-main", "Original playlist", listOf(existingTrack))

        // Backup carries the same playlist id with different content plus a new playlist.
        val conflictingTrack = richTrack("t-new").copy(title = "Imported title")
        val backup = LibraryBackup.exportLibraryJson(
            listOf(
                playlist("pl-main", "Imported playlist", listOf(conflictingTrack)),
                playlist("pl-new", "Fresh", listOf(richTrack("t-3")))
            )
        )

        val result = LibraryBackup.importLibraryJson(listOf(existing), backup)

        assertEquals(1, result.importedPlaylists)
        assertEquals(1, result.skippedPlaylists)
        assertEquals(1, result.importedTracks)
        assertEquals(0, result.skippedTracks)

        // Existing playlist wins: same id, original content untouched.
        assertEquals(2, result.playlists.size)
        val winner = result.playlists.first { it.id == "pl-main" }
        assertEquals("Original playlist", winner.title)
        assertEquals(listOf("t-old"), winner.tracks.map { it.id })
        // The imported duplicate's tracks must not leak in.
        assertFalse(winner.tracks.any { it.id == "t-new" })
        // The non-duplicate playlist is appended.
        assertEquals("pl-new", result.playlists.last().id)
    }

    @Test
    fun `import dedupes duplicate track ids inside an imported playlist`() {
        val backup = LibraryBackup.exportLibraryJson(
            listOf(
                playlist("pl-a", "A", listOf(richTrack("t-1"), richTrack("t-2"), richTrack("t-1"), richTrack("t-3")))
            )
        )

        val result = LibraryBackup.importLibraryJson(emptyList(), backup)

        assertEquals(1, result.importedPlaylists)
        assertEquals(3, result.importedTracks)
        assertEquals(1, result.skippedTracks)
        // First occurrence wins and order is preserved.
        assertEquals(listOf("t-1", "t-2", "t-3"), result.playlists[0].tracks.map { it.id })
    }

    @Test
    fun `import never deletes existing library content`() {
        val existing = playlist("pl-keep", "Keep me", listOf(richTrack("t-old")))
        val backup = LibraryBackup.exportLibraryJson(listOf(playlist("pl-new", "New", listOf(richTrack("t-1")))))

        val result = LibraryBackup.importLibraryJson(listOf(existing), backup)

        assertEquals(2, result.playlists.size)
        assertEquals("pl-keep", result.playlists[0].id)
        assertEquals("pl-new", result.playlists[1].id)
        // Existing playlist's tracks remain untouched.
        assertEquals(listOf("t-old"), result.playlists[0].tracks.map { it.id })
    }

    // ---- corrupt / invalid input ----

    @Test
    fun `non-json input throws LibraryBackupException`() {
        assertThrowsLibraryBackup { LibraryBackup.importLibraryJson(emptyList(), "this is not json") }
        assertThrowsLibraryBackup { LibraryBackup.importLibraryJson(emptyList(), "") }
    }

    @Test
    fun `json without a playlists array throws LibraryBackupException`() {
        assertThrowsLibraryBackup { LibraryBackup.importLibraryJson(emptyList(), """{"version":1}""") }
        assertThrowsLibraryBackup { LibraryBackup.importLibraryJson(emptyList(), """{}""") }
    }

    @Test
    fun `non-object elements throw LibraryBackupException`() {
        assertThrowsLibraryBackup { LibraryBackup.importLibraryJson(emptyList(), """[1,2,3]""") }
        assertThrowsLibraryBackup { LibraryBackup.importLibraryJson(emptyList(), """["a"]""") }
    }

    @Test
    fun `playlist without a valid id throws LibraryBackupException`() {
        assertThrowsLibraryBackup {
            LibraryBackup.importLibraryJson(emptyList(), """[{"title":"no id here"}]""")
        }
    }

    @Test
    fun `failed parse leaves the existing library untouched`() {
        val existing = playlist("pl-keep", "Keep me", listOf(richTrack("t-old")))
        try {
            LibraryBackup.importLibraryJson(listOf(existing), "garbage")
            fail("Expected LibraryBackupException")
        } catch (expected: LibraryBackupException) {
            // No mutation on failure: the merge only happens after parsing succeeds.
            assertEquals("pl-keep", existing.id)
            assertEquals(listOf("t-old"), existing.tracks.map { it.id })
        }
    }

    // ---- M3U ----

    @Test
    fun `m3u output includes header and track locations`() {
        val track = SunoTrack(
            id = "t-1",
            title = "Neon Drive",
            localPath = "/data/user/0/com.duskript.sunolocal/music/t-1.mp3",
            audioUrl = "https://cdn1.suno.ai/t-1.mp3",
            sourceUrl = "https://suno.com/song/t-1",
            durationMs = 217_000L
        )
        val playlist = SunoPlaylist(id = "pl-1", title = "Drive Mix", tracks = listOf(track))

        val m3u = LibraryBackup.exportPlaylistM3u(playlist)

        assertTrue(m3u.startsWith("#EXTM3U"))
        assertTrue(m3u.contains("#EXTINF:217,Neon Drive"))
        assertTrue(m3u.contains("/data/user/0/com.duskript.sunolocal/music/t-1.mp3"))
    }

    @Test
    fun `m3u falls back to audio url when no local path and omits tracks with no location`() {
        val streamOnly = SunoTrack(
            id = "t-2",
            title = "Cloud Only",
            audioUrl = "https://cdn1.suno.ai/t-2.mp3"
        )
        val orphan = SunoTrack(id = "t-3", title = "No location")
        val playlist = SunoPlaylist(id = "pl-2", title = "Mix", tracks = listOf(streamOnly, orphan))

        val m3u = LibraryBackup.exportPlaylistM3u(playlist)

        assertTrue(m3u.contains("#EXTINF:-1,Cloud Only"))
        assertTrue(m3u.contains("https://cdn1.suno.ai/t-2.mp3"))
        // Orphan track has no location: no dangling line for it.
        assertFalse(m3u.contains("t-3"))
    }

    // ---- helpers ----

    private fun assertThrowsLibraryBackup(block: () -> Unit) {
        try {
            block()
            fail("Expected LibraryBackupException")
        } catch (e: LibraryBackupException) {
            assertNotNull("Exception should carry a message", e.message)
        }
    }
}
