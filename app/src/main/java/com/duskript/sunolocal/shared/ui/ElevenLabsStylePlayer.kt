package com.duskript.sunolocal.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * ElevenLabsStylePlayer — A polished, dark-styled music player control bar
 * inspired by ElevenLabs' audio/conversation UI.
 *
 * Displays the current track info and transport controls at the bottom of the
 * screen, plus a scrub-capable seek bar with elapsed/total time and a repeat
 * mode toggle (off / all / one).
 *
 * This is a pure Jetpack Compose implementation. The @elevenlabs/react npm package
 * contains conversation/transcription hooks, NOT a music-player widget, and cannot
 * be used in Kotlin-native Compose. See docs/ELEVENLABS_REACT_NOTES.md for details.
 *
 * @param currentTrack The currently playing track (null if nothing is playing).
 * @param isPlaying Whether audio is currently playing.
 * @param shuffleEnabled Whether shuffle mode is active.
 * @param progress Current playback progress (0f..1f).
 * @param positionMs Elapsed playback position of the current item, in ms.
 * @param durationMs Duration of the current item, in ms (0 when unknown).
 * @param repeatMode Media3 repeat mode (REPEAT_MODE_OFF / ALL / ONE).
 * @param hasNext Whether the queue has a next item; disables the next button when false.
 * @param hasPrevious Whether the queue has a previous item; disables the previous button when false.
 * @param onPlayPause Callback when play/pause is toggled.
 * @param onNext Callback to skip to next track.
 * @param onPrevious Callback to go to previous track.
 * @param onToggleShuffle Callback to toggle shuffle mode.
 * @param onToggleRepeat Callback to cycle repeat mode (off -> all -> one -> off).
 * @param onSeekProgress Callback with a 0f..1f fraction when scrubbing finishes.
 * @param onTrackClick Callback when the track info area is tapped (e.g. expand full player).
 */
@Composable
fun ElevenLabsStylePlayer(
    currentTrack: SunoTrack?,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    repeatMode: Int,
    hasNext: Boolean = true,
    hasPrevious: Boolean = true,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onTrackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Local drag state so scrubbing feels smooth without fighting the
    // position flow; the seek only commits to the player when the drag ends.
    var sliderValue by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    // Boolean drag flag — must use mutableStateOf, not mutableFloatStateOf.
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(progress, isDragging) {
        if (!isDragging) sliderValue = progress.coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
    ) {
        Column {
            // Scrub bar (replaces the old thin progress line; still compact).
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = {
                    sliderValue = it.coerceIn(0f, 1f)
                    isDragging = true
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeekProgress(sliderValue.coerceIn(0f, 1f))
                },
                enabled = durationMs > 0L,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            // Elapsed / total time row.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPlaybackTime(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatPlaybackTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Track info + controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTrackClick)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Track details
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = currentTrack?.title ?: "No track selected",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp
                    )
                    Text(
                        text = currentTrack?.creatorName ?: "\u2014",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Shuffle toggle
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (shuffleEnabled) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
                        contentDescription = if (shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Previous — disabled (not just dimmed) when the queue has no
                // previous item, mirroring the Media3 notification/lockscreen
                // command availability.
                IconButton(
                    onClick = onPrevious,
                    enabled = hasPrevious,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous track",
                        tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Play/Pause button (ElevenLabs-style circular)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = currentTrack != null, onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Next — disabled (not just dimmed) when the queue has no next
                // item, mirroring the Media3 notification/lockscreen command
                // availability.
                IconButton(
                    onClick = onNext,
                    enabled = hasNext,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next track",
                        tint = if (hasNext) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Repeat toggle (off -> all -> one -> off)
                IconButton(
                    onClick = onToggleRepeat,
                    modifier = Modifier.size(36.dp)
                ) {
                    val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> "Repeat off"
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            else -> "Repeat one"
                        },
                        tint = if (repeatActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** Formats milliseconds as m:ss (e.g. 1:23). Unknown/zero durations render as 0:00. */
private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
