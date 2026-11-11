package com.aigames.voicetext

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.Transliterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer

/**
 * Turns speech into text with the recognizer built into Android.
 *
 * ```
 * val voice = VoiceToText(this)
 * voice.language = "ur-PK"
 * voice.roman = true                                   // "aap kaise hain"
 * voice.onPartial = { text -> preview.text = text }   // while speaking
 * voice.onFinal = { text -> box.append("$text ") }     // a finished sentence
 * voice.onError = { error -> toast(error.message) }
 * voice.start()
 * ...
 * voice.release()   // in onDestroy
 * ```
 *
 * The app must hold the RECORD_AUDIO permission before [start]; ask for it
 * with `requestPermissions`. Create and use this class on the main thread;
 * every callback also arrives on the main thread.
 */
class VoiceToText(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val engine = AndroidSpeechEngine(appContext)
    private val session = SessionController(engine) { delayMs, block -> handler.postDelayed(block, delayMs) }

    /** Language to listen for, as a tag such as "ur-PK" or "en-US". See [VoiceLanguages]. */
    var language: String = "en-US"

    /**
     * Write the text in Roman (English) letters whatever the language:
     * "aap kaise hain" instead of "آپ کیسے ہیں".
     *
     * Urdu script leaves out most vowels, so it cannot be turned into good
     * Roman text. With this on, Urdu is therefore listened to as Hindi (the
     * two sound the same) and the Hindi letters are written in Roman by
     * [Romanizer]. Other scripts go through Android's own transliterator
     * (Android 10+), which is rougher.
     */
    var roman = false

    /**
     * false: listen for one sentence, then stop.
     * true: keep listening sentence after sentence until [stop].
     */
    var continuous: Boolean
        get() = session.continuous
        set(value) { session.continuous = value }

    /** Ask for on-device recognition when the phone has the language downloaded. */
    var preferOffline: Boolean
        get() = session.preferOffline
        set(value) { session.preferOffline = value }

    /** Text heard so far in the sentence being spoken. Replaces the previous partial text. */
    var onPartial: (String) -> Unit = {}

    /** A finished sentence. */
    var onFinal: (String) -> Unit = {}

    /** Listening ended with a problem. */
    var onError: (VoiceError) -> Unit
        get() = session.onError
        set(value) { session.onError = value }

    /** Called with true when listening starts and false when it has ended. */
    var onListeningChanged: (Boolean) -> Unit
        get() = session.onListeningChanged
        set(value) { session.onListeningChanged = value }

    /** How loud the voice is right now, 0 to 1. Useful for a microphone animation. */
    var onLevel: (Float) -> Unit
        get() = session.onLevel
        set(value) { session.onLevel = value }

    val isListening: Boolean
        get() = session.isListening

    init {
        session.onPartial = { onPartial(if (roman) romanize(it) else it) }
        session.onFinal = { onFinal(if (roman) romanize(it) else it) }
    }

    /** Start listening. Reports [VoiceError.NO_PERMISSION] or [VoiceError.NOT_AVAILABLE] through [onError]. */
    fun start() {
        when {
            !isAvailable(appContext) -> onError(VoiceError.NOT_AVAILABLE)
            !hasMicPermission(appContext) -> onError(VoiceError.NO_PERMISSION)
            else -> {
                session.language = if (roman && language.startsWith("ur", ignoreCase = true)) "hi-IN" else language
                session.start()
            }
        }
    }

    /** Stop recording. The sentence being spoken is still delivered to [onFinal]. */
    fun stop() = session.stop()

    /** Stop at once and deliver nothing more. */
    fun cancel() = session.cancel()

    /** Free the recognizer. Call from onDestroy; the object can be started again later. */
    fun release() {
        session.cancel()
        handler.removeCallbacksAndMessages(null)
        engine.reset()
    }

    companion object {
        private var anyToLatin: Transliterator? = null

        /**
         * [text] in Roman letters: by rule for Hindi letters ([Romanizer]), by
         * Android's transliterator for every other script (Android 10+).
         */
        @JvmStatic
        fun romanize(text: String): String {
            val result = Romanizer.toRoman(text)
            if (result.none { it.isLetter() && it.code > 0x24F }) return result
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return result
            return try {
                val icu = anyToLatin ?: Transliterator.getInstance("Any-Latin; Latin-ASCII").also { anyToLatin = it }
                icu.transliterate(result)
            } catch (e: IllegalArgumentException) {
                result
            }
        }

        /** Does this phone have a speech recognition service at all? */
        @JvmStatic
        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        @JvmStatic
        fun hasMicPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
