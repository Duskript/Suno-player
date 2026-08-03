package com.duskript.sunolocal.core.widget

import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * SunoPlaybackWidgetState — pure, JVM-testable snapshot of everything the home
 * screen widget renders (v0.1.26).
 *
 * Keeping this free of Android types lets the widget's formatting/fallback
 * logic be unit-tested without Robolectric, and lets
 * [SunoPlaybackWidgetUpdater] de-dupe renders by data-class equality.
 *
 * Fallback strings default to the values of the matching string resources
 * (widget_title_fallback / widget_subtitle_fallback / widget_creator_fallback);
 * the updater passes the localized resource values when building states from a
 * live Context.
 */
data class SunoPlaybackWidgetState(
    val title: String,
    val subtitle: String,
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean
) {
    companion object {
        const val FALLBACK_TITLE = "Suno Local"
        const val FALLBACK_SUBTITLE = "Ready to play"
        const val FALLBACK_UNKNOWN_CREATOR = "Unknown creator"

        /**
         * Builds the widget snapshot from the playback truth the UI already
         * exposes. A blank track title falls back to [fallbackTitle]; a missing
         * track shows [fallbackSubtitle] (inviting play); a track without a
         * creator shows [fallbackUnknownCreator].
         */
        fun from(
            track: SunoTrack?,
            isPlaying: Boolean,
            hasPrevious: Boolean,
            hasNext: Boolean,
            fallbackTitle: String = FALLBACK_TITLE,
            fallbackSubtitle: String = FALLBACK_SUBTITLE,
            fallbackUnknownCreator: String = FALLBACK_UNKNOWN_CREATOR
        ): SunoPlaybackWidgetState {
            val title = track?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val creator = track?.creatorName?.takeIf { it.isNotBlank() }
            val subtitle = when {
                track == null -> fallbackSubtitle
                creator != null -> creator
                else -> fallbackUnknownCreator
            }
            return SunoPlaybackWidgetState(
                title = title,
                subtitle = subtitle,
                isPlaying = isPlaying,
                hasPrevious = hasPrevious,
                hasNext = hasNext
            )
        }
    }
}
