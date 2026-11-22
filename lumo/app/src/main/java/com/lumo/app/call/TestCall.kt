package com.lumo.app.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumo.app.ui.C
import com.lumo.app.ui.LumoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.*

/** Network speeds you can try in a test call. kbps = the total the connection can carry. */
enum class TestNet(val label: String, val detail: String, val kbps: Int) {
    G2("2G", "40 kbps", 40),
    G3("3G", "150 kbps", 150),
    Slow4G("Slow 4G", "500 kbps", 500),
    G4("4G", "1.5 Mbps", 1500),
    WiFi("Wi-Fi", "4 Mbps", 4000),
}

/**
 * A call to yourself through the exact engine a real call uses: your camera and microphone are
 * encoded, sent, received and decoded on this phone, so what you see and hear is what the other
 * person would get. The chosen network speed caps the data, live.
 */
class TestCallEngine(private val context: Context, private val video: Boolean) {
    data class Stats(val width: Int = 0, val height: Int = 0, val fps: Double = 0.0, val videoKbps: Int = 0, val audioKbps: Int = 0, val connected: Boolean = false)

    val stats = MutableStateFlow(Stats())
    val local = MutableStateFlow<VideoTrack?>(null)
    val remote = MutableStateFlow<VideoTrack?>(null)

    private val main = Handler(Looper.getMainLooper())
    private val f = CallManager.factory()
    private var a: PeerConnection? = null
    private var b: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null
    private var router: AudioRouter? = null
    private var net = TestNet.G4
    private var lastVideoBytes = 0L
    private var lastAudioBytes = 0L
    private var lastAt = 0L

