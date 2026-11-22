package com.lumo.app.call

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Design previews of small LED light shapes (images only, not pass/fail). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
class LightShapesShot {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val warm = Color(0xFFFFF3E4) // soft white

    private fun DrawScope.led(l: Float, t: Float, r: Float, b: Float, radius: Float) = drawIntoCanvas { c ->
        fun pass(grow: Float, blur: Float, alpha: Float) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = warm.copy(alpha = alpha).toArgb()
                if (blur > 0) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            c.nativeCanvas.drawRoundRect(l - grow, t - grow, r + grow, b + grow, radius + grow, radius + grow, p)
        }
        pass(30.dp.toPx(), 50.dp.toPx(), 0.55f)   // soft glow around it
        pass(6.dp.toPx(), 10.dp.toPx(), 0.85f)    // halo
        pass(0f, 1.5f.dp.toPx(), 1f)               // the LED itself
        val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = 0.55f).toArgb() }
        c.nativeCanvas.drawRoundRect(l + (r - l) * 0.15f, t + (b - t) * 0.25f, r - (r - l) * 0.15f, b - (b - t) * 0.25f, radius, radius, shine)
    }

    private fun shot(file: String, draw: DrawScope.() -> Unit) {
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2433), Color(0xFF101014))))) {
                Text("Video call", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
                Canvas(Modifier.fillMaxSize(), onDraw = draw)
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(out, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 1. A small pill-shaped LED bar at the top, right under the front camera. */
    @Test fun bar() = shot("option-1-top-bar.png") {
        val w = 150.dp.toPx(); val h = 22.dp.toPx(); val top = 46.dp.toPx()
        led(size.width / 2 - w / 2, top, size.width / 2 + w / 2, top + h, h / 2)
    }

    /** 2. A small rounded-square softbox at the top centre. */
    @Test fun panel() = shot("option-2-softbox.png") {
        val w = 120.dp.toPx(); val h = 70.dp.toPx(); val top = 40.dp.toPx()
        led(size.width / 2 - w / 2, top, size.width / 2 + w / 2, top + h, 20.dp.toPx())
    }

    /** 3. Two slim LED strips, one on each side at eye level. */
    @Test fun sides() = shot("option-3-side-strips.png") {
        val w = 14.dp.toPx(); val h = 170.dp.toPx(); val top = 130.dp.toPx(); val m = 14.dp.toPx()
        led(m, top, m + w, top + h, w / 2)
        led(size.width - m - w, top, size.width - m, top + h, w / 2)
    }
}
