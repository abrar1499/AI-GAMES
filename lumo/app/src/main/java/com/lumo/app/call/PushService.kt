package com.lumo.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lumo.app.LumoApp
import com.lumo.app.data.Repo

/** Wakes the phone for calls and messages when the app is closed (needs the Cloud Function). */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Repo.savePushToken(token)

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "call" -> CallManager.onIncoming(d["callId"].orEmpty())
            "message" -> if (!LumoApp.inForeground) {
                Notifs.showMessage(this, d["from"].orEmpty().ifBlank { "New message" }, d["text"].orEmpty(), d["chatId"].orEmpty())
            }
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DECLINE) CallManager.decline()
    }

    companion object {
        const val DECLINE = "com.lumo.app.DECLINE"
    }
}
