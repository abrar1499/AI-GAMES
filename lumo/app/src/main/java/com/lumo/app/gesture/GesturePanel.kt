package com.lumo.app.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.lumo.app.ui.C
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Reads hand gestures from the front camera, entirely on the phone (nothing is uploaded).
 * Calls [onGesture] on the main thread each time a deliberate gesture is made.
 */
class GestureEngine(context: Context, private val onGesture: (Gesture) -> Unit, private val onSeen: (Gesture?) -> Unit) {
    private val main = ContextCompat.getMainExecutor(context)
    private val logic = GestureLogic()
    private val recognizer: GestureRecognizer = GestureRecognizer.createFromOptions(
        context,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handle(result) }
            .setErrorListener { }
            .build(),
    )
    @Volatile private var closed = false
    private var lastTs = 0L

    fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            val bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot == 0) bmp else Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true,
            )
            val ts = SystemClock.uptimeMillis().coerceAtLeast(lastTs + 1)
            lastTs = ts
            recognizer.recognizeAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun handle(r: GestureRecognizerResult) {
        val frame = r.landmarks().firstOrNull()?.let { lm ->
            val top = r.gestures().firstOrNull()?.firstOrNull()
            HandFrame(top?.categoryName(), top?.score() ?: 0f, lm.map { it.x() to it.y() })
        }
        val fired = logic.onFrame(frame, SystemClock.uptimeMillis())
        val seen = logic.current()
        main.execute {
            onSeen(seen)
            if (fired != null && !closed) onGesture(fired)
        }
    }

    fun close() {
        closed = true
        runCatching { recognizer.close() }
    }
}

/**
 * Hand signs without any button or camera window: while a chat is on screen, the front camera
 * runs quietly in the background (Android still shows its green camera dot) and every deliberate
 * sign is sent. The camera stops by itself when the chat is left or the app goes to the background.
 */
@Composable
fun BackgroundSigns(enabled: Boolean, onGesture: (Gesture) -> Unit) {
    if (!enabled) return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val send by rememberUpdatedState(onGesture)
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        var engine: GestureEngine? = null
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                engine = GestureEngine(context, onGesture = { g ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    send(g)
                }, onSeen = {})
                val p = future.get().also { provider = it }
                analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { a -> a.setAnalyzer(executor) { img -> engine?.analyze(img) ?: img.close() } }
                // Bound to the screen's lifecycle: the camera pauses whenever the chat isn't visible.
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Throwable) {
                // No front camera, camera busy, or recognizer unavailable: simply no hand signs.
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            analysis?.let { a -> runCatching { provider?.unbind(a) } }
            engine?.close()
            executor.shutdown()
        }
    }
}
