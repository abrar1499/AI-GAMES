package com.lumo.app.call

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Puts the phone into call audio mode and picks the output. Headphones always win: wired,
 * USB or Bluetooth headphones get the call (video calls included), and plugging them in or
 * out mid-call switches over at once. Without headphones: speaker for video, earpiece for voice.
 * [onRoute] reports whether the loudspeaker is in use, so the call screen's button matches.
 */
class AudioRouter(context: Context, private val onRoute: (speaker: Boolean) -> Unit = {}) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val main = Handler(Looper.getMainLooper())
    private var proximity: PowerManager.WakeLock? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var wantSpeaker = false
    private var scoStarted = false

    /** True when the call is on the loudspeaker right now. */
    var speakerOn = false
        private set

    private val deviceWatcher = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (added.any { it.isHeadset() }) main.postDelayed({ route() }, 300)
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.isHeadset() }) main.postDelayed({ route() }, 300)
        }
    }

    fun start(speaker: Boolean) {
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        wantSpeaker = speaker
        am.registerAudioDeviceCallback(deviceWatcher, main)
        route()
    }

    fun hasHeadset(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isHeadset() }

    private fun AudioDeviceInfo.isHeadset() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    /** The speaker button: on = loudspeaker even with headphones in, off = headphones or earpiece. */
    fun setSpeaker(on: Boolean) {
        wantSpeaker = on
        route(forceSpeaker = on)
    }

    private fun route(forceSpeaker: Boolean = false) {
        val headset = hasHeadset()
        // Headphones take over unless the user pressed the speaker button on purpose.
        val speaker = if (forceSpeaker) true else wantSpeaker && !headset
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val pick = if (speaker) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.isHeadset() }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (pick != null) am.setCommunicationDevice(pick) else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                am.isSpeakerphoneOn = speaker
                // Older Android: Bluetooth headphones need their call channel (SCO) opened by hand.
                val bt = !speaker && am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                if (bt && !scoStarted) { am.startBluetoothSco(); am.isBluetoothScoOn = true; scoStarted = true }
                if (!bt && scoStarted) { am.isBluetoothScoOn = false; am.stopBluetoothSco(); scoStarted = false }
            }
        }
        speakerOn = speaker
        // Screen goes dark at the ear only when the earpiece is actually used.
        updateProximity(!speaker && !headset)
        onRoute(speaker)
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
        runCatching { am.unregisterAudioDeviceCallback(deviceWatcher) }
        main.removeCallbacksAndMessages(null)
        updateProximity(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") {
            am.isSpeakerphoneOn = false
            if (scoStarted) { am.isBluetoothScoOn = false; am.stopBluetoothSco(); scoStarted = false }
        }
        am.mode = savedMode
    }
}
