package com.duskript.sunolocal.core.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the pre-sync auth guard decision
 * (v0.1.23-auth-refresh-flow). Pure decision logic only — no Android, no
 * network, no secrets. Fake JWTs and an injected clock keep every branch
 * reproducible.
 */
class PreSyncAuthGuardTest {

    private val now = 1_750_000_000L
    private val window = 5 * 60L

    private fun base64Url(input: String): String =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(Charsets.UTF_8))

    private fun fakeJwt(exp: Long): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url("""{"sub":"test-user","exp":$exp}""")
        return "$header.$payload.fake-signature"
    }

    private fun freshness(header: String?): CookieFreshness = CookieFreshness.of(header, now)

    private fun decide(
        configured: Boolean,
        header: String?,
        windowSeconds: Long = window,
    ): PreSyncAuthDecision = PreSyncAuthGuard.decide(
        configured = configured,
        freshness = freshness(header),
        nearExpiryWindowSeconds = windowSeconds
    )

    // --- missing cookie -----------------------------------------------------

    @Test
    fun `no configured cookie means login required`() {
        val decision = decide(configured = false, header = null)
        assertEquals(PreSyncAuthAction.LOGIN_REQUIRED, decision.action)
        assertFalse(decision.configured)
        assertFalse(decision.hasSession)
        assertTrue(decision.reason.isNotBlank())
    }

    // --- fresh cookie -------------------------------------------------------

    @Test
    fun `fresh session proceeds without validation`() {
        val decision = decide(configured = true, header = "__session=${fakeJwt(now + 3600)}")
        assertEquals(PreSyncAuthAction.PROCEED, decision.action)
        assertTrue(decision.configured)
        assertTrue(decision.hasSession)
        assertFalse(decision.isExpired)
        assertFalse(decision.expiresWithinWindow)
    }

    @Test
    fun `unknown expiry but present session proceeds`() {
        // A JWT without a parseable exp cannot be proven near-expiry, so the
        // guard trusts it and lets the sync's own auth retry catch problems.
        val decision = decide(configured = true, header = "__session=abc.def.ghi")
        assertEquals(PreSyncAuthAction.PROCEED, decision.action)
        assertTrue(decision.hasSession)
    }

    // --- expired / near-expired ---------------------------------------------

    @Test
    fun `expired session triggers validation`() {
        val decision = decide(configured = true, header = "__session=${fakeJwt(now - 100)}")
        assertEquals(PreSyncAuthAction.VALIDATE, decision.action)
        assertTrue(decision.isExpired)
    }

    @Test
    fun `session expiring within window triggers validation`() {
        val decision = decide(configured = true, header = "__session=${fakeJwt(now + 240)}")
        assertEquals(PreSyncAuthAction.VALIDATE, decision.action)
        assertTrue(decision.expiresWithinWindow)
    }

    @Test
    fun `session expiring exactly at window edge triggers validation`() {
        val decision = decide(configured = true, header = "__session=${fakeJwt(now + window)}")
        assertEquals(PreSyncAuthAction.VALIDATE, decision.action)
        assertTrue(decision.expiresWithinWindow)
    }

    @Test
    fun `session just outside window proceeds`() {
        val decision = decide(configured = true, header = "__session=${fakeJwt(now + window + 1)}")
        assertEquals(PreSyncAuthAction.PROCEED, decision.action)
        assertFalse(decision.expiresWithinWindow)
    }

    // --- configured but no usable session -----------------------------------

    @Test
    fun `configured cookie without session triggers validation`() {
        // Header exists but carries no __session — cannot authenticate, so the
        // guard asks for a live probe which will surface login-required on 401.
        val decision = decide(configured = true, header = "suno_device_id=abc")
        assertEquals(PreSyncAuthAction.VALIDATE, decision.action)
        assertFalse(decision.hasSession)
    }

    // --- messages ------------------------------------------------------------

    @Test
    fun `login required messages distinguish auto skip from manual failure`() {
        assertEquals("Auto-sync skipped: login required", PreSyncAuthMessages.AUTO_SYNC_SKIPPED_LOGIN_REQUIRED)
        assertEquals("Sync failed: login required", PreSyncAuthMessages.SYNC_FAILED_LOGIN_REQUIRED)
    }
}
