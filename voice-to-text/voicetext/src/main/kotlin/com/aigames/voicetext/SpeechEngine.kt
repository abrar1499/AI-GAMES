package com.aigames.voicetext

/**
 * The thing that actually listens. On a phone this is Android's
 * SpeechRecognizer ([AndroidSpeechEngine]); tests use a fake one.
 * All calls and all events happen on the main thread.
 */
internal interface SpeechEngine {
    var events: Events?

    fun start(language: String, preferOffline: Boolean)

    /** Stop recording; whatever was heard so far still arrives as a result. */
    fun stop()

    /** Stop and throw away whatever was heard. */
    fun cancel()

    /** Drop the recognizer so the next [start] makes a fresh one. */
    fun reset()

    interface Events {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onFailure(error: VoiceError)
        fun onLevel(level: Float)
    }
}
