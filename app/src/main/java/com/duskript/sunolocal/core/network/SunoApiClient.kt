package com.duskript.sunolocal.core.network

import com.duskript.sunolocal.core.auth.CookieStore
import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * SunoApiClient — client for Suno's unofficial REST API.
 *
 * Suno moved the browser API behind studio-api-prod.suno.com. The old
 * suno.com api-v1 endpoint family now returns Next.js HTML 404 pages, which is
 * what the first APK surfaced after cookie entry. Authenticated browser calls
 * use a Clerk/Suno JWT as Authorization: Bearer plus normal Suno cookies.
 */
class SunoApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val cookieStore: CookieStore
) {
    /** Base URL used by the current Suno web app. */
    private val apiBase: String get() = "https://studio-api-prod.suno.com/api"

    /** Current browser endpoint for a page of the authenticated user's playlists. */
    private fun myPlaylistsUrl(page: Int, includeSavedSharelists: Boolean = false): String =
        "$apiBase/playlist/me?page=$page&show_trashed=false&show_sharelist=$includeSavedSharelists"

    /** Current browser endpoint for one playlist detail page including playlist_clips[].clip. */
    private fun playlistV2Url(id: String, page: Int = 1): String = "$apiBase/playlist/v2/$id?page=$page"

    /** Legacy detail endpoint kept as a fallback because Suno rotates private paths. */
    private fun playlistLegacyUrl(id: String, page: Int = 1): String = "$apiBase/playlist/$id/?page=$page"

    /** Endpoint to verify current session/config. */
    private val sessionUrl: String get() = "$apiBase/session/"

    /** CDN base for downloading generated audio files. */
    private val cdnBase: String get() = "https://cdn1.suno.ai"

    private fun Request.Builder.withSunoHeaders(): Request.Builder {
        val cookie = cookieStore.getCookie()
        if (!cookie.isNullOrBlank()) {
            addHeader("Cookie", cookie)
            extractSessionJwt(cookie)?.let { addHeader("Authorization", "Bearer $it") }
        }
        addHeader("User-Agent", "Mozilla/5.0 SunoLocalPlayer/0.1.0")
        addHeader("Accept", "application/json,text/plain,*/*")
        addHeader("Origin", "https://suno.com")
        addHeader("Referer", "https://suno.com/")
        return this
    }

    suspend fun fetchMyPlaylists(): List<SunoPlaylist> = withContext(Dispatchers.IO) {
        val ownPlaylists = fetchPlaylistSummaries(includeSavedSharelists = false)
        val savedSharelists = fetchPlaylistSummaries(includeSavedSharelists = true)
            .map { it.copy(savedFromOtherCreator = true) }

        // The `show_sharelist=true` endpoint can overlap with normal playlists on
        // some Suno accounts. Preserve the normal playlist copy first, then add
        // only extra playlists saved from other profiles.
        val mergedById = linkedMapOf<String, SunoPlaylist>()
        (ownPlaylists + savedSharelists).forEach { playlist ->
            mergedById.putIfAbsent(playlist.id, playlist)
        }

        mergedById.values.map { playlist ->
            fetchPlaylistDetailOrFallback(playlist)
        }
    }

    private fun fetchPlaylistSummaries(includeSavedSharelists: Boolean): List<SunoPlaylist> {
        val allPlaylists = mutableListOf<SunoPlaylist>()
        var page = 1
        var totalResults: Int? = null

        do {
            val request = Request.Builder()
                .url(myPlaylistsUrl(page, includeSavedSharelists))
                .withSunoHeaders()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                val label = if (includeSavedSharelists) "saved/shared playlists" else "playlists"
                throw apiFailure("Failed to fetch $label", response.code, response.message, body)
            }

            val pageResult = parsePlaylistsPage(body)
            totalResults = pageResult.totalResults ?: totalResults
            allPlaylists += pageResult.playlists
            page++
        } while (totalResults != null && allPlaylists.size < totalResults && page <= MAX_PLAYLIST_PAGES)

        return allPlaylists
    }

    suspend fun fetchPlaylistFromUrl(url: String): SunoPlaylist = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(url)
            ?: throw SunoApiException("Could not extract playlist ID from URL: $url", 400)

        fetchPlaylistDetail(playlistId, sourceUrl = url)
    }

    /** Probe the authenticated user's playlist endpoint. HTTP 200 means the stored cookie is usable. */
    suspend fun testConnection() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(myPlaylistsUrl(page = 1))
            .withSunoHeaders()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful) {
            throw apiFailure("Suno connection test failed", response.code, response.message, body)
        }
    }

    suspend fun downloadTrack(track: SunoTrack, outputDir: File): File? = withContext(Dispatchers.IO) {
        val audioSource = track.audioUrl ?: "$cdnBase/${track.id}.mp3"
        val fileName = "${sanitiseFileName(track.title)}_${track.id}.mp3"
        val outputFile = File(outputDir, fileName)

        if (outputFile.exists() && outputFile.length() > 0L) {
            return@withContext outputFile
        }

        val request = Request.Builder()
            .url(audioSource)
            .withSunoHeaders()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw SunoApiException(
                    "Download failed for ${track.id}: HTTP ${response.code}",
                    response.code
                )
            }

            response.body?.byteStream()?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (outputFile.exists() && outputFile.length() > 0L) outputFile else null
        } catch (e: Exception) {
            throw SunoApiException("Download error for ${track.id}: ${e.message}", 0, e)
        }
    }

    private suspend fun fetchPlaylistDetailOrFallback(playlist: SunoPlaylist): SunoPlaylist {
        return try {
            fetchPlaylistDetail(
                playlistId = playlist.id,
                sourceUrl = playlist.sourceUrl ?: "https://suno.com/playlist/${playlist.id}"
            ).copy(savedFromOtherCreator = playlist.savedFromOtherCreator, isCustom = playlist.isCustom)
        } catch (e: Exception) {
            playlist
        }
    }

    private fun fetchPlaylistDetail(playlistId: String, sourceUrl: String): SunoPlaylist {
        var page = 1
        var totalResults: Int? = null
        var merged: SunoPlaylist? = null
        val allTracks = mutableListOf<SunoTrack>()

        do {
            val detailResponse = executeFirstSuccessfulPlaylistDetailRequest(playlistId, page)
            val response = detailResponse.response
            val body = detailResponse.body
            if (!response.isSuccessful || body == null) {
                throw apiFailure("Failed to fetch playlist $playlistId", response.code, response.message, body)
            }

            val pagePlaylist = parseSinglePlaylist(body, sourceUrl)
            if (merged == null) merged = pagePlaylist
            allTracks += pagePlaylist.tracks
            totalResults = parsePlaylistTrackTotal(body) ?: totalResults
            page++
        } while (totalResults != null && allTracks.size < totalResults && page <= MAX_PLAYLIST_DETAIL_PAGES)

        val base = merged ?: throw SunoApiException("Failed to parse playlist JSON", 0)
        return base.copy(tracks = allTracks.distinctBy { it.id })
    }

    private fun parsePlaylistsResponse(json: String): List<SunoPlaylist> {
        return parsePlaylistsPage(json).playlists
    }

    private fun parsePlaylistsPage(json: String): PlaylistPage {
        val trimmed = json.trim()
        val totalResults: Int?
        val results = if (trimmed.startsWith("[")) {
            totalResults = null
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            totalResults = root.optInt("num_total_results", 0).takeIf { it > 0 }
            when {
                root.has("playlists") -> root.getJSONArray("playlists")
                root.has("results") -> root.getJSONArray("results")
                root.has("data") && root.get("data") is JSONArray -> root.getJSONArray("data")
                root.has("data") && root.get("data") is JSONObject -> {
                    val data = root.getJSONObject("data")
                    when {
                        data.has("playlists") -> data.getJSONArray("playlists")
                        data.has("results") -> data.getJSONArray("results")
                        else -> JSONArray().put(data)
                    }
                }
                else -> JSONArray().put(root)
            }
        }

        val playlists = (0 until results.length()).mapNotNull { i ->
            parsePlaylistJson(results.getJSONObject(i))
        }
        return PlaylistPage(playlists = playlists, totalResults = totalResults)
    }

    private fun parseSinglePlaylist(json: String, sourceUrl: String): SunoPlaylist {
        val root = JSONObject(json)
        val playlistJson = when {
            root.has("playlist") -> root.getJSONObject("playlist")
            root.has("data") && root.get("data") is JSONObject -> root.getJSONObject("data")
            else -> root
        }
        val base = parsePlaylistJson(playlistJson) ?: throw SunoApiException("Failed to parse playlist JSON", 0)

        val tracksJson = when {
            root.has("tracks") -> root.getJSONArray("tracks")
            root.has("clips") -> root.getJSONArray("clips")
            playlistJson.has("tracks") -> playlistJson.getJSONArray("tracks")
            playlistJson.has("clips") -> playlistJson.getJSONArray("clips")
            playlistJson.has("playlist_clips") -> playlistJson.getJSONArray("playlist_clips")
            else -> JSONArray()
        }

        val tracks = (0 until tracksJson.length()).mapNotNull { i ->
            parseTrackOrPlaylistClip(tracksJson.getJSONObject(i), base.id)
        }

        return base.copy(
            tracks = tracks.ifEmpty { base.tracks },
            sourceUrl = sourceUrl,
            savedFromOtherCreator = true,
            isCustom = false
        )
    }

    private fun executeFirstSuccessfulPlaylistDetailRequest(playlistId: String, page: Int): PlaylistDetailResponse {
        var lastResponse: okhttp3.Response? = null
        var lastBody: String? = null

        for (url in listOf(playlistV2Url(playlistId, page), playlistLegacyUrl(playlistId, page))) {
            val request = Request.Builder()
                .url(url)
                .withSunoHeaders()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                lastResponse?.close()
                return PlaylistDetailResponse(response = response, body = body)
            }
            lastResponse?.close()
            lastResponse = response
            lastBody = body
        }

        return PlaylistDetailResponse(
            response = lastResponse ?: throw SunoApiException("Failed to fetch playlist $playlistId", 0),
            body = lastBody
        )
    }

    private fun parsePlaylistTrackTotal(json: String): Int? {
        return try {
            val root = JSONObject(json)
            root.optInt("num_total_results", 0).takeIf { it > 0 }
                ?: root.optInt("song_count", 0).takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePlaylistJson(json: JSONObject): SunoPlaylist? {
        val id = firstNonBlank(json, "id", "playlist_id", "slug") ?: return null
        val title = firstNonBlank(json, "title", "name", "display_name") ?: "Untitled Playlist"
        val creatorName = firstNonBlank(json, "creator_name", "creator", "user_name", "handle")
        val sourceUrl = firstNonBlank(json, "source_url", "url", "share_url", "playlist_url")

        val tracks = when {
            json.has("tracks") -> parseTrackArray(json.getJSONArray("tracks"), id)
            json.has("clips") -> parseTrackArray(json.getJSONArray("clips"), id)
            json.has("songs") -> parseTrackArray(json.getJSONArray("songs"), id)
            json.has("playlist_clips") -> parseTrackArray(json.getJSONArray("playlist_clips"), id)
            else -> emptyList()
        }

        return SunoPlaylist(
            id = id,
            title = title,
            creatorName = creatorName,
            sourceUrl = sourceUrl,
            tracks = tracks,
            savedFromOtherCreator = false,
            isCustom = false
        )
    }

    private fun parseTrackArray(array: JSONArray, playlistId: String): List<SunoTrack> {
        return (0 until array.length()).mapNotNull { i ->
            parseTrackOrPlaylistClip(array.getJSONObject(i), playlistId)
        }
    }

    private fun parseTrackOrPlaylistClip(json: JSONObject, playlistId: String): SunoTrack? {
        val clipJson = if (json.has("clip") && json.get("clip") is JSONObject) {
            json.getJSONObject("clip")
        } else {
            json
        }
        return parseTrackJson(clipJson, playlistId)
    }

    private fun parseTrackJson(json: JSONObject, playlistId: String): SunoTrack? {
        val id = firstNonBlank(json, "id", "clip_id", "song_id") ?: return null
        val title = firstNonBlank(json, "title", "name") ?: "Untitled"
        val metadata = json.optJSONObject("metadata")
        val audioUrl = firstNonBlank(json, "audio_url", "audio_file", "stream_url", "mp3_url")
            ?: metadata?.let { firstNonBlank(it, "audio_url", "audio_file", "stream_url", "mp3_url") }
        val imageUrl = firstNonBlank(json, "image_url", "cover_url", "image_large_url", "image_s", "image_path")
            ?: metadata?.let { firstNonBlank(it, "image_url", "cover_url", "image_large_url", "image_s", "image_path") }
        val durationMs = firstPositiveLong(json, "duration_ms")
            ?: firstPositiveLong(json, "duration")?.times(1000L)
            ?: metadata?.let { firstPositiveLong(it, "duration_ms") ?: firstPositiveLong(it, "duration")?.times(1000L) }
        val creatorName = firstNonBlank(json, "creator_name", "display_name", "user_name", "handle", "artist_name")
            ?: metadata?.let { firstNonBlank(it, "creator_name", "display_name", "user_name", "handle", "artist_name") }
            ?: firstNestedNonBlank(json, "user", "display_name", "handle", "username", "name")
            ?: firstNestedNonBlank(json, "creator", "display_name", "handle", "username", "name")
            ?: firstNestedNonBlank(json, "owner", "display_name", "handle", "username", "name")
        val sourceUrl = firstNonBlank(json, "source_url", "share_url", "url") ?: "https://suno.com/song/$id"
        val lyrics = firstNonBlank(json, "lyrics", "lyric", "prompt")
            ?: metadata?.let { firstNonBlank(it, "lyrics", "lyric", "prompt") }
        val stylePrompt = firstNonBlank(json, "style_prompt", "style", "tags")
            ?: metadata?.let { firstNonBlank(it, "style_prompt", "style", "tags") }
        val descriptionPrompt = firstNonBlank(json, "description_prompt", "gpt_description_prompt", "description")
            ?: metadata?.let { firstNonBlank(it, "description_prompt", "gpt_description_prompt", "description") }

        // Batch 5 — optional discovery metadata. Tags may arrive as a JSON
        // array or a comma/line-separated string, at top level or inside
        // `metadata`. Mood/genre are plain strings; genre falls back to the
        // first tag when Suno does not expose an explicit genre field.
        val tags = extractStringList(json, "tags", "tag", "genres", "genre")
            ?: metadata?.let { extractStringList(it, "tags", "genres") }
            ?: emptyList()
        val mood = firstNonBlank(json, "mood")
            ?: metadata?.let { firstNonBlank(it, "mood") }
        val genre = firstNonBlank(json, "genre")
            ?: metadata?.let { firstNonBlank(it, "genre") }
            ?: tags.firstOrNull()

        return SunoTrack(
            id = id,
            title = title,
            audioUrl = audioUrl,
            imageUrl = imageUrl,
            durationMs = durationMs,
            playlistId = playlistId,
            creatorName = creatorName,
            sourceUrl = sourceUrl,
            lyrics = lyrics,
            stylePrompt = stylePrompt,
            descriptionPrompt = descriptionPrompt,
            tags = tags,
            mood = mood,
            genre = genre,
            localPath = null,
            downloadedAtEpochMs = 0L
        )
    }

    private fun apiFailure(prefix: String, code: Int, message: String, body: String?): SunoApiException {
        val detail = body?.let { extractApiErrorDetail(it) }
        val human = when (code) {
            401 -> "$prefix: HTTP 401 Unauthorized. Your Suno session cookie/JWT is expired; export a fresh cookie file from an active Suno browser tab."
            403 -> "$prefix: HTTP 403 Forbidden. Suno rejected this session for the requested resource."
            404 -> "$prefix: HTTP 404 Not Found. The Suno endpoint changed or the playlist does not exist."
            else -> "$prefix: HTTP $code $message${detail?.let { " — $it" } ?: ""}"
        }
        return SunoApiException(human, code)
    }

    private fun extractApiErrorDetail(body: String): String? {
        return try {
            val root = JSONObject(body)
            firstNonBlank(root, "detail", "error", "message")
        } catch (_: Exception) {
            null
        }
    }

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.optString(key, "").trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun firstNestedNonBlank(json: JSONObject, parentKey: String, vararg childKeys: String): String? {
        val parent = json.optJSONObject(parentKey) ?: return null
        return firstNonBlank(parent, *childKeys)
    }

    private fun firstPositiveLong(json: JSONObject, key: String): Long? {
        if (!json.has(key)) return null
        val value = json.optLong(key, 0L)
        return value.takeIf { it > 0L }
    }

    /**
     * Reads a list of tag strings from the first present key. Handles both a
     * JSON array value and a comma/line-separated string value. Returns null
     * when none of the keys carries usable tags, so callers can fall back.
     */
    private fun extractStringList(json: JSONObject, vararg keys: String): List<String>? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.opt(key)
            val items = when (value) {
                is JSONArray -> (0 until value.length()).mapNotNull { i ->
                    value.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }
                }
                is String -> value.split(',', ';', '\n')
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "null" }
                else -> emptyList()
            }
            if (items.isNotEmpty()) return items
        }
        return null
    }

    private fun extractSessionJwt(cookieHeader: String): String? {
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("__session=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractPlaylistId(url: String): String? {
        val uuidPattern = Regex("""[0-9a-fA-F\-]{20,}""")
        val match = uuidPattern.find(url.trim())
        if (match != null) return match.value

        val playlistPattern = Regex("""playlist/([^/?&]+)""")
        val playlistMatch = playlistPattern.find(url.trim())
        if (playlistMatch != null) return playlistMatch.groupValues[1]

        return url.trim().takeIf { it.isNotBlank() && it.length >= 8 }
    }

    private fun sanitiseFileName(name: String): String {
        return name.replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(100)
    }
}

private const val MAX_PLAYLIST_PAGES = 10
private const val MAX_PLAYLIST_DETAIL_PAGES = 20

private data class PlaylistPage(
    val playlists: List<SunoPlaylist>,
    val totalResults: Int?
)

private data class PlaylistDetailResponse(
    val response: okhttp3.Response,
    val body: String?
)

class SunoApiException(
    message: String,
    val httpCode: Int,
    cause: Throwable? = null
) : Exception(message, cause)
