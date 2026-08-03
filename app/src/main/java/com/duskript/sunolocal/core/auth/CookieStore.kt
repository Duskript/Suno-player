package com.duskript.sunolocal.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * CookieStore — persists Suno cookies for API authentication.
 *
 * The app accepts either a normal HTTP Cookie header value:
 *     __session=...; suno_device_id=...
 *
 * or a browser-exported Netscape cookie file. Netscape exports are common from
 * cookie.txt browser extensions and contain one tab-delimited cookie per line.
 * Saving them raw as the Cookie header breaks OkHttp/Suno requests, so inputs
 * are normalized before persistence.
 */
class CookieStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback — plain SharedPreferences (less secure, but functional on unsupported devices).
        // CAVEAT: Cookie stored in plaintext on disk. Consider using Android Keystore directly for production.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Save Suno cookie material after converting exports to an HTTP Cookie header value. */
    fun saveCookie(cookie: String) {
        prefs.edit().putString(KEY_COOKIE, normaliseCookieInput(cookie)).apply()
    }

    /** Retrieve the saved HTTP Cookie header value, or null if none set. */
    fun getCookie(): String? = prefs.getString(KEY_COOKIE, null)

    /** Clear the saved cookie (e.g. on sign-out or expired session). */
    fun clearCookie() {
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    /** True if a non-blank cookie has been saved. */
    fun isConfigured(): Boolean = !getCookie().isNullOrBlank()

    /** Epoch seconds when the stored __session JWT expires, if the cookie uses JWT format. */
    fun sessionExpiresAtEpochSeconds(): Long? = freshness().expiresAtEpochSeconds

    /** True once the session is within [windowSeconds] of expiry or already expired. */
    fun sessionExpiresWithin(windowSeconds: Long): Boolean =
        freshness().expiresWithin(windowSeconds)

    /**
     * Freshness of the stored cookie as of [nowEpochSeconds]. Defaults to the
     * current wall clock so runtime call sites stay unchanged; tests inject a
     * fixed [nowEpochSeconds] for deterministic assertions.
     */
    fun freshness(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): CookieFreshness =
        CookieFreshness.of(getCookie(), nowEpochSeconds)

    companion object {
        private const val PREFS_NAME = "suno_local_cookie_prefs"
        private const val KEY_COOKIE = "suno_session_cookie"

        /**
         * Convert pasted Suno cookie text into a legal HTTP Cookie header value.
         *
         * Supported inputs:
         * - Netscape HTTP Cookie File lines:
         *   .suno.com TRUE / TRUE 1234567890 __session jwt...
         * - Raw header values:
         *   Cookie: __session=jwt...; suno_device_id=abc
         *   __session=jwt...; suno_device_id=abc
         * - Accidental pasted single cookie assignment:
         *   __session=jwt...
         */
        fun normaliseCookieInput(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return ""

            val parsedNetscape = parseNetscapeCookieExport(trimmed)
            if (parsedNetscape.isNotEmpty()) {
                return parsedNetscape
            }

            return trimmed
                .removePrefix("Cookie:")
                .removePrefix("cookie:")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("; ")
                .replace(Regex(";\\s*;"), ";")
                .trim(';', ' ')
        }

        fun extractCookieValue(cookieHeader: String, name: String): String? {
            return cookieHeader.split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
        }

        /**
         * Parse the `exp` claim (epoch seconds) from a JWT, or null when the
         * token is malformed or carries no positive `exp`. Delegates to the
         * pure [CookieFreshness] parser (java.util.Base64 + org.json) so the
         * logic is exercised by deterministic unit tests.
         */
        fun jwtExpiresAtEpochSeconds(jwt: String): Long? =
            CookieFreshness.jwtExpiresAtEpochSeconds(jwt)

        private fun parseNetscapeCookieExport(input: String): String {
            val cookies = linkedMapOf<String, String>()

            input.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size < 7) return@forEach

                    val domain = fields[0]
                    if (!domain.contains("suno.com")) return@forEach

                    val name = fields[5].trim()
                    val value = fields.subList(6, fields.size).joinToString("\t").trim()
                    if (name.isBlank() || value.isBlank()) return@forEach

                    cookies[name] = value
                }

            return cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        }
    }
}
