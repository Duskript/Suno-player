package com.duskript.sunolocal.core.auth

import android.webkit.CookieManager

/**
 * WebViewCookieBridge reads Suno cookies from the app-owned Android WebView
 * cookie jar and persists them through CookieStore.
 *
 * This only works for login sessions created inside this app's WebView. Android
 * intentionally isolates Chrome/Firefox cookies from app WebViews, so browser
 * sign-in outside the app cannot be read here.
 */
object WebViewCookieBridge {
    private val sunoCookieUrls = listOf(
        "https://suno.com",
        "https://www.suno.com",
        "https://studio-api-prod.suno.com"
    )

    fun readCookieHeader(): String? {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val cookiesByName = linkedMapOf<String, String>()
        sunoCookieUrls
            .mapNotNull { url -> cookieManager.getCookie(url) }
            .flatMap { header -> header.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    cookiesByName[name] = value
                }
            }

        return cookiesByName.entries
            .joinToString("; ") { (name, value) -> "$name=$value" }
            .takeIf { it.contains("__session=") }
    }

    /**
     * Adopt the WebView cookie jar into [cookieStore] using the safe
     * no-overwrite-older-cookie rule. The stored cookie is only replaced when
     * the WebView `__session` is missing-or-newer (or the stored cookie is
     * missing/already expired), never downgraded by an older/equal WebView
     * session. Returns a [CookieRefreshResult] with no secret material.
     */
    fun refreshCookieStore(cookieStore: CookieStore): CookieRefreshResult {
        val webViewHeader = readCookieHeader()
        val result = CookieAdoption.decide(
            webViewCookieHeader = webViewHeader,
            storedCookieHeader = cookieStore.getCookie(),
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        )
        if (result.saved && webViewHeader != null) {
            cookieStore.saveCookie(webViewHeader)
        }
        return result
    }
}
