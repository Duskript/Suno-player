package com.metrolist.music.utils

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps Suno track song IDs to their local audio file paths.
 *
 * When a Suno track is imported, the MP3 is downloaded to [sunoAudioDir].
 * This cache maps the song ID (e.g. "suno_8af15b0e...") to the absolute
 * file path so the playback engine can resolve it without going through
 * YouTube's streaming protocol.
 */
@Singleton
class SunoAudioCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioDir = File(context.filesDir, "suno_audio").also { it.mkdirs() }

    // In-memory cache: songId -> absolute file path
    private val filePathCache = ConcurrentHashMap<String, String>()

    init {
        // Scan the directory on startup to rebuild the cache
        rebuildCache()
    }

    /**
     * Register a Suno song ID with its local audio file path.
     */
    fun register(songId: String, audioFilePath: String) {
        filePathCache[songId] = audioFilePath
    }

    /**
     * Get the local file path for a Suno song, or null if not cached.
     */
    fun getLocalPath(songId: String): String? {
        return filePathCache[songId]
    }

    /**
     * Check if a song ID is a Suno local track.
     */
    fun isSunoTrack(songId: String): Boolean {
        return songId.startsWith("suno_")
    }

    /**
     * Remove a song's cached path.
     */
    fun remove(songId: String) {
        filePathCache.remove(songId)
    }

    /**
     * Get the Suno audio directory.
     */
    fun getAudioDir(): File = audioDir

    /**
     * Resolve the playback URI for a Suno track.
     * Returns a "file://" URI if the audio file exists, null otherwise.
     */
    fun resolvePlaybackUri(songId: String): String? {
        val path = filePathCache[songId] ?: return null
        val file = File(path)
        if (file.exists()) {
            return file.toURI().toString()
        }
        Timber.w("SunoAudioCache: File not found for $songId at $path")
        return null
    }

    private fun rebuildCache() {
        val files = audioDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isFile && file.extension == "mp3") {
                    // Derive song ID from file name
                    // File names are UUIDs from Suno CDN like "8af15b0e-4e27-4c46-838c-256c75ad4909.mp3"
                    val clipId = file.nameWithoutExtension
                    val songId = "suno_$clipId"
                    filePathCache[songId] = file.absolutePath
                }
            }
            Timber.d("SunoAudioCache: Rebuilt cache with ${filePathCache.size} entries")
        }
    }
}
