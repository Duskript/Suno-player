package com.duskript.sunolocal.core.player

import android.content.Context
import android.content.Intent
import android.util.Log
import com.duskript.sunolocal.core.storage.LibraryStore
import com.duskript.sunolocal.core.storage.SunoPlaylistJson
import com.duskript.sunolocal.core.storage.SunoTrackJson
import com.duskript.sunolocal.domain.model.SunoTrack

object PlaybackAutoResume {

    fun resumeIfPossible(context: Context, reason: String): PlaybackAutoResumeResult {
        val appContext = context.applicationContext
        val existingPlayer = SunoPlaybackEngine.currentPlayerOrNull()
        if (existingPlayer != null && existingPlayer.mediaItemCount > 0) {
            if (existingPlayer.isPlaying) {
                Log.i(TAG, "Auto-resume ignored: already playing")
                return PlaybackAutoResumeResult.AlreadyPlaying
            }
            appContext.startService(Intent(appContext, SunoPlaybackService::class.java))
            existingPlayer.play()
            Log.i(TAG, "Auto-resumed existing queue after route connect: reason=$reason")
            return PlaybackAutoResumeResult.ResumedExistingQueue
        }

        val savedState = PlaybackStateStore(appContext).load()
            ?: return PlaybackAutoResumeResult.NoSavedState
        val playlists = LibraryStore(appContext).loadPlaylists()
        return when (val plan = resumePlanFor(savedState, playlists)) {
            is PlaybackAutoResumePlan.Ready -> {
                val localAudioPlayer = LocalAudioPlayer(appContext)
                try {
                    localAudioPlayer.setQueue(plan.queue, plan.startTrackId)
                    localAudioPlayer.seekTo(savedState.positionMs)
                    localAudioPlayer.playPause()
                    Log.i(TAG, "Auto-resumed saved queue after route connect: reason=$reason")
                    PlaybackAutoResumeResult.ResumedSavedState
                } finally {
                    localAudioPlayer.release()
                }
            }
            is PlaybackAutoResumePlan.Unavailable -> {
                Log.i(TAG, "Auto-resume unavailable: ${plan.status}")
                PlaybackAutoResumeResult.NotResumable(plan.status)
            }
        }
    }

    private const val TAG = "PlaybackAutoResume"
}

sealed class PlaybackAutoResumeResult {
    object AlreadyPlaying : PlaybackAutoResumeResult()
    object ResumedExistingQueue : PlaybackAutoResumeResult()
    object ResumedSavedState : PlaybackAutoResumeResult()
    object NoSavedState : PlaybackAutoResumeResult()
    data class NotResumable(val status: ResumePlaybackStatus) : PlaybackAutoResumeResult()
}

internal sealed class PlaybackAutoResumePlan {
    data class Ready(
        val queue: List<SunoTrack>,
        val startTrackId: String
    ) : PlaybackAutoResumePlan()

    data class Unavailable(val status: ResumePlaybackStatus) : PlaybackAutoResumePlan()
}

internal fun resumePlanFor(
    state: PlaybackState,
    playlists: List<SunoPlaylistJson>
): PlaybackAutoResumePlan {
    return when (val status = ResumePlaybackStatus.evaluate(state, playlists.toDomainTracks())) {
        is ResumePlaybackStatus.Ready -> PlaybackAutoResumePlan.Ready(status.queue, status.startTrackId)
        is ResumePlaybackStatus.SavedTrackUnplayable -> {
            PlaybackAutoResumePlan.Ready(status.queue, status.startTrackId)
        }
        else -> PlaybackAutoResumePlan.Unavailable(status)
    }
}

internal fun List<SunoPlaylistJson>.toDomainTracks(): List<SunoTrack> =
    flatMap { playlist -> playlist.tracks.map { it.toDomainTrack() } }

internal fun SunoTrackJson.toDomainTrack(): SunoTrack = SunoTrack(
    id = id,
    title = title,
    audioUrl = audioUrl,
    localPath = localPath,
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
    downloadedAtEpochMs = downloadedAtEpochMs
)
