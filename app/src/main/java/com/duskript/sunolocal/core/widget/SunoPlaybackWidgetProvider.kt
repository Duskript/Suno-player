package com.duskript.sunolocal.core.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * SunoPlaybackWidgetProvider — home screen playback widget (v0.1.26).
 *
 * Classic AppWidgetProvider + RemoteViews (no Glance). All rendering lives in
 * [SunoPlaybackWidgetUpdater]; this provider only forwards widget-update
 * broadcasts. Widget controls route through the existing media-button receiver
 * / MediaSessionService path, so no playback logic lives here.
 */
class SunoPlaybackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Always render (no de-dupe here) so a freshly placed widget shows the
        // current track even when playback state has not changed.
        SunoPlaybackWidgetUpdater.updateAll(context)
    }
}
