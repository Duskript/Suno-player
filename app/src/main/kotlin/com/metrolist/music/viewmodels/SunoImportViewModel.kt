package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.SunoAudioCache
import com.metrolist.suno.Suno
import com.metrolist.suno.SunoException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject

data class SunoImportState(
    val isImporting: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val importedSongTitle: String? = null,
)

@HiltViewModel
class SunoImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val sunoAudioCache: SunoAudioCache,
) : ViewModel() {

    private val suno = Suno()

    private val _state = MutableStateFlow(SunoImportState())
    val state: StateFlow<SunoImportState> = _state.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    fun updateUrl(url: String) {
        _urlInput.value = url.trim()
        if (_state.value.success || _state.value.error != null) {
            _state.value = SunoImportState()
        }
    }

    fun importFromUrl() {
        val url = _urlInput.value
        if (url.isBlank()) {
            _state.value = SunoImportState(error = "Please enter a Suno URL")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _state.value = SunoImportState(isImporting = true)

            try {
                val isPlaylist = url.contains("/playlist/")

                if (isPlaylist) {
                    importPlaylist(url)
                } else {
                    importSingleTrack(url)
                }
            } catch (e: SunoException) {
                Timber.e(e, "Suno import failed")
                _state.value = SunoImportState(error = e.message ?: "Import failed")
            } catch (e: Exception) {
                Timber.e(e, "Suno import unexpected error")
                _state.value = SunoImportState(error = "Unexpected error: ${e.message}")
            }
        }
    }

    fun reset() {
        _state.value = SunoImportState()
        _urlInput.value = ""
    }

    private suspend fun importSingleTrack(url: String) {
        val track = suno.fetchTrack(url)
        Timber.d("Suno: Fetched track '${track.title}' by ${track.displayName}")

        val now = LocalDateTime.now()
        val songId = "suno_${track.id}"
        val audioDir = sunoAudioCache.getAudioDir()
        val audioFile = suno.downloadAudio(track.audioUrl, audioDir)

        Timber.d("Suno: Downloaded audio to ${audioFile.absolutePath} (${audioFile.length()} bytes)")

        // Register the local file path for playback resolution
        sunoAudioCache.register(songId, audioFile.absolutePath)

        // Use the existing MediaMetadata -> Room pipeline
        val mediaMetadata = MediaMetadata(
            id = songId,
            title = track.title,
            artists = listOf(
                MediaMetadata.Artist(
                    id = null,
                    name = track.displayName,
                )
            ),
            duration = track.duration.toInt(),
            thumbnailUrl = track.imageUrl,
            inLibrary = now,
        )

        database.query {
            // The insert helper handles SongEntity + ArtistEntity + SongArtistMap
            insert(mediaMetadata) { songEntity ->
                songEntity.copy(
                    isLocal = true,
                    isCached = true,
                    dateDownload = now,
                )
            }
        }

        _state.value = SunoImportState(
            success = true,
            importedSongTitle = track.title,
        )
    }

    private suspend fun importPlaylist(url: String) {
        val tracks = suno.fetchPlaylist(url)
        Timber.d("Suno: Fetched playlist with ${tracks.size} tracks")

        val now = LocalDateTime.now()
        val audioDir = sunoAudioCache.getAudioDir()
        var imported = 0
        var failed = 0

        for (track in tracks) {
            try {
                val songId = "suno_${track.id}"
                val audioFile = suno.downloadAudio(track.audioUrl, audioDir)

                sunoAudioCache.register(songId, audioFile.absolutePath)

                val mediaMetadata = MediaMetadata(
                    id = songId,
                    title = track.title,
                    artists = listOf(
                        MediaMetadata.Artist(
                            id = null,
                            name = track.displayName,
                        )
                    ),
                    duration = track.duration.toInt(),
                    thumbnailUrl = track.imageUrl,
                    inLibrary = now,
                )

                database.query {
                    insert(mediaMetadata) { songEntity ->
                        songEntity.copy(
                            isLocal = true,
                            isCached = true,
                            dateDownload = now,
                        )
                    }
                }

                imported++
            } catch (e: Exception) {
                Timber.w(e, "Failed to import Suno track: ${track.title}")
                failed++
            }
        }

        _state.value = SunoImportState(
            success = true,
            importedSongTitle = "Imported $imported tracks" +
                if (failed > 0) " ($failed failed)" else "",
        )
    }

    override fun onCleared() {
        super.onCleared()
        suno.close()
    }
}
