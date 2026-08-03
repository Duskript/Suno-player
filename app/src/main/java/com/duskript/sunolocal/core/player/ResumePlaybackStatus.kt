package com.duskript.sunolocal.core.player

import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * ResumePlaybackStatus — Batch E: pure result of trying to rebuild a saved
 * playback session from a [PlaybackState] snapshot against the current
 * library. Lets LibraryViewModel explain *why* Resume is or is not available
 * instead of silently no-opping.
 *
 * Pure JVM (no Android; File checks happen inside [PlaybackSource.resolve]).
 */
sealed class ResumePlaybackStatus {

    /** Queue rebuilt and the saved track itself is playable; start at [startTrackId]. */
    data class Ready(
        val queue: List<SunoTrack>,
        val startTrackId: String
    ) : ResumePlaybackStatus()

    /** The saved track id is not in the current library at all. */
    data class SavedTrackMissing(val trackId: String) : ResumePlaybackStatus()

    /** Saved queue resolves but no track has a usable audio source. */
    object NoPlayableTracks : ResumePlaybackStatus()

    /**
     * The saved track itself is unplayable (missing local file, no audioUrl),
     * but the queue still has playable tracks; resume from [startTrackId] and
     * surface [trackTitle] in guidance so the user knows what changed.
     */
    data class SavedTrackUnplayable(
        val trackId: String,
        val trackTitle: String,
        val queue: List<SunoTrack>,
        val startTrackId: String
    ) : ResumePlaybackStatus()

    companion object {
        /**
         * Evaluate a saved [PlaybackState] against the current library tracks.
         * When the saved track is playable, the queue is rebuilt as saved;
         * when only the saved track is unplayable, playback still proceeds from
         * the first playable queue track. Never auto-plays — the caller decides.
         */
        fun evaluate(state: PlaybackState, libraryTracks: List<SunoTrack>): ResumePlaybackStatus {
            val byId = libraryTracks.associateBy { it.id }
            val target = byId[state.trackId]
            if (target == null) return SavedTrackMissing(state.trackId)

            val queueIds = state.queueIds.takeIf { it.isNotEmpty() && state.trackId in it }
                ?: listOf(state.trackId)
            val resolvedQueue = queueIds.mapNotNull { byId[it] }
            val playableQueue = resolvedQueue.filter { PlaybackSource.resolve(it).isPlayable }
            if (playableQueue.isEmpty()) return NoPlayableTracks

            val startId = playableQueue.firstOrNull { it.id == target.id }?.id
                ?: playableQueue.first().id
            return if (startId == target.id) {
                Ready(playableQueue, startId)
            } else {
                SavedTrackUnplayable(target.id, target.title, playableQueue, startId)
            }
        }
    }
}
