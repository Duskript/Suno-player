package com.duskript.sunolocal.core.player

import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Batch E — pure JVM tests for ResumePlaybackStatus.evaluate: the resume
 * snapshot either rebuilds a playable queue or explains exactly why not
 * (track missing from library / queue missing / no playable tracks / saved
 * track unplayable but queue salvageable). No Android or network calls.
 */
class ResumePlaybackStatusTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun localTrack(id: String, title: String, audioUrl: String? = null): SunoTrack {
        val file = tempFolder.newFile("$id.mp3").apply { writeBytes(ByteArray(512)) }
        return SunoTrack(id = id, title = title, localPath = file.absolutePath, audioUrl = audioUrl)
    }

    private fun staleLocalOnlyTrack(id: String, title: String): SunoTrack =
        SunoTrack(id = id, title = title, localPath = "missing-$id.mp3", audioUrl = null)

    private fun state(trackId: String, queueIds: List<String> = listOf(trackId)) =
        PlaybackState(trackId = trackId, queueIds = queueIds, positionMs = 42_000L)

    @Test
    fun `ready when saved track and queue are playable`() {
        val t1 = localTrack("t1", "One")
        val t2 = localTrack("t2", "Two")
        val status = ResumePlaybackStatus.evaluate(state("t2", listOf("t1", "t2")), listOf(t1, t2))
        assertTrue("expected Ready, got $status", status is ResumePlaybackStatus.Ready)
        status as ResumePlaybackStatus.Ready
        assertEquals(listOf(t1, t2), status.queue)
        assertEquals("t2", status.startTrackId)
    }

    @Test
    fun `saved track missing from library`() {
        val t1 = localTrack("t1", "One")
        val status = ResumePlaybackStatus.evaluate(state("ghost"), listOf(t1))
        assertTrue("expected SavedTrackMissing, got $status", status is ResumePlaybackStatus.SavedTrackMissing)
        assertEquals("ghost", (status as ResumePlaybackStatus.SavedTrackMissing).trackId)
    }

    @Test
    fun `no playable tracks when every resolved track lacks a source`() {
        val a = staleLocalOnlyTrack("a", "Alpha")
        val b = staleLocalOnlyTrack("b", "Beta")
        val status = ResumePlaybackStatus.evaluate(state("a", listOf("a", "b")), listOf(a, b))
        assertEquals(ResumePlaybackStatus.NoPlayableTracks, status)
    }

    @Test
    fun `saved track unplayable but queue salvageable starts at first playable`() {
        val stale = staleLocalOnlyTrack("stale", "Gone Song")
        val ok = localTrack("ok", "Fine Song")
        val status = ResumePlaybackStatus.evaluate(
            state("stale", listOf("stale", "ok")),
            listOf(stale, ok)
        )
        assertTrue("expected SavedTrackUnplayable, got $status", status is ResumePlaybackStatus.SavedTrackUnplayable)
        status as ResumePlaybackStatus.SavedTrackUnplayable
        assertEquals("stale", status.trackId)
        assertEquals("Gone Song", status.trackTitle)
        assertEquals(listOf(ok), status.queue)
        assertEquals("ok", status.startTrackId)
    }

    @Test
    fun `queue ids fall back to the single saved track`() {
        val t1 = localTrack("t1", "One")
        val status = ResumePlaybackStatus.evaluate(
            PlaybackState(trackId = "t1", queueIds = emptyList()),
            listOf(t1)
        )
        assertTrue("expected Ready, got $status", status is ResumePlaybackStatus.Ready)
        assertEquals(listOf(t1), (status as ResumePlaybackStatus.Ready).queue)
        assertEquals("t1", status.startTrackId)
    }

    @Test
    fun `streaming-only saved track resumes as ready`() {
        val stream = SunoTrack(id = "s1", title = "Streamy", audioUrl = "https://example.com/s1.mp3")
        val status = ResumePlaybackStatus.evaluate(state("s1"), listOf(stream))
        assertTrue("expected Ready, got $status", status is ResumePlaybackStatus.Ready)
        assertEquals(listOf(stream), (status as ResumePlaybackStatus.Ready).queue)
    }
}
