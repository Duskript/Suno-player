package com.duskript.sunolocal.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.duskript.sunolocal.MainActivity
import com.duskript.sunolocal.R
import com.duskript.sunolocal.core.player.SunoPlaybackEngine
import com.duskript.sunolocal.domain.model.SunoTrack

/**
 * SunoPlaybackWidgetUpdater — renders the home screen playback widget from a
 * pure [SunoPlaybackWidgetState] snapshot using classic AppWidgetProvider +
 * RemoteViews (v0.1.28, no Glance).
 *
 * Control routing: the previous/play-pause/next buttons send explicit
 * app-private broadcasts to [SunoPlaybackWidgetProvider], which acts directly
 * on the existing process-wide player. Tapping the widget body opens
 * [MainActivity].
 *
 * Update cadence: [updateAll] skips a render when the incoming state equals
 * the last rendered one, so [com.duskript.sunolocal.core.player.LocalAudioPlayer]
 * can call it from every syncStateFromPlayer() pass without RemoteViews churn.
 * The 500ms position tick never reaches this class — the v0.1.26 widget has no
 * progress bar.
 */
object SunoPlaybackWidgetUpdater {

    private const val REQUEST_CODE_OPEN_APP = 1000
    private const val REQUEST_CODE_PREVIOUS = 1001
    private const val REQUEST_CODE_PLAY_PAUSE = 1002
    private const val REQUEST_CODE_NEXT = 1003

    private const val ENABLED_ALPHA = 1f
    private const val DISABLED_ALPHA = 0.35f

    private const val PLAY_GLYPH = "\u25B6" // ▶
    private const val PAUSE_GLYPH = "\u23F8" // ⏸

    /** Last rendered state; identical states are skipped (state de-dupe). */
    @Volatile
    private var lastRendered: SunoPlaybackWidgetState? = null

    /**
     * Renders every active widget id from the shared player's current
     * snapshot. Always renders (no de-dupe) so a freshly placed widget shows
     * the current track even when playback state has not changed since the
     * last state-sync render.
     */
    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        render(appContext, snapshotFromPlayer(appContext))
    }

    /** Renders every active widget id from an explicit track/flags snapshot. */
    fun updateAll(
        context: Context,
        track: SunoTrack?,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean
    ) {
        val appContext = context.applicationContext
        updateAll(appContext, stateFrom(appContext, track, isPlaying, hasPrevious, hasNext))
    }

    /** Renders every active widget id from an explicit state (de-duped). */
    fun updateAll(context: Context, state: SunoPlaybackWidgetState) {
        val appContext = context.applicationContext
        if (lastRendered == state) return
        lastRendered = state
        render(appContext, state)
    }

    /** Builds the localized fallback-aware state for the updater's own use. */
    private fun stateFrom(
        context: Context,
        track: SunoTrack?,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean
    ): SunoPlaybackWidgetState = SunoPlaybackWidgetState.from(
        track = track,
        isPlaying = isPlaying,
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        fallbackTitle = context.getString(R.string.widget_title_fallback),
        fallbackSubtitle = context.getString(R.string.widget_subtitle_fallback),
        fallbackUnknownCreator = context.getString(R.string.widget_creator_fallback)
    )

    /**
     * Best-effort snapshot straight from the process-wide player, used when
     * [updateAll] is called without explicit state (e.g. a widget is placed).
     * The queue built by LocalAudioPlayer tags every MediaItem with its
     * SunoTrack, so the tag is the primary source; session metadata is the
     * fallback for queues built outside the wrapper.
     */
    private fun snapshotFromPlayer(context: Context): SunoPlaybackWidgetState {
        val player = SunoPlaybackEngine.currentPlayerOrNull()
            ?: return stateFrom(context, null, false, false, false)
        val track = player.currentMediaItem?.localConfiguration?.tag as? SunoTrack
        if (track != null) {
            return stateFrom(
                context,
                track,
                player.isPlaying,
                player.hasPreviousMediaItem(),
                player.hasNextMediaItem()
            )
        }
        val metadata = player.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.widget_title_fallback)
        val creator = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.widget_creator_fallback)
        return SunoPlaybackWidgetState(
            title = title,
            subtitle = creator,
            isPlaying = player.isPlaying,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem()
        )
    }

    private fun render(context: Context, state: SunoPlaybackWidgetState) {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, SunoPlaybackWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.suno_playback_widget)
        views.setTextViewText(R.id.widget_title, state.title)
        views.setTextViewText(R.id.widget_subtitle, state.subtitle)
        views.setTextViewText(
            R.id.widget_play_pause,
            if (state.isPlaying) PAUSE_GLYPH else PLAY_GLYPH
        )
        views.setContentDescription(
            R.id.widget_play_pause,
            if (state.isPlaying) {
                context.getString(R.string.widget_pause_desc)
            } else {
                context.getString(R.string.widget_play_desc)
            }
        )
        // RemoteViews cannot reliably disable a view; dim instead. Taps on an
        // unavailable command are harmless no-ops in the provider action path.
        views.setFloat(
            R.id.widget_previous,
            "setAlpha",
            if (state.hasPrevious) ENABLED_ALPHA else DISABLED_ALPHA
        )
        views.setFloat(
            R.id.widget_next,
            "setAlpha",
            if (state.hasNext) ENABLED_ALPHA else DISABLED_ALPHA
        )

        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        views.setOnClickPendingIntent(
            R.id.widget_previous,
            widgetActionPendingIntent(
                context,
                SunoPlaybackWidgetProvider.ACTION_PREVIOUS,
                REQUEST_CODE_PREVIOUS
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            widgetActionPendingIntent(
                context,
                SunoPlaybackWidgetProvider.ACTION_PLAY_PAUSE,
                REQUEST_CODE_PLAY_PAUSE
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            widgetActionPendingIntent(
                context,
                SunoPlaybackWidgetProvider.ACTION_NEXT,
                REQUEST_CODE_NEXT
            )
        )

        manager.updateAppWidget(widgetIds, views)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Explicit provider PendingIntent for widget-only playback commands. */
    private fun widgetActionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).apply {
                component = ComponentName(context, SunoPlaybackWidgetProvider::class.java)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
