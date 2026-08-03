package com.duskript.sunolocal.core.auth

/**
 * AuthFlowStatus — user-facing status strings for the capture + validate
 * login flow and connection testing (v0.1.23-auth-refresh-flow).
 *
 * Pure Kotlin so the mapping is unit-testable without Android. These strings
 * never include cookie/JWT material — only HTTP codes, outcome words and
 * recovery guidance.
 */
object AuthFlowStatus {
    const val STATUS_CAPTURING = "Capturing WebView cookie…"
    const val STATUS_CAPTURED_VALIDATING = "Captured, validating…"
    const val STATUS_CAPTURED_KEPT_STORED = "Captured (kept stored cookie), validating…"
    const val STATUS_NO_WEBVIEW_NO_STORED = "Login to Suno required — no Suno cookie found."
    const val STATUS_NO_NEW_WEBVIEW_VALIDATING = "No new WebView cookie — validating stored cookie…"
    const val STATUS_VALID = "Valid — Suno playlist/me returned HTTP 200."

    /**
     * Status shown when the playlist/me probe is rejected by Suno. HTTP 401
     * and 403 both mean the session cookie was refused; the guidance points
     * the user at the in-app login recovery action.
     */
    fun statusForRejection(httpCode: Int): String = when (httpCode) {
        401 -> "Login required — Suno returned HTTP 401. Tap Login to Suno to refresh."
        403 -> "Rejected — Suno returned HTTP 403 for this session. Tap Login to Suno."
        else -> "Failed — Suno connection test failed."
    }

    /** Status shown when the probe fails for a non-HTTP reason. */
    fun statusForFailure(message: String?): String =
        "Failed — ${message ?: "Suno connection test failed"}"
}
