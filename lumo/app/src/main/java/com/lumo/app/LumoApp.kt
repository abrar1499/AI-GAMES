package com.lumo.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.lumo.app.call.CallManager
import com.lumo.app.call.Notifs
import com.lumo.app.data.Repo

class LumoApp : Application() {
    private var ringListener: ListenerRegistration? = null
    private var started = 0
    private val main = Handler(Looper.getMainLooper())

    /** While the app is on screen, refresh "online" every 30s so a dead phone drops off within ~a minute. */
    private val heartbeat = object : Runnable {
        override fun run() {
            Repo.setPresence(true)
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
        if (!BuildConfig.HAS_FIREBASE) return
        CallManager.init(this)
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            ringListener?.remove()
            ringListener = null
            val uid = auth.currentUser?.uid ?: return@addAuthStateListener
            Repo.refreshPushToken()
            if (inForeground) { main.removeCallbacks(heartbeat); heartbeat.run() }
            // While the app is alive, pick up calls instantly without waiting for a push.
            ringListener = FirebaseFirestore.getInstance().collection("calls")
                .whereEqualTo("callee", uid)
                .whereEqualTo("status", "calling")
                .addSnapshotListener { snap, _ ->
                    snap?.documentChanges?.filter { it.type == DocumentChange.Type.ADDED }
                        ?.forEach { CallManager.onIncoming(it.document.id) }
                }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                if (started == 1) {
                    inForeground = true
                    main.removeCallbacks(heartbeat)
                    heartbeat.run()
                }
            }

            override fun onActivityStopped(a: Activity) {
                started--
                if (started == 0) {
                    inForeground = false
                    main.removeCallbacks(heartbeat)
                    Repo.setPresence(false)
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        @Volatile var inForeground = false
    }
}
