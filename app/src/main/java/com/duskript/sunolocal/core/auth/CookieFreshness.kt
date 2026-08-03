package com.duskript.sunolocal.core.auth

/**
 * CookieFreshness — pure, Android-free description of the stored Suno
 * `__session` cookie's expiry state.
 *
 * All parsing is deterministic: the only time-dependent input is the
 * [nowEpochSeconds] passed into [of], which keeps unit tests fully
 * deterministic without touching clocks or Android framework classes.
 */
data class CookieFreshness(
    /** True when the cookie header contains a non-blank `__session` value. */
    val hasSession: Boolean,
    /** Epoch seconds when the `__session` JWT `exp` claim fires, if parseable. */
    val expiresAtEpochSeconds: Long?,
    /** `expiresAtEpochSeconds - nowEpochSeconds`; negative once already expired. */
    val secondsUntilExpiry: Long?,
    /** True when expiry is known and has already passed. */
    val isExpired: Boolean,
    /** Short human label: Missing / Unknown expiry / Expired / Expires in ~N min / Valid. */
    val statusLabel: String,
) {

    /** True when the session is already expired or expires within [windowSeconds] of the `now` used to build this instance. */
    fun expiresWithin(windowSeconds: Long): Boolean {
        val seconds = secondsUntilExpiry ?: return false
        return seconds <= windowSeconds
    }

    companion object {

        /** Build freshness for [cookieHeader] as of [nowEpochSeconds]. */
        fun of(cookieHeader: String?, nowEpochSeconds: Long): CookieFreshness {
            if (cookieHeader.isNullOrBlank()) {
                return CookieFreshness(
                    hasSession = false,
                    expiresAtEpochSeconds = null,
                    secondsUntilExpiry = null,
                    isExpired = false,
                    statusLabel = "Missing"
                )
            }
            val session = CookieStore.extractCookieValue(cookieHeader, "__session")
                ?: return CookieFreshness(
                    hasSession = false,
                    expiresAtEpochSeconds = null,
                    secondsUntilExpiry = null,
                    isExpired = false,
                    statusLabel = "Missing"
                )
            val expiresAt = jwtExpiresAtEpochSeconds(session)
            if (expiresAt == null) {
                return CookieFreshness(
                    hasSession = true,
                    expiresAtEpochSeconds = null,
                    secondsUntilExpiry = null,
                    isExpired = false,
                    statusLabel = "Unknown expiry"
                )
            }
            val secondsUntil = expiresAt - nowEpochSeconds
            val expired = secondsUntil <= 0L
            val label = when {
                expired -> "Expired"
                secondsUntil <= 60L -> "Expires in <1 min"
                secondsUntil < 3600L -> "Expires in ~${secondsUntil / 60L} min"
                else -> "Valid — expires in ~${secondsUntil / 3600L} h"
            }
            return CookieFreshness(
                hasSession = true,
                expiresAtEpochSeconds = expiresAt,
                secondsUntilExpiry = secondsUntil,
                isExpired = expired,
                statusLabel = label
            )
        }

        /**
         * Parse the `exp` claim (epoch seconds) from a JWT, or null when the
         * token is malformed or carries no positive `exp`. Pure JVM
         * implementation (java.util.Base64 + org.json) so it runs in unit
         * tests without Android stubs.
         */
        fun jwtExpiresAtEpochSeconds(jwt: String): Long? {
            return try {
                val parts = jwt.split('.')
                if (parts.size < 2) return null
                val payload = parts[1]
                // JWT payloads are unpadded base64url; java.util.Base64 is
                // padding-lenient, but pad explicitly for robustness.
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                val decoded = java.util.Base64.getUrlDecoder().decode(padded)
                org.json.JSONObject(String(decoded, Charsets.UTF_8))
                    .optLong("exp", 0L)
                    .takeIf { it > 0L }
            } catch (_: Exception) {
                null
            }
        }
    }
}
