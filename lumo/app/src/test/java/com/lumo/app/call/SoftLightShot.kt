package com.lumo.app.call

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the soft light over a dark call screen to a PNG, to look at the design (not a pass/fail test). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
class SoftLightShot {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(tone: LightTone, file: String) {
        rule.setContent {
            val s = remember { SoftLightState().apply { on = true; this.tone = tone } }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2433), Color(0xFF101014))))) {
                Text("Video call", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
                SoftLightOverlay(s)
                SoftLightButton(s, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 40.dp))
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(out, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun warm() = shot(LightTone.Warm, "light-warm.png")
    @Test fun cool() = shot(LightTone.Cool, "light-cool.png")
}
