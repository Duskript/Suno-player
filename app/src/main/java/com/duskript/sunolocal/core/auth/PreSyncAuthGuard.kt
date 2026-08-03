package com.duskript.sunolocal.core.auth

/**
 * PreSyncAuthGuard — pure decision logic for the pre-sync auth guard
 * (v0.1.23-auth-refresh-flow).
 *
 * The download worker runs this before any playlist fetch/download so a sync
 * never starts on a stored cookie that is missing, expired, or provably about
 * to expire. The decision is deterministic: the only time-dependent input is
 * the [CookieFreshness] built from the stored cookie, so unit tests exercise
 * every branch without Android or network access.
 *
 * The guard never inspects cookie/JWT values and never clears library data.
 */
enum class PreSyncAuthAction {
    /** Stored cookie is present and not provably near expiry — sync may start. */
    PROCEED,

    /** Stored cookie is missing/expired/near-expired — live-validate with playlist/me first. */
    VALIDATE,

    /** No usable cookie is configured — surface login-required guidance and skip the sync. */
    LOGIN_REQUIRED,

    /** Live validation hit a transient network failure — let WorkManager retry with backoff. */
    RETRY,
}

/** Outcome of [PreSyncAuthGuard.decide] with an honest, log-safe reason. */
data class PreSyncAuthDecision(
    val action: PreSyncAuthAction,
    val reason: String,
    val configured: Boolean,
    val hasSession: Boolean,
    val isExpired: Boolean,
    val expiresWithinWindow: Boolean,
)

object PreSyncAuthGuard {

    /**
     * Decide what the pre-sync guard must do.
     *
     * @param configured whether CookieStore holds a non-blank cookie header.
     * @param freshness the stored cookie's [CookieFreshness].
     * @param nearExpiryWindowSeconds cookies expiring within this window are
     *   treated as needing a live validation (spec suggests 5 minutes; the
     *   caller picks the constant).
     */
    fun decide(
        configured: Boolean,
        freshness: CookieFreshness,
        nearExpiryWindowSeconds: Long,
    ): PreSyncAuthDecision {
        if (!configured) {
            return PreSyncAuthDecision(
                action = PreSyncAuthAction.LOGIN_REQUIRED,
                reason = "No Suno cookie configured",
                configured = false,
                hasSession = false,
                isExpired = false,
                expiresWithinWindow = false,
            )
        }

        val expiresWithinWindow = freshness.expiresWithin(nearExpiryWindowSeconds)
        val needsValidation = !freshness.hasSession || freshness.isExpired || expiresWithinWindow

        return if (needsValidation) {
            PreSyncAuthDecision(
                action = PreSyncAuthAction.VALIDATE,
                reason = when {
                    !freshness.hasSession -> "Stored cookie has no __session — must live-validate"
                    freshness.isExpired -> "Stored cookie expired — must live-validate"
                    else -> "Stored cookie expires within $nearExpiryWindowSeconds s — must live-validate"
                },
                configured = true,
                hasSession = freshness.hasSession,
                isExpired = freshness.isExpired,
                expiresWithinWindow = expiresWithinWindow,
            )
        } else {
            PreSyncAuthDecision(
                action = PreSyncAuthAction.PROCEED,
                reason = "Stored cookie looks fresh",
                configured = true,
                hasSession = freshness.hasSession,
                isExpired = false,
                expiresWithinWindow = false,
            )
        }
    }
}

/**
 * Concise SyncSummary messages used by the pre-sync auth guard call sites.
 * Auto-sync skips quietly (success, no retry spam); manual sync fails loudly.
 */
object PreSyncAuthMessages {
    const val AUTO_SYNC_SKIPPED_LOGIN_REQUIRED = "Auto-sync skipped: login required"
    const val SYNC_FAILED_LOGIN_REQUIRED = "Sync failed: login required"
}
