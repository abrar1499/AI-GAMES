package com.lumo.app.call

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
    private val timeout = Runnable { if (_state.value.phase == Phase.Outgoing) hangUp("No answer") }

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
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
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
        val turn = BuildConfig.TURN_URLS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (turn.isNotEmpty()) {
            list += PeerConnection.IceServer.builder(turn)
                .setUsername(BuildConfig.TURN_USER)
                .setPassword(BuildConfig.TURN_PASS)
                .createIceServer()
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
        // Start the bandwidth estimate high so the picture is sharp from the first second.
        peer.setBitrate(300_000, if (video) 2_500_000 else 128_000, 6_000_000)
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

    /** Full HD at 30fps when the camera can do it, otherwise the closest below. */
    private fun bestFormat(e: Camera2Enumerator, name: String): Triple<Int, Int, Int> {
        val formats = e.getSupportedFormats(name).orEmpty()
            .filter { it.width * it.height <= 1920 * 1080 && it.framerate.max >= 30_000 }
        val best = formats.maxByOrNull { it.width * it.height } ?: return Triple(1280, 720, 30)
        return Triple(best.width, best.height, 30)
    }

    private fun tuneSenders() {
        videoSender?.let { s ->
            val p = s.parameters
            p.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            val pixels = captureFormat.first * captureFormat.second
            val max = if (pixels >= 1920 * 1080) 4_500_000 else 2_500_000
            p.encodings.forEach {
                it.maxBitrateBps = max
                it.minBitrateBps = 250_000
                it.maxFramerate = 30
                it.bitratePriority = 2.0
            }
            s.parameters = p
        }
        audioSender?.let { s ->
            val p = s.parameters
            p.encodings.forEach {
                it.maxBitrateBps = 128_000
                it.bitratePriority = 4.0
            }
            s.parameters = p
        }
    }

    /** Opus at studio settings: full-band 48kHz, high bitrate, packet-loss recovery. */
    private fun tuneSdp(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000/2").find(sdp)?.groupValues?.get(1) ?: return sdp
        val wanted = linkedMapOf(
            "minptime" to "10", "useinbandfec" to "1", "usedtx" to "0", "stereo" to "1",
            "sprop-stereo" to "1", "maxaveragebitrate" to "128000", "maxplaybackrate" to "48000",
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
                update {
                    it.copy(
                        phase = Phase.Connected, reconnecting = false,
                        connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt,
                    )
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> update { it.copy(reconnecting = true) }
            PeerConnection.PeerConnectionState.FAILED -> {
                update { it.copy(reconnecting = true) }
                if (isCaller) restartIce()
            }
            else -> {}
        }
    }

    /** Network changed (Wi-Fi to mobile data, etc.): find a new path without dropping the call. */
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
        playRingback()
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
                    "status" to "ringing",
                    "callerCam" to video,
                    "calleeCam" to video,
                    "offer" to mapOf("type" to "offer", "sdp" to tuned.description),
                    "createdAt" to FieldValue.serverTimestamp(),
                )).addOnSuccessListener { flushLocalIce() }
                listenCall()
            }, tuned)
        }, MediaConstraints())
        main.postDelayed(timeout, 45_000)
    }

    // ---------------------------------------------------------------- incoming

    /** Called by the push service or the in-app listener when someone rings us. */
    fun onIncoming(callId: String) {
        if (!::app.isInitialized || callId.isEmpty()) return
        db.collection("calls").document(callId).get().addOnSuccessListener { doc ->
            if (doc.getString("status") != "ringing" || doc.getString("callee") != Repo.myUid) return@addOnSuccessListener
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
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    private fun listenCall() {
        listeners += callRef.addSnapshotListener { doc, _ ->
            if (doc == null || !doc.exists()) return@addSnapshotListener
            val s = _state.value
            if (s.phase == Phase.Idle || s.phase == Phase.Ended || doc.id != s.callId) return@addSnapshotListener

            when (doc.getString("status")) {
                "declined" -> return@addSnapshotListener finish("Declined")
                "busy" -> return@addSnapshotListener finish("Busy on another call")
                "ended" -> return@addSnapshotListener finish("Call ended")
                "missed" -> return@addSnapshotListener finish(if (isCaller) "No answer" else "Missed call")
            }
            doc.getBoolean(peerCamField)?.let { cam -> if (cam != s.peerCamOn) update { it.copy(peerCamOn = cam) } }

            val peer = pc ?: return@addSnapshotListener
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
        listeners += callRef.collection(peerIce).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.filter { it.type == DocumentChange.Type.ADDED }?.forEach { ch ->
                val d = ch.document
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
