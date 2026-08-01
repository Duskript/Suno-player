package com.duskript.sunolocal.features.settings.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.duskript.sunolocal.BuildConfig
import com.duskript.sunolocal.domain.model.COOKIE_EXPIRED_GUIDANCE
import com.duskript.sunolocal.domain.model.SyncSummary
import com.duskript.sunolocal.domain.model.isCookieAuthError
import com.duskript.sunolocal.features.library.state.LibraryViewModel

private const val TAG = "SunoLoginWebView"
private const val SUNO_LOGIN_URL = "https://suno.com/login"

/**
 * SettingsScreen — app info, cookie-management, and in-app Suno login surface.
 *
 * Suno access tokens are short-lived browser session JWTs. The preferred flow is
 * to sign into Suno inside this app's WebView, copy the WebView cookie jar into
 * CookieStore, then probe playlist/me. Manual cookie paste remains as fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel,
    onNavigateBack: () -> Unit
) {
    val cookieConfigured by viewModel.cookieConfigured.collectAsState()
    val cookieStatus by viewModel.cookieStatus.collectAsState()
    val connectionTestStatus by viewModel.connectionTestStatus.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateCheckRunning by viewModel.updateCheckRunning.collectAsState()
    // v0.1.15 — download health + hidden-playlist restore state.
    val storedPlaylists by viewModel.storedPlaylists.collectAsState()
    val hiddenPlaylistCount by viewModel.hiddenPlaylistCount.collectAsState()
    val context = LocalContext.current
    var showCookieDialog by remember { mutableStateOf(false) }
    var showSunoLogin by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Update Suno Cookie", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Paste a fresh Suno cookie when sync returns 401. " +
                            "Use either the full Netscape cookie export or a single " +
                            "__session=eyJ... value. Suno session JWTs usually expire quickly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        label = { Text("Fresh cookie") },
                        placeholder = { Text("__session=eyJ...") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (cookieInput.isNotBlank()) {
                        viewModel.saveCookie(cookieInput.trim())
                        cookieInput = ""
                        showCookieDialog = false
                    }
                }) {
                    Text("Save Cookie")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    cookieInput = ""
                    showCookieDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSunoLogin) {
        SunoLoginScreen(
            viewModel = viewModel,
            onDone = {
                viewModel.captureWebViewCookie()
                showSunoLogin = false
            },
            onBack = { showSunoLogin = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to library"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("Cookie")
            InfoRow("Status", cookieStatus)
            Text(
                text = "Login to Suno opens Suno's direct login page in an app-owned WebView. " +
                    "After login, tap Done to capture the WebView cookie jar for sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showSunoLogin = true }) {
                    Text("Login to Suno")
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = { showCookieDialog = true }) {
                    Text(if (cookieConfigured) "Update Manually" else "Set Manually")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { viewModel.testConnection() }) {
                    Text("Test Connection")
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = { viewModel.clearCookie() }) {
                    Text("Clear Cookie")
                }
            }
            if (connectionTestStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = connectionTestStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Library Sync")
            InfoRow(
                "Status",
                syncStatus.lastError?.let { "Error — $it" }
                    ?: syncStatus.lastMessage
                    ?: if (syncStatus.isRunning) "Syncing…" else "Idle"
            )
            if (syncStatus.isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { syncStatus.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Last sync result — persisted by SunoDownloadWorker, survives restarts.
            lastSyncSummary?.let { summary ->
                Spacer(modifier = Modifier.height(12.dp))
                LastSyncResultSection(summary)
            }

            // v0.1.15 — download health dashboard: honest totals from the stored
            // library plus the last sync's failed/unchanged counts. Per-track
            // failure lists are not tracked, so nothing is invented here.
            Spacer(modifier = Modifier.height(16.dp))
            DownloadHealthSection(
                playlistCount = storedPlaylists.size,
                totalTrackCount = storedPlaylists.sumOf { it.trackCount },
                downloadedTrackCount = storedPlaylists.sumOf { it.downloadedTrackCount },
                lastSyncSummary = lastSyncSummary
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.resyncMine() },
                enabled = !syncStatus.isRunning
            ) {
                Text("Resync Library")
            }
            if (!syncStatus.isRunning && syncStatus.lastError != null) {
                Text(
                    text = "Last sync failed — tap Resync Library to try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // v0.1.15 — playlist cleanup restore tool.
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Hidden playlists")
            Text(
                text = if (hiddenPlaylistCount > 0) {
                    "$hiddenPlaylistCount playlist(s) were removed from the Library and stay hidden during Resync Library."
                } else {
                    "No playlists are hidden. Removing a synced playlist from the Library hides it here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.restoreHiddenPlaylists() },
                enabled = hiddenPlaylistCount > 0
            ) {
                Text("Restore hidden playlists")
            }
            Text(
                text = "Restoring clears the hidden list — run Resync Library afterwards to bring the playlists back.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Updates")
            InfoRow("Installed version", BuildConfig.VERSION_NAME)
            Text(
                text = updateInfo?.message ?: "Not checked yet — tap Check for Updates to compare against GitHub (Duskript/Suno-player).",
                style = MaterialTheme.typography.bodySmall,
                color = if (updateInfo?.updateAvailable == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.checkForUpdates() },
                    enabled = !updateCheckRunning
                ) {
                    Text(if (updateCheckRunning) "Checking…" else "Check for Updates")
                }
                if (updateInfo?.updateAvailable == true) {
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(onClick = { viewModel.openUpdatePage(context) }) {
                        Text("Open Release")
                    }
                    if (updateInfo?.assetDownloadUrl != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(onClick = { viewModel.openUpdateDownload(context) }) {
                            Text("Download Update")
                        }
                    }
                }
            }
            Text(
                text = "Updates open in your browser — Android does not allow silent APK installs.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("App Info")
            InfoRow("Version", BuildConfig.VERSION_NAME)
            InfoRow("Build", "compileSdk 35, minSdk 26")
            InfoRow("Player", "Media3 / ExoPlayer")
            InfoRow("Storage", "JSON in app-private directory")

            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Documentation")
            InfoRow("Suno API", "docs/SUNO_API_NOTES.md — endpoints are unofficial")
            InfoRow("ElevenLabs", "docs/ELEVENLABS_REACT_NOTES.md — pure Compose implementation")

            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Disclaimer")
            Text(
                text = "This app interacts with Suno's unofficial API. Endpoints may break " +
                    "without notice. Downloaded tracks are for personal offline use only. " +
                    "No copyright infringement is intended.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SunoLoginScreen(
    viewModel: LibraryViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(SUNO_LOGIN_URL) }
    var pageStatus by remember { mutableStateOf("Loading Suno login…") }
    var progress by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login to Suno") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to settings"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        webView?.loadUrl(SUNO_LOGIN_URL)
                        pageStatus = "Reloading login page…"
                    }) {
                        Text("Reload")
                    }
                    TextButton(onClick = onDone) {
                        Text("Done")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = pageStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView.setWebContentsDebuggingEnabled(true)
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            flush()
                        }
                        WebView(context).apply {
                            webView = this
                            setBackgroundColor(android.graphics.Color.WHITE)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false)
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv", "")
                                .replace("Version/4.0 ", "")
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    consoleMessage?.let {
                                        Log.d(TAG, "${it.messageLevel()}: ${it.message()} @ ${it.sourceId()}:${it.lineNumber()}")
                                    }
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    return handleUrl(view, url)
                                }

                                @Suppress("OVERRIDE_DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    return handleUrl(view, url ?: return false)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    currentUrl = url.orEmpty().ifBlank { SUNO_LOGIN_URL }
                                    pageStatus = "Loading…"
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    currentUrl = url.orEmpty().ifBlank { currentUrl }
                                    val captured = viewModel.captureWebViewCookie()
                                    pageStatus = if (captured) {
                                        "Suno cookie captured — tap Done, then Test Connection."
                                    } else {
                                        "Loaded. If Suno shows an app prompt, choose Web Browser → Continue."
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        pageStatus = "Load error: ${error?.description ?: "unknown"}"
                                    }
                                }

                                private fun handleUrl(view: WebView?, url: String): Boolean {
                                    currentUrl = url
                                    val uri = Uri.parse(url)
                                    val scheme = uri.scheme.orEmpty()
                                    if (scheme == "http" || scheme == "https") {
                                        return false
                                    }
                                    Log.d(TAG, "Blocked external login/app URL in WebView: $url")
                                    pageStatus = "External app link blocked — continue in Web Browser inside this screen."
                                    view?.loadUrl(SUNO_LOGIN_URL)
                                    return true
                                }
                            }
                            loadUrl(SUNO_LOGIN_URL)
                        }
                    },
                    update = { view ->
                        if (webView !== view) webView = view
                    }
                )
                if (progress == 0) {
                    Text(
                        text = "Starting WebView…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Detailed last-sync result shown under Settings → Library Sync. */
