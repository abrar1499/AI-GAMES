package com.lumo.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.automirrored.rounded.Send
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

/** The five send/receive animation styles (chosen in the profile menu). */
enum class FlightStyle(val label: String, val emoji: String) {
    Flight("Flight", "\u2728"), Plane("Paper plane", "\u2708\uFE0F"), Comet("Comet", "\u2604\uFE0F"),
    Bubble("Bubble", "\uD83E\uDEE7"), Portal("Portal", "\uD83C\uDF00"),
}

@Composable
private fun FlightItem(f: Flight, w: Float, h: Float, list: Rect, onLanded: (Flight) -> Unit) {
    val style = FlightStyle.entries.firstOrNull { it.name == com.lumo.app.data.Prefs.sendStyle.collectAsState().value } ?: FlightStyle.Portal
    val d = LocalDensity.current
    val cardW = with(d) { 170.dp.toPx() }
    val cardH = with(d) { cardHeight(f.type).toPx() }
    val m = with(d) { 12.dp.toPx() }
    val endY = list.bottom - cardH - m
    val endX = if (f.outgoing) list.right - cardW - m else list.left + m
    val start = if (f.outgoing) Offset(w - with(d) { 56.dp.toPx() }, h - with(d) { 70.dp.toPx() }) else Offset(-cardW * .7f, list.top + 20)
    val pts = when {
        style == FlightStyle.Plane && f.outgoing -> listOf(Pt(start.x, start.y, .5f, 0f), Pt(w * .2f, h * .55f, 1f, 0f), Pt(w * .7f - cardW / 2, endY + 10, 1f, 0f), Pt(endX, endY, 1f, 0f))
        style == FlightStyle.Plane -> listOf(Pt(-40f, list.top + 30, .7f, 0f), Pt(w * .5f, list.top + 60, 1f, 0f), Pt(w - cardW * .6f, list.top + 160, 1f, 0f), Pt(w * .4f - cardW / 2, endY - 30, 1f, 0f), Pt(endX, endY, 1f, 0f))
        style == FlightStyle.Portal && f.outgoing -> listOf(Pt(start.x - cardW * .3f, start.y - cardH * .4f, .1f, 180f), Pt(start.x - cardW * .9f, start.y - cardH * 1.4f, .6f, 40f), Pt(endX, endY, 1f, 0f))
        style == FlightStyle.Portal -> listOf(Pt(m, list.top + 30, .1f, -180f), Pt(w * .45f, list.top + 90, .7f, -10f), Pt(w * .4f - cardW / 2, endY - 50, .95f, 0f), Pt(endX, endY, 1f, 0f))
        f.outgoing -> listOf(
            Pt(start.x, start.y, .22f, -14f), Pt(w * .62f - cardW / 2, h * .62f, .7f, -6f),
            Pt(w * .58f - cardW / 2, endY - 40, 1.04f, 2f), Pt(endX, endY, 1f, 0f),
        )
        else -> listOf(
            Pt(start.x, start.y, .3f, 10f), Pt(w * .2f, list.top + 50, .55f, 4f), Pt(w - cardW - m * 3, list.top + 80, .75f, -4f),
            Pt(w * .45f - cardW / 2, endY - 60, .95f, -2f), Pt(endX, endY, 1f, 0f),
        )
    }

    // Flight position follows the real transfer, smoothed, and never rushes past ~1 s.
    val target = if (f.done) 1f else f.progress * 0.92f
    val shown by animateFloatAsState(target, tween(if (f.done) 450 else 700, easing = LinearEasing), label = "p")
    val minClock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { minClock.animateTo(1f, tween(1000, easing = LinearEasing)) }
    val t = minOf(shown, minClock.value)
    var p = along(pts, easeInOut(t))
    if (style == FlightStyle.Bubble) p = p.copy(x = p.x + kotlin.math.sin(t * Math.PI.toFloat() * 5) * with(d) { 24.dp.toPx() } * (1 - t))
    val arrived = f.done && t >= 0.999f

    // Direction of travel, for the paper plane's nose.
    var heading by remember { mutableFloatStateOf(0f) }
    var last by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(p.x, p.y) {
        val l = last
        if (l != null && (kotlin.math.abs(p.x - l.x) + kotlin.math.abs(p.y - l.y)) > 2f) {
            heading = Math.toDegrees(kotlin.math.atan2((p.y - l.y).toDouble(), (p.x - l.x).toDouble())).toFloat()
        }
        last = Offset(p.x, p.y)
    }

    // Landing: shapes turn into the card (flip, pop), a burst or ripple, then the real bubble.
    val reveal = remember { Animatable(if (style == FlightStyle.Flight || style == FlightStyle.Portal) 1f else 0f) }
    val bounce = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(arrived) {
        if (!arrived) return@LaunchedEffect
        if (style == FlightStyle.Comet || style == FlightStyle.Bubble) launch { burst.animateTo(1f, tween(420)) }
        if (reveal.value < 1f) reveal.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        bounce.animateTo(1.06f, tween(110)); bounce.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
        if (!f.outgoing) ripple.animateTo(1f, tween(480))
        fade.animateTo(0f, tween(160))
        onLanded(f)
    }

    // Trail and effects.
    val trail = remember { mutableStateListOf<Pair<Offset, Long>>() }
    val now = withFrameMillisState()
    val centre = Offset(p.x + cardW * p.s / 2, p.y + cardH * p.s / 2)
    val shapeCentre = if (style == FlightStyle.Flight || style == FlightStyle.Portal) Offset(p.x + cardW * p.s / 2, p.y + cardH * p.s * .7f)
        else Offset(p.x + cardW / 2, p.y + cardH / 2)
    LaunchedEffect(p.x.roundToInt() / 5, p.y.roundToInt() / 5) {
        if (t < .97f && style != FlightStyle.Bubble) trail += shapeCentre to System.currentTimeMillis()
        while (trail.size > (if (style == FlightStyle.Comet) 70 else 40)) trail.removeAt(0)
    }
    val portalSpin = rememberInfiniteTransition(label = "portal").animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spin")
    Canvas(Modifier.fillMaxSize()) {
        val comet = style == FlightStyle.Comet
        val life = if (comet) 600f else 550f
        trail.forEach { (o, at) ->
            val age = (now - at) / life
            val r0 = if (comet) 14f else 9f
            if (age in 0f..1f) drawCircle(
                Brush.radialGradient(listOf(Color.White, (if (comet) Color(0xFF9AD8FF) else C.Accent).copy(alpha = .7f), Color.Transparent), o, r0 * (1 - age) + 2f),
                radius = r0 * (1 - age) + 2f, center = o, alpha = 1 - age,
            )
        }
        // Portal: a spinning rainbow ring where the item comes out.
        if (style == FlightStyle.Portal && t < .45f) {
            val k = (if (t < .1f) t / .1f else 1f) * (1 - ((t - .3f).coerceAtLeast(0f) / .15f)).coerceIn(0f, 1f)
            val c = if (f.outgoing) Offset(start.x - 10.dp.toPx(), start.y - 10.dp.toPx()) else Offset(m + 30.dp.toPx(), list.top + 50.dp.toPx())
            rotate(portalSpin.value, c) {
                drawCircle(
                    Brush.sweepGradient(listOf(Color(0xFF3FA9F5), Color(0xFF7C6CFF), Color(0xFFFF6BC1), Color(0xFF3FA9F5)), c),
                    radius = 32.dp.toPx() * k, center = c, style = Stroke(9.dp.toPx() * k),
                )
            }
        }
        // Comet flash / bubble droplets on arrival.
        if (burst.value in 0.001f..0.999f) {
            val b = burst.value
            val o = Offset(endX + cardW / 2, endY + cardH / 2)
            if (comet) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 1 - b), C.Accent.copy(alpha = (1 - b) * .5f), Color.Transparent), o, 140f * (0.3f + b)), 140f * (0.3f + b), o)
            for (i in 0 until 14) {
                val ang = i / 14f * 2 * Math.PI.toFloat()
                val dist = 90.dp.toPx() * b
                drawCircle(
                    if (comet) Color.White else Color(0xFFAADCFF),
                    radius = 4.dp.toPx() * (1 - b) + 1f,
                    center = Offset(o.x + kotlin.math.cos(ang) * dist, o.y + kotlin.math.sin(ang) * dist),
                    alpha = 1 - b,
                )
            }
        }
        if (ripple.value > 0f && ripple.value < 1f) {
            val r = ripple.value
            drawRoundRect(
                color = C.Accent.copy(alpha = (1 - r) * .9f),
                topLeft = Offset(endX - cardW * r * .12f, endY - cardH * r * .12f),
                size = androidx.compose.ui.geometry.Size(cardW * (1 + r * .24f), cardH * (1 + r * .24f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
        }
    }

    val percent = if (f.showPercent && !arrived) (if (f.done) 1f else f.progress) else null
    val showCard = style == FlightStyle.Flight || style == FlightStyle.Portal || reveal.value > 0f
    if (showCard) {
        val reveal01 = reveal.value
        FlightCard(
            f,
            Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (style == FlightStyle.Plane) .5f else 0f, if (style == FlightStyle.Plane) .5f else 0f)
                    val pop = if (style == FlightStyle.Comet || style == FlightStyle.Bubble) .3f + .7f * reveal01 else 1f
                    scaleX = p.s * bounce.value * pop; scaleY = p.s * bounce.value * pop
                    if (style == FlightStyle.Plane) { rotationY = 90f * (1 - reveal01); cameraDistance = 12f * density }
                    else rotationZ = p.r
                    alpha = fade.value * (if (style == FlightStyle.Flight || style == FlightStyle.Portal) 1f else reveal01)
                },
            percent = if (style == FlightStyle.Flight || style == FlightStyle.Portal) percent else null,
        )
    }
    // In-flight shapes for the other styles, with their own % badge.
    if (reveal.value < 1f && style != FlightStyle.Flight && style != FlightStyle.Portal) {
        val size = when (style) { FlightStyle.Plane -> 46.dp; FlightStyle.Comet -> 22.dp; else -> 92.dp }
        val sizePx = with(d) { size.toPx() }
        Box(
            Modifier.offset { IntOffset((p.x + cardW / 2 - sizePx / 2).roundToInt(), (p.y + cardH / 2 - sizePx / 2).roundToInt()) }
                .graphicsLayer { alpha = 1f - reveal.value },
            contentAlignment = Alignment.Center,
        ) {
            when (style) {
                FlightStyle.Plane -> Icon(
                    Icons.AutoMirrored.Rounded.Send, null, tint = Color.White,
                    modifier = Modifier.size(size).graphicsLayer { rotationZ = heading }
                        .shadow(12.dp, androidx.compose.foundation.shape.CircleShape, ambientColor = C.Accent2, spotColor = C.Accent2),
                )
                FlightStyle.Comet -> Box(
                    Modifier.size(size).background(
                        Brush.radialGradient(listOf(Color.White, Color(0xFF9AD8FF), Color.Transparent)),
                        androidx.compose.foundation.shape.CircleShape,
                    ),
                )
                else -> Box(
                    Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(C.Accent.copy(alpha = .14f))
                        .border(2.dp, Brush.sweepGradient(listOf(Color(0xCCFFFFFF), Color(0x993FA9F5), Color(0x99FF6BC1), Color(0xCCFFFFFF))), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = f.thumb
                    if (img != null) Image(img, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape))
                    Box(Modifier.size(28.dp).align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp)
                        .background(Color.White.copy(alpha = .5f), androidx.compose.foundation.shape.CircleShape))
                }
            }
            if (percent != null) Text(
                "${(percent * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = 18.dp)
                    .background(C.Accent.copy(alpha = .85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
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

/**
 * "Send animation" picker: the five styles, and each choice plays at once on a mini chat:
 * your send (bottom-right up) then their side (top-left in).
 */
@Composable
fun SendStylePicker(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current by com.lumo.app.data.Prefs.sendStyle.collectAsState()
    var run by remember { mutableIntStateOf(0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(26.dp)).background(C.Surface).padding(18.dp),
        ) {
            Text("Send animation", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Tap a style to see it", color = C.Sub, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            // Mini chat where the preview plays.
            Box(
                Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(18.dp)).background(C.Bg),
            ) {
                val flights = remember(run, current) {
                    mutableStateListOf(
                        Flight("o$run", true, "image", "Photo", null, showPercent = true),
                    )
                }
                LaunchedEffect(run, current) {
                    val out = flights.first()
                    for (i in 1..10) { kotlinx.coroutines.delay(90); out.progress = i / 10f }
                    out.done = true
                    kotlinx.coroutines.delay(1500)
                    val inc = Flight("i$run", false, "image", "Photo", null, showPercent = true)
                    flights += inc
                    for (i in 1..10) { kotlinx.coroutines.delay(100); inc.progress = i / 10f }
                    inc.done = true
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                    FlightLayer(flights.toList(), Rect(0f, h * .08f, w, h * .96f)) { flights.remove(it) }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlightStyle.entries.forEach { s ->
                val sel = s.name == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (sel) C.Accent.copy(alpha = .15f) else Color.Transparent)
                        .clickable { com.lumo.app.data.Prefs.setSendStyle(context, s.name); run++ }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(s.label, color = C.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (sel) Text("✓", color = C.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Done", color = C.Accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss).padding(10.dp))
        }
    }
}
