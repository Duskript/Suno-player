package com.duskript.sunolocal.core.widget

import com.duskript.sunolocal.domain.model.SunoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SunoPlaybackWidgetState] formatting/fallback logic.
 * No Android types involved, so no Robolectric needed.
 */
class SunoPlaybackWidgetStateTest {

    private val track = SunoTrack(
        id = "t1",
        title = "Neon Skyline",
        creatorName = "duskript"
    )

    @Test
    fun `from maps a loaded track onto title and creator`() {
        val state = SunoPlaybackWidgetState.from(
            track,
            isPlaying = true,
            hasPrevious = true,
            hasNext = true
        )
        assertEquals("Neon Skyline", state.title)
        assertEquals("duskript", state.subtitle)
        assertTrue(state.isPlaying)
        assertTrue(state.hasPrevious)
        assertTrue(state.hasNext)
    }

    @Test
    fun `from falls back to idle copy when no track is loaded`() {
        val state = SunoPlaybackWidgetState.from(
            null,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false
        )
        assertEquals(SunoPlaybackWidgetState.FALLBACK_TITLE, state.title)
        assertEquals(SunoPlaybackWidgetState.FALLBACK_SUBTITLE, state.subtitle)
        assertFalse(state.isPlaying)
        assertFalse(state.hasPrevious)
        assertFalse(state.hasNext)
    }

    @Test
    fun `from falls back to unknown creator when creator is missing`() {
        val anonymous = track.copy(creatorName = null)
        val state = SunoPlaybackWidgetState.from(
            anonymous,
            isPlaying = false,
            hasPrevious = true,
            hasNext = false
        )
        assertEquals("Neon Skyline", state.title)
        assertEquals(SunoPlaybackWidgetState.FALLBACK_UNKNOWN_CREATOR, state.subtitle)
    }

    @Test
    fun `from treats blank title and creator as missing`() {
        val blank = track.copy(title = "   ", creatorName = "")
        val state = SunoPlaybackWidgetState.from(
            blank,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false
        )
        assertEquals(SunoPlaybackWidgetState.FALLBACK_TITLE, state.title)
        assertEquals(SunoPlaybackWidgetState.FALLBACK_UNKNOWN_CREATOR, state.subtitle)
    }

    @Test
    fun `custom fallbacks override defaults`() {
        val state = SunoPlaybackWidgetState.from(
            null,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false,
            fallbackTitle = "Suno",
            fallbackSubtitle = "Pick a track"
        )
        assertEquals("Suno", state.title)
        assertEquals("Pick a track", state.subtitle)
    }

    @Test
    fun `equal playback flags produce equal states for updater de-dupe`() {
        val a = SunoPlaybackWidgetState.from(track, isPlaying = true, hasPrevious = false, hasNext = false)
        val b = SunoPlaybackWidgetState.from(track.copy(), isPlaying = true, hasPrevious = false, hasNext = false)
        assertEquals(a, b)
    }
}
