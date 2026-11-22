package com.lumo.app.call

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The one light colour: Lumo's own soft white (the app's light background). */
val SoftWhite = Color(0xFFF7F7FA)

class SoftLightState {
    var on by mutableStateOf(false)
}

/**
 * Video-call light: a small soft-white LED bar at the top centre, just under the front camera,
 * with a gentle glow, and full screen brightness while it is on (restored when it goes off).
 */
@Composable
fun SoftLightOverlay(state: SoftLightState) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(state.on) {
        val w = activity?.window
        if (state.on) w?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose {
            w?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    val level by animateFloatAsState(if (state.on) 1f else 0f, tween(300), label = "light")
    if (level == 0f) return
    val topInset = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    Canvas(Modifier.fillMaxSize()) {
        val w = 150.dp.toPx()
        val h = 22.dp.toPx()
        val top = topInset + 14.dp.toPx()
        val l = size.width / 2 - w / 2
        drawIntoCanvas { c ->
            fun pass(grow: Float, blur: Float, alpha: Float) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = SoftWhite.copy(alpha = alpha * level).toArgb()
                    if (blur > 0f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                val r = h / 2 + grow
                c.nativeCanvas.drawRoundRect(l - grow, top - grow, l + w + grow, top + h + grow, r, r, p)
            }
            pass(30.dp.toPx(), 50.dp.toPx(), 0.5f)  // soft glow around the bar
            pass(6.dp.toPx(), 10.dp.toPx(), 0.8f)   // halo
            pass(0f, 1.5f.dp.toPx(), 1f)             // the LED bar: one even colour
        }
    }
}

/** 💡 on / off. */
@Composable
fun SoftLightButton(state: SoftLightState, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape)
            .background(if (state.on) SoftWhite else Color.Black.copy(alpha = 0.35f))
            .clickable { state.on = !state.on },
        contentAlignment = Alignment.Center,
    ) { Text("💡", fontSize = 20.sp) }
}
