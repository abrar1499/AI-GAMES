package com.lumo.app.call

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.lumo.app.BuildConfig
import com.lumo.app.LumoApp
import com.lumo.app.data.LumoUser
import com.lumo.app.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.Priority
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class Phase { Idle, Outgoing, Incoming, Connecting, Connected, Ended }

data class CallState(
    val phase: Phase = Phase.Idle,
    val callId: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val peerUsername: String = "",
    val video: Boolean = false,
    val micOn: Boolean = true,
    val camOn: Boolean = true,
    val peerCamOn: Boolean = true,
    val speakerOn: Boolean = false,
    val frontCamera: Boolean = true,
    val reconnecting: Boolean = false,
    /** The other phone has the call on screen (it is online and logged in). */
    val peerRinging: Boolean = false,
    /** The connection is too slow for full quality right now. */
    val weakNetwork: Boolean = false,
    /** Going through the relay server (carrier NAT) instead of directly. */
    val relayed: Boolean = false,
    val connectedAt: Long = 0L,
    val endReason: String = "",
)

/**
 * One call at a time, peer-to-peer over WebRTC. Firestore carries the offer/answer
 * and ICE candidates; media never touches a server (except a TURN relay, if set).
 */
@SuppressLint("StaticFieldLeak")
object CallManager {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val db by lazy { FirebaseFirestore.getInstance() }

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state
    val localVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null
    private var captureFormat = Triple(1280, 720, 30)

    private var isCaller = false
    private var remoteSet = false
    private var lastRemoteSdp = ""
    private val pendingIce = mutableListOf<IceCandidate>()
    // Our own candidates wait until the call document exists (security rules check it).
    private val localIce = mutableListOf<IceCandidate>()
    private var docReady = false
    private val listeners = mutableListOf<ListenerRegistration>()
    private var incomingDoc: DocumentSnapshot? = null

    private var audio: AudioRouter? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val timeout = Runnable {
        val s = _state.value
        if (s.phase == Phase.Outgoing) hangUp(if (s.peerRinging) "No answer" else "Not reachable right now")
    }

    private val callRef get() = db.collection("calls").document(_state.value.callId)
    private val myIce get() = if (isCaller) "callerIce" else "calleeIce"
    private val peerIce get() = if (isCaller) "calleeIce" else "callerIce"
    private val myCamField get() = if (isCaller) "callerCam" else "calleeCam"
    private val peerCamField get() = if (isCaller) "calleeCam" else "callerCam"

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun update(f: (CallState) -> CallState) {
        _state.value = f(_state.value)
    }

