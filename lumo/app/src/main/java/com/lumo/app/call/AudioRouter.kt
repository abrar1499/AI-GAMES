package com.lumo.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager

/**
 * Puts the phone into voice-call audio mode and picks the best output:
 * Bluetooth or wired headset first, otherwise speaker (video) or earpiece (voice).
 */
class AudioRouter(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var proximity: PowerManager.WakeLock? = null
    private var savedMode = AudioManager.MODE_NORMAL

    fun start(speaker: Boolean) {
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    fun hasHeadset(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isHeadset() }

    private fun AudioDeviceInfo.isHeadset() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val pick = if (on) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else devices.firstOrNull { it.isHeadset() }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (pick != null) am.setCommunicationDevice(pick) else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on && !hasHeadset()
        }
        updateProximity(!on)
    }

    /** Screen goes dark when the phone is held to the ear on voice calls. */
    private fun updateProximity(earpiece: Boolean) {
        if (earpiece && proximity == null &&
            pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        ) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "lumo:proximity")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        } else if (!earpiece) {
            proximity?.let { if (it.isHeld) it.release() }
            proximity = null
        }
    }

    fun stop() {
        updateProximity(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
        am.mode = savedMode
    }
}
