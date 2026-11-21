package com.lumo.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps the camera and microphone alive while the call runs behind other apps. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra("video", false) == true
        val name = CallManager.state.value.peerName.ifBlank { "Lumo call" }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, Notifs.ID_ONGOING, Notifs.ongoing(this, name, video), type)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        fun start(c: Context, video: Boolean) {
            runCatching {
                ContextCompat.startForegroundService(c, Intent(c, CallService::class.java).putExtra("video", video))
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, CallService::class.java))
        }
    }
}
