package com.duskript.sunolocal.core.player

import com.duskript.sunolocal.domain.model.SunoTrack
import java.io.File

/**
 * PlaybackSource — Batch E: resolves the actual audio source for a [SunoTrack],
 * preferring a verified local file over the network URL.
 *
 * Pure JVM (java.io.File only, no Android, no network) so unit tests can
 * exercise local / streaming / unavailable resolution with temp files.
 *
 * Selection rules (see [resolve]):
 * 1. localPath wins when nonblank, not "null", the file exists, and length > 0;
 * 2. otherwise fall back to audioUrl when nonblank and not "null";
 * 3. otherwise [Unavailable] — the track cannot be played at all.
 */
sealed class PlaybackSource {

    /** A real, non-empty local file (exists && length > 0). */
    data class Local(val file: File) : PlaybackSource()

    /** No usable local file; the track streams from its audioUrl. */
    data class Streaming(val url: String) : PlaybackSource()

    /** Neither a valid local file nor an audioUrl — not playable. */
    object Unavailable : PlaybackSource()

    /** True when this source can feed a player. */
    val isPlayable: Boolean get() = this !is Unavailable

    companion object {
        /**
         * Resolve a track's playback source. Stale local paths (deleted or
         * zero-byte files) are treated as absent so playback prefers verified
         * local audio and only falls back to the network URL when the local
         * file is genuinely unusable.
         */
        fun resolve(track: SunoTrack): PlaybackSource {
            val localPath = track.localPath?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            if (localPath != null) {
                val file = File(localPath)
                if (file.isFile && file.length() > 0L) {
                    return Local(file)
                }
            }
            val url = track.audioUrl?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            return if (url != null) Streaming(url) else Unavailable
        }

        /** User-facing guidance when a track has no usable local file and no audioUrl. */
        fun missingLocalAudioMessage(trackTitle: String): String =
            "Missing local audio for \"$trackTitle\" — resync or re-download this playlist."

        /** Prefix used to recognise missing-file messages (see LocalAudioPlayer). */
        const val MISSING_LOCAL_AUDIO_PREFIX: String = "Missing local audio"
    }
}
