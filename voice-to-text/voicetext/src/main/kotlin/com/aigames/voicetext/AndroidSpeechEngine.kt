package com.aigames.voicetext

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** [SpeechEngine] backed by the speech recognition service built into Android. */
internal class AndroidSpeechEngine(context: Context) : SpeechEngine {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override var events: SpeechEngine.Events? = null

    override fun start(language: String, preferOffline: Boolean) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun reset() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            events?.onReady()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            events?.onPartial(best(partialResults))
        }

        override fun onResults(results: Bundle?) {
            events?.onResult(best(results))
        }

        override fun onError(error: Int) {
            events?.onFailure(toVoiceError(error))
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Roughly -2 dB (quiet) to 10 dB (loud); scale to 0..1.
            events?.onLevel((rmsdB + 2f) / 12f)
        }

        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun toVoiceError(code: Int): VoiceError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NOTHING_HEARD
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.NETWORK
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.NO_PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
        SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
        SpeechRecognizer.ERROR_SERVER -> VoiceError.SERVER
        ERROR_LANGUAGE_NOT_SUPPORTED,
        ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.LANGUAGE_NOT_SUPPORTED
        else -> VoiceError.UNKNOWN
    }

    private companion object {
        // Added in Android 12; the numbers are used so this compiles for older phones too.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
