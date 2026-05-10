package com.metrolist.suno

import com.metrolist.suno.models.SunoTrack
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File
import java.util.regex.Pattern

/**
 * Suno API — scrapes public Suno song pages to extract audio URLs and metadata.
 *
 * Suno embeds all data in React Server Component payloads via `self.__next_f.push()`
 * in the rendered HTML. No API key, no auth, no cookies required.
 *
 * Usage:
 *   val suno = Suno()
 *   val track = suno.fetchTrack("https://suno.com/s/abc123")
 *   val file = suno.downloadAudio(track.audioUrl, targetDir)
 */
class Suno(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) {
    companion object {
        private const val SUNO_SONG_URL = "https://suno.com/s/{id}"
        private const val SUNO_PLAYLIST_URL = "https://suno.com/playlist/{id}"
        private const val SUNO_CDN = "https://cdn1.suno.ai"

        // Matches self.__next_f.push() chunks containing JSON with audio_url
        private val NEXT_F_PUSH_REGEX =
            Pattern.compile(
                """self\.__next_f\.push\(\[1,"(\\[\\]{2}\"[^"]+)"\]\)""",
            )

        // Extracts a specific escaped field from the RSC payload: \"fieldName\":\"value\"
        private fun escapedFieldPattern(field: String): Pattern =
            Pattern.compile(
                """\\\\x5c"${field}\\\\x5c":\\\\x5c"([^\\\\x5c"]+)\\\\x5c" """.trimMargin(),
            )
    }

    /**
     * Fetch a single track from a Suno song page URL.
     *
     * @param url Full URL like https://suno.com/s/Gsag1epmtoQuVqpA
     * @return [SunoTrack] with extracted metadata and CDN audio URL
     * @throws SunoException if the page can't be fetched or parsed
     */
    suspend fun fetchTrack(url: String): SunoTrack {
        val pageHtml = fetchPage(url)
        return parseTrackHtml(pageHtml)
    }

    /**
     * Fetch all tracks from a Suno playlist page URL.
     *
     * @param url Full URL like https://suno.com/playlist/abc123
     * @return List of [SunoTrack] for all songs in the playlist
     * @throws SunoException if the page can't be fetched or parsed
     */
    suspend fun fetchPlaylist(url: String): List<SunoTrack> {
        val pageHtml = fetchPage(url)
        return parsePlaylistHtml(pageHtml)
    }

    /**
     * Download a Suno audio track to local storage.
     *
     * @param audioUrl The CDN audio URL from [SunoTrack.audioUrl]
     * @param outputDir Directory to save the MP3 file
     * @return The downloaded [File]
     */
    suspend fun downloadAudio(
        audioUrl: String,
        outputDir: File,
    ): File = withContext(Dispatchers.IO) {
        val fileName = audioUrl.substringAfterLast("/").ifEmpty { "suno_track.mp3" }
        val outputFile = File(outputDir, fileName)

        if (outputFile.exists() && outputFile.length() > 0) {
            Timber.d("Suno: Audio already cached: ${outputFile.absolutePath}")
            return@withContext outputFile
        }

        Timber.d("Suno: Downloading audio from $audioUrl")
        val response = client.get(audioUrl)

        outputFile.outputStream().use { output ->
            response.bodyAsChannel().copyTo(output)
        }

        Timber.d("Suno: Downloaded ${outputFile.length()} bytes to ${outputFile.absolutePath}")
        outputFile
    }

    // ---- Internal: Page Fetching ----

