package com.personal.docvault

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/** Locks the vault as soon as the app leaves the screen. */
class App : Application(), Application.ActivityLifecycleCallbacks {

    private var started = 0

    /** Set when the app went to the background with a picker / share sheet open. */
    private var graceDeadline = 0L

    override fun onCreate() {
        super.onCreate()
        Vault.clearTemp(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(a: Activity) {
        // Came back too late from a picker / share sheet: lock anyway.
        if (started <= 0 && graceDeadline != 0L && System.currentTimeMillis() > graceDeadline) {
            Vault.lock(this)
        }
        graceDeadline = 0
        started++
    }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0 && !a.isChangingConfigurations) {
            if (System.currentTimeMillis() > Vault.suppressLockUntil) {
                Vault.lock(this)
            } else {
                graceDeadline = Vault.suppressLockUntil
            }
        }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

/** Base for every screen: blocks screenshots and sends a locked vault back to the PIN screen. */
open class SecureActivity : Activity() {

    protected open val needsUnlock = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** True when the vault is locked and this screen is being closed. */
    protected fun bounceIfLocked(): Boolean {
        if (!needsUnlock || Vault.unlocked) return false
        startActivity(
            Intent(this, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Returned from a picker / share sheet: normal auto-lock applies again.
        Vault.suppressLockUntil = 0
        bounceIfLocked()
    }
}
