package com.duskript.sunolocal.core.storage

import android.content.Context
import android.util.Log
import com.duskript.sunolocal.domain.model.SyncSummary
import org.json.JSONObject
import java.io.File

/**
 * SyncSummaryStore — persists the single most recent sync result as JSON in
 * app-private storage (suno_last_sync.json), mirroring LibraryStore's style.
 *
 * Load errors never crash the app: a corrupt/missing file yields null and the
 * file is reset. Save errors are logged and swallowed so a status write can
 * never fail the sync worker itself.
 */
class SyncSummaryStore(context: Context) {

    private val summaryFile = File(context.filesDir, FILE_NAME)

    /** Load the last sync summary, or null when none exists / the file is corrupt. */
    fun load(): SyncSummary? {
        if (!summaryFile.exists()) return null
        return try {
            parseSyncSummary(JSONObject(summaryFile.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $FILE_NAME — resetting", e)
            summaryFile.delete()
            null
        }
    }

    /** Persist the last sync summary. Failures are logged, never thrown. */
    fun save(summary: SyncSummary) {
        try {
            summaryFile.writeText(summary.toJsonObject().toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $FILE_NAME", e)
        }
    }

    companion object {
        private const val TAG = "SyncSummaryStore"
        private const val FILE_NAME = "suno_last_sync.json"
    }
}

internal fun SyncSummary.toJsonObject(): JSONObject = JSONObject().apply {
    put("finished_at_epoch_ms", finishedAtEpochMs)
    put("mode", mode)
    put("source", source ?: JSONObject.NULL)
    put("success", success)
    put("total_tracks", totalTracks)
    put("downloaded_count", downloadedCount)
    put("skipped_count", skippedCount)
    put("failed_count", failedCount)
    put("message", message)
    put("error", error ?: JSONObject.NULL)
}

internal fun parseSyncSummary(json: JSONObject): SyncSummary? {
    val mode = json.optString("mode").takeIf { it.isNotBlank() } ?: return null
    return SyncSummary(
        finishedAtEpochMs = json.optLong("finished_at_epoch_ms", 0L),
        mode = mode,
        source = json.optNullableString("source"),
        success = json.optBoolean("success", false),
        totalTracks = json.optInt("total_tracks", 0),
        downloadedCount = json.optInt("downloaded_count", 0),
        skippedCount = json.optInt("skipped_count", 0),
        failedCount = json.optInt("failed_count", 0),
        message = json.optString("message").takeIf { it.isNotBlank() } ?: "Sync finished",
        error = json.optNullableString("error")
    )
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}
