package com.duskript.sunolocal.core.update

/**
 * AppUpdateInfo — result of a GitHub update check for the Suno Local Player app.
 *
 * @property currentVersion Installed app version from BuildConfig.VERSION_NAME.
 * @property latestVersion Newest GitHub release/tag name reported by the check ("" if unknown).
 * @property updateAvailable True when a newer release/tag exists than the installed version.
 * @property releaseUrl Browser URL for the GitHub release page (null if unavailable).
 * @property assetDownloadUrl Direct APK download URL from the release assets (null if none).
 * @property message Human-readable status: "You're up to date", "Update available", or a failure
 * description such as "No release published yet" / network error text.
 */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val updateAvailable: Boolean,
    val releaseUrl: String?,
    val assetDownloadUrl: String?,
    val message: String
) {
    companion object {
        /** Convenience factory for failed checks — never marks an update available. */
        fun failure(currentVersion: String, message: String) = AppUpdateInfo(
            currentVersion = currentVersion,
            latestVersion = "",
            updateAvailable = false,
            releaseUrl = null,
            assetDownloadUrl = null,
            message = message
        )
    }
}
