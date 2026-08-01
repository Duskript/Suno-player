package com.duskript.sunolocal.features.library.state

import com.duskript.sunolocal.domain.model.SunoPlaylist
import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * Pure helpers for Batch 4 playlist-manager mutations (rename / duplicate).
 *
 * Kept free of Android dependencies so they can be unit-tested on the JVM
 * (see PlaylistManagerHelpersTest). All persistence still goes through
 * LibraryStore via the ViewModel — these functions only shape the data.
 */

/** Trim a user-entered playlist title; fall back when the input is blank. */
fun cleanPlaylistTitle(raw: String, fallback: String): String =
    raw.trim().takeIf { it.isNotBlank() } ?: fallback

/**
 * Default title for a duplicated playlist: "<original> Copy".
 * Blank-safe so a duplicate of an untitled playlist still gets a usable name.
 */
fun defaultDuplicateTitle(originalTitle: String): String {
    val clean = originalTitle.trim().takeIf { it.isNotBlank() } ?: return "My Suno Mix Copy"
    return "$clean Copy"
}

/**
 * Build a new custom playlist that copies [source]'s tracks — and their
 * order — into a fresh custom mix. Track [SunoTrack.playlistId]s are rewritten
 * to [newId] so the copies belong to the new playlist, matching how
 * addTrackToCustomPlaylist stamps membership. The output is always a custom
 * playlist owned by "You" regardless of the source type.
 */
fun buildDuplicatePlaylist(
    source: SunoPlaylist,
    newId: String,
    newTitle: String,
    createdAtEpochMs: Long = System.currentTimeMillis()
): SunoPlaylist = SunoPlaylist(
    id = newId,
    title = newTitle,
    creatorName = "You",
    sourceUrl = null,
    tracks = source.tracks.map { it.copy(playlistId = newId) },
    savedFromOtherCreator = false,
    isCustom = true,
    lastSyncedAtEpochMs = createdAtEpochMs
)
