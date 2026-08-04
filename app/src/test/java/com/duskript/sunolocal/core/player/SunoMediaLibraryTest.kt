package com.duskript.sunolocal.core.player

import androidx.media3.common.MediaMetadata
import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.duskript.sunolocal.core.storage.SunoTrackJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.1.27 — pure JVM tests for the Android Auto browse tree builder
 * (SunoMediaLibrary): tree shape (root → Playlists → playlists → tracks),
 * browsable/playable flags and metadata, local-file-first / streaming-fallback
 * URI decisions, unplayable-track exclusion, and id lookup. Temp files are
 * created under a JUnit TemporaryFolder and deleted automatically; no Android
 * framework calls (MediaItem/MediaMetadata are real media3 classes, and the
 * mockable android.jar Uri stubs return defaults per build.gradle testOptions).
 */
class SunoMediaLibraryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun track(
        id: String = "track-1",
        title: String = "Song One",
        localPath: String? = null,
        audioUrl: String? = null,
        imageUrl: String? = null,
        durationMs: Long? = 180_000L,
        creatorName: String? = "Test Creator"
    ) = SunoTrackJson(
        id = id,
        title = title,
        localPath = localPath,
        audioUrl = audioUrl,
        imageUrl = imageUrl,
        durationMs = durationMs,
        creatorName = creatorName,
        playlistId = "pl-1"
    )

    private fun playlist(
        id: String = "pl-1",
        title: String = "Test Playlist",
        tracks: List<SunoTrackJson> = emptyList()
    ) = SunoPlaylistJson(
        id = id,
        title = title,
        creatorName = "Test Creator",
        tracks = tracks,
        isCustom = false
    )

    private fun playableLocalFile(): File =
        tempFolder.newFile("local.mp3").apply { writeBytes(ByteArray(2048)) }

    // ---- root ----

    @Test
    fun `root item is browsable and not playable`() {
        val root = SunoMediaLibrary.rootItem()
        assertEquals(SunoMediaLibrary.ROOT_ID, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, root.mediaMetadata.mediaType)
        assertEquals("Suno Local", root.mediaMetadata.title?.toString())
    }

    // ---- tree shape ----

    @Test
    fun `root children contain the playlists folder`() {
        val children = SunoMediaLibrary.childrenFor(SunoMediaLibrary.ROOT_ID, emptyList())
        assertEquals(1, children.size)
        val folder = children.first()
        assertEquals(SunoMediaLibrary.PLAYLISTS_ID, folder.mediaId)
        assertEquals(true, folder.mediaMetadata.isBrowsable)
        assertEquals(false, folder.mediaMetadata.isPlayable)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, folder.mediaMetadata.mediaType)
        assertEquals("Playlists", folder.mediaMetadata.title?.toString())
    }

    @Test
    fun `playlists folder lists saved playlists as browsable folders`() {
        val playlists = listOf(
            playlist(id = "pl-1", title = "Morning Mix"),
            playlist(id = "pl-2", title = "Focus Beats")
        )
        val children = SunoMediaLibrary.childrenFor(SunoMediaLibrary.PLAYLISTS_ID, playlists)
        assertEquals(2, children.size)
        assertEquals("playlist:pl-1", children[0].mediaId)
        assertEquals("Morning Mix", children[0].mediaMetadata.title?.toString())
        assertEquals(true, children[0].mediaMetadata.isBrowsable)
        assertEquals(false, children[0].mediaMetadata.isPlayable)
        assertEquals(MediaMetadata.MEDIA_TYPE_PLAYLIST, children[0].mediaMetadata.mediaType)
        assertEquals("playlist:pl-2", children[1].mediaId)
    }

    // ---- playlist children: playable tracks ----

    @Test
    fun `playlist children produce playable track items for local and streaming tracks`() {
        val localFile = playableLocalFile()
        val tracks = listOf(
            track(
                id = "t-local",
                title = "Local Track",
                localPath = localFile.absolutePath,
                audioUrl = "https://example.com/fallback.mp3"
            ),
            track(
                id = "t-stream",
                title = "Stream Track",
                localPath = null,
                audioUrl = "https://example.com/stream.mp3"
            )
        )
        val library = listOf(playlist(id = "pl-1", title = "Album Title", tracks = tracks))
        val children = SunoMediaLibrary.childrenFor("playlist:pl-1", library)

        assertEquals(2, children.size)

        val local = children.first { it.mediaId == "track:t-local" }
        assertEquals(true, local.mediaMetadata.isPlayable)
        assertEquals(false, local.mediaMetadata.isBrowsable)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, local.mediaMetadata.mediaType)
        assertEquals("Local Track", local.mediaMetadata.title?.toString())
        assertEquals("Test Creator", local.mediaMetadata.artist?.toString())
        assertEquals("Album Title", local.mediaMetadata.albumTitle?.toString())
        assertEquals(180_000L, local.mediaMetadata.durationMs)

        val stream = children.first { it.mediaId == "track:t-stream" }
        assertEquals(true, stream.mediaMetadata.isPlayable)
        assertEquals(false, stream.mediaMetadata.isBrowsable)
        assertEquals("https://example.com/stream.mp3", SunoMediaLibrary.trackUriString(tracks[1]))
    }

    @Test
    fun `tracks without local file or audioUrl are excluded from children`() {
        val missingFile = File(tempFolder.root, "gone.mp3").absolutePath
        val tracks = listOf(
            track(id = "t-ok", localPath = null, audioUrl = "https://example.com/ok.mp3"),
            track(id = "t-dead", localPath = missingFile, audioUrl = null),
            track(id = "t-empty", localPath = null, audioUrl = null)
        )
        val library = listOf(playlist(tracks = tracks))
        val children = SunoMediaLibrary.childrenFor("playlist:pl-1", library)
        assertEquals(1, children.size)
        assertEquals("track:t-ok", children.single().mediaId)
    }

    // ---- URI decision mirrors PlaybackSource semantics ----

    @Test
    fun `track uri string prefers verified local file over audioUrl`() {
        val localFile = playableLocalFile()
        val t = track(localPath = localFile.absolutePath, audioUrl = "https://example.com/fallback.mp3")
        assertEquals(localFile.toURI().toString(), SunoMediaLibrary.trackUriString(t))
    }

    @Test
    fun `track uri string falls back to audioUrl when local file missing or zero byte`() {
        val missing = File(tempFolder.root, "missing.mp3").absolutePath
        assertEquals(
            "https://example.com/fallback.mp3",
            SunoMediaLibrary.trackUriString(track(localPath = missing, audioUrl = "https://example.com/fallback.mp3"))
        )
        val empty = tempFolder.newFile("empty.mp3")
        assertEquals(
            "https://example.com/fallback.mp3",
            SunoMediaLibrary.trackUriString(track(localPath = empty.absolutePath, audioUrl = "https://example.com/fallback.mp3"))
        )
    }

    @Test
    fun `track uri string is null when neither source exists`() {
        assertNull(SunoMediaLibrary.trackUriString(track(localPath = null, audioUrl = null)))
        assertNull(SunoMediaLibrary.trackUriString(track(localPath = "   ", audioUrl = "null")))
    }

    @Test
    fun `trackItem is null for unplayable tracks`() {
        val missing = File(tempFolder.root, "gone.mp3").absolutePath
        assertNull(SunoMediaLibrary.trackItem(track(localPath = missing, audioUrl = null)))
    }

    // ---- id lookup ----

    @Test
    fun `itemFor resolves root, folder, playlist and track ids`() {
        val localFile = playableLocalFile()
        val tracks = listOf(track(id = "t-1", title = "First", localPath = localFile.absolutePath))
        val playlists = listOf(playlist(id = "pl-1", title = "Mix", tracks = tracks))

        assertEquals(
            SunoMediaLibrary.ROOT_ID,
            SunoMediaLibrary.itemFor(SunoMediaLibrary.ROOT_ID, playlists)?.mediaId
        )
        assertEquals(
            SunoMediaLibrary.PLAYLISTS_ID,
            SunoMediaLibrary.itemFor(SunoMediaLibrary.PLAYLISTS_ID, playlists)?.mediaId
        )
        assertEquals(
            "playlist:pl-1",
            SunoMediaLibrary.itemFor("playlist:pl-1", playlists)?.mediaId
        )

        val trackItem = SunoMediaLibrary.itemFor("track:t-1", playlists)
        assertNotNull(trackItem)
        assertEquals("track:t-1", trackItem?.mediaId)
        assertEquals(true, trackItem?.mediaMetadata?.isPlayable)
        assertEquals("First", trackItem?.mediaMetadata?.title?.toString())
    }

    @Test
    fun `itemFor returns null for unknown ids`() {
        val playlists = listOf(playlist(id = "pl-1"))
        assertNull(SunoMediaLibrary.itemFor("playlist:unknown", playlists))
        assertNull(SunoMediaLibrary.itemFor("track:unknown", playlists))
        assertNull(SunoMediaLibrary.itemFor("bogus", playlists))
    }

    @Test
    fun `playbackQueueFor selected track uses containing playlist queue and start index`() {
        val playlists = listOf(
            playlist(
                id = "pl-a",
                title = "Playlist A",
                tracks = listOf(
                    track(id = "shared", title = "Shared A", audioUrl = "https://example.com/shared-a.mp3")
                )
            ),
            playlist(
                id = "pl-b",
                title = "Playlist B",
                tracks = listOf(
                    track(id = "b-1", title = "First B", audioUrl = "https://example.com/b-1.mp3"),
                    track(id = "target", title = "Target B", audioUrl = "https://example.com/target.mp3"),
                    track(id = "b-2", title = "Last B", audioUrl = "https://example.com/b-2.mp3")
                )
            )
        )

        val queue = SunoMediaLibrary.playbackQueueFor("track:target", playlists)

        assertNotNull(queue)
        assertEquals(3, queue?.mediaItems?.size)
        assertEquals(1, queue?.startIndex)
        assertEquals(0L, queue?.startPositionMs)
        assertEquals(listOf("track:b-1", "track:target", "track:b-2"), queue?.mediaItems?.map { it.mediaId })
        assertEquals("Playlist B", queue?.mediaItems?.get(1)?.mediaMetadata?.albumTitle?.toString())
    }

    @Test
    fun `playbackQueueFor excludes unplayable tracks and indexes selected playable track`() {
        val playlists = listOf(
            playlist(
                id = "pl-b",
                title = "Playlist B",
                tracks = listOf(
                    track(id = "dead-before", audioUrl = null, localPath = null),
                    track(id = "target", audioUrl = "https://example.com/target.mp3"),
                    track(id = "dead-after", audioUrl = null, localPath = null),
                    track(id = "after", audioUrl = "https://example.com/after.mp3")
                )
            )
        )

        val queue = SunoMediaLibrary.playbackQueueFor("track:target", playlists)

        assertNotNull(queue)
        assertEquals(listOf("track:target", "track:after"), queue?.mediaItems?.map { it.mediaId })
        assertEquals(0, queue?.startIndex)
    }

    @Test
    fun `playbackQueueFor returns null when selected track is unplayable`() {
        val playlists = listOf(
            playlist(
                id = "pl-b",
                tracks = listOf(
                    track(id = "dead", audioUrl = null, localPath = null),
                    track(id = "after", audioUrl = "https://example.com/after.mp3")
                )
            )
        )

        assertNull(SunoMediaLibrary.playbackQueueFor("track:dead", playlists))
    }

    @Test
    fun `childrenFor returns empty for unknown parents and known-parent check works`() {
        val playlists = listOf(playlist(id = "pl-1"))
        assertTrue(SunoMediaLibrary.childrenFor("playlist:unknown", playlists).isEmpty())
        assertFalse(SunoMediaLibrary.isKnownParent("playlist:unknown", playlists))
        assertTrue(SunoMediaLibrary.isKnownParent(SunoMediaLibrary.ROOT_ID, emptyList()))
        assertTrue(SunoMediaLibrary.isKnownParent(SunoMediaLibrary.PLAYLISTS_ID, emptyList()))
        assertTrue(SunoMediaLibrary.isKnownParent("playlist:pl-1", playlists))
    }

    // ---- stable ids ----

    @Test
    fun `browse and media ids are stable and round-trip`() {
        assertEquals("playlist:abc", SunoMediaLibrary.playlistBrowseId("abc"))
        assertEquals("abc", SunoMediaLibrary.playlistIdFromBrowseId("playlist:abc"))
        assertNull(SunoMediaLibrary.playlistIdFromBrowseId("track:abc"))
        assertEquals("track:xyz", SunoMediaLibrary.trackMediaId("xyz"))
    }
}
