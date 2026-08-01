package com.duskript.sunolocal.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/**
 * AudioNoisyReceiver — pauses playback when audio output becomes noisy.
 *
 * ACTION_AUDIO_BECOMING_NOISY fires when a wired headset is unplugged or a
 * Bluetooth A2DP route disconnects. Pausing (instead of letting audio blast
 * through the speaker) is the expected media-app behaviour.
 *
 * The receiver only touches an already-existing player: if the process-wide
 * engine has no player instance (nothing ever played) or the player is not
 * playing, it does nothing — it never constructs playback from scratch.
 *
 * Static manifest registration works for this action on API 26+ because
 * ACTION_AUDIO_BECOMING_NOISY is exempt from the Android 8+ implicit-broadcast
 * restrictions for manifest receivers.
 */
class AudioNoisyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
        val player = SunoPlaybackEngine.currentPlayerOrNull() ?: return
        if (!player.isPlaying) return
        Log.i(TAG, "Audio becoming noisy — pausing playback")
        player.pause()
    }

    private companion object {
        const val TAG = "AudioNoisyReceiver"
    }
}
