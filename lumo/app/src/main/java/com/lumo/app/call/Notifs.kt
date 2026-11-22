package com.lumo.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.lumo.app.MainActivity
import com.lumo.app.R

object Notifs {
    const val CH_CALLS = "lumo_calls"
    const val CH_ONGOING = "lumo_ongoing"
    const val CH_MESSAGES = "lumo_messages"
    private const val ID_INCOMING = 41
    const val ID_ONGOING = 42

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // the app plays the ringtone itself
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun callIntent(c: Context, action: String?, code: Int): PendingIntent {
        val i = Intent(c, CallActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showIncoming(c: Context, s: CallState) {
        val caller = Person.Builder().setName(s.peerName.ifBlank { s.peerUsername }).setImportant(true).build()
        val decline = PendingIntent.getBroadcast(
            c, 2, Intent(c, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(caller.name)
            .setContentText(if (s.video) "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(callIntent(c, null, 0))
            .setFullScreenIntent(callIntent(c, null, 0), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, callIntent(c, CallActivity.ACCEPT, 1)))
            .setTimeoutAfter(50_000)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(ID_INCOMING, n) }
    }

    fun cancelIncoming(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING)

    fun ongoing(c: Context, title: String, video: Boolean): Notification =
        NotificationCompat.Builder(c, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (video) "Video call · tap to return" else "Voice call · tap to return")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(callIntent(c, null, 3))
            .build()

    fun showMessage(c: Context, from: String, text: String, tag: String) {
        val open = PendingIntent.getActivity(
            c, tag.hashCode(), Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(from)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(tag, 7, n) }
    }
}
