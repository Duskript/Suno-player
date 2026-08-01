package com.duskript.sunolocal.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHubUpdateChecker — queries the public GitHub REST API (unauthenticated) for
 * the newest release/tag of the Duskript/Suno-player repository.
 *
 * Primary endpoint: GET /repos/Duskript/Suno-player/releases/latest
 * Fallback endpoint: GET /repos/Duskript/Suno-player/tags (used when no release
 * has been published yet, e.g. HTTP 404).
 *
 * Comparison is intentionally conservative: versions are normalized by stripping
 * a leading "v" and any "-suffix" (e.g. "v0.2.0" -> "0.2.0", "0.1.0-mvp" ->
 * "0.1.0"). If the normalized latest version is non-empty and differs from the
 * installed version, an update is reported as available. No semver parsing.
 */
class GitHubUpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        const val REPO = "Duskript/Suno-player"
        const val RELEASES_LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val TAGS_URL = "https://api.github.com/repos/$REPO/tags"
        const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases"
    }

    /**
     * Checks GitHub for a newer version than [currentVersion].
     * Never throws: failures are folded into an [AppUpdateInfo] with a "failed"
     * style message so the UI can report them without crashing.
     */
    suspend fun checkForUpdates(currentVersion: String): AppUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease()
            if (release != null) {
                val latestNormalized = normalizeVersion(release.tagName)
                val currentNormalized = normalizeVersion(currentVersion)
                val updateAvailable = latestNormalized.isNotEmpty() && latestNormalized != currentNormalized
                AppUpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = release.tagName,
                    updateAvailable = updateAvailable,
                    releaseUrl = release.htmlUrl ?: RELEASES_PAGE_URL,
                    assetDownloadUrl = release.apkAssetUrl,
                    message = if (updateAvailable) {
                        "Update available: ${release.tagName}"
                    } else {
                        "You're up to date (${release.tagName})"
                    }
                )
            } else {
                // No published release — fall back to the newest tag.
                val tag = fetchLatestTag()
                if (tag.isNullOrEmpty()) {
                    AppUpdateInfo.failure(currentVersion, "No release published yet")
                } else {
                    val latestNormalized = normalizeVersion(tag)
                    val updateAvailable = latestNormalized.isNotEmpty() && latestNormalized != normalizeVersion(currentVersion)
                    AppUpdateInfo(
                        currentVersion = currentVersion,
                        latestVersion = tag,
                        updateAvailable = updateAvailable,
                        releaseUrl = RELEASES_PAGE_URL,
                        assetDownloadUrl = null,
                        message = if (updateAvailable) {
                            "Update available: $tag"
                        } else {
                            "You're up to date ($tag)"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            AppUpdateInfo.failure(currentVersion, "Update check failed: ${e.message ?: "network error"}")
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val request = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SunoLocalPlayer")
            .build()

        val response = client.newCall(request).execute()
        response.use {
            if (!response.isSuccessful) return null // 404 (no releases) or rate-limited
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        apkUrl = url
                        break
                    }
                }
            }
            return ReleaseInfo(
                tagName = json.optString("tag_name", ""),
                htmlUrl = json.optString("html_url").takeIf { it.isNotBlank() },
                apkAssetUrl = apkUrl
            )
        }
    }

    private fun fetchLatestTag(): String? {
        val request = Request.Builder()
            .url(TAGS_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SunoLocalPlayer")
            .build()

        val response = client.newCall(request).execute()
        response.use {
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val array = org.json.JSONArray(body)
            if (array.length() == 0) return null
            return array.optJSONObject(0)?.optString("name", "")
        }
    }

    private fun normalizeVersion(version: String): String =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .trim()

    private data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String?,
        val apkAssetUrl: String?
    )
}
