package com.duskript.sunolocal.features.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import com.duskript.sunolocal.domain.model.SyncSummary
import com.duskript.sunolocal.features.library.state.LibraryViewModel
import com.duskript.sunolocal.shared.ui.ElevenLabsStylePlayer

/**
 * Main library screen with lyrics-on-tap, Suno metadata/art, custom playlists,
 * and a visible editable playback queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToSettings: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsState()
    val cookieConfigured by viewModel.cookieConfigured.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val currentTrack by viewModel.audioPlayer.currentTrack.collectAsState()
    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()
    val shuffleEnabled by viewModel.audioPlayer.shuffleEnabled.collectAsState()
    val queue by viewModel.audioPlayer.queue.collectAsState()
    val playbackPositionMs by viewModel.playbackPositionMs.collectAsState()
    val playbackDurationMs by viewModel.playbackDurationMs.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val playbackErrorMessage by viewModel.playbackErrorMessage.collectAsState()

    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var addPlaylistMode by remember { mutableStateOf<AddPlaylistMode?>(null) }
    var playlistNameInput by remember { mutableStateOf("") }
    var playlistUrlInput by remember { mutableStateOf("") }
    var showErrorDialog by remember { mutableStateOf(false) }
    var currentError by remember { mutableStateOf<String?>(null) }
    var selectedTrackDetails by remember { mutableStateOf<SunoTrack?>(null) }
    var showQueueSheet by remember { mutableStateOf(false) }
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    if (errorMessage != null && !showErrorDialog) {
        currentError = errorMessage
        showErrorDialog = true
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Set Suno Cookie", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Paste a Suno __session cookie or use Settings → Login to Suno.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        label = { Text("Cookie string") },
                        placeholder = { Text("__session=eyJ...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
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
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCookieDialog = false }) { Text("Cancel") } }
        )
    }

    // Step 1: choose what kind of playlist to add.
    if (showAddPlaylistDialog && addPlaylistMode == null) {
        AlertDialog(
            onDismissRequest = { showAddPlaylistDialog = false },
            title = { Text("Add playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Create a local mix from your downloaded tracks, or save a Suno playlist by URL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { addPlaylistMode = AddPlaylistMode.LOCAL },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Local playlist") }
                    OutlinedButton(
                        onClick = { addPlaylistMode = AddPlaylistMode.URL },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Playlist URL") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddPlaylistDialog = false }) { Text("Cancel") } }
        )
    }

    // Step 2a: name a new local playlist/mix.
    if (addPlaylistMode == AddPlaylistMode.LOCAL) {
        AlertDialog(
            onDismissRequest = { addPlaylistMode = null },
            title = { Text("New Local Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = playlistNameInput,
                    onValueChange = { playlistNameInput = it },
                    label = { Text("Playlist name") },
                    placeholder = { Text("Roadtrip Mix") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createCustomPlaylist(playlistNameInput)
                    playlistNameInput = ""
                    addPlaylistMode = null
                    showAddPlaylistDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { addPlaylistMode = null }) { Text("Cancel") } }
        )
    }

    // Step 2b: paste a Suno playlist URL to save.
    if (addPlaylistMode == AddPlaylistMode.URL) {
        AlertDialog(
            onDismissRequest = { addPlaylistMode = null },
            title = { Text("Add Playlist from URL", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = playlistUrlInput,
                        onValueChange = { playlistUrlInput = it },
                        label = { Text("Suno playlist URL") },
                        placeholder = { Text("https://suno.com/playlist/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The playlist and its tracks are downloaded in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveCreatorPlaylist(playlistUrlInput.trim())
                    playlistUrlInput = ""
                    addPlaylistMode = null
                    showAddPlaylistDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addPlaylistMode = null }) { Text("Cancel") } }
        )
    }

    selectedTrackDetails?.let { track ->
        TrackDetailDialog(
            track = track,
            onDismiss = { selectedTrackDetails = null },
            onPlay = {
                viewModel.playTrack(track.id)
                selectedTrackDetails = null
            },
            onAddToQueue = { viewModel.addTrackToQueue(track.id) }
        )
    }

    if (showQueueSheet) {
        ModalBottomSheet(
            sheetState = queueSheetState,
            onDismissRequest = { showQueueSheet = false }
        ) {
            QueueSheetContent(
                queue = queue,
                currentTrack = currentTrack,
                onPlayTrack = viewModel::playQueuedTrack,
                onMoveTrack = viewModel::moveQueuedTrack,
                onRemoveTrack = viewModel::removeTrackFromQueue
            )
        }
    }

    if (showErrorDialog && currentError != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("Heads up", fontWeight = FontWeight.Bold) },
            text = { Text(currentError ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    showErrorDialog = false
                    currentError = null
                }) { Text("OK") }
            }
        )
    }

    // Playback error heads-up: surfaced from Media3 (corrupt/missing file, etc.).
    // Dismissible — never blocks app controls; dismissing clears the error state.
    playbackErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearPlaybackError() },
            title = { Text("Playback issue", fontWeight = FontWeight.Bold) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearPlaybackError() }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Suno Local", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (syncStatus.isRunning) {
                            Text(
                                text = syncStatus.lastMessage ?: "Syncing…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Open playback queue")
                    }
                    IconButton(onClick = { showAddPlaylistDialog = true }) {
                        Icon(Icons.Filled.AddCircle, contentDescription = "Add playlist (local mix or URL)")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            ElevenLabsStylePlayer(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                shuffleEnabled = shuffleEnabled,
                progress = playbackProgress,
                positionMs = playbackPositionMs,
                durationMs = playbackDurationMs,
                repeatMode = repeatMode,
                onPlayPause = { viewModel.playPause() },
                onNext = { viewModel.next() },
                onPrevious = { viewModel.previous() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onToggleRepeat = { viewModel.toggleRepeatMode() },
                onSeekProgress = { viewModel.seekToProgress(it) },
                onTrackClick = { showQueueSheet = true }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AnimatedVisibility(visible = syncStatus.isRunning, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    progress = { syncStatus.progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (!cookieConfigured) {
                CookieSetupCard(onSetCookie = { showCookieDialog = true })
            } else {
                LibraryControls(
                    shuffleEnabled = shuffleEnabled,
                    syncError = syncStatus.lastError,
                    lastSyncSummary = lastSyncSummary,
                    onShuffle = viewModel::toggleShuffle,
                    onAddPlaylist = { showAddPlaylistDialog = true }
                )
            }

            val selected = selectedPlaylist
            when {
                selected != null -> TrackListView(
                    playlist = selected,
                    customPlaylists = playlists.filter { it.isCustom },
                    onBack = viewModel::clearSelection,
                    onPlayTrack = viewModel::playTrack,
                    onPlayAll = { viewModel.playPlaylist(selected.id) },
                    onShowTrackDetails = { selectedTrackDetails = it },
                    onAddToQueue = { viewModel.addTrackToQueue(it.id) },
                    onAddToPlaylist = viewModel::addTrackToCustomPlaylist,
                    onMoveTrack = { trackId, direction -> viewModel.moveTrackInPlaylist(selected.id, trackId, direction) },
                    onRemoveTrack = { trackId -> viewModel.removeTrackFromCustomPlaylist(selected.id, trackId) }
                )
                playlists.isEmpty() -> EmptyLibrary(cookieConfigured = cookieConfigured)
                else -> PlaylistListView(
                    playlists = playlists,
                    onPlaylistClick = viewModel::selectPlaylist,
                    onPlayPlaylist = viewModel::playPlaylist
                )
            }
        }
    }
}

@Composable
private fun CookieSetupCard(onSetCookie: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Set up your Suno cookie to sync metadata, lyrics, art, and audio.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onSetCookie, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enter Cookie")
            }
        }
    }
}

@Composable
private fun LibraryControls(
    shuffleEnabled: Boolean,
    syncError: String?,
    lastSyncSummary: SyncSummary?,
    onShuffle: () -> Unit,
    onAddPlaylist: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (syncError != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Sync error: $syncError",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        lastSyncSummary?.let { summary ->
            LastSyncCard(summary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onAddPlaylist, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Add Playlist")
            }
            OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(if (shuffleEnabled) "Shuffle ON" else "Shuffle")
            }
        }
    }
}

/** Compact last-sync status on the library page (full details live in Settings). */
@Composable
private fun LastSyncCard(summary: SyncSummary) {
    val failed = summary.hasFailures
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (summary.success) "Last sync: ${summary.timeLabel()}"
                else "Last sync failed: ${summary.timeLabel()}",
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = buildSummaryLine(summary),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
            )
            summary.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

