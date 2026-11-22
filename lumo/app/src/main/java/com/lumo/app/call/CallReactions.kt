package com.lumo.app.call

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.lumo.app.gesture.Gesture
import com.lumo.app.gesture.GestureLogic
import com.lumo.app.gesture.HandFrame
import com.lumo.app.gesture.FaceLogic
import com.lumo.app.gesture.blendshapes
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.Executors
import kotlin.math.max

/** What each hand sign sends as a live reaction in a call. */
fun reactionFor(g: Gesture): String = when (g) {
    Gesture.LoveYou -> "❤️"
    Gesture.Fist -> "👊"
    else -> g.emoji
}

/**
 * Watches the video call's own camera frames (no second camera needed) a few times a second,
 * reads hand signs with MediaPipe on the phone, and reports deliberate ones.
 * Frames are shrunk to 256 px first, so it costs little battery and never slows the call.
 */
class CallReactionReader(context: Context, private val onSign: (String) -> Unit) : VideoSink {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val logic = GestureLogic(holdMs = 350, cooldownMs = 1800)
    @Volatile private var recognizer: GestureRecognizer? = null
    @Volatile private var faces: FaceLandmarker? = null
    private val face = FaceLogic(withSmile = false, cooldownMs = 3000)
    private var frameNo = 0L
    @Volatile private var busy = false
    @Volatile private var closed = false
    private var lastAt = 0L

    init {
        val app = context.applicationContext
        worker.execute {
            recognizer = runCatching {
                GestureRecognizer.createFromOptions(
                    app,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.6f)
                        .setMinHandPresenceConfidence(0.6f)
                        .setMinTrackingConfidence(0.5f)
                        .build(),
                )
            }.getOrNull()
            faces = runCatching {
                FaceLandmarker.createFromOptions(
                    app,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumFaces(1)
                        .setOutputFaceBlendshapes(true)
                        .build(),
                )
            }.getOrNull()
        }
    }

    override fun onFrame(frame: VideoFrame) {
        val now = SystemClock.uptimeMillis()
        if (closed || busy || recognizer == null || now - lastAt < 90) return
        busy = true
        lastAt = now
        frame.retain()
        worker.execute {
            try {
                val img = BitmapImageBuilder(toBitmap(frame)).build()
                val f = faces
                // Hands and face take turns on the frames, so each costs half.
                if (frameNo++ % 2 == 1L && f != null) {
                    face.onFrame(blendshapes(f.detectForVideo(img, now)), now)
                        ?.let { e -> if (!closed) main.post { onSign(e.emoji) } }
                } else {
                    val r = recognizer?.recognizeForVideo(img, now) ?: return@execute
                    val hand = r.landmarks().firstOrNull()?.let { lm ->
                        val top = r.gestures().firstOrNull()?.firstOrNull()
                        HandFrame(top?.categoryName(), top?.score() ?: 0f, lm.map { it.x() to it.y() })
                    }
                    logic.onFrame(hand, now)?.let { g -> if (!closed) main.post { onSign(reactionFor(g)) } }
                }
            } catch (_: Throwable) {
            } finally {
                frame.release()
                busy = false
            }
        }
    }

    /** Small upright RGB copy of a camera frame. */
    private fun toBitmap(frame: VideoFrame): Bitmap {
        val src = frame.buffer
        val scale = 256f / max(src.width, src.height)
        val w = ((src.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((src.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val small = src.cropAndScale(0, 0, src.width, src.height, w, h)
        val i420 = small.toI420()
        small.release()
        val px = IntArray(w * h)
        try {
            val y = i420!!.dataY; val u = i420.dataU; val v = i420.dataV
            val sy = i420.strideY; val su = i420.strideU; val sv = i420.strideV
            for (j in 0 until h) {
                for (i in 0 until w) {
                    val yy = (y.get(j * sy + i).toInt() and 0xff)
                    val uu = (u.get((j / 2) * su + i / 2).toInt() and 0xff) - 128
                    val vv = (v.get((j / 2) * sv + i / 2).toInt() and 0xff) - 128
                    val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                    val g = (yy - 0.344f * uu - 0.714f * vv).toInt().coerceIn(0, 255)
                    val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                    px[j * w + i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        } finally {
            i420?.release()
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val rot = frame.rotation
        return if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rot.toFloat()) }, true)
    }

    fun close() {
        closed = true
        worker.execute { runCatching { recognizer?.close() }; runCatching { faces?.close() }; recognizer = null; faces = null }
        worker.shutdown()
    }
}