    private fun observer(onIce: (IceCandidate) -> Unit, onTrack: (RtpTransceiver) -> Unit = {}) = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = main.post { onIce(c) }.let {}
        override fun onTrack(t: RtpTransceiver) = main.post { onTrack(t) }.let {}
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            main.post { stats.value = stats.value.copy(connected = s == PeerConnection.PeerConnectionState.CONNECTED) }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(r: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    fun start() {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pa = f.createPeerConnection(config, observer({ c -> b?.addIceCandidate(c) })) ?: return
        val pb = f.createPeerConnection(config, observer({ c -> a?.addIceCandidate(c) }) { t ->
            (t.receiver.track() as? VideoTrack)?.let { remote.value = it }
        }) ?: return
        a = pa; b = pb

        router = AudioRouter(context).also { it.start(speaker = video) }
        val ac = MediaConstraints().apply {
            listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")
                .forEach { mandatory += MediaConstraints.KeyValuePair(it, "true") }
        }
        audioSource = f.createAudioSource(ac)
        audioSender = pa.addTrack(f.createAudioTrack("test-a", audioSource), listOf("test"))
        if (video) startCamera(pa)
        CallManager.preferCodecs(f, pa)

        pa.createOffer(sdp { offer ->
            val tuned = SessionDescription(offer.type, CallManager.tuneSdp(offer.description))
            pa.setLocalDescription(done {}, tuned)
            pb.setRemoteDescription(done {
                pb.createAnswer(sdp { answer ->
                    val ta = SessionDescription(answer.type, CallManager.tuneSdp(answer.description))
                    pb.setLocalDescription(done {}, ta)
                    pa.setRemoteDescription(done { setNetwork(net) }, ta)
                }, MediaConstraints())
            }, tuned)
        }, MediaConstraints())
        main.postDelayed(statsTick, 1000)
    }

    private fun startCamera(pa: PeerConnection) {
        val e = Camera2Enumerator(context)
        val name = e.deviceNames.firstOrNull { e.isFrontFacing(it) } ?: e.deviceNames.firstOrNull() ?: return
        val cam = e.createCapturer(name, null) ?: return
        val (w, h, fps) = CallManager.bestFormat(e, name)
        helper = SurfaceTextureHelper.create("test-camera", CallManager.egl.eglBaseContext)
        val src = f.createVideoSource(false)
        cam.initialize(helper, context, src.capturerObserver)
        cam.startCapture(w, h, fps)
        capturer = cam; videoSource = src
        val track = f.createVideoTrack("test-v", src)
        videoSender = pa.addTrack(track, listOf("test"))
        local.value = track
    }

    /** Caps the data like a real network of that speed: voice keeps its share, video gets the rest. */
    fun setNetwork(n: TestNet) {
        net = n
        val total = n.kbps * 1000
        val audio = minOf(32_000, (total * 0.45).toInt()).coerceAtLeast(8_000)
        val videoMax = (total - audio - total / 10).coerceAtLeast(15_000)
        audioSender?.let { s -> s.parameters = s.parameters.apply { encodings.forEach { it.maxBitrateBps = audio } } }
        videoSender?.let { s ->
            s.parameters = s.parameters.apply {
                degradationPreference = RtpParameters.DegradationPreference.BALANCED
                encodings.forEach { it.maxBitrateBps = videoMax; it.minBitrateBps = minOf(30_000, videoMax) }
            }
        }
        a?.setBitrate(minOf(30_000, total), minOf(600_000, total), total)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val pb = b ?: return
            pb.getStats { report ->
                var vb = 0L; var ab = 0L; var w = 0; var h = 0; var fps = 0.0
                report.statsMap.values.filter { it.type == "inbound-rtp" }.forEach { st ->
                    val bytes = (st.members["bytesReceived"] as? java.math.BigInteger)?.toLong()
                        ?: (st.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    if (st.members["kind"] == "video") {
                        vb = bytes
                        w = (st.members["frameWidth"] as? Number)?.toInt() ?: 0
                        h = (st.members["frameHeight"] as? Number)?.toInt() ?: 0
                        fps = (st.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                    } else ab = bytes
                }
                val now = System.currentTimeMillis()
                val dt = if (lastAt > 0) (now - lastAt) / 1000.0 else 0.0
                val vk = if (dt > 0) ((vb - lastVideoBytes) * 8 / dt / 1000).toInt().coerceAtLeast(0) else 0
                val ak = if (dt > 0) ((ab - lastAudioBytes) * 8 / dt / 1000).toInt().coerceAtLeast(0) else 0
                lastVideoBytes = vb; lastAudioBytes = ab; lastAt = now
                main.post { stats.value = stats.value.copy(width = w, height = h, fps = fps, videoKbps = vk, audioKbps = ak) }
            }
            main.postDelayed(this, 1000)
        }
    }

    fun stop() {
        main.removeCallbacks(statsTick)
        local.value = null; remote.value = null
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        videoSource?.dispose(); videoSource = null
        helper?.dispose(); helper = null
        a?.dispose(); a = null
        b?.dispose(); b = null
        audioSource?.dispose(); audioSource = null
        router?.stop(); router = null
    }

    private fun sdp(ok: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { main.post { ok(d) } }
        override fun onCreateFailure(e: String?) {}
        override fun onSetSuccess() {}
        override fun onSetFailure(e: String?) {}
    }

    private fun done(ok: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetSuccess() { main.post(ok) }
        override fun onSetFailure(e: String?) {}
    }
}

class TestCallActivity : ComponentActivity() {
    private lateinit var engine: TestCallEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val video = intent.getBooleanExtra("video", true)
        engine = TestCallEngine(this, video).also { it.start() }
        setContent { LumoTheme(dark = true) { TestCallScreen(engine, video) { finish() } } }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    companion object {
        fun start(c: Context, video: Boolean) {
            if (CallManager.state.value.phase != Phase.Idle) return // not during a real call
            c.startActivity(Intent(c, TestCallActivity::class.java).putExtra("video", video))
        }
    }
}

@Composable
private fun TestCallScreen(engine: TestCallEngine, video: Boolean, onEnd: () -> Unit) {
    val remote by engine.remote.collectAsState()
    val local by engine.local.collectAsState()
    val st by engine.stats.collectAsState()
    var net by remember { mutableStateOf(TestNet.G4) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1640), Color.Black)))) {
        if (video) {
            // Big: what the other person would see. Small: your camera as it is.
            Renderer(remote, mirror = true, overlay = false, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 70.dp, end = 16.dp).size(96.dp, 150.dp).clip(RoundedCornerShape(16.dp))) {
                Renderer(local, mirror = true, overlay = true, Modifier.fillMaxSize())
            }
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
            Text("Test call", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (video) "The big picture is what the other person would see. You hear yourself as they would."
                else "Speak: you hear your voice exactly as the other person would.",
                color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Headphones, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use earphones to hear yourself clearly", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }

        if (!video) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(C.AccentBrush), contentAlignment = Alignment.Center) {
                Text("🎤", fontSize = 52.sp)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding().padding(top = 30.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    !st.connected -> "Connecting…"
                    video -> "${st.width}×${st.height} · ${"%.0f".format(st.fps)} fps · video ${st.videoKbps} kbps · voice ${st.audioKbps} kbps"
                    else -> "Voice ${st.audioKbps} kbps"
                },
                color = Color.White, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            NoiseSwitch()
            Spacer(Modifier.height(12.dp))
            Text("Network speed", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TestNet.entries.forEach { n ->
                    val on = n == net
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
                            .clickable { net = n; engine.setNetwork(n) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(n.label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(n.detail, color = if (on) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(C.Red).clickable(onClick = onEnd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.CallEnd, "End test", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
    }
}

/** Turn AI noise cancelling on and off mid-test to hear the difference (also used in real calls). */
@Composable
private fun NoiseSwitch() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on by com.lumo.app.data.Prefs.noiseAi.collectAsState()
    if (!Denoiser.available) return
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) Color(0xFF2BD17E).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f))
            .clickable { com.lumo.app.data.Prefs.setNoiseAi(context, !on) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\uD83E\uDD16", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("AI noise cancelling", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(if (on) "On \u00B7 background noise removed" else "Off \u00B7 tap to hear the difference",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { com.lumo.app.data.Prefs.setNoiseAi(context, it) })
    }
}

@Composable
private fun Renderer(track: VideoTrack?, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(CallManager.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                setMirror(mirror)
                view = this
            }
        },
        onRelease = { it.release() },
    )
    DisposableEffect(track, view) {
        val v = view
        if (track != null && v != null) runCatching { track.addSink(v) }
        onDispose { if (track != null && v != null) runCatching { track.removeSink(v) } }
    }
}
