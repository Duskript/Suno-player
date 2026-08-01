package com.duskript.sunolocal.features.library.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
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
import com.duskript.sunolocal.features.library.state.LibraryPlaylistFilter
import com.duskript.sunolocal.features.library.state.LibraryViewModel
import com.duskript.sunolocal.features.library.state.TrackFilter
import com.duskript.sunolocal.features.library.state.defaultDuplicateTitle
import com.duskript.sunolocal.features.library.state.filterPlaylists
import com.duskript.sunolocal.features.library.state.filterTracks
import com.duskript.sunolocal.features.library.state.isSmartMixId
import com.duskript.sunolocal.features.library.state.playlistsByCreator
import com.duskript.sunolocal.features.library.state.similarTracks
import com.duskript.sunolocal.features.library.state.trackMetadataLine
import com.duskript.sunolocal.features.library.state.tracksByCreator
import com.duskript.sunolocal.shared.ui.ElevenLabsStylePlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val storedPlaylists by viewModel.storedPlaylists.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val selectedCreator by viewModel.selectedCreator.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsState()
    val cookieConfigured by viewModel.cookieConfigured.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    // v0.1.15 — favorites, resume snapshot, and hidden-playlist restore state.
    val favoriteTrackIds by viewModel.favoriteTrackIds.collectAsState()
    val lastPlaybackState by viewModel.lastPlaybackState.collectAsState()

    val currentTrack by viewModel.audioPlayer.currentTrack.collectAsState()
    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()
    val shuffleEnabled by viewModel.audioPlayer.shuffleEnabled.collectAsState()
    val queue by viewModel.audioPlayer.queue.collectAsState()
    val playbackPositionMs by viewModel.playbackPositionMs.collectAsState()
    val playbackDurationMs by viewModel.playbackDurationMs.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val playbackErrorMessage by viewModel.playbackErrorMessage.collectAsState()
    // Batch 6 — export/import result messages surface in a dismissible dialog.
    val backupMessage by viewModel.backupMessage.collectAsState()

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

    // Batch 4 — playlist manager dialog state: rename (text input), delete
    // (confirm with title), duplicate (name input, defaulted to "<title> Copy").
    var playlistToRename by remember { mutableStateOf<SunoPlaylist?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<SunoPlaylist?>(null) }
    var playlistToDuplicate by remember { mutableStateOf<SunoPlaylist?>(null) }
    var duplicateInput by remember { mutableStateOf("") }

    // Batch 6 — SAF export/import wiring. Launchers write/read user-chosen
    // documents; no storage permissions are requested (SAF grants URI access).
    // The M3U launcher remembers which playlist to export via m3uPlaylistId,
    // set right before launching so the result callback can resolve it.
    var m3uPlaylistId by remember { mutableStateOf<String?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportLibraryToUri(it) } }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importLibraryFromUri(it) } }

    val exportM3uLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val playlistId = m3uPlaylistId
        if (uri != null && playlistId != null) {
            viewModel.exportM3uToUri(uri, playlistId)
        }
    }

    // Batch 3 — search/filter is local UI state; playlist persistence is untouched.
    var playlistSearchQuery by remember { mutableStateOf("") }
    var playlistFilter by remember { mutableStateOf(LibraryPlaylistFilter.ALL) }
    var trackSearchQuery by remember { mutableStateOf("") }
    // v0.1.15 — track-level filter chips (All / Favorites / Not downloaded).
    var trackFilter by remember { mutableStateOf(TrackFilter.ALL) }

    // Track search applies per-playlist: reset it whenever the open playlist
    // changes (including returning to the playlist list page).
    LaunchedEffect(selectedPlaylist?.id) {
        trackSearchQuery = ""
        trackFilter = TrackFilter.ALL
    }

    // Batch 5 — flat, deduped library track list for the local "Similar
    // tracks" heuristic (no network; derived from in-memory playlists).
    val allTracks = remember(playlists) {
        playlists.flatMap { it.tracks }.distinctBy { it.id }
    }

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

    // Batch 4 — rename custom playlist (text input, seeded with current title).
    playlistToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text("Rename Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameCustomPlaylist(target.id, renameInput)
                    playlistToRename = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { playlistToRename = null }) { Text("Cancel") } }
        )
    }

    // Playlist removal: custom mixes are deleted; synced playlists are hidden
    // locally so Resync Library does not resurrect unwanted playlists.
    playlistToDelete?.let { target ->
        val isCustom = target.isCustom
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(if (isCustom) "Delete Playlist?" else "Remove Playlist?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = if (isCustom) {
                        "Delete \"${target.title}\"? This removes the custom mix and its track order. Downloaded tracks stay in your library."
                    } else {
                        "Remove \"${target.title}\" from this app? It will stay hidden during Resync Library. Downloaded audio files are left alone."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (isCustom) viewModel.deleteCustomPlaylist(target.id)
                    else viewModel.removeSyncedPlaylistFromLibrary(target.id)
                    playlistToDelete = null
                }) { Text(if (isCustom) "Delete" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text("Cancel") } }
        )
    }

    // Batch 4 — duplicate any playlist into a new custom mix (name input).
    playlistToDuplicate?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToDuplicate = null },
            title = { Text("Duplicate Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Copies ${target.trackCount} tracks into a new custom mix you can reorder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = duplicateInput,
                        onValueChange = { duplicateInput = it },
                        label = { Text("New playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.duplicatePlaylist(target.id, duplicateInput)
                    playlistToDuplicate = null
                }) { Text("Duplicate") }
            },
            dismissButton = { TextButton(onClick = { playlistToDuplicate = null }) { Text("Cancel") } }
        )
    }

    selectedTrackDetails?.let { track ->
        // Batch 5 — track details now show mood/genre/tags and a local
        // "Similar tracks" list. Play keeps the existing in-playlist queue
        // behavior; the library-wide fallback (playTrackFromLibrary) only
        // kicks in for tracks opened outside the selected playlist, e.g. a
        // similar track from another playlist or a creator-view track, where
        // the playlist-scoped playTrack would silently no-op.
        // v0.1.15 — the detail dialog also carries the star/favorite control.
        val inSelectedPlaylist = selectedPlaylist?.tracks?.any { it.id == track.id } == true
        TrackDetailDialog(
            track = track,
            allTracks = allTracks,
            isFavorite = track.id in favoriteTrackIds,
            onToggleFavorite = { viewModel.toggleFavoriteTrack(track.id) },
            onDismiss = { selectedTrackDetails = null },
            onPlay = {
                if (inSelectedPlaylist) {
                    viewModel.playTrack(track.id)
                } else {
                    viewModel.playTrackFromLibrary(track.id)
                }
                selectedTrackDetails = null
            },
            onAddToQueue = { viewModel.addTrackToQueue(track.id) },
            onShowTrack = { selectedTrackDetails = it },
            onQueueTrack = { viewModel.addTrackToQueue(it.id) },
            onCreatorClick = { name ->
                viewModel.selectCreator(name)
                selectedTrackDetails = null
            }
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

    // Batch 6 — export/import result (success counts or a clear failure),
    // shown as a dismissible dialog so the user can read and acknowledge it.
    backupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearBackupMessage() },
            title = { Text("Backup", fontWeight = FontWeight.Bold) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearBackupMessage() }) { Text("OK") }
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
                    // v0.1.15 — download health line: stored playlists only
                    // (smart mixes are derived, not synced).
                    playlistCount = storedPlaylists.size,
                    totalTrackCount = storedPlaylists.sumOf { it.trackCount },
                    downloadedTrackCount = storedPlaylists.sumOf { it.downloadedTrackCount },
                    onShuffle = viewModel::toggleShuffle,
                    onAddPlaylist = { showAddPlaylistDialog = true }
                )
            }

            // Batch 6 — backup actions are always available (even before the
            // cookie is configured, so local custom mixes can be exported).
            BackupActionsRow(
                onExportBackup = {
                    exportBackupLauncher.launch(backupFileName())
                },
                onImportBackup = {
                    importBackupLauncher.launch(arrayOf("application/json"))
                }
            )

            // v0.1.15 — Resume where left off: shown only when a saved playback
            // snapshot exists and nothing is currently loaded in the player.
            if (lastPlaybackState != null && currentTrack == null) {
                ResumePlaybackCard(
                    trackTitle = lastPlaybackState?.trackId?.let { trackId ->
                        allTracks.firstOrNull { it.id == trackId }?.title
                    },
                    positionMs = lastPlaybackState?.positionMs ?: 0L,
                    onResume = viewModel::resumeLastPlayback
                )
            }

            val selected = selectedPlaylist
            val creator = selectedCreator
            when {
                // Batch 5 — Creator view sits above the playlist/track lists;
                // Back clears it and returns to whichever list was underneath.
                creator != null -> CreatorView(
                    creatorName = creator,
                    creatorPlaylists = playlistsByCreator(playlists, creator),
                    tracks = tracksByCreator(playlists, creator),
                    favoriteTrackIds = favoriteTrackIds,
                    onToggleFavorite = viewModel::toggleFavoriteTrack,
                    onBack = viewModel::clearCreatorSelection,
                    onPlaylistClick = { playlistId ->
                        viewModel.selectPlaylist(playlistId)
                        viewModel.clearCreatorSelection()
                    },
                    onPlayTrack = viewModel::playTrackFromLibrary,
                    onShowTrackDetails = { selectedTrackDetails = it },
                    onAddToQueue = { viewModel.addTrackToQueue(it.id) }
                )
                selected != null -> TrackListView(
                    playlist = selected,
                    trackSearchQuery = trackSearchQuery,
                    onTrackSearchQueryChange = { trackSearchQuery = it },
                    favoriteTrackIds = favoriteTrackIds,
                    trackFilter = trackFilter,
                    onTrackFilterChange = { trackFilter = it },
                    onToggleFavorite = viewModel::toggleFavoriteTrack,
                    customPlaylists = playlists.filter { it.isCustom },
                    onBack = viewModel::clearSelection,
                    onPlayTrack = viewModel::playTrack,
                    onPlayAll = { viewModel.playPlaylist(selected.id) },
                    onShowTrackDetails = { selectedTrackDetails = it },
                    onAddToQueue = { viewModel.addTrackToQueue(it.id) },
                    onAddToPlaylist = viewModel::addTrackToCustomPlaylist,
                    onMoveTrack = { trackId, direction -> viewModel.moveTrackInPlaylist(selected.id, trackId, direction) },
                    onRemoveTrack = { trackId -> viewModel.removeTrackFromCustomPlaylist(selected.id, trackId) },
                    onRenamePlaylist = {
                        playlistToRename = selected
                        renameInput = selected.title
                    },
                    onDeletePlaylist = { playlistToDelete = selected },
                    onDuplicatePlaylist = {
                        playlistToDuplicate = selected
                        duplicateInput = defaultDuplicateTitle(selected.title)
                    },
                    onOpenSource = { viewModel.openPlaylistSource(selected.id) },
                    onShareSource = { viewModel.sharePlaylistSource(selected.id) },
                    onExportM3u = {
                        m3uPlaylistId = selected.id
                        exportM3uLauncher.launch(m3uFileName(selected.title))
                    },
                    onCreatorClick = viewModel::selectCreator
                )
                playlists.isEmpty() -> EmptyLibrary(cookieConfigured = cookieConfigured)
                else -> {
                    // Playlist page: search field + All/Downloaded/Custom chips feed
                    // pure filter functions; the underlying list and its
                    // no-main-page-resync behavior are untouched.
                    val filteredPlaylists = filterPlaylists(playlists, playlistSearchQuery, playlistFilter)
                    PlaylistSearchBar(
                        query = playlistSearchQuery,
                        onQueryChange = { playlistSearchQuery = it },
                        filter = playlistFilter,
                        onFilterChange = { playlistFilter = it }
                    )
                    if (filteredPlaylists.isEmpty()) {
                        EmptyFilteredPlaylists()
                    } else {
                        PlaylistListView(
                            playlists = filteredPlaylists,
                            onPlaylistClick = viewModel::selectPlaylist,
                            onPlayPlaylist = viewModel::playPlaylist,
                            onRenamePlaylist = { playlist ->
                                playlistToRename = playlist
                                renameInput = playlist.title
                            },
                            onDeletePlaylist = { playlistToDelete = it },
                            onDuplicatePlaylist = { playlist ->
                                playlistToDuplicate = playlist
                                duplicateInput = defaultDuplicateTitle(playlist.title)
                            },
                            onOpenSource = viewModel::openPlaylistSource,
                            onShareSource = viewModel::sharePlaylistSource,
                            onCreatorClick = viewModel::selectCreator
                        )
                    }
                }
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
    // v0.1.15 — download health line counts (stored library only).
    playlistCount: Int,
    totalTrackCount: Int,
    downloadedTrackCount: Int,
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
            LastSyncCard(
                summary = summary,
                playlistCount = playlistCount,
                totalTrackCount = totalTrackCount,
                downloadedTrackCount = downloadedTrackCount
            )
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

/**
 * Batch 6 — Export Backup / Import Backup actions. Both use SAF document
 * intents (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT), so no storage
 * permissions are needed; the user picks the target file per action.
 */
@Composable
private fun BackupActionsRow(
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Export Backup")
        }
        OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Import Backup")
        }
    }
}

