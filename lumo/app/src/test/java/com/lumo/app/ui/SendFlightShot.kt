package com.lumo.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Frames of the send / receive flight, to check the paths on a phone-sized screen (images only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
class SendFlightShot {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun frames(outgoing: Boolean, prefix: String, style: FlightStyle = FlightStyle.Flight, times: List<Long> = listOf(150L, 350L, 700L), done: Boolean = false) {
        com.lumo.app.data.Prefs.sendStyle.value = style.name
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LumoTheme(dark = true) {
                val f = remember { Flight("x", outgoing, "image", "photo.jpg", null, showPercent = true).apply { progress = .55f; if (done) { progress = 1f; this.done = true } } }
                Box(Modifier.fillMaxSize().background(C.Bg)) {
                    Text(if (outgoing) "You (sending)" else "Them (receiving)", color = C.Text, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                        FlightLayer(listOf(f), Rect(0f, h * 0.12f, w, h * 0.86f)) {}
                    }
                }
            }
        }
        times.forEachIndexed { i, ms ->
            rule.mainClock.advanceTimeBy(if (i == 0) ms else ms - times[i - 1])
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            val out = File("build/shots").apply { mkdirs() }
            File(out, "$prefix-${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    @Test fun sending() = frames(true, "flight-send")
    @Test fun receiving() = frames(false, "flight-recv")

    private val t = listOf(250L, 600L, 1150L)
    @Test fun styleFlight() = frames(true, "style-1Flight", FlightStyle.Flight, t, done = true)
    @Test fun stylePlane() = frames(true, "style-2Plane", FlightStyle.Plane, t, done = true)
    @Test fun styleComet() = frames(true, "style-3Comet", FlightStyle.Comet, t, done = true)
    @Test fun styleBubble() = frames(true, "style-4Bubble", FlightStyle.Bubble, t, done = true)
    @Test fun stylePortal() = frames(true, "style-5Portal", FlightStyle.Portal, t, done = true)
}