    // ---------------------------------------------------------------- engine

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                // RED: every audio packet also carries a copy of the previous one, so a lost
                // packet is rebuilt from the next instead of becoming a gap in the voice.
                .setFieldTrials("WebRTC-Audio-Red-For-Opus/Enabled/")
                .createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(app)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder(
                listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302", "stun:stun2.l.google.com:19302")
            ).createIceServer()
        )
        // Relay for phones behind carrier NAT (most mobile data), where a direct path is impossible.
        // Your own relay (set at build time) is used if present, otherwise the free Open Relay servers.
        val own = BuildConfig.TURN_URLS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        list += if (own.isNotEmpty()) {
            PeerConnection.IceServer.builder(own).setUsername(BuildConfig.TURN_USER).setPassword(BuildConfig.TURN_PASS).createIceServer()
        } else {
            PeerConnection.IceServer.builder(
                listOf(
                    "turn:openrelay.metered.ca:80",
                    "turn:openrelay.metered.ca:443",
                    "turn:openrelay.metered.ca:443?transport=tcp",
                    "turns:openrelay.metered.ca:443?transport=tcp",
                )
            ).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        }
        return list
    }

    private fun createPeer(video: Boolean) {
        val f = factory()
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            // Notice a dead network path within ~2.5s instead of ~10s, and keep a backup path warm.
            iceConnectionReceivingTimeout = 2500
            iceBackupCandidatePairPingInterval = 2000
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val peer = f.createPeerConnection(config, observer) ?: error("WebRTC unavailable")
        pc = peer

        val audioConstraints = MediaConstraints().apply {
            listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression",
                "googHighpassFilter", "googTypingNoiseDetection").forEach {
                mandatory += MediaConstraints.KeyValuePair(it, "true")
            }
        }
        audioSource = f.createAudioSource(audioConstraints)
        audioTrack = f.createAudioTrack("lumo-a", audioSource).also {
            audioSender = peer.addTrack(it, listOf("lumo"))
        }

        if (video) startCamera(f, peer)
        preferCodecs(f, peer)
        // Start at a rate weak networks can carry; on good networks it climbs within seconds.
        // The floor is very low so video gets softer on a bad connection instead of freezing.
        if (video) peer.setBitrate(40_000, 600_000, 2_500_000) else peer.setBitrate(24_000, 64_000, 96_000)
        tuneSenders()
    }

    private fun startCamera(f: PeerConnectionFactory, peer: PeerConnection) {
        val enumerator = Camera2Enumerator(app)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        val cam = enumerator.createCapturer(name, null) ?: return
        captureFormat = bestFormat(enumerator, name)
        textureHelper = SurfaceTextureHelper.create("lumo-camera", egl.eglBaseContext)
        val source = f.createVideoSource(false)
        cam.initialize(textureHelper, app, source.capturerObserver)
        cam.startCapture(captureFormat.first, captureFormat.second, captureFormat.third)
        capturer = cam
        videoSource = source
        val track = f.createVideoTrack("lumo-v", source)
        videoSender = peer.addTrack(track, listOf("lumo"))
        localVideo.value = track
        update { it.copy(frontCamera = enumerator.isFrontFacing(name)) }
    }

    /**
     * HD 720p at 30fps. On a phone screen it looks as sharp as 1080p but needs about half the
     * data, so it stays clear on mobile networks. WebRTC scales it down further when needed.
     */
    private fun bestFormat(e: Camera2Enumerator, name: String): Triple<Int, Int, Int> {
        val formats = e.getSupportedFormats(name).orEmpty()
            .filter { it.width * it.height <= 1280 * 720 && it.framerate.max >= 30_000 }
        val best = formats.maxByOrNull { it.width * it.height } ?: return Triple(1280, 720, 30)
        return Triple(best.width, best.height, 30)
    }

    private fun tuneSenders() {
        videoSender?.let { s ->
            val p = s.parameters
            // Weak network: lower resolution and frame rate together, keeping motion smooth.
            p.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            p.encodings.forEach {
                it.maxBitrateBps = 2_000_000
                it.minBitrateBps = 30_000
                it.maxFramerate = 30
                it.bitratePriority = 1.0
                it.networkPriority = Priority.MEDIUM
            }
            s.parameters = p
        }
        audioSender?.let { s ->
            val p = s.parameters
            // Voice always wins over video when the connection is tight.
            p.encodings.forEach {
                it.maxBitrateBps = 80_000 // 32 kbps Opus plus its redundant copy and headers
                it.bitratePriority = 4.0
                it.networkPriority = Priority.HIGH
            }
            s.parameters = p
        }
    }

    /**
     * Video: VP9 when this phone has a hardware VP9 encoder (about 40% less data than VP8/H.264
     * for the same picture), otherwise the phone's hardware codecs first. Audio: RED first.
     */
    private fun preferCodecs(f: PeerConnectionFactory, peer: PeerConnection) = runCatching {
        val hw = HardwareVideoEncoderFactory(egl.eglBaseContext, true, true).supportedCodecs.map { it.name.uppercase() }
        peer.transceivers.forEach { t ->
            when (t.mediaType) {
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    val caps = f.getRtpSenderCapabilities(t.mediaType).codecs
                    val order = (if ("VP9" in hw) listOf("VP9") else emptyList()) + hw.filter { it != "VP9" } + listOf("VP8", "VP9", "H264", "AV1")
                    t.setCodecPreferences(caps.sortedBy { c -> order.indexOf(c.name.uppercase()).let { if (it < 0) 99 else it } })
                }
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    val caps = f.getRtpSenderCapabilities(t.mediaType).codecs
                    val order = listOf("RED", "OPUS")
                    t.setCodecPreferences(caps.sortedBy { c -> order.indexOf(c.name.uppercase()).let { if (it < 0) 99 else it } })
                }
                else -> {}
            }
        }
    }

    /**
     * Opus tuned for voice over any network: 32 kbps mono full-band (as clear as a studio
     * voice note) with in-band error correction, and 10ms packets for low delay.
     */
    private fun tuneSdp(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000/2").find(sdp)?.groupValues?.get(1) ?: return sdp
        val wanted = linkedMapOf(
            "minptime" to "10", "useinbandfec" to "1", "usedtx" to "0", "stereo" to "0",
            "sprop-stereo" to "0", "maxaveragebitrate" to "32000", "maxplaybackrate" to "48000",
        )
        return sdp.split("\r\n").joinToString("\r\n") { line ->
            if (!line.startsWith("a=fmtp:$pt ")) return@joinToString line
            val params = linkedMapOf<String, String>()
            line.substringAfter(' ').split(';').forEach { kv ->
                val (k, v) = kv.split('=', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
                if (k.isNotEmpty()) params[k] = v
            }
            params.putAll(wanted)
            "a=fmtp:$pt " + params.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            main.post { if (docReady) sendIce(c) else localIce += c }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) main.post { remoteVideo.value = track }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            main.post { onPeerState(newState) }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    private fun onPeerState(s: PeerConnection.PeerConnectionState) {
        val phase = _state.value.phase
        if (phase == Phase.Idle || phase == Phase.Ended) return
        when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                stopRinging()
                tuneSenders()
                main.removeCallbacks(restartSoon)
                main.removeCallbacks(giveUp)
                update {
                    it.copy(
                        phase = Phase.Connected, reconnecting = false,
                        connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt,
                    )
                }
                startStats()
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                // Often recovers on its own in a second; if not, find a new path.
                update { it.copy(reconnecting = true) }
                main.removeCallbacks(restartSoon)
                main.postDelayed(restartSoon, 2000)
                armGiveUp()
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                update { it.copy(reconnecting = true) }
                requestRestart()
                armGiveUp()
            }
            else -> {}
        }
    }

    private val restartSoon = Runnable {
        if (_state.value.reconnecting) requestRestart()
    }

    /** Only end a call after a long outage; short drops (tunnels, lifts) reconnect. */
    private val giveUp = Runnable {
        if (_state.value.reconnecting) hangUp("Connection lost")
    }

    private fun armGiveUp() {
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, 45_000)
    }

    private var lastRestartAt = 0L
    private var handledRestartReq = 0L

    /**
     * New network path. Either phone can ask; the caller always does the actual restart so the
     * two never collide. The callee asks by writing restartReq to the call document.
     */
    private fun requestRestart() {
        val now = System.currentTimeMillis()
        if (now - lastRestartAt < 1500) return // debounce bursts of network events
        lastRestartAt = now
        if (isCaller) restartIce()
        else if (_state.value.callId.isNotEmpty()) callRef.update("restartReq", now)
    }

    private fun restartIce() {
        val peer = pc ?: return
        val c = MediaConstraints().apply { mandatory += MediaConstraints.KeyValuePair("IceRestart", "true") }
        peer.createOffer(sdp { offer ->
            val tuned = SessionDescription(offer.type, tuneSdp(offer.description))
            peer.setLocalDescription(done {
                callRef.update("offer", mapOf("type" to "offer", "sdp" to tuned.description))
            }, tuned)
        }, c)
    }

    // ---------------------------------------------------------------- network changes

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    /** Wi-Fi to mobile data (or back): switch the call to the new network right away. */
    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        currentNetwork = cm.activeNetwork
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val changed = currentNetwork != null && currentNetwork != network
                    currentNetwork = network
                    val p = _state.value.phase
                    if (changed && (p == Phase.Connected || p == Phase.Connecting)) {
                        update { it.copy(reconnecting = true) }
                        requestRestart()
                        armGiveUp()
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        netCallback = null
        currentNetwork = null
    }

    // ---------------------------------------------------------------- connection quality

    private val statsTick = object : Runnable {
        override fun run() {
            val peer = pc ?: return
            if (_state.value.phase != Phase.Connected) return
            peer.getStats { report ->
                var rtt = 0.0
                var outKbps = Double.MAX_VALUE
                var relay = false
                report.statsMap.values.forEach { st ->
                    if (st.type == "candidate-pair" && st.members["nominated"] == true && st.members["state"] == "succeeded") {
                        (st.members["currentRoundTripTime"] as? Double)?.let { rtt = it }
                        (st.members["availableOutgoingBitrate"] as? Double)?.let { outKbps = it / 1000 }
                        val local = report.statsMap[st.members["localCandidateId"] as? String]
                        relay = local?.members?.get("candidateType") == "relay"
                    }
                }
                val video = _state.value.video
                val weak = rtt > 0.7 || outKbps < (if (video) 150.0 else 40.0)
                main.post { if (weak != _state.value.weakNetwork || relay != _state.value.relayed) update { it.copy(weakNetwork = weak, relayed = relay) } }
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun startStats() {
        main.removeCallbacks(statsTick)
        main.postDelayed(statsTick, 2000)
    }

    // ---------------------------------------------------------------- outgoing

    fun startCall(context: Context, peer: LumoUser, video: Boolean) {
        if (_state.value.phase != Phase.Idle) return
        isCaller = true
        resetSignaling()
        val id = db.collection("calls").document().id
        _state.value = CallState(
            phase = Phase.Outgoing, callId = id, peerUid = peer.uid, peerName = peer.name,
            peerUsername = peer.username, video = video, speakerOn = video, camOn = video,
        )
        startAudio(video)
        createPeer(video)
        watchNetwork()
        CallService.start(app, video)
        context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val peerConn = pc ?: return
        peerConn.createOffer(sdp { offer ->
            val tuned = SessionDescription(offer.type, tuneSdp(offer.description))
            peerConn.setLocalDescription(done {
                callRef.set(mapOf(
                    "caller" to Repo.myUid,
                    "callee" to peer.uid,
                    "callerName" to Repo.myName,
                    "callerUsername" to Repo.myUsername,
                    "video" to video,
                    // "calling" until the other phone picks it up and marks it "ringing".
                    "status" to "calling",
                    "callerCam" to video,
                    "calleeCam" to video,
                    "offer" to mapOf("type" to "offer", "sdp" to tuned.description),
                    "createdAt" to FieldValue.serverTimestamp(),
                )).addOnSuccessListener {
                    // Only now does the document exist, so the security rules let us listen to it.
                    if (_state.value.callId != id) return@addOnSuccessListener
                    flushLocalIce()
                    listenCall()
                }.addOnFailureListener { hangUp("Call failed") }
            }, tuned)
        }, MediaConstraints())
        main.postDelayed(timeout, 45_000)
    }

    // ---------------------------------------------------------------- incoming

    /** Called by the push service or the in-app listener when someone rings us. */
    fun onIncoming(callId: String) {
        if (!::app.isInitialized || callId.isEmpty()) return
        db.collection("calls").document(callId).get().addOnSuccessListener { doc ->
            if (doc.getString("status") != "calling" || doc.getString("callee") != Repo.myUid) return@addOnSuccessListener
            val created = doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()
            if (System.currentTimeMillis() - created > 60_000) return@addOnSuccessListener
            val current = _state.value
            if (current.callId == callId) return@addOnSuccessListener
            if (current.phase != Phase.Idle) {
                doc.reference.update("status", "busy")
                return@addOnSuccessListener
            }
            isCaller = false
            resetSignaling()
            incomingDoc = doc
            val video = doc.getBoolean("video") == true
            _state.value = CallState(
                phase = Phase.Incoming, callId = callId,
                peerUid = doc.getString("caller").orEmpty(),
                peerName = doc.getString("callerName").orEmpty(),
                peerUsername = doc.getString("callerUsername").orEmpty(),
                video = video, speakerOn = video, camOn = video,
            )
            // Tell the caller this phone is ringing.
            doc.reference.update("status", "ringing")
            listenCall()
            playRingtone()
            Notifs.showIncoming(app, _state.value)
            if (LumoApp.inForeground) runCatching {
                app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun accept() {
        val doc = incomingDoc ?: return
        if (_state.value.phase != Phase.Incoming) return
        stopRinging()
        Notifs.cancelIncoming(app)
        val video = _state.value.video
        update { it.copy(phase = Phase.Connecting) }
        startAudio(video)
        createPeer(video)
        watchNetwork()
        CallService.start(app, video)
        flushLocalIce() // the caller already created the document
        val peer = pc ?: return
        @Suppress("UNCHECKED_CAST")
        val offer = (doc.get("offer") as? Map<String, Any>)?.get("sdp") as? String ?: return hangUp("Call failed")
        answerOffer(peer, offer, first = true)
    }

    private fun answerOffer(peer: PeerConnection, offerSdp: String, first: Boolean) {
        lastRemoteSdp = offerSdp
        peer.setRemoteDescription(done {
            remoteSet = true
            drainIce()
            peer.createAnswer(sdp { answer ->
                val tuned = SessionDescription(answer.type, tuneSdp(answer.description))
                peer.setLocalDescription(done {
                    val data = mutableMapOf<String, Any>("answer" to mapOf("type" to "answer", "sdp" to tuned.description))
                    if (first) data["status"] = "accepted"
                    callRef.update(data)
                }, tuned)
            }, MediaConstraints())
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    fun decline() {
        if (_state.value.phase != Phase.Incoming) return
        callRef.update("status", "declined")
        finish("Declined")
    }

    // ---------------------------------------------------------------- signaling

    private fun sendIce(c: IceCandidate) {
        if (_state.value.callId.isEmpty() || pc == null) return
        callRef.collection(myIce).add(mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp))
    }

    private fun flushLocalIce() {
        docReady = true
        localIce.forEach(::sendIce)
        localIce.clear()
    }

    private fun resetSignaling() {
        docReady = false
        localIce.clear()
        remoteSet = false
        lastRemoteSdp = ""
        pendingIce.clear()
        seenIce.clear()
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    private fun listenCall() {
        listenDoc()
        listenIce()
    }

    /** If the server ever drops a listener (rules, network), attach it again instead of going deaf. */
    private fun retry(callId: String, attach: () -> Unit) {
        main.postDelayed({
            val p = _state.value.phase
            if (_state.value.callId == callId && p != Phase.Idle && p != Phase.Ended) attach()
        }, 1000)
    }

    private fun listenDoc() {
        val callId = _state.value.callId
        listeners += callRef.addSnapshotListener { doc, err ->
            if (err != null) return@addSnapshotListener retry(callId, ::listenDoc)
            if (doc == null || !doc.exists()) return@addSnapshotListener
            val s = _state.value
            if (s.phase == Phase.Idle || s.phase == Phase.Ended || doc.id != s.callId) return@addSnapshotListener

            when (doc.getString("status")) {
                "declined" -> return@addSnapshotListener finish("Declined")
                "busy" -> return@addSnapshotListener finish("Busy on another call")
                "ended" -> return@addSnapshotListener finish("Call ended")
                "missed" -> return@addSnapshotListener finish(if (isCaller) "No answer" else "Missed call")
                "ringing" -> if (isCaller && s.phase == Phase.Outgoing && !s.peerRinging) {
                    update { it.copy(peerRinging = true) }
                    playRingback()
                }
            }
            doc.getBoolean(peerCamField)?.let { cam -> if (cam != s.peerCamOn) update { it.copy(peerCamOn = cam) } }

            val peer = pc ?: return@addSnapshotListener
            val req = doc.getLong("restartReq") ?: 0L
            if (isCaller && req > handledRestartReq) {
                val first = handledRestartReq == 0L
                handledRestartReq = req
                // Ignore a request left over from before we started listening.
                if (!first || s.phase == Phase.Connected) restartIce()
            }
            @Suppress("UNCHECKED_CAST")
            if (isCaller) {
                val answer = (doc.get("answer") as? Map<String, Any>)?.get("sdp") as? String ?: return@addSnapshotListener
                if (answer == lastRemoteSdp || peer.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return@addSnapshotListener
                lastRemoteSdp = answer
                main.removeCallbacks(timeout)
                stopRinging()
                if (s.phase == Phase.Outgoing) update { it.copy(phase = Phase.Connecting) }
                peer.setRemoteDescription(done { remoteSet = true; drainIce() },
                    SessionDescription(SessionDescription.Type.ANSWER, answer))
            } else if (s.phase != Phase.Incoming) {
                // Re-offer from the caller after a network change.
                val offer = (doc.get("offer") as? Map<String, Any>)?.get("sdp") as? String ?: return@addSnapshotListener
                if (offer != lastRemoteSdp && lastRemoteSdp.isNotEmpty()) answerOffer(peer, offer, first = false)
            }
        }
    }

    private val seenIce = mutableSetOf<String>()

    private fun listenIce() {
        val callId = _state.value.callId
        listeners += callRef.collection(peerIce).addSnapshotListener { snap, err ->
            if (err != null) return@addSnapshotListener retry(callId, ::listenIce)
            snap?.documentChanges?.filter { it.type == DocumentChange.Type.ADDED }?.forEach { ch ->
                val d = ch.document
                if (!seenIce.add(d.id)) return@forEach // a re-attached listener replays old ones
                val c = IceCandidate(
                    d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), d.getString("candidate").orEmpty()
                )
                if (remoteSet && pc != null) pc?.addIceCandidate(c) else pendingIce += c
            }
        }
    }

    private fun drainIce() = main.post {
        pendingIce.forEach { pc?.addIceCandidate(it) }
        pendingIce.clear()
    }

    // ---------------------------------------------------------------- controls

    fun toggleMic() {
        val on = !_state.value.micOn
        audioTrack?.setEnabled(on)
        update { it.copy(micOn = on) }
    }

    fun toggleCamera() {
        val track = localVideo.value ?: return
        val on = !_state.value.camOn
        track.setEnabled(on)
        if (on) capturer?.startCapture(captureFormat.first, captureFormat.second, captureFormat.third)
        else capturer?.stopCapture()
        update { it.copy(camOn = on) }
        callRef.set(mapOf(myCamField to on), SetOptions.merge())
    }

    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = main.post { update { it.copy(frontCamera = front) } }.let {}
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        audio?.setSpeaker(on)
        update { it.copy(speakerOn = on) }
    }

    fun hangUp(reason: String = "Call ended") {
        val s = _state.value
        if (s.phase == Phase.Idle || s.phase == Phase.Ended) return
        if (s.callId.isNotEmpty()) {
            val status = when {
                s.phase == Phase.Incoming -> "declined"
                s.phase == Phase.Outgoing -> "missed"
                else -> "ended"
            }
            callRef.update(mapOf("status" to status, "endedAt" to FieldValue.serverTimestamp()))
        }
        finish(reason)
    }

    // ---------------------------------------------------------------- teardown

    private fun finish(reason: String) {
        main.removeCallbacks(timeout)
        main.removeCallbacks(restartSoon)
        main.removeCallbacks(giveUp)
        main.removeCallbacks(statsTick)
        unwatchNetwork()
        handledRestartReq = 0L
        lastRestartAt = 0L
        stopRinging()
        Notifs.cancelIncoming(app)
        listeners.forEach { it.remove() }
        listeners.clear()
        localVideo.value = null
        remoteVideo.value = null
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        videoSource?.dispose(); videoSource = null
        textureHelper?.dispose(); textureHelper = null
        pc?.dispose(); pc = null
        audioSource?.dispose(); audioSource = null
        audioTrack = null; videoSender = null; audioSender = null
        audio?.stop(); audio = null
        incomingDoc = null
        CallService.stop(app)
        update { it.copy(phase = Phase.Ended, endReason = reason, reconnecting = false) }
        main.postDelayed({ if (_state.value.phase == Phase.Ended) _state.value = CallState() }, 1600)
    }

    // ---------------------------------------------------------------- sounds

    private fun startAudio(video: Boolean) {
        audio = AudioRouter(app).also { it.start(speaker = video) }
    }

    private fun playRingtone() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            vibrator = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).also {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 900), 0))
            }
        }
    }

    private fun playRingback() {
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
            ?.apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
    }

    private fun stopRinging() {
        ringtone?.stop(); ringtone = null
        vibrator?.cancel(); vibrator = null
        ringback?.stopTone(); ringback?.release(); ringback = null
    }

    // ---------------------------------------------------------------- helpers

    private fun sdp(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { main.post { onSuccess(d) } }
        override fun onCreateFailure(e: String?) { main.post { hangUp("Call failed") } }
        override fun onSetSuccess() {}
        override fun onSetFailure(e: String?) {}
    }

    private fun done(onSuccess: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetSuccess() { main.post(onSuccess) }
        override fun onSetFailure(e: String?) { main.post { if (_state.value.phase != Phase.Connected) hangUp("Call failed") } }
    }
}
