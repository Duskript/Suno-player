package com.duskript.sunolocal.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SyncSummary — the persisted result of the last completed library sync.
 *
 * Written by SunoDownloadWorker when a sync finishes (success or failure) and
 * loaded by LibraryViewModel so the Library page and Settings → Library Sync
 * can show what happened, even after an app restart.
 *
 * @property finishedAtEpochMs When the sync finished (System.currentTimeMillis).
 * @property mode Worker mode that ran (SunoDownloadWorker.MODE_MY_LIBRARY or MODE_PLAYLIST_URL).
 * @property source Playlist URL for MODE_PLAYLIST_URL syncs, null for library syncs.
 * @property success True when the sync operation completed (partial download failures allowed).
 * @property totalTracks Tracks considered by the sync.
 * @property downloadedCount Tracks newly downloaded.
 * @property skippedCount Tracks already present locally (unchanged).
 * @property failedCount Tracks whose download failed.
 * @property message Human-readable outcome line.
 * @property error Failure/partial-failure detail (null on clean success).
 */
data class SyncSummary(
    val finishedAtEpochMs: Long,
    val mode: String,
    val source: String? = null,
    val success: Boolean,
    val totalTracks: Int = 0,
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val message: String,
    val error: String? = null
) {
    /** Compact "8:42 PM" wall-clock label for the finish time. */
    fun timeLabel(): String = try {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(finishedAtEpochMs))
    } catch (e: Exception) {
        ""
    }

    /** True when the sync ended with a failure (hard failure or partial download failures). */
    val hasFailures: Boolean get() = !success || failedCount > 0 || error != null
}

/**
 * Guidance shown when the last sync failed because the Suno session cookie
 * expired or was rejected (HTTP 401/403, or an expired/unauthorized message).
 */
const val COOKIE_EXPIRED_GUIDANCE = "Cookie expired — Re-login in Settings, then Resync Library."

/**
 * True when a sync error message means the Suno session cookie expired,
 * was rejected, or is otherwise unauthorized (HTTP 401/403,
 * "unauthorized", "expired", "cookie"). Used to surface re-login guidance
 * instead of a generic error; network and validation errors return false.
 */
fun isCookieAuthError(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val lower = message.lowercase()
    return lower.contains("401") ||
        lower.contains("403") ||
        lower.contains("unauthorized") ||
        lower.contains("expired") ||
        lower.contains("cookie")
}
