package com.duskript.sunolocal.domain.model

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Unit tests for SyncSummary and its cookie-auth error detection helper.
 * Pure JVM — no Android context required (./gradlew test).
 */
class SyncSummaryTest {

    @Test
    fun `isCookieAuthError detects HTTP 401 and expired-cookie messages`() {
        assertTrue(
            isCookieAuthError(
                "Failed to fetch playlists: HTTP 401 Unauthorized. " +
                    "Your Suno session cookie/JWT is expired; export a fresh cookie file."
            )
        )
        assertTrue(isCookieAuthError("Suno returned HTTP 403 for this session."))
        assertTrue(isCookieAuthError("cookie expired"))
        assertTrue(isCookieAuthError("session unauthorized"))
    }

    @Test
    fun `isCookieAuthError ignores network and validation errors`() {
        assertFalse(isCookieAuthError("Failed to connect to suno.com: timeout"))
        assertFalse(isCookieAuthError("Could not extract playlist ID from URL"))
        assertFalse(isCookieAuthError("HTTP 404 Not Found"))
        assertFalse(isCookieAuthError(null))
        assertFalse(isCookieAuthError(""))
    }

    @Test
    fun `SyncSummary defaults and computed fields`() {
        val summary = SyncSummary(
            finishedAtEpochMs = 0L,
            mode = "my_library",
            success = true,
            message = "Sync complete"
        )
        assertEquals(0, summary.totalTracks)
        assertEquals(0, summary.downloadedCount)
        assertEquals(0, summary.skippedCount)
        assertEquals(0, summary.failedCount)
        assertFalse(summary.hasFailures)
        assertTrue(summary.copy(failedCount = 2).hasFailures)
        assertTrue(summary.copy(success = false).hasFailures)
        assertTrue(summary.copy(error = "boom").hasFailures)
    }
}
