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
 */
class SunoCookieRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cookieStore = CookieStore(applicationContext)
        val refreshed = WebViewCookieBridge.refreshCookieStore(cookieStore)

        return if (refreshed) {
            Log.i(TAG, "Suno WebView cookie refresh succeeded")
            Result.success(workDataOf(KEY_REFRESHED to true))
        } else {
            Log.w(TAG, "No Suno __session cookie found in WebView cookie jar")
            Result.success(workDataOf(KEY_REFRESHED to false))
        }
    }

    companion object {
        private const val TAG = "SunoCookieRefreshWorker"
        const val KEY_REFRESHED = "refreshed"
    }
}