private fun buildSummaryLine(summary: SyncSummary): String = buildString {
    append("${summary.downloadedCount} new")
    append(" • ${summary.skippedCount} unchanged")
    if (summary.failedCount > 0) append(" • ${summary.failedCount} failed")
}

@Composable
private fun EmptyLibrary(cookieConfigured: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (cookieConfigured) "Go to Settings → Resync to download your library, or tap + to add a playlist" else "Set your Suno cookie to get started",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistListView(
    playlists: List<SunoPlaylist>,
    onPlaylistClick: (String) -> Unit,
    onPlayPlaylist: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistCard(
                playlist = playlist,
                onClick = { onPlaylistClick(playlist.id) },
                onPlay = { onPlayPlaylist(playlist.id) }
            )
        }
    }
}

@Composable
private fun PlaylistCard(playlist: SunoPlaylist, onClick: () -> Unit, onPlay: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArt(playlist.tracks.firstOrNull()?.imageUrl, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (playlist.isCustom) "${playlist.title}  •  Custom" else playlist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                playlist.creatorName?.let {
                    Text("by $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = "${playlist.downloadedTrackCount}/${playlist.trackCount} tracks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${playlist.title}")
            }
        }
    }
}

@Composable
private fun TrackListView(
    playlist: SunoPlaylist,
    customPlaylists: List<SunoPlaylist>,
    onBack: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onPlayAll: () -> Unit,
    onShowTrackDetails: (SunoTrack) -> Unit,
    onAddToQueue: (SunoTrack) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onMoveTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Playlists") }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onPlayAll) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play all tracks in ${playlist.title}")
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = "${playlist.trackCount} tracks • ${playlist.downloadedTrackCount} downloaded" + if (playlist.isCustom) " • custom order" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tap a song for lyrics • Play button starts audio • +Q adds to queue",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(playlist.tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    isCustomPlaylist = playlist.isCustom,
                    customPlaylists = customPlaylists,
                    onClick = { onShowTrackDetails(track) },
                    onPlay = { onPlayTrack(track.id) },
                    onAddToQueue = { onAddToQueue(track) },
                    onAddToPlaylist = { targetId -> onAddToPlaylist(track.id, targetId) },
                    onMoveUp = { onMoveTrack(track.id, -1) },
                    onMoveDown = { onMoveTrack(track.id, 1) },
                    onRemove = { onRemoveTrack(track.id) }
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: SunoTrack,
    isCustomPlaylist: Boolean,
    customPlaylists: List<SunoPlaylist>,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add to custom playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (customPlaylists.isEmpty()) {
                        Text("Create a custom playlist first with New Mix.")
                    } else {
                        customPlaylists.forEach { playlist ->
                            OutlinedButton(
                                onClick = {
                                    onAddToPlaylist(playlist.id)
                                    showAddDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(playlist.title) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            PlaylistArt(track.imageUrl, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "Creator: ${track.creatorName ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.metadataSummary.isNotBlank()) {
                    Text(track.metadataSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                track.descriptionPrompt?.takeIf { it.isNotBlank() }?.let {
                    Text("Prompt: ${it.take(120)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                track.lyrics?.takeIf { it.isNotBlank() }?.let {
                    Text("Lyrics: ${it.lineSequence().firstOrNull().orEmpty().take(120)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onPlay, enabled = track.isPlayable) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${track.title}")
                }
                IconButton(onClick = onAddToQueue, enabled = track.isPlayable) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "Add ${track.title} to queue")
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.AddCircle, contentDescription = "Add ${track.title} to custom playlist")
                }
                if (isCustomPlaylist) {
                    IconButton(onClick = onMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                    IconButton(onClick = onMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove from custom playlist") }
                } else if (!track.isDownloaded) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "Not downloaded", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun TrackDetailDialog(
    track: SunoTrack,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(track.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "Creator: ${track.creatorName ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlaylistArt(track.imageUrl, modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                DetailSection("Creator", track.creatorName ?: "Unknown")
                if (track.metadataSummary.isNotBlank()) {
                    DetailSection("Metadata", track.metadataSummary)
                }
                track.descriptionPrompt?.takeIf { it.isNotBlank() }?.let {
                    DetailSection("Prompt", it)
                }
                track.stylePrompt?.takeIf { it.isNotBlank() }?.let {
                    DetailSection("Style", it)
                }
                DetailSection(
                    title = "Lyrics",
                    body = track.lyrics?.takeIf { it.isNotBlank() } ?: "No lyrics stored for this song yet. Resync metadata after refreshing the Suno login."
                )
            }
        },
        confirmButton = {
            Button(onClick = onPlay, enabled = track.isPlayable) { Text("Play") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddToQueue, enabled = track.isPlayable) { Text("+ Queue") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun DetailSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueSheetContent(
    queue: List<SunoTrack>,
    currentTrack: SunoTrack?,
    onPlayTrack: (String) -> Unit,
    onMoveTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            text = if (queue.isEmpty()) "No queued songs yet. Tap +Q on a song to add it." else "${queue.size} songs queued",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(queue, key = { it.id }) { track ->
                    QueueRow(
                        track = track,
                        isCurrent = currentTrack?.id == track.id,
                        onPlay = { onPlayTrack(track.id) },
                        onMoveUp = { onMoveTrack(track.id, -1) },
                        onMoveDown = { onMoveTrack(track.id, 1) },
                        onRemove = { onRemoveTrack(track.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: SunoTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PlaylistArt(track.imageUrl, modifier = Modifier.size(44.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isCurrent) "▶ ${track.title}" else track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Creator: ${track.creatorName ?: "Unknown"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${track.title}") }
        IconButton(onClick = onMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move queued track up") }
        IconButton(onClick = onMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move queued track down") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove from queue") }
    }
}

@Composable
private fun PlaylistArt(imageUrl: String?, modifier: Modifier = Modifier) {
    if (imageUrl.isNullOrBlank()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    } else {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Suno cover art",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

/** Which kind of playlist the Add Playlist wizard is currently collecting input for. */
private enum class AddPlaylistMode { LOCAL, URL }
