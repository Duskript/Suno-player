package com.duskript.sunolocal.core.auth

/**
 * CookieRefreshResult — richer outcome of a WebView cookie adoption attempt.
 *
 * `captured` means a WebView `__session` was found; `saved` means the WebView
 * header was actually written into CookieStore. A capture can be refused
 * (saved=false) when the stored cookie is newer/equal or when expiry cannot
 * prove the WebView session is safe to adopt — see [CookieAdoption].
 *
 * The result carries no cookie/JWT material, only booleans, timestamps and
 * reason text, so it is always safe to log.
 */
data class CookieRefreshResult(
    val captured: Boolean,
    val saved: Boolean,
    val reason: String,
    val newExpiresAt: Long? = null,
    val oldExpiresAt: Long? = null,
)

/**
 * CookieAdoption — pure decision logic for adopting the app WebView cookie
 * jar into CookieStore. No Android dependencies: unit-testable with fake
 * JWTs and injected clocks.
 *
 * Documented rule summary (enforced here):
 * 1. No WebView `__session` -> captured=false, saved=false; store untouched.
 * 2. Stored `__session` missing -> adopt the WebView header even when its
 *    expiry is unknown (nothing newer to protect).
 * 3. WebView expiry newer than stored -> adopt (saved=true), keeping all
 *    non-`__session` cookies that ride along in the WebView header.
 * 4. WebView expiry older or equal -> refuse (saved=false); never overwrite
 *    a stored cookie with an older/equal WebView cookie.
 * 5. One or both expiries unknown -> adopt only when the stored cookie is
 *    missing or already expired; otherwise preserve the stored cookie
 *    (saved=false) because we cannot prove the WebView session is newer.
 */
object CookieAdoption {

    fun decide(
        webViewCookieHeader: String?,
        storedCookieHeader: String?,
        nowEpochSeconds: Long,
    ): CookieRefreshResult {
        val webViewSession = webViewCookieHeader
            ?.let { CookieStore.extractCookieValue(it, "__session") }
        if (webViewSession == null) {
            return CookieRefreshResult(
                captured = false,
                saved = false,
                reason = "No __session cookie found in WebView cookie jar"
            )
        }

        val newExpiresAt = CookieFreshness.jwtExpiresAtEpochSeconds(webViewSession)
        val storedSession = storedCookieHeader
            ?.let { CookieStore.extractCookieValue(it, "__session") }
        if (storedSession == null) {
            // Nothing stored to protect — adopt the WebView header even if its
            // expiry is unknown.
            return CookieRefreshResult(
                captured = true,
                saved = true,
                reason = "No stored __session — adopted WebView cookie",
                newExpiresAt = newExpiresAt,
                oldExpiresAt = null
            )
        }

        val oldExpiresAt = CookieFreshness.jwtExpiresAtEpochSeconds(storedSession)

        return when {
            newExpiresAt != null && oldExpiresAt != null -> {
                if (newExpiresAt > oldExpiresAt) {
                    CookieRefreshResult(
                        captured = true,
                        saved = true,
                        reason = "WebView __session is newer than stored",
                        newExpiresAt = newExpiresAt,
                        oldExpiresAt = oldExpiresAt
                    )
                } else {
                    CookieRefreshResult(
                        captured = true,
                        saved = false,
                        reason = "WebView __session not newer than stored — preserving stored cookie",
                        newExpiresAt = newExpiresAt,
                        oldExpiresAt = oldExpiresAt
                    )
                }
            }
            // WebView expiry unknown, stored expiry known.
            newExpiresAt == null && oldExpiresAt != null -> {
                if (oldExpiresAt <= nowEpochSeconds) {
                    CookieRefreshResult(
                        captured = true,
                        saved = true,
                        reason = "Stored cookie already expired — adopted WebView cookie (WebView expiry unknown)",
                        newExpiresAt = null,
                        oldExpiresAt = oldExpiresAt
                    )
                } else {
                    CookieRefreshResult(
                        captured = true,
                        saved = false,
                        reason = "WebView expiry unknown and stored cookie not expired — preserving stored cookie",
                        newExpiresAt = null,
                        oldExpiresAt = oldExpiresAt
                    )
                }
            }
            // WebView expiry known, stored expiry unknown.
            newExpiresAt != null && oldExpiresAt == null -> {
                CookieRefreshResult(
                    captured = true,
                    saved = false,
                    reason = "Stored cookie expiry unknown — cannot prove WebView is newer; preserving stored cookie",
                    newExpiresAt = newExpiresAt,
                    oldExpiresAt = null
                )
            }
            // Both expiries unknown.
            else -> {
                CookieRefreshResult(
                    captured = true,
                    saved = false,
                    reason = "Both expiries unknown — preserving stored cookie",
                    newExpiresAt = null,
                    oldExpiresAt = null
                )
            }
        }
    }
}