    private suspend fun fetchPage(url: String): String {
        val response = client.get(url) {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                append(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
        }

        if (response.status != HttpStatusCode.OK) {
            throw SunoException("HTTP ${response.status.value} fetching $url")
        }

        return response.bodyAsText()
    }

    // ---- Internal: Track Parsing (Single Song Page) ----

    private fun parseTrackHtml(html: String): SunoTrack {
        val payload = extractPayloadChunk(html)
            ?: throw SunoException("Could not find __next_f data in page HTML")

        val clipJson = extractClipFromPayload(payload)
            ?: throw SunoException("Could not extract clip data from page payload")

        return parseClipJson(clipJson)
    }

    // ---- Internal: Playlist Parsing ----

    private fun parsePlaylistHtml(html: String): List<SunoTrack> {
        val payload = extractPayloadChunk(html)
            ?: throw SunoException("Could not find __next_f data in page HTML")

        // Playlist pages may embed a "playlist_clips" array
        val clipsArray = extractPlaylistClips(payload)
            ?: // Fallback: try extracting a single song
            return listOfNotNull(extractClipFromPayload(payload)?.let { parseClipJson(it) })

        return clipsArray.map { parseClipJson(it.toString()) }
    }

    // ---- Internal: Extraction Helpers ----

    /**
     * Extracts the first RSC payload chunk from the HTML that contains "audio_url".
     */
    private fun extractPayloadChunk(html: String): String? {
        val matcher = NEXT_F_PUSH_REGEX.matcher(html)
        while (matcher.find()) {
            val chunk = matcher.group(1)
            if (chunk.contains("audio_url", ignoreCase = true)) {
                return chunk
            }
        }
        return null
    }

    /**
     * Extracts the clip JSON object from a payload string using regex.
     * Suno stores clip data with escaped field names like \"audio_url\":\"...\"
     */
    private fun extractClipFromPayload(payload: String): JsonObject? {
        val id = extractEscapedField(payload, "id") ?: return null
        val title = extractEscapedField(payload, "title")
        val audioUrl = extractEscapedField(payload, "audio_url")
        val imageUrl = extractEscapedField(payload, "image_url")
        val displayName = extractEscapedField(payload, "display_name")
        val handle = extractEscapedField(payload, "handle")
        val tags = extractEscapedField(payload, "tags")
        val modelVersion = extractEscapedField(payload, "major_model_version")
        val durationStr = extractEscapedField(payload, "duration")

        // Try numeric duration from metadata
        val metadata = extractMetadataObject(payload)
        val duration = metadata?.get("duration")?.jsonPrimitive?.floatOrNull
            ?: durationStr?.toFloatOrNull()
            ?: 0f

        val playCount = extractEscapedField(payload, "play_count")?.toLongOrNull() ?: 0
        val upvoteCount = extractEscapedField(payload, "upvote_count")?.toLongOrNull() ?: 0
        val isLiked = extractEscapedField(payload, "is_liked")?.toBooleanStrictOrNull() ?: false
        val createdAt = extractEscapedField(payload, "created_at")

        return buildJsonObject {
            put("id", id)
            title?.let { put("title", it) }
            audioUrl?.let { put("audio_url", it) }
            imageUrl?.let { put("image_url", it) }
            displayName?.let { put("display_name", it) }
            handle?.let { put("handle", it) }
            tags?.let { put("tags", it) }
            modelVersion?.let { put("major_model_version", it) }
            put("duration", duration.toDouble())
            put("play_count", playCount)
            put("upvote_count", upvoteCount)
            put("is_liked", isLiked)
            createdAt?.let { put("created_at", it) }
        }
    }

    /**
     * Extracts playlist_clips array from a playlist page payload.
     */
    private fun extractPlaylistClips(payload: String): JsonArray? {
        // Look for "playlist_clips":[ pattern in the payload
        val pattern = Pattern.compile(
            """\\\\x5c"playlist_clips\\\\x5c":\[(.+?)\]""".trimMargin(),
        )
        val matcher = pattern.matcher(payload)
        if (!matcher.find()) return null

        val jsonStr = unescapePayload("[" + matcher.group(1) + "]")
        return try {
            Json.parseToJsonElement(jsonStr).jsonArray
        } catch (e: Exception) {
            Timber.w("Suno: Failed to parse playlist_clips: ${e.message}")
            null
        }
    }

    /**
     * Extracts the metadata object which contains the real duration and prompt/tags.
     */
    private fun extractMetadataObject(payload: String): JsonObject? {
        val raw = extractEscapedField(payload, "metadata") ?: return null
        val unescaped = unescapePayload(raw)
        return try {
            Json.parseToJsonElement(unescaped).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts a specific escaped field value from the RSC payload string.
     * Handles Suno's double-escaped JSON format.
     */
    private fun extractEscapedField(
        payload: String,
        field: String,
    ): String? {
        val pattern = escapedFieldPattern(field)
        val matcher = pattern.matcher(payload)
        return if (matcher.find()) {
            unescapeValue(matcher.group(1))
        } else {
            null
        }
    }

    /**
     * Unescapes a payload string that contains double-escaped characters
     * from Suno's RSC format. Handles \\x5c, \\\", \\n, etc.
     */
    private fun unescapePayload(value: String): String {
        return value
            .replace("\\\\x5c", "\\")
            .replace("\\\\n", "\n")
            .replace("\\\\t", "\t")
            .replace("\\\\\"", "\"")
            .replace("\\\\\\\\", "\\\\")
    }

    /**
     * Unescapes a single field value from the RSC format.
     */
    private fun unescapeValue(value: String): String {
        return value
            .replace("\\x5c", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    /**
     * Parses a JsonObject into a [SunoTrack].
     */
    private fun parseClipJson(json: JsonObject): SunoTrack {
        return SunoTrack(
            id = json["id"]?.jsonPrimitive?.contentOrNull ?: "",
            title = json["title"]?.jsonPrimitive?.contentOrNull ?: "Unknown Track",
            audioUrl = json["audio_url"]?.jsonPrimitive?.contentOrNull ?: "",
            imageUrl = json["image_url"]?.jsonPrimitive?.contentOrNull ?: "",
            displayName = json["display_name"]?.jsonPrimitive?.contentOrNull ?: "",
            handle = json["handle"]?.jsonPrimitive?.contentOrNull ?: "",
            tags = json["tags"]?.jsonPrimitive?.contentOrNull ?: "",
            majorModelVersion = json["major_model_version"]?.jsonPrimitive?.contentOrNull ?: "",
            duration = json["duration"]?.jsonPrimitive?.floatOrNull ?: 0f,
            playCount = json["play_count"]?.jsonPrimitive?.longOrNull ?: 0,
            upvoteCount = json["upvote_count"]?.jsonPrimitive?.longOrNull ?: 0,
            isLiked = json["is_liked"]?.jsonPrimitive?.booleanOrNull ?: false,
            createdAt = json["created_at"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * Releases resources held by the HTTP client.
     */
    fun close() {
        client.close()
    }
}

/**
 * Exception thrown when Suno page scraping fails.
 */
class SunoException(message: String, cause: Throwable? = null) : Exception(message, cause)
