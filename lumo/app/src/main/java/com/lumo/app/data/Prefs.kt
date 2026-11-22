package com.lumo.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Small on-phone settings. */
object Prefs {
    private const val FILE = "lumo"

    /** Hand signs in chats (front camera in the background). On unless turned off in the menu. */
    val handSigns = MutableStateFlow(true)

    /** AI noise cancelling in calls (RNNoise). */
    val noiseAi = MutableStateFlow(false)

    fun load(c: Context) {
        handSigns.value = c.getSharedPreferences(FILE, 0).getBoolean("handSigns", true)
        noiseAi.value = c.getSharedPreferences(FILE, 0).getBoolean("noiseAi", false)
        com.lumo.app.call.Denoiser.enabled = noiseAi.value
    }

    fun setNoiseAi(c: Context, on: Boolean) {
        noiseAi.value = on
        com.lumo.app.call.Denoiser.enabled = on
        c.getSharedPreferences(FILE, 0).edit().putBoolean("noiseAi", on).apply()
    }

    fun setHandSigns(c: Context, on: Boolean) {
        handSigns.value = on
        c.getSharedPreferences(FILE, 0).edit().putBoolean("handSigns", on).apply()
    }

    /** True if we already asked for the camera once (then we never nag again). */
    fun askedCamera(c: Context): Boolean {
        val p = c.getSharedPreferences(FILE, 0)
        if (p.getBoolean("askedCamera", false)) return true
        p.edit().putBoolean("askedCamera", true).apply()
        return false
    }
}
