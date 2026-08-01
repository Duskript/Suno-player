package com.duskript.sunolocal.domain.model

/**
 * SyncStatus — represents the current state of a Suno sync operation.
 *
 * @property isRunning True if a sync/download operation is currently in progress.
 * @property lastMessage Human-readable description of the current or last sync state.
 * @property lastError If the last sync failed, a description of the error (null on success or idle).
 * @property progress Progress as a float from 0.0 (starting) to 1.0 (complete).
 */
data class SyncStatus(
    val isRunning: Boolean = false,
    val lastMessage: String? = null,
    val lastError: String? = null,
    val progress: Float = 0f
) {
    companion object {
        val IDLE = SyncStatus(isRunning = false, lastMessage = "Idle", progress = 0f)
        val RUNNING = SyncStatus(isRunning = true, lastMessage = "Syncing\u2026", progress = 0f)

        fun error(message: String) = SyncStatus(
            isRunning = false,
            lastMessage = "Error",
            lastError = message,
            progress = 0f
        )

        fun progress(progress: Float, message: String) = SyncStatus(
            isRunning = true,
            lastMessage = message,
            progress = progress.coerceIn(0f, 1f)
        )
    }
}
