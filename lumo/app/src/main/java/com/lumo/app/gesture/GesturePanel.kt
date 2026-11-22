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

/** Small camera window above the message box. Show a sign and it is sent. */
@Composable
fun GesturePanel(onGesture: (Gesture) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var seen by remember { mutableStateOf<Gesture?>(null) }
    var sent by remember { mutableStateOf<Gesture?>(null) }
    var failed by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        var engine: GestureEngine? = null
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                engine = GestureEngine(context, onGesture = { g ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sent = g
                    onGesture(g)
                }, onSeen = { seen = it })
                val p = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { a -> a.setAnalyzer(executor) { img -> engine?.analyze(img) ?: img.close() } }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                failed = true
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { provider?.unbindAll() }
            engine?.close()
            executor.shutdown()
        }
    }

    // The big emoji pops up for a moment after each send.
    LaunchedEffect(sent) { if (sent != null) { delay(1100); sent = null } }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(C.Surface2).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 96.dp, height = 128.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            androidx.compose.animation.AnimatedVisibility(
                visible = sent != null, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)), exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(sent?.emoji ?: "", fontSize = 54.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    failed -> "Camera unavailable"
                    sent != null -> "Sent ${sent!!.emoji}"
                    seen != null -> "${seen!!.emoji}  Hold it…"
                    else -> "Show a hand sign"
                },
                color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "👍 👌 👋 wave ✌️ 👎 🤟 ☝️ ✊",
                color = C.Sub, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("Runs on your phone. Nothing is recorded.", color = C.Sub, fontSize = 11.sp)
        }
        IconButton(onClick = onClose, Modifier.align(Alignment.Top)) { Icon(Icons.Rounded.Close, "Close", tint = C.Sub) }
    }
}
