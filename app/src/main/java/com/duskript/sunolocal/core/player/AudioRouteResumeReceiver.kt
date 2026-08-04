package com.duskript.sunolocal.core.player

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resumes saved playback when Android tells the app that an output route was
 * connected. Static delivery is best-effort on modern Android: the OS may skip
 * implicit broadcasts while the app process is stopped or background-limited,
 * so this cannot guarantee every car/Bluetooth/wired connection will wake us.
 */
class AudioRouteResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = resumeReasonFor(intent) ?: return
        val result = PlaybackAutoResume.resumeIfPossible(context, reason)
        Log.i(TAG, "Route-connect auto-resume result=$result reason=$reason")
    }

    private fun resumeReasonFor(intent: Intent): String? {
        return when (intent.action) {
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", 0)
                if (state == 1) "wired-headset-connected" else null
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> "bluetooth-device-connected"
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                connectedProfileReason(intent, "bluetooth-a2dp-connected")
            }
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                connectedProfileReason(intent, "bluetooth-headset-connected")
            }
            else -> null
        }
    }

    private fun connectedProfileReason(intent: Intent, reason: String): String? {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        return if (state == BluetoothProfile.STATE_CONNECTED) reason else null
    }

    private companion object {
        const val TAG = "AudioRouteResumeReceiver"
    }
}
