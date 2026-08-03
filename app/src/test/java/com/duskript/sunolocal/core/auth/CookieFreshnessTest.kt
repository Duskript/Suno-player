package com.duskript.sunolocal.core.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for CookieFreshness and JWT `exp` parsing.
 *
 * All assertions use an injected `nowEpochSeconds` and locally-built fake JWTs
 * (no real secrets, nothing printed) so nothing depends on clocks or Android.
 */
class CookieFreshnessTest {

    private val now = 1_750_000_000L

    private fun base64Url(input: String): String =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(Charsets.UTF_8))

    /** Build an unsigned fake JWT with the given `exp` claim. */
    private fun fakeJwt(exp: Long, extraClaims: String = ""): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url("""{"sub":"test-user","iat":1700000000,"exp":$exp$extraClaims}""")
        return "$header.$payload.fake-signature"
    }

    /** Build a fake JWT without an `exp` claim. */
    private fun fakeJwtWithoutExp(): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url("""{"sub":"test-user","iat":1700000000}""")
        return "$header.$payload.fake-signature"
    }

    // --- JWT exp parsing ---------------------------------------------------

    @Test
    fun `jwtExpiresAtEpochSeconds parses exp from fake JWT`() {
        assertEquals(1_751_000_000L, CookieFreshness.jwtExpiresAtEpochSeconds(fakeJwt(1_751_000_000L)))
    }

    @Test
    fun `jwtExpiresAtEpochSeconds returns null for malformed tokens`() {
        assertNull(CookieFreshness.jwtExpiresAtEpochSeconds("not-a-jwt"))
        assertNull(CookieFreshness.jwtExpiresAtEpochSeconds("a.b"))
        assertNull(CookieFreshness.jwtExpiresAtEpochSeconds(""))
    }

    @Test
    fun `jwtExpiresAtEpochSeconds returns null when exp claim missing or non-positive`() {
        assertNull(CookieFreshness.jwtExpiresAtEpochSeconds(fakeJwtWithoutExp()))
        assertNull(CookieFreshness.jwtExpiresAtEpochSeconds(fakeJwt(0)))
        assertNull(CookieFreshness.jwtExpiresAtEpochSeconds(fakeJwt(-5)))
    }

    // --- CookieFreshness states --------------------------------------------

    @Test
    fun `missing session when no cookie header`() {
        val freshness = CookieFreshness.of(null, now)
        assertFalse(freshness.hasSession)
        assertNull(freshness.expiresAtEpochSeconds)
        assertNull(freshness.secondsUntilExpiry)
        assertFalse(freshness.isExpired)
        assertEquals("Missing", freshness.statusLabel)
    }

    @Test
    fun `missing session when header has no __session`() {
        val freshness = CookieFreshness.of("suno_device_id=device-123; _ga=GA1.1.x", now)
        assertFalse(freshness.hasSession)
        assertEquals("Missing", freshness.statusLabel)
    }

    @Test
    fun `unknown expiry when __session is present but exp unparseable`() {
        val freshness = CookieFreshness.of("__session=abc.def.ghi; suno_device_id=d1", now)
        assertTrue(freshness.hasSession)
        assertNull(freshness.expiresAtEpochSeconds)
        assertNull(freshness.secondsUntilExpiry)
        assertFalse(freshness.isExpired)
        assertEquals("Unknown expiry", freshness.statusLabel)
    }

    @Test
    fun `expired when exp is in the past`() {
        val freshness = CookieFreshness.of("__session=${fakeJwt(now - 100)}", now)
        assertTrue(freshness.hasSession)
        assertEquals(now - 100, freshness.expiresAtEpochSeconds)
        assertEquals(-100L, freshness.secondsUntilExpiry)
        assertTrue(freshness.isExpired)
        assertEquals("Expired", freshness.statusLabel)
    }

    @Test
    fun `valid session reports seconds remaining and label`() {
        val freshness = CookieFreshness.of("__session=${fakeJwt(now + 300)}", now)
        assertTrue(freshness.hasSession)
        assertFalse(freshness.isExpired)
        assertEquals(300L, freshness.secondsUntilExpiry)
        assertTrue(freshness.statusLabel.contains("5 min"))
    }

    @Test
    fun `far-future session reports hour label`() {
        val freshness = CookieFreshness.of("__session=${fakeJwt(now + 7200)}", now)
        assertFalse(freshness.isExpired)
        assertTrue(freshness.statusLabel.contains("2 h"))
    }

    // --- expiresWithin -----------------------------------------------------

    @Test
    fun `expiresWithin true when inside window`() {
        val freshness = CookieFreshness.of("__session=${fakeJwt(now + 60)}", now)
        assertTrue(freshness.expiresWithin(120))
    }

    @Test
    fun `expiresWithin false when outside window`() {
        val freshness = CookieFreshness.of("__session=${fakeJwt(now + 300)}", now)
        assertFalse(freshness.expiresWithin(120))
    }

    @Test
    fun `expiresWithin true for already expired session`() {
        val freshness = CookieFreshness.of("__session=${fakeJwt(now - 10)}", now)
        assertTrue(freshness.expiresWithin(0))
    }

    @Test
    fun `expiresWithin false when expiry unknown`() {
        val freshness = CookieFreshness.of("__session=abc.def.ghi", now)
        assertFalse(freshness.expiresWithin(60))
    }

    // --- CookieStore companion compatibility --------------------------------

    @Test
    fun `CookieStore jwtExpiresAtEpochSeconds delegates to pure parser`() {
        assertEquals(1_751_000_000L, CookieStore.jwtExpiresAtEpochSeconds(fakeJwt(1_751_000_000L)))
        assertNull(CookieStore.jwtExpiresAtEpochSeconds(fakeJwtWithoutExp()))
    }
}
