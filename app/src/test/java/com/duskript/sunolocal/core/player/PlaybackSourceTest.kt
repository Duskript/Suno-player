package com.duskript.sunolocal.core.player

import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Batch E — pure JVM tests for PlaybackSource.resolve: verified local files
 * win over audioUrl, missing/zero-byte local paths fall back to streaming, and
 * local-path-only missing files resolve to Unavailable. Temp files are created
 * under a JUnit TemporaryFolder and deleted automatically; no Android calls.
 */
class PlaybackSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun track(
        localPath: String? = null,
        audioUrl: String? = null
    ) = SunoTrack(
        id = "track-1",
        title = "Test Song",
        localPath = localPath,
        audioUrl = audioUrl
    )

    @Test
    fun `valid local file is preferred over audioUrl`() {
        val localFile = tempFolder.newFile("song.mp3").apply {
            writeBytes(ByteArray(1024))
        }
        val source = PlaybackSource.resolve(
            track(localPath = localFile.absolutePath, audioUrl = "https://example.com/song.mp3")
        )
        assertTrue("expected Local, got $source", source is PlaybackSource.Local)
        assertEquals(localFile, (source as PlaybackSource.Local).file)
        assertTrue(source.isPlayable)
    }

    @Test
    fun `missing local path falls back to audioUrl`() {
        val missingPath = File(tempFolder.root, "does-not-exist.mp3").absolutePath
        val source = PlaybackSource.resolve(
            track(localPath = missingPath, audioUrl = "https://example.com/song.mp3")
        )
        assertTrue("expected Streaming, got $source", source is PlaybackSource.Streaming)
        assertEquals("https://example.com/song.mp3", (source as PlaybackSource.Streaming).url)
        assertTrue(source.isPlayable)
    }

    @Test
    fun `zero-byte local file falls back to audioUrl`() {
        val emptyFile = tempFolder.newFile("empty.mp3") // length 0
        val source = PlaybackSource.resolve(
            track(localPath = emptyFile.absolutePath, audioUrl = "https://example.com/song.mp3")
        )
        assertTrue("expected Streaming, got $source", source is PlaybackSource.Streaming)
    }

    @Test
    fun `blank and null literal local paths are treated as absent`() {
        val streaming = PlaybackSource.resolve(
            track(localPath = "   ", audioUrl = "https://example.com/song.mp3")
        )
        assertTrue("expected Streaming, got $streaming", streaming is PlaybackSource.Streaming)

        val nullLiteral = PlaybackSource.resolve(
            track(localPath = "null", audioUrl = "https://example.com/song.mp3")
        )
        assertTrue("expected Streaming, got $nullLiteral", nullLiteral is PlaybackSource.Streaming)
    }

    @Test
    fun `local-path-only missing file is unavailable with clear reason`() {
        val missingPath = File(tempFolder.root, "gone.mp3").absolutePath
        val source = PlaybackSource.resolve(track(localPath = missingPath))
        assertEquals(PlaybackSource.Unavailable, source)
        assertFalse(source.isPlayable)
        assertEquals(
            "Missing local audio for \"Test Song\" — resync or re-download this playlist.",
            PlaybackSource.missingLocalAudioMessage("Test Song")
        )
    }

    @Test
    fun `no local path and no audioUrl is unavailable`() {
        assertEquals(PlaybackSource.Unavailable, PlaybackSource.resolve(track()))
        assertFalse(PlaybackSource.resolve(track()).isPlayable)
    }

    @Test
    fun `directory path is not a playable local file`() {
        val dir = tempFolder.newFolder("not-audio")
        val source = PlaybackSource.resolve(track(localPath = dir.absolutePath))
        assertEquals(PlaybackSource.Unavailable, source)
    }
}
