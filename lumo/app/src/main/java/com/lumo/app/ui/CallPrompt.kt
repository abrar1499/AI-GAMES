package com.lumo.app.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What a spoken phrase asks for: true = video call, false = voice call, null = not a call word. */
fun callKindFromSpeech(text: String): Boolean? {
    val t = text.lowercase()
    val video = listOf("video", "vedio", "camera", "face", "वीडियो", "विडियो", "ویڈیو", "وڈیو", "ویڈیو کال")
    val audio = listOf("audio", "voice", "awaz", "awaaz", "aawaz", "phone", "normal", "simple", "ऑडियो", "आवाज", "آڈیو", "آواز", "وائس")
    return when {
        video.any { it in t } -> true
        audio.any { it in t } -> false
        else -> null
    }
}

/**
 * Pops up after the 🤙 sign: "Call wife?" with Audio and Video buttons, while the phone listens
 * for "audio" or "video" (English, Urdu or Hindi). Says nothing for a few seconds: it closes.
 */
@Composable
fun CallPrompt(name: String, uid: String, canListen: Boolean, onCall: (video: Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var heard by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(7) }
    val call by rememberUpdatedState(onCall)
    val dismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) { delay(1000); secondsLeft-- }
        dismiss()
    }

    // Listen for the call type, restarting if the phone stops early (silence, noise).
    DisposableEffect(canListen) {
        if (!canListen || !SpeechRecognizer.isRecognitionAvailable(context)) return@DisposableEffect onDispose {}
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        var done = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        fun check(b: Bundle?) {
            val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            texts.firstOrNull()?.let { heard = it }
            val kind = texts.firstNotNullOfOrNull { callKindFromSpeech(it) } ?: return
            if (!done) { done = true; call(kind) }
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                if (!done) runCatching { sr.cancel(); sr.startListening(intent) }
            }
            override fun onResults(b: Bundle?) {
                check(b)
                if (!done) runCatching { sr.startListening(intent) }
            }
            override fun onPartialResults(b: Bundle?) = check(b)
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        runCatching { sr.startListening(intent) }
        onDispose {
            done = true
            runCatching { sr.cancel(); sr.destroy() }
        }
    }

    val pulse = rememberInfiniteTransition(label = "listen")
    val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "r")

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.Surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Close, "Cancel", tint = C.Sub,
                    modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).clickable { onDismiss() }.padding(4.dp))
            }
            Avatar(name.ifBlank { " " }, uid, 72.dp)
            Spacer(Modifier.height(14.dp))
            Text("Call $name?", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            if (canListen) {
                Box(contentAlignment = Alignment.Center) {
                    if (listening) Box(Modifier.size(54.dp).scale(ring).clip(CircleShape).background(C.Accent.copy(alpha = 0.18f)))
                    Box(Modifier.size(54.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Mic, null, tint = C.Accent)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (heard.isNotBlank()) "“$heard”" else "Say “audio” or “video”",
                    color = C.Sub, fontSize = 15.sp,
                )
                Spacer(Modifier.height(20.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Choice(Icons.Rounded.Call, "Audio", Modifier.weight(1f)) { call(false) }
                Choice(Icons.Rounded.Videocam, "Video", Modifier.weight(1f)) { call(true) }
            }
            Spacer(Modifier.height(12.dp))
            Text("Closes in $secondsLeft s", color = C.Sub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(18.dp)).background(C.AccentBrush).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
