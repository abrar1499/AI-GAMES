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

    private fun frames(outgoing: Boolean, prefix: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LumoTheme(dark = true) {
                val f = remember { Flight("x", outgoing, "image", "photo.jpg", null, showPercent = true).apply { progress = .55f } }
                Box(Modifier.fillMaxSize().background(C.Bg)) {
                    Text(if (outgoing) "You (sending)" else "Them (receiving)", color = C.Text, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                        FlightLayer(listOf(f), Rect(0f, h * 0.12f, w, h * 0.86f)) {}
                    }
                }
            }
        }
        listOf(150L, 350L, 700L).forEachIndexed { i, ms ->
            rule.mainClock.advanceTimeBy(if (i == 0) ms else ms - listOf(150L, 350L, 700L)[i - 1])
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
}
