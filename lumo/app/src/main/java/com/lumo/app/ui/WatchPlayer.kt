package com.lumo.app.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lumo.app.data.Attachment
import com.lumo.app.data.Files
import com.lumo.app.data.Repo
import com.lumo.app.data.Watch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Floating, borderless video you can drag anywhere. Play, pause and seek are shared live with
 * the other person; their photo lights up under the video while they are watching too.
 */
@OptIn(UnstableApi::class)
@Composable
fun WatchPlayer(chatId: String, att: Attachment, otherUid: String, otherName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenW = LocalConfiguration.current.screenWidthDp.dp

    val file by produceState<java.io.File?>(null, att.fileId) {
        value = runCatching { Files.get(context, chatId, att.fileId, att.pieces) }.getOrNull()
    }
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    var playing by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(0.5f, 2.4f)
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
            Watch.leave(chatId)
        }
    }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare()
    }
    LaunchedEffect(Unit) { while (true) { position = player.currentPosition; duration = player.duration.coerceAtLeast(0); delay(250) } }
    LaunchedEffect(Unit) { while (true) { Watch.heartbeat(chatId); delay(5000) } }

    // ---- follow the other person's play / pause / seek
    val session by remember { Watch.listen(chatId) }.collectAsState(initial = null)
    var appliedSeq by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(session, file) {
        val s = session ?: return@LaunchedEffect
        if (file == null || s.fileId != att.fileId) return@LaunchedEffect
        if (appliedSeq == -1L) {
            // Joining: pick up where the other person is (or stay paused at the start).
            appliedSeq = s.seq
            if (s.by != Repo.myUid && s.seq > 0) apply(player, s)
            return@LaunchedEffect
        }
        if (s.seq > appliedSeq && s.by != Repo.myUid) apply(player, s)
        appliedSeq = maxOf(appliedSeq, s.seq)
    }
    fun control(play: Boolean, pos: Long = player.currentPosition) {
        player.seekTo(pos)
        player.playWhenReady = play
        Watch.control(chatId, att.fileId, play, pos)
    }

    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(2000) } }
    val otherWatching = session?.viewers?.get(otherUid)?.let { now - it < 15_000 } == true

    // ---- floating card: drag to move, double-tap to resize
    var big by remember { mutableStateOf(true) }
    val width by animateDpAsState(if (big) screenW - 24.dp else screenW * 0.58f, label = "w")
    var offset by remember { mutableStateOf(IntOffset(with(density) { 12.dp.roundToPx() }, with(density) { 90.dp.roundToPx() })) }
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls, playing) { if (controls && playing) { delay(3000); controls = false } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset { offset }.width(width)
                .shadow(28.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset = IntOffset(offset.x + drag.x.roundToInt(), offset.y + drag.y.roundToInt())
                    }
                },
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(aspect)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { big = !big })
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (file == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.5.dp)

                androidx.compose.animation.AnimatedVisibility(
                    visible = controls || !playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize(),
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.55f))))) {
                        Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                            RoundIcon(if (big) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull) { big = !big }
                            Spacer(Modifier.width(6.dp))
                            RoundIcon(Icons.Rounded.Close) { onClose() }
                        }
                        Box(
                            Modifier.align(Alignment.Center).size(if (big) 64.dp else 48.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f))
                                .clickable(enabled = file != null) { control(!playing) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                                tint = Color.White, modifier = Modifier.size(if (big) 38.dp else 28.dp))
                        }
                        Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(clock(scrubbing?.let { (it * duration).toLong() } ?: position), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = scrubbing ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                                onValueChange = { scrubbing = it },
                                onValueChangeFinished = {
                                    scrubbing?.let { control(playing, (it * duration).toLong()) }
                                    scrubbing = null
                                },
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text(clock(duration), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            // Who is watching: you and them, glowing while they are here.
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF0E0E14)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Viewer(Repo.myName.ifBlank { "Me" }, Repo.myUid, active = true)
                Box(Modifier.offset(x = (-8).dp)) { Viewer(otherName.ifBlank { " " }, otherUid, active = otherWatching) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (otherWatching) "Watching together" else "Watch together", color = Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (otherWatching) "Play and pause move for both of you" else "Waiting for ${otherName.ifBlank { "them" }} to open it…",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1,
                    )
                }
                if (otherWatching) LiveDot()
            }
        }
    }
}

private fun apply(player: ExoPlayer, s: Watch.Session) {
    // Catch up on the time the message spent travelling, so both screens show the same frame.
    val travel = if (s.playing && s.at != null) (System.currentTimeMillis() - s.at).coerceIn(0, 3000) else 0
    player.seekTo(s.positionMs + travel)
    player.playWhenReady = s.playing
}

@Composable
private fun Viewer(name: String, uid: String, active: Boolean) {
    val ring by animateFloatAsState(if (active) 1f else 0f, label = "ring")
    Box(
        Modifier.size(38.dp).clip(CircleShape)
            .border(2.dp, C.Green.copy(alpha = ring), CircleShape).padding(3.dp)
            .alpha(0.45f + 0.55f * ring),
    ) { Avatar(name, uid, 32.dp) }
}

@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(C.Red.copy(alpha = a)))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
