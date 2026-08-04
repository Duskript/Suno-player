package com.duskript.sunolocal.core.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.duskript.sunolocal.core.player.SunoPlaybackEngine
import com.duskript.sunolocal.core.player.SunoPlaybackService

/**
 * SunoPlaybackWidgetProvider — home screen playback widget (v0.1.28).
 *
 * Classic AppWidgetProvider + RemoteViews (no Glance). All rendering lives in
 * [SunoPlaybackWidgetUpdater]. Widget controls are explicit app-private
 * broadcasts handled here against the existing shared ExoPlayer.
 */
class SunoPlaybackWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREVIOUS -> {
                previous()
                SunoPlaybackWidgetUpdater.updateAll(context)
            }
            ACTION_PLAY_PAUSE -> {
                playPause(context)
                SunoPlaybackWidgetUpdater.updateAll(context)
            }
            ACTION_NEXT -> {
                next()
                SunoPlaybackWidgetUpdater.updateAll(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

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

    private fun previous() {
        val player = SunoPlaybackEngine.currentPlayerOrNull()
        if (player?.hasPreviousMediaItem() == true) {
            player.seekToPreviousMediaItem()
        } else {
            Log.i(TAG, "Widget previous ignored; no previous media item")
        }
    }

    private fun next() {
        val player = SunoPlaybackEngine.currentPlayerOrNull()
        if (player?.hasNextMediaItem() == true) {
            player.seekToNextMediaItem()
        } else {
            Log.i(TAG, "Widget next ignored; no next media item")
        }
    }

    private fun playPause(context: Context) {
        val player = SunoPlaybackEngine.currentPlayerOrNull()
        when {
            player == null -> Log.i(TAG, "Widget play/pause ignored; no player")
            player.isPlaying -> player.pause()
            player.mediaItemCount > 0 -> {
                val serviceIntent = Intent(context.applicationContext, SunoPlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(serviceIntent)
                } else {
                    context.applicationContext.startService(serviceIntent)
                }
                player.play()
            }
            else -> Log.i(TAG, "Widget play/pause ignored; empty queue")
        }
    }

    companion object {
        const val ACTION_PREVIOUS = "com.duskript.sunolocal.widget.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.duskript.sunolocal.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.duskript.sunolocal.widget.NEXT"

        private const val TAG = "SunoPlaybackWidget"
    }
}