/** Compact last-sync status on the library page (full details live in Settings). */
@Composable
private fun LastSyncCard(
    summary: SyncSummary,
    playlistCount: Int,
    totalTrackCount: Int,
    downloadedTrackCount: Int
) {
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
            // v0.1.15 — download health line: "N playlists • N tracks • N downloaded • N failed".
            Text(
                text = buildHealthLine(
                    playlistCount = playlistCount,
                    totalTrackCount = totalTrackCount,
                    downloadedTrackCount = downloadedTrackCount,
                    failedCount = summary.failedCount
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
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

/**
 * v0.1.15 — compact download-health line for the Library page and the Settings
 * download-health card. Failed count comes from the last sync summary (per-track
 * failure lists are not tracked, so the line stays honest).
 */
private fun buildHealthLine(
    playlistCount: Int,
    totalTrackCount: Int,
    downloadedTrackCount: Int,
    failedCount: Int
): String = buildString {
    append("$playlistCount playlists")
    append(" • $totalTrackCount tracks")
    append(" • $downloadedTrackCount downloaded")
    if (failedCount > 0) append(" • $failedCount failed")
}

/**
 * v0.1.15 — Resume where left off card. Shown on the Library page after a
 * restart when a saved playback snapshot exists and nothing is loaded yet.
 * Tapping Resume rebuilds the queue from the current library and seeks near
 * the saved position (see LibraryViewModel.resumeLastPlayback). Playback never
 * starts automatically — the user always taps Resume.
 */
@Composable
private fun ResumePlaybackCard(
    trackTitle: String?,
    positionMs: Long,
    onResume: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Resume where you left off",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = trackTitle?.let { "\"$it\" at ${formatPosition(positionMs)}" }
                        ?: "Continue the last queue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onResume) { Text("Resume") }
        }
    }
}

