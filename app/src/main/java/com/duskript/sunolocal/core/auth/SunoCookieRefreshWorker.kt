package com.duskript.sunolocal.core.auth

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Periodic worker that copies fresh Suno cookies from the app WebView cookie jar
 * into CookieStore. This keeps CookieStore aligned with an in-app WebView login
 * session without requiring the user to paste a cookie repeatedly.
 *
 * Since Batch C (v0.1.22-cookie-freshness) the adoption goes through the safe
 * no-overwrite-older-cookie rule in [CookieAdoption]: a WebView `__session`
 * that is older/equal to (or not provably newer than) the stored cookie is
 * refused, and the stored cookie is preserved.
 */
class SunoCookieRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cookieStore = CookieStore(applicationContext)
        val result = WebViewCookieBridge.refreshCookieStore(cookieStore)

        return if (result.saved) {
            Log.i(
                TAG,
                "Suno WebView cookie refresh succeeded: captured=${result.captured} saved=true " +
                    "reason=${result.reason} newExpiresAt=${result.newExpiresAt} oldExpiresAt=${result.oldExpiresAt}"
            )
            Result.success(workDataOf(KEY_REFRESHED to true, KEY_REASON to result.reason))
        } else if (result.captured) {
            Log.w(
                TAG,
                "Suno WebView __session found but not saved (stored cookie kept): " +
                    "reason=${result.reason} newExpiresAt=${result.newExpiresAt} oldExpiresAt=${result.oldExpiresAt}"
            )
            Result.success(workDataOf(KEY_REFRESHED to false, KEY_REASON to result.reason))
        } else {
            Log.w(TAG, "No Suno __session cookie found in WebView cookie jar")
            Result.success(workDataOf(KEY_REFRESHED to false))
        }
    }

    companion object {
        private const val TAG = "SunoCookieRefreshWorker"
        const val KEY_REFRESHED = "refreshed"
        const val KEY_REASON = "reason"
    }
}
