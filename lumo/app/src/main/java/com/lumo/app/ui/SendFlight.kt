package com.lumo.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * One item flying across the chat. [outgoing]: lifts off bottom-right by the send button and
 * climbs into place (upload %). Incoming: enters top-left, glides right, then drops into place
 * (download %). [progress] is the real transfer; the flight follows it, never faster than ~1 s.
 */
class Flight(
    val key: String,
    val outgoing: Boolean,
    val type: String,
    val name: String,
    thumb: ImageBitmap?,
    /** Shows a % ring only when there is a real transfer to follow. */
    val showPercent: Boolean,
) {
    var thumb by mutableStateOf(thumb)
    var progress by mutableFloatStateOf(0f)
    var done by mutableStateOf(false)
    /** The file id of the message this becomes, so its bubble stays hidden until landing. */
    var fileId by mutableStateOf<String?>(null)
}

private data class Pt(val x: Float, val y: Float, val s: Float, val r: Float)

private fun along(pts: List<Pt>, t: Float): Pt {
    val f = t.coerceIn(0f, 1f) * (pts.size - 1)
    val i = f.toInt().coerceAtMost(pts.size - 2)
    val k = f - i
    val a = pts[i]; val b = pts[i + 1]
    return Pt(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.s + (b.s - a.s) * k, a.r + (b.r - a.r) * k)
}

private fun easeInOut(t: Float) = if (t < .5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2

/** Draws all active flights over the chat. [list] is where the messages are drawn (in this layer's coordinates). */
@Composable
fun FlightLayer(flights: List<Flight>, list: Rect, onLanded: (Flight) -> Unit) {
    if (list.isEmpty) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        flights.forEach { f -> key(f.key) { FlightItem(f, w, h, list, onLanded) } }
    }
}

@Composable
private fun FlightItem(f: Flight, w: Float, h: Float, list: Rect, onLanded: (Flight) -> Unit) {
    val d = LocalDensity.current
    val cardW = with(d) { 170.dp.toPx() }
    val cardH = with(d) { cardHeight(f.type).toPx() }
    val m = with(d) { 12.dp.toPx() }
    val endY = list.bottom - cardH - m
    val pts = if (f.outgoing) listOf(
        Pt(w - with(d) { 56.dp.toPx() }, h - with(d) { 70.dp.toPx() }, .22f, -14f),
        Pt(w * .62f - cardW / 2, h * .62f, .7f, -6f),
        Pt(w * .58f - cardW / 2, endY - 40, 1.04f, 2f),
        Pt(list.right - cardW - m, endY, 1f, 0f),
    ) else listOf(
        Pt(-cardW * .7f, list.top + 20, .3f, 10f),
        Pt(w * .2f, list.top + 50, .55f, 4f),
        Pt(w - cardW - m * 3, list.top + 80, .75f, -4f),
        Pt(w * .45f - cardW / 2, endY - 60, .95f, -2f),
        Pt(list.left + m, endY, 1f, 0f),
    )

    // Flight position follows the real transfer, smoothed, and never rushes past ~1 s.
    val target = if (f.done) 1f else f.progress * 0.92f
    val shown by animateFloatAsState(target, tween(if (f.done) 450 else 700, easing = LinearEasing), label = "p")
    val minClock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { minClock.animateTo(1f, tween(1000, easing = LinearEasing)) }
    val t = minOf(shown, minClock.value)
    val p = along(pts, easeInOut(t))

    // Landing: a small bounce, a ripple on arrival, then hand over to the real bubble.
    val bounce = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(f.done, t >= 0.999f) {
        if (f.done && t >= 0.999f) {
            bounce.animateTo(1.06f, tween(110)); bounce.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
            if (!f.outgoing) ripple.animateTo(1f, tween(480))
            fade.animateTo(0f, tween(160))
            onLanded(f)
        }
    }

    // Glowing trail behind the card.
    val trail = remember { mutableStateListOf<Pair<Offset, Long>>() }
    val now = withFrameMillisState()
    LaunchedEffect(p.x.roundToInt() / 6, p.y.roundToInt() / 6) {
        if (t < .95f) trail += Offset(p.x + cardW * p.s / 2, p.y + cardH * p.s * .7f) to System.currentTimeMillis()
        while (trail.size > 40) trail.removeAt(0)
    }
    Canvas(Modifier.fillMaxSize()) {
        trail.forEach { (o, at) ->
            val age = (now - at) / 550f
            if (age in 0f..1f) drawCircle(
                Brush.radialGradient(listOf(Color.White, C.Accent.copy(alpha = .7f), Color.Transparent), o, 9f * (1 - age) + 2f),
                radius = 9f * (1 - age) + 2f, center = o, alpha = 1 - age,
            )
        }
        if (ripple.value > 0f && ripple.value < 1f) {
            val r = ripple.value
            drawRoundRect(
                color = C.Accent.copy(alpha = (1 - r) * .9f),
                topLeft = Offset(p.x - cardW * r * .12f, p.y - cardH * r * .12f),
                size = androidx.compose.ui.geometry.Size(cardW * (1 + r * .24f), cardH * (1 + r * .24f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
        }
    }

    FlightCard(
        f,
        Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
            .graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = p.s * bounce.value; scaleY = p.s * bounce.value
                rotationZ = p.r; alpha = fade.value
            },
        percent = if (f.showPercent && !(f.done && t >= .999f)) (if (f.done) 1f else f.progress) else null,
    )
}

/** Milliseconds clock that ticks every frame while a flight is on screen (for the fading trail). */
@Composable
private fun withFrameMillisState(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { withFrameMillis { now = System.currentTimeMillis() } } }
    return now
}

private fun cardHeight(type: String) = when (type) {
    "image", "video" -> 120.dp
    "audio" -> 60.dp
    else -> 74.dp
}

@Composable
private fun FlightCard(f: Flight, modifier: Modifier, percent: Float?) {
    Box(
        modifier.width(170.dp).height(cardHeight(f.type))
            .shadow(16.dp, RoundedCornerShape(16.dp), ambientColor = C.Accent, spotColor = C.Accent)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp)),
    ) {
        when (f.type) {
            "image" -> {
                val img = f.thumb
                if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF7BB6FF), Color(0xFFC8A7FF)))))
            }
            "video" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2A4A), Color(0xFF3A1F4F)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            "audio" -> Row(Modifier.fillMaxSize().background(C.AccentBrush).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Mic, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Voice message", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            else -> Row(Modifier.fillMaxSize().background(Color(0xFFF4F3FA)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = Color(0xFFE5484D), modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(8.dp))
                Text(f.name, color = Color(0xFF17151F), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (percent != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .3f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val sw = 4.5.dp.toPx()
                drawArc(Color.White.copy(alpha = .25f), 0f, 360f, false, style = Stroke(sw))
                drawArc(Color.White, -90f, 360f * percent, false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text("${(percent * 100).roundToInt()}%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