/** mm:ss label for a millisecond position, used by the Resume card. */
private fun formatPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
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

/** Search field + All / Downloaded only / Custom mixes filter chips (Batch 3). */
@Composable
private fun PlaylistSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: LibraryPlaylistFilter,
    onFilterChange: (LibraryPlaylistFilter) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search playlists") },
            placeholder = { Text("Title or creator") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear playlist search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == LibraryPlaylistFilter.ALL,
                onClick = { onFilterChange(LibraryPlaylistFilter.ALL) },
                label = { Text("All") }
            )
            FilterChip(
                selected = filter == LibraryPlaylistFilter.DOWNLOADED_ONLY,
                onClick = { onFilterChange(LibraryPlaylistFilter.DOWNLOADED_ONLY) },
                label = { Text("Downloaded only") }
            )
            FilterChip(
                selected = filter == LibraryPlaylistFilter.CUSTOM_MIXES,
                onClick = { onFilterChange(LibraryPlaylistFilter.CUSTOM_MIXES) },
                label = { Text("Custom mixes") }
            )
        }
    }
}

/** Shown when the playlist search/filter hides every playlist. */
@Composable
private fun EmptyFilteredPlaylists() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("No playlists match", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Try a different search or filter",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistListView(
    playlists: List<SunoPlaylist>,
    onPlaylistClick: (String) -> Unit,
    onPlayPlaylist: (String) -> Unit,
    onRenamePlaylist: (SunoPlaylist) -> Unit,
    onDeletePlaylist: (SunoPlaylist) -> Unit,
    onDuplicatePlaylist: (SunoPlaylist) -> Unit,
    onOpenSource: (String) -> Unit,
    onShareSource: (String) -> Unit,
    onCreatorClick: (String) -> Unit
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
                onPlay = { onPlayPlaylist(playlist.id) },
                onRename = { onRenamePlaylist(playlist) },
                onDuplicate = { onDuplicatePlaylist(playlist) },
                onDelete = { onDeletePlaylist(playlist) },
                onOpenSource = { onOpenSource(playlist.id) },
                onShareSource = { onShareSource(playlist.id) },
                onCreatorClick = onCreatorClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: SunoPlaylist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onOpenSource: () -> Unit,
    onShareSource: () -> Unit,
    onCreatorClick: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = {
                        menuExpanded = false
                        onPlay()
                    }
                )
                if (playlist.isCustom) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    onClick = {
                        menuExpanded = false
                        onDuplicate()
                    }
                )
                if (playlist.sourceUrl != null) {
                    DropdownMenuItem(
                        text = { Text("Open in Suno") },
                        onClick = {
                            menuExpanded = false
                            onOpenSource()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuExpanded = false
                            onShareSource()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (playlist.isCustom) "Delete" else "Remove from Library") },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArt(playlist.tracks.firstOrNull()?.imageUrl, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // v0.1.15 — smart mixes get a "Smart mix" suffix so users can
                // tell derived lists (favorites/recent/streaming) from stored
                // playlists at a glance.
                Text(
                    text = when {
                        playlist.isCustom -> "${playlist.title}  •  Custom"
                        isSmartMixId(playlist.id) -> "${playlist.title}  •  Smart mix"
                        else -> playlist.title
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Batch 5 — creator names are tappable and open the local
                // Creator view (no network; grouping is in-memory only).
                playlist.creatorName?.let { creator ->
                    Text(
                        text = "by $creator",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onCreatorClick(creator) }
                    )
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
        // Batch 4 — manager actions: rename/delete only for custom mixes;
        // duplicate works for any playlist; Open in Suno / Share for URL-saved ones.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (playlist.isCustom) {
                PlaylistManagerButton("Rename", onClick = onRename)
                PlaylistManagerButton("Duplicate", onClick = onDuplicate)
                PlaylistManagerButton(
                    "Delete",
                    onClick = onDelete,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                PlaylistManagerButton("Duplicate", onClick = onDuplicate)
                PlaylistManagerButton(
                    "Remove",
                    onClick = onDelete,
                    color = MaterialTheme.colorScheme.error
                )
                if (playlist.sourceUrl != null) {
                    PlaylistManagerButton("Open in Suno", onClick = onOpenSource)
                    PlaylistManagerButton("Share", onClick = onShareSource)
                }
            }
        }
    }
}

/** Compact text-button used by the playlist manager rows. */
@Composable
private fun PlaylistManagerButton(
    label: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun TrackListView(
    playlist: SunoPlaylist,
    trackSearchQuery: String,
    onTrackSearchQueryChange: (String) -> Unit,
    // v0.1.15 — favorites + track filter chips.
    favoriteTrackIds: Set<String>,
    trackFilter: TrackFilter,
    onTrackFilterChange: (TrackFilter) -> Unit,
    onToggleFavorite: (String) -> Unit,
    customPlaylists: List<SunoPlaylist>,
    onBack: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onPlayAll: () -> Unit,
    onShowTrackDetails: (SunoTrack) -> Unit,
    onAddToQueue: (SunoTrack) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onMoveTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRenamePlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onDuplicatePlaylist: () -> Unit,
    onOpenSource: () -> Unit,
    onShareSource: () -> Unit,
    onExportM3u: () -> Unit,
    onCreatorClick: (String) -> Unit
) {
    // Batch 3 — pure search over the playlist's own tracks. Persistence and the
    // original track ids used by move/remove actions are untouched.
    // v0.1.15 — the filter chips (All / Favorites / Not downloaded) combine
    // with the query in the pure filterTracks helper.
    val filteredTracks = filterTracks(
        tracks = playlist.tracks,
        query = trackSearchQuery,
        favoriteTrackIds = favoriteTrackIds,
        filter = trackFilter
    )
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
                text = "Tap a song for lyrics • Play button starts audio • +Q adds to queue" +
                    if (playlist.isCustom) " • ↑/↓ reorder this mix" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            // Batch 4 — source URL for URL-saved playlists, with external
            // open/share actions (ACTION_VIEW / ACTION_SEND; no silent mutations).
            playlist.sourceUrl?.let { sourceUrl ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Source URL: $sourceUrl",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenSource, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Open in Suno", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onShareSource, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Share", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // Batch 4 — manager actions; duplicate works from the details page too.
            // Batch 6 — Export M3U writes a plain-text playlist via SAF.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PlaylistManagerButton("Export M3U", onClick = onExportM3u)
                if (playlist.isCustom) {
                    PlaylistManagerButton("Rename", onClick = onRenamePlaylist)
                    PlaylistManagerButton("Duplicate", onClick = onDuplicatePlaylist)
                    PlaylistManagerButton(
                        "Delete",
                        onClick = onDeletePlaylist,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    PlaylistManagerButton("Duplicate", onClick = onDuplicatePlaylist)
                }
            }
        }
        if (playlist.tracks.isNotEmpty()) {
            OutlinedTextField(
                value = trackSearchQuery,
                onValueChange = onTrackSearchQueryChange,
                label = { Text("Search tracks") },
                placeholder = { Text("Title, creator, lyrics, style, prompt") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (trackSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onTrackSearchQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear track search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            )
            // v0.1.15 — track filter chips: All / Favorites / Not downloaded.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = trackFilter == TrackFilter.ALL,
                    onClick = { onTrackFilterChange(TrackFilter.ALL) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = trackFilter == TrackFilter.FAVORITES,
                    onClick = { onTrackFilterChange(TrackFilter.FAVORITES) },
                    label = { Text("Favorites") }
                )
                FilterChip(
                    selected = trackFilter == TrackFilter.NOT_DOWNLOADED,
                    onClick = { onTrackFilterChange(TrackFilter.NOT_DOWNLOADED) },
                    label = { Text("Not downloaded") }
                )
            }
        }
        if (filteredTracks.isEmpty()) {
            // Search hid everything (an empty playlist shows nothing extra).
            if (trackSearchQuery.isNotBlank() || trackFilter != TrackFilter.ALL) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tracks match", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredTracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isCustomPlaylist = playlist.isCustom,
                        customPlaylists = customPlaylists,
                        isFavorite = track.id in favoriteTrackIds,
                        onToggleFavorite = { onToggleFavorite(track.id) },
                        onClick = { onShowTrackDetails(track) },
                        onPlay = { onPlayTrack(track.id) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddToPlaylist = { targetId -> onAddToPlaylist(track.id, targetId) },
                        onMoveUp = { onMoveTrack(track.id, -1) },
                        onMoveDown = { onMoveTrack(track.id, 1) },
                        onRemove = { onRemoveTrack(track.id) },
                        onCreatorClick = onCreatorClick
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: SunoTrack,
    isCustomPlaylist: Boolean,
    customPlaylists: List<SunoPlaylist>,
    // v0.1.15 — star/favorite control on every track row.
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onCreatorClick: (String) -> Unit
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
                // Batch 5 — creator name is tappable and opens the local
                // Creator view listing every library playlist/track by them.
                val creator = track.creatorName
                Text(
                    text = "Creator: ${creator ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (creator != null) {
                        Modifier.clickable { onCreatorClick(creator) }
                    } else {
                        Modifier
                    }
                )
                if (track.metadataSummary.isNotBlank()) {
                    Text(track.metadataSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // Batch 5 — genre/mood/tags line when Suno provided them.
                trackMetadataLine(track)?.let { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                track.descriptionPrompt?.takeIf { it.isNotBlank() }?.let {
                    Text("Prompt: ${it.take(120)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                track.lyrics?.takeIf { it.isNotBlank() }?.let {
                    Text("Lyrics: ${it.lineSequence().firstOrNull().orEmpty().take(120)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // v0.1.15 — star control; works for any synced/custom track and
                // persists via FavoritesStore (never mutates Suno API data).
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFavorite) "Unfavorite ${track.title}" else "Favorite ${track.title}",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
    allTracks: List<SunoTrack>,
    // v0.1.15 — star/favorite control in the detail dialog.
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onShowTrack: (SunoTrack) -> Unit,
    onQueueTrack: (SunoTrack) -> Unit,
    onCreatorClick: (String) -> Unit
) {
    // Batch 5 — local-only similar-track heuristic over the in-memory library.
    val similar = remember(track, allTracks) { similarTracks(track, allTracks) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) "Unfavorite ${track.title}" else "Favorite ${track.title}",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Tappable creator name opens the local Creator view.
                val creator = track.creatorName
                Text(
                    text = "Creator: ${creator ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (creator != null) {
                        Modifier.clickable { onCreatorClick(creator) }
                    } else {
                        Modifier
                    }
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
                // Batch 5 — discovery metadata when Suno provided it.
                track.genre?.takeIf { it.isNotBlank() }?.let {
                    DetailSection("Genre", it)
                }
                track.mood?.takeIf { it.isNotBlank() }?.let {
                    DetailSection("Mood", it)
                }
                track.tags.asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let { DetailSection("Tags", it.joinToString(", ")) }
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
                if (similar.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Similar tracks", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    similar.forEach { similarTrack ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onShowTrack(similarTrack) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlaylistArt(similarTrack.imageUrl, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    similarTrack.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Creator: ${similarTrack.creatorName ?: "Unknown"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = { onQueueTrack(similarTrack) },
                                enabled = similarTrack.isPlayable,
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("+ Queue", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
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

/**
 * Batch 5 — local Creator view: every library playlist and track by one
 * creator. Pure in-memory grouping (MetadataDiscoveryHelpers) — no network
 * calls and no persistence writes. Back returns to the previous list.
 */
@Composable
private fun CreatorView(
    creatorName: String,
    creatorPlaylists: List<SunoPlaylist>,
    tracks: List<SunoTrack>,
    // v0.1.15 — star control on creator-view track rows too.
    favoriteTrackIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onBack: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onPlayTrack: (String) -> Unit,
    onShowTrackDetails: (SunoTrack) -> Unit,
    onAddToQueue: (SunoTrack) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Back") }
            Spacer(modifier = Modifier.weight(1f))
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Creator: $creatorName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = "${creatorPlaylists.size} playlists • ${tracks.size} tracks in your library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (creatorPlaylists.isNotEmpty()) {
                item(key = "header-playlists") {
                    Text(
                        "Playlists by $creatorName",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(creatorPlaylists, key = { "pl-${it.id}" }) { playlist ->
                    CreatorPlaylistRow(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.id) }
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                item(key = "header-tracks") {
                    Text(
                        "Tracks by $creatorName",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(tracks, key = { "tr-${it.id}" }) { track ->
                    CreatorTrackRow(
                        track = track,
                        isFavorite = track.id in favoriteTrackIds,
                        onToggleFavorite = { onToggleFavorite(track.id) },
                        onClick = { onShowTrackDetails(track) },
                        onPlay = { onPlayTrack(track.id) },
                        onAddToQueue = { onAddToQueue(track) }
                    )
                }
            }
            if (creatorPlaylists.isEmpty() && tracks.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No library items by this creator yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorPlaylistRow(
    playlist: SunoPlaylist,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArt(playlist.tracks.firstOrNull()?.imageUrl, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.trackCount} tracks • ${playlist.downloadedTrackCount} downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CreatorTrackRow(
    track: SunoTrack,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArt(track.imageUrl, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                trackMetadataLine(track)?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavorite) "Unfavorite ${track.title}" else "Favorite ${track.title}",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPlay, enabled = track.isPlayable) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${track.title}")
            }
            IconButton(onClick = onAddToQueue, enabled = track.isPlayable) {
                Icon(Icons.Filled.QueueMusic, contentDescription = "Add ${track.title} to queue")
            }
        }
    }
}

/** Which kind of playlist the Add Playlist wizard is currently collecting input for. */
private enum class AddPlaylistMode { LOCAL, URL }

// Batch 6 — suggested SAF document names.

/** Timestamped default name for library backups, e.g. suno-library-backup-20260731-183000.json. */
private fun backupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "suno-library-backup-$stamp.json"
}

/** File name for an M3U export, slugified from the playlist title. */
private fun m3uFileName(title: String): String {
    val slug = title.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
    return "${slug.ifBlank { "playlist" }}.m3u"
}
