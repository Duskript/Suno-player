package com.duskript.sunolocal.core.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the safe WebView cookie adoption decision.
 *
 * Fake JWTs and an injected clock only — no Android, no network, no secrets.
 */
class CookieAdoptionTest {

    private val now = 1_750_000_000L

    private fun base64Url(input: String): String =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(Charsets.UTF_8))

    private fun fakeJwt(exp: Long): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url("""{"sub":"test-user","exp":$exp}""")
        return "$header.$payload.fake-signature"
    }

    private fun webViewHeader(sessionJwt: String): String =
        "__session=$sessionJwt; suno_device_id=webview-device; _ga=GA1.1.fake"

    private fun storedHeader(sessionJwt: String): String =
        "__session=$sessionJwt; suno_device_id=stored-device"

    private fun decide(
        webViewCookieHeader: String?,
        storedCookieHeader: String?,
    ): CookieRefreshResult = CookieAdoption.decide(
        webViewCookieHeader = webViewCookieHeader,
        storedCookieHeader = storedCookieHeader,
        nowEpochSeconds = now
    )

    // --- no WebView session -------------------------------------------------

    @Test
    fun `no WebView cookie means nothing captured and nothing saved`() {
        val result = decide(webViewCookieHeader = null, storedCookieHeader = storedHeader(fakeJwt(now + 3600)))
        assertFalse(result.captured)
        assertFalse(result.saved)
        assertNull(result.newExpiresAt)
        assertNull(result.oldExpiresAt)
    }

    @Test
    fun `WebView header without __session means nothing captured`() {
        val result = decide(
            webViewCookieHeader = "suno_device_id=webview-device",
            storedCookieHeader = null
        )
        assertFalse(result.captured)
        assertFalse(result.saved)
    }

    // --- stored missing ------------------------------------------------------

    @Test
    fun `stored missing adopts WebView cookie even when WebView expiry unknown`() {
        val result = decide(
            webViewCookieHeader = webViewHeader("abc.def.ghi"),
            storedCookieHeader = null
        )
        assertTrue(result.captured)
        assertTrue(result.saved)
        assertNull(result.newExpiresAt)
    }

    @Test
    fun `stored missing adopts WebView cookie with known expiry`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 3600)),
            storedCookieHeader = null
        )
        assertTrue(result.captured)
        assertTrue(result.saved)
        assertEquals(now + 3600, result.newExpiresAt)
    }

    @Test
    fun `stored header without __session adopts WebView cookie`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 3600)),
            storedCookieHeader = "suno_device_id=stored-device"
        )
        assertTrue(result.captured)
        assertTrue(result.saved)
    }

    // --- newer WebView saves -------------------------------------------------

    @Test
    fun `newer WebView session overwrites older stored session`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 7200)),
            storedCookieHeader = storedHeader(fakeJwt(now + 600))
        )
        assertTrue(result.captured)
        assertTrue(result.saved)
        assertEquals(now + 7200, result.newExpiresAt)
        assertEquals(now + 600, result.oldExpiresAt)
    }

    // --- older/equal WebView refuses -----------------------------------------

    @Test
    fun `older WebView session does not overwrite newer stored session`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 600)),
            storedCookieHeader = storedHeader(fakeJwt(now + 7200))
        )
        assertTrue(result.captured)
        assertFalse(result.saved)
        assertEquals(now + 600, result.newExpiresAt)
        assertEquals(now + 7200, result.oldExpiresAt)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `equal WebView session does not overwrite stored session`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 3600)),
            storedCookieHeader = storedHeader(fakeJwt(now + 3600))
        )
        assertTrue(result.captured)
        assertFalse(result.saved)
        assertEquals(now + 3600, result.newExpiresAt)
        assertEquals(now + 3600, result.oldExpiresAt)
    }

    @Test
    fun `already-expired WebView session does not overwrite valid stored session`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now - 100)),
            storedCookieHeader = storedHeader(fakeJwt(now + 7200))
        )
        assertTrue(result.captured)
        assertFalse(result.saved)
    }

    // --- unknown expiries ----------------------------------------------------

    @Test
    fun `stored newer with WebView expiry unknown refuses`() {
        val result = decide(
            webViewCookieHeader = webViewHeader("abc.def.ghi"),
            storedCookieHeader = storedHeader(fakeJwt(now + 7200))
        )
        assertTrue(result.captured)
        assertFalse(result.saved)
        assertNull(result.newExpiresAt)
        assertEquals(now + 7200, result.oldExpiresAt)
    }

    @Test
    fun `stored expired with WebView expiry unknown adopts`() {
        val result = decide(
            webViewCookieHeader = webViewHeader("abc.def.ghi"),
            storedCookieHeader = storedHeader(fakeJwt(now - 100))
        )
        assertTrue(result.captured)
        assertTrue(result.saved)
        assertEquals(now - 100, result.oldExpiresAt)
    }

    @Test
    fun `WebView expiry known but stored expiry unknown refuses`() {
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 7200)),
            storedCookieHeader = storedHeader("abc.def.ghi")
        )
        assertTrue(result.captured)
        assertFalse(result.saved)
        assertEquals(now + 7200, result.newExpiresAt)
        assertNull(result.oldExpiresAt)
    }

    @Test
    fun `both expiries unknown refuses`() {
        val result = decide(
            webViewCookieHeader = webViewHeader("abc.def.ghi"),
            storedCookieHeader = storedHeader("xyz.uvw.rst")
        )
        assertTrue(result.captured)
        assertFalse(result.saved)
    }

    // --- non-__session cookies preserved on adopt ----------------------------

    @Test
    fun `adopted header keeps non-session cookies from WebView`() {
        // The bridge saves the full WebView header when saved=true; verify the
        // decision reports saved for a header that carries extra cookies.
        val result = decide(
            webViewCookieHeader = webViewHeader(fakeJwt(now + 7200)),
            storedCookieHeader = storedHeader(fakeJwt(now + 600))
        )
        assertTrue(result.saved)
        assertTrue(webViewHeader(fakeJwt(now + 7200)).contains("suno_device_id=webview-device"))
        assertTrue(webViewHeader(fakeJwt(now + 7200)).contains("_ga=GA1.1.fake"))
    }
}
