package com.metrolist.suno.models

import kotlinx.serialization.Serializable

@Serializable
data class SunoTrack(
    val id: String,
    val title: String,
    val audioUrl: String,
    val imageUrl: String,
    val displayName: String,
    val handle: String,
    val tags: String,
    val majorModelVersion: String,
    val duration: Float,
    val playCount: Long = 0,
    val upvoteCount: Long = 0,
    val isLiked: Boolean = false,
    val createdAt: String? = null,
)
