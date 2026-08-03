package com.duskript.sunolocal.core.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the capture + validate status mapping
 * (v0.1.23-auth-refresh-flow). Pure strings only — no Android, no network,
 * no secrets.
 */
class AuthFlowStatusTest {

    @Test
    fun `valid status names the playlist me probe and HTTP 200`() {
        assertTrue(AuthFlowStatus.STATUS_VALID.contains("playlist/me"))
        assertTrue(AuthFlowStatus.STATUS_VALID.contains("HTTP 200"))
    }

    @Test
    fun `401 maps to login required guidance`() {
        val status = AuthFlowStatus.statusForRejection(401)
        assertTrue(status.contains("401"))
        assertTrue(status.contains("Login"))
    }

    @Test
    fun `403 maps to rejected guidance`() {
        val status = AuthFlowStatus.statusForRejection(403)
        assertTrue(status.contains("403"))
        assertTrue(status.contains("Rejected"))
    }

    @Test
    fun `other codes map to generic failed status`() {
        val status = AuthFlowStatus.statusForRejection(500)
        assertTrue(status.contains("Failed"))
        assertFalse(status.contains("401"))
        assertFalse(status.contains("403"))
    }

    @Test
    fun `failure status carries the message`() {
        assertEquals("Failed — boom", AuthFlowStatus.statusForFailure("boom"))
        assertEquals("Failed — Suno connection test failed", AuthFlowStatus.statusForFailure(null))
    }

    @Test
    fun `no status string carries cookie or JWT material`() {
        val all = listOf(
            AuthFlowStatus.STATUS_CAPTURING,
            AuthFlowStatus.STATUS_CAPTURED_VALIDATING,
            AuthFlowStatus.STATUS_CAPTURED_KEPT_STORED,
            AuthFlowStatus.STATUS_NO_WEBVIEW_NO_STORED,
            AuthFlowStatus.STATUS_NO_NEW_WEBVIEW_VALIDATING,
            AuthFlowStatus.STATUS_VALID,
            AuthFlowStatus.statusForRejection(401),
            AuthFlowStatus.statusForRejection(403),
            AuthFlowStatus.statusForRejection(500),
            AuthFlowStatus.statusForFailure("boom")
        ).joinToString(" ")
        assertFalse(all.contains("__session"))
        assertFalse(all.contains("eyJ"))
        assertFalse(all.contains("jwt"))
    }
}