@Composable
private fun LastSyncResultSection(summary: SyncSummary) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InfoRow("Last sync", summary.timeLabel())
        InfoRow("Mode", modeLabel(summary.mode))
        InfoRow(
            "Result",
            when {
                !summary.success -> "Failed"
                summary.failedCount > 0 -> "Partial — some downloads failed"
                else -> "Success"
            }
        )
        InfoRow(
            "Tracks",
            "${summary.totalTracks} total — ${summary.downloadedCount} new, " +
                "${summary.skippedCount} unchanged" +
                (if (summary.failedCount > 0) ", ${summary.failedCount} failed" else "")
        )
        if (summary.source != null) InfoRow("Source", summary.source)
        Text(
            text = summary.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        summary.error?.let { error ->
            Spacer(modifier = Modifier.height(4.dp))
            if (isCookieAuthError(error)) {
                Text(
                    text = COOKIE_EXPIRED_GUIDANCE,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "Error — $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "my_library" -> "My Library"
    "playlist_url" -> "Playlist URL"
    else -> mode
}

/**
 * v0.1.15 — Download health dashboard. Shows honest totals derived from the
 * stored library (playlist/track/downloaded counts) plus the last sync's new /
 * unchanged / failed counts, with cookie re-login guidance when the last sync
 * failed on an auth error. Per-track failure lists are not tracked by the sync
 * pipeline, so the card never invents them.
 */
@Composable
private fun DownloadHealthSection(
    playlistCount: Int,
    totalTrackCount: Int,
    downloadedTrackCount: Int,
    lastSyncSummary: SyncSummary?
) {
    SectionHeader("Download health")
    InfoRow(
        "Library",
        "$playlistCount playlists • $totalTrackCount tracks • $downloadedTrackCount downloaded"
    )
    val summary = lastSyncSummary
    if (summary != null) {
        InfoRow("Last sync", summary.timeLabel())
        InfoRow(
            "Result",
            when {
                !summary.success -> "Failed"
                summary.failedCount > 0 -> "Partial — some downloads failed"
                else -> "Success"
            }
        )
        InfoRow(
            "Downloads",
            "${summary.downloadedCount} new • ${summary.skippedCount} unchanged" +
                (if (summary.failedCount > 0) " • ${summary.failedCount} failed" else "")
        )
        val error = summary.error
        if (error != null && isCookieAuthError(error)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = COOKIE_EXPIRED_GUIDANCE,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
        }
    } else {
        Text(
            text = "No sync has run yet — sync your library to see download totals here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
