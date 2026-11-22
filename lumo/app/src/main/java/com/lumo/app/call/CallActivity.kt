package com.lumo.app.call

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.lumo.app.ui.Avatar
import com.lumo.app.ui.C
import com.lumo.app.ui.LumoTheme
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

class CallActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)

    private val acceptPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) CallManager.accept() else CallManager.decline()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        handle(intent)
        setContent {
            LumoTheme {
                val state by CallManager.state.collectAsState()
                LaunchedEffect(state.phase) { if (state.phase == Phase.Idle) finishAndRemoveTask() }
                CallScreen(state, inPip, onAccept = ::accept)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACCEPT) accept()
    }

    private fun accept() {
        if (CallManager.state.value.phase != Phase.Incoming) return
        // Answering from the lock screen: ask to unlock only for video, voice answers straight away.
        if (CallManager.state.value.video) {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (CallManager.state.value.video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) CallManager.accept() else acceptPermissions.launch(needed.toTypedArray())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = CallManager.state.value
        if (s.video && s.phase == Phase.Connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    companion object {
        const val ACCEPT = "com.lumo.app.ACCEPT"
    }
}

@Composable
private fun VideoView(track: VideoTrack?, mirror: Boolean, overlay: Boolean, corner: Float, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(CallManager.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL, RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                if (corner > 0f) {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, corner)
                    }
                    clipToOutline = true
                }
                view = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { it.release() },
    )
    DisposableEffect(track, view) {
        val v = view
        if (track != null && v != null) runCatching { track.addSink(v) }
        onDispose { if (track != null && v != null) runCatching { track.removeSink(v) } }
    }
}

@Composable
private fun CallScreen(s: CallState, pip: Boolean, onAccept: () -> Unit) {
    val local by CallManager.localVideo.collectAsState()
    val remote by CallManager.remoteVideo.collectAsState()
    val showRemote = s.video && remote != null && s.peerCamOn && s.phase == Phase.Connected
    var controls by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(controls, showRemote, tick) {
        if (controls && showRemote) { delay(5000); controls = false }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controls = !controls; tick++
            },
    ) {
        // Backdrop for ringing, voice calls and when the other side turns the camera off.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1640), Color(0xFF0A0A12), Color.Black))))
        if (s.video && remote != null) {
            AnimatedVisibility(showRemote, enter = fadeIn(tween(500)), exit = fadeOut()) {
                VideoView(remote, mirror = false, overlay = false, corner = 0f, modifier = Modifier.fillMaxSize())
            }
        }

        val showLocal = s.video && local != null && s.camOn && s.phase != Phase.Incoming && !pip
        if (showLocal && !showRemote) LocalPreview(local, s, small = false)

        if (!showRemote) PeerHeader(s, Modifier.align(Alignment.TopCenter).padding(top = if (pip) 12.dp else 120.dp), pip)
        else AnimatedVisibility(controls && !pip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding().padding(top = 16.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.peerName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                StatusText(s)
            }
        }

        if (showLocal && showRemote) LocalPreview(local, s, small = true)

        if (!pip) {
            if (s.phase == Phase.Incoming) IncomingControls(s, onAccept, Modifier.align(Alignment.BottomCenter))
            else AnimatedVisibility(
                controls || !showRemote, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter),
            ) { InCallControls(s) }
        }
    }
}

@Composable
private fun PeerHeader(s: CallState, modifier: Modifier, pip: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring")
    val ringAlpha by pulse.animateFloat(0.45f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "alpha")
    val ringing = s.phase == Phase.Incoming || s.phase == Phase.Outgoing
    val size = if (pip) 56.dp else 132.dp
    Column(modifier.statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (ringing && !pip) Box(
                Modifier.size(size).scale(ring).clip(CircleShape).background(C.Accent.copy(alpha = ringAlpha)),
            )
            Avatar(s.peerName.ifBlank { s.peerUsername }, s.peerUid, size)
        }
        if (!pip) {
            Spacer(Modifier.height(28.dp))
            Text(s.peerName.ifBlank { s.peerUsername }, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            StatusText(s)
        }
    }
}

@Composable
private fun StatusText(s: CallState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) { while (s.phase == Phase.Connected) { now = System.currentTimeMillis(); delay(500) } }
    val text = when {
        s.reconnecting -> "Reconnecting…"
        s.phase == Phase.Outgoing -> "Ringing…"
        s.phase == Phase.Incoming -> if (s.video) "Incoming video call" else "Incoming voice call"
        s.phase == Phase.Connecting -> "Connecting…"
        s.phase == Phase.Connected -> {
            val sec = ((now - s.connectedAt) / 1000).coerceAtLeast(0)
            if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)
        }
        s.phase == Phase.Ended -> s.endReason
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.phase == Phase.Connected && !s.reconnecting) {
            Icon(Icons.Rounded.Lock, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
    }
}

@Composable
private fun BoxScope.LocalPreview(track: VideoTrack?, s: CallState, small: Boolean) {
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    val w = 112.dp
    if (!small) {
        // Before the other side's video arrives, show yourself full screen like a mirror.
        VideoView(track, mirror = s.frontCamera, overlay = false, corner = 0f, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        return
    }
    Box(
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 16.dp)
            .offset { offset }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset = IntOffset(offset.x + drag.x.roundToInt(), offset.y + drag.y.roundToInt())
                }
            }
            .size(width = w, height = w * 16f / 9f),
    ) {
        VideoView(track, mirror = s.frontCamera, overlay = true, corner = with(density) { 18.dp.toPx() }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, active: Boolean, bg: Color? = null, size: Int = 62, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size.dp).clip(CircleShape)
                .background(bg ?: if (active) Color.White else Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (bg == null && active) Color.Black else Color.White, modifier = Modifier.size((size * 0.42f).dp))
        }
    }
}

@Composable
private fun InCallControls(s: CallState) {
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
            .navigationBarsPadding().padding(top = 40.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(40.dp)).background(Color.White.copy(alpha = 0.08f)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(if (s.speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeDown, "Speaker", s.speakerOn) { CallManager.toggleSpeaker() }
            if (s.video) {
                RoundButton(if (s.camOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Camera", !s.camOn) { CallManager.toggleCamera() }
                RoundButton(Icons.Rounded.Cameraswitch, "Flip", false) { CallManager.switchCamera() }
            }
            RoundButton(if (s.micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, "Mute", !s.micOn) { CallManager.toggleMic() }
            RoundButton(Icons.Rounded.CallEnd, "End", false, bg = C.Red) { CallManager.hangUp() }
        }
    }
}

@Composable
private fun IncomingControls(s: CallState, onAccept: () -> Unit, modifier: Modifier) {
    val breathe = rememberInfiniteTransition(label = "breathe")
    val pulse by breathe.animateFloat(1f, 1.08f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = 48.dp, end = 48.dp, bottom = 64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RoundButton(Icons.Rounded.CallEnd, "Decline", false, bg = C.Red, size = 76) { CallManager.decline() }
            Spacer(Modifier.height(10.dp))
            Text("Decline", color = Color.White.copy(alpha = 0.8f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.scale(pulse)) {
                RoundButton(if (s.video) Icons.Rounded.Videocam else Icons.Rounded.Call, "Accept", false, bg = C.Green, size = 76, onClick = onAccept)
            }
            Spacer(Modifier.height(10.dp))
            Text("Accept", color = Color.White.copy(alpha = 0.8f))
        }
    }
}
