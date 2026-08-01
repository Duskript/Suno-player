package com.duskript.sunolocal.core.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules the lowest-latency periodic sync Android allows: 15 minutes.
 *
 * This does not magically extend Suno's server-side JWT lifetime. It does keep
 * the app's stored cookie aligned with the WebView cookie jar before each sync,
 * then pulls playlist changes while the session is still valid.
 */
object SunoAutoSyncScheduler {
    private const val TAG = "SunoAutoSyncScheduler"
    private const val UNIQUE_WORK_NAME = "suno_auto_sync_every_15m"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SunoDownloadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    SunoDownloadWorker.KEY_MODE to SunoDownloadWorker.MODE_MY_LIBRARY,
                    SunoDownloadWorker.KEY_AUTO_SYNC to true
                )
            )
            .addTag("suno_auto_sync")
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Scheduled Suno auto-sync every 15 minutes")
    }
}
