package com.lumo.app.call

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the soft light: warm flatters skin, cool looks like daylight. */
enum class LightTone(val label: String, val color: Color) {
    Warm("Warm", Color(0xFFFFD3A1)),
    White("Soft white", Color(0xFFFFF3E4)),
    Cool("Cool", Color(0xFFE3EEFF)),
}

class SoftLightState {
    var on by mutableStateOf(false)
    var tone by mutableStateOf(LightTone.White) // soft white by default
}

/**
 * Turns the phone's screen into a soft light for your face: a glowing rounded LED band around
 * the screen's edge and full screen brightness while it is on (restored when it goes off).
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
    val level by animateFloatAsState(if (state.on) 1f else 0f, tween(350), label = "light")
    if (level == 0f) return
    val c = state.tone.color
    Canvas(Modifier.fillMaxSize()) {
        val band = 12.dp.toPx()          // the slim LED strip
        val radius = 42.dp.toPx()
        drawIntoCanvas { canvas ->
            fun ring(width: Float, blur: Float, alpha: Float) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = width
                    color = c.copy(alpha = alpha * level).toArgb()
                    if (blur > 0f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                val inset = band / 2
                canvas.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, radius, radius, paint)
            }
            ring(band * 5f, 60.dp.toPx(), 0.55f)  // wide soft glow spilling inward
            ring(band * 2f, 14.dp.toPx(), 0.8f)   // halo around the strip
            ring(band, 2.dp.toPx(), 1f)           // the bright LED strip itself
        }
    }
}

/** 💡 button; when the light is on, the three colours appear next to it. */
@Composable
fun SoftLightButton(state: SoftLightState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (state.on) state.tone.color else Color.Black.copy(alpha = 0.35f))
                .clickable { state.on = !state.on },
            contentAlignment = Alignment.Center,
        ) { Text("💡", fontSize = 20.sp) }
        if (state.on) {
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LightTone.entries.forEach { t ->
                    val sel = t == state.tone
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(t.color)
                            .border(if (sel) 3.dp else 0.dp, if (sel) Color.White else Color.Transparent, CircleShape)
                            .clickable { state.tone = t },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(state.tone.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
