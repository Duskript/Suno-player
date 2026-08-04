package com.duskript.sunolocal.core.player

import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.duskript.sunolocal.core.storage.SunoTrackJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackAutoResumeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resume plan maps stored tracks with full domain metadata`() {
        val file = tempFolder.newFile("song.mp3").apply { writeBytes(ByteArray(256)) }
        val track = SunoTrackJson(
            id = "track-1",
            title = "Saved Song",
            audioUrl = "https://example.com/song.mp3",
            localPath = file.absolutePath,
            imageUrl = "https://example.com/image.jpg",
            durationMs = 123_000L,
            playlistId = "playlist-1",
            creatorName = "Creator",
            sourceUrl = "https://suno.com/song/track-1",
            lyrics = "words",
            stylePrompt = "bright pop",
            descriptionPrompt = "upbeat",
            tags = listOf("pop", "bright"),
            mood = "Happy",
            genre = "Pop",
            downloadedAtEpochMs = 42L
        )
        val plan = resumePlanFor(
            PlaybackState(trackId = "track-1", queueIds = listOf("track-1")),
            listOf(SunoPlaylistJson(id = "playlist-1", title = "Playlist", tracks = listOf(track)))
        )

        assertTrue("expected Ready, got $plan", plan is PlaybackAutoResumePlan.Ready)
        plan as PlaybackAutoResumePlan.Ready
        assertEquals("track-1", plan.startTrackId)
        val domain = plan.queue.single()
        assertEquals(track.id, domain.id)
        assertEquals(track.title, domain.title)
        assertEquals(track.audioUrl, domain.audioUrl)
        assertEquals(track.localPath, domain.localPath)
        assertEquals(track.imageUrl, domain.imageUrl)
        assertEquals(track.durationMs, domain.durationMs)
        assertEquals(track.playlistId, domain.playlistId)
        assertEquals(track.creatorName, domain.creatorName)
        assertEquals(track.sourceUrl, domain.sourceUrl)
        assertEquals(track.lyrics, domain.lyrics)
        assertEquals(track.stylePrompt, domain.stylePrompt)
        assertEquals(track.descriptionPrompt, domain.descriptionPrompt)
        assertEquals(track.tags, domain.tags)
        assertEquals(track.mood, domain.mood)
        assertEquals(track.genre, domain.genre)
        assertEquals(track.downloadedAtEpochMs, domain.downloadedAtEpochMs)
    }

    @Test
    fun `resume plan salvages queue when saved track is unplayable`() {
        val file = tempFolder.newFile("ok.mp3").apply { writeBytes(ByteArray(256)) }
        val playlists = listOf(
            SunoPlaylistJson(
                id = "playlist-1",
                title = "Playlist",
                tracks = listOf(
                    SunoTrackJson(id = "stale", title = "Stale", localPath = "missing.mp3"),
                    SunoTrackJson(id = "ok", title = "OK", localPath = file.absolutePath)
                )
            )
        )

        val plan = resumePlanFor(
            PlaybackState(trackId = "stale", queueIds = listOf("stale", "ok")),
            playlists
        )

        assertTrue("expected Ready, got $plan", plan is PlaybackAutoResumePlan.Ready)
        plan as PlaybackAutoResumePlan.Ready
        assertEquals("ok", plan.startTrackId)
        assertEquals(listOf("ok"), plan.queue.map { it.id })
    }

    @Test
    fun `resume plan reports unavailable without mutating saved state`() {
        val plan = resumePlanFor(
            PlaybackState(trackId = "missing", queueIds = listOf("missing")),
            listOf(SunoPlaylistJson(id = "playlist-1", title = "Playlist"))
        )

        assertTrue("expected Unavailable, got $plan", plan is PlaybackAutoResumePlan.Unavailable)
        assertTrue(
            (plan as PlaybackAutoResumePlan.Unavailable).status is ResumePlaybackStatus.SavedTrackMissing
        )
    }
}
