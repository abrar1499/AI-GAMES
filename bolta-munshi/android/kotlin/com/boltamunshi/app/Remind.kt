package com.boltamunshi.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * Reminders: a task in the memory book with a time ("kal subah 9 baje bill yaad dilana")
 * rings as a notification at that time. Alarms are set again after the phone restarts,
 * and taken away when the task is deleted.
 */
class Remind : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            sync(context, Memory(File(context.filesDir, "memory.json")))
            return
        }
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text").orEmpty()
        val m = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && m.getNotificationChannel(CHANNEL) == null)
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        b.setSmallIcon(context.applicationInfo.icon).setContentTitle("Smart Book · Yaad dihani").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        runCatching { m.notify(1000 + id, b.build()) }
    }

    companion object {
        private const val CHANNEL = "reminders"

        private fun pending(context: Context, r: Memory.Rec?, id: Int, flags: Int): PendingIntent? {
            val i = Intent(context, Remind::class.java).setAction("com.boltamunshi.app.REMIND.$id")
            if (r != null) i.putExtra("id", r.id).putExtra("text", r.text.ifBlank { r.item }.ifBlank { "Yaad dihani" })
            return PendingIntent.getBroadcast(context, id, i, flags or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Sets an alarm for every reminder still to come and takes away the ones no longer in the book. */
        fun sync(context: Context, memory: Memory) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val due = memory.reminders(now)
            val old = prefs.getString("alarms", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
            for (id in old) if (due.none { it.id == id }) pending(context, null, id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            for (r in due) {
                val pi = pending(context, r, r.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
                val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                runCatching {
                    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMs(), pi)
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMs(), pi)
                }
            }
            prefs.edit().putString("alarms", due.joinToString(",") { it.id.toString() }).apply()
        }
    }
}
