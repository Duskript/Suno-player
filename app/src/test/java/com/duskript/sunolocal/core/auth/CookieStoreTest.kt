package com.duskript.sunolocal.core.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

/** Unit tests for cookie input normalization. */
class CookieStoreTest {

    @Test
    fun `normaliseCookieInput converts Netscape export to Cookie header`() {
        val netscapeExport = """
            # Netscape HTTP Cookie File
            .suno.com	TRUE	/	FALSE	1820042096	_ga	GA1.1.fake
            suno.com	FALSE	/	TRUE	1817018098	__session	fake.jwt.value
            .suno.com	TRUE	/	TRUE	1817018096	__client_uat	1778814221
            example.com	FALSE	/	FALSE	1817018098	ignored	nope
        """.trimIndent()

        val normalized = CookieStore.normaliseCookieInput(netscapeExport)

        assertTrue(normalized.contains("__session=fake.jwt.value"))
        assertTrue(normalized.contains("__client_uat=1778814221"))
        assertTrue(normalized.contains("_ga=GA1.1.fake"))
        assertFalse(normalized.contains("ignored=nope"))
        assertFalse(normalized.contains("Netscape"))
        assertFalse(normalized.contains("\n"))
    }

    @Test
    fun `normaliseCookieInput preserves raw cookie header values`() {
        val normalized = CookieStore.normaliseCookieInput(
            "Cookie: __session=fake.jwt.value; suno_device_id=device-123"
        )

        assertEquals("__session=fake.jwt.value; suno_device_id=device-123", normalized)
    }

    @Test
    fun `normaliseCookieInput joins accidental multiline raw cookies`() {
        val normalized = CookieStore.normaliseCookieInput(
            """
                __session=fake.jwt.value
                suno_device_id=device-123
            """.trimIndent()
        )

        assertEquals("__session=fake.jwt.value; suno_device_id=device-123", normalized)
    }
}
