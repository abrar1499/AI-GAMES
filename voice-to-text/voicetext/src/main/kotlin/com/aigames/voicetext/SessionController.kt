package com.aigames.voicetext

/**
 * Decides what to do with each event from the [SpeechEngine]: pass text on,
 * start listening again in continuous mode, retry when the recognizer is
 * busy, and stay quiet about errors that arrive after the user said stop.
 *
 * It knows nothing about Android, so it can be tested on a computer.
 *
 * @param later runs a block after a delay in milliseconds, on the main thread.
 */
internal class SessionController(
    private val engine: SpeechEngine,
    private val later: (delayMs: Long, block: () -> Unit) -> Unit,
) : SpeechEngine.Events {

    var language = "en-US"
    var continuous = false
    var preferOffline = false

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onError: (VoiceError) -> Unit = {}
    var onListeningChanged: (Boolean) -> Unit = {}
    var onLevel: (Float) -> Unit = {}

    /** True from [start] until listening has completely ended. */
    var isListening = false
        private set

    /** The user wants to keep listening (not yet stopped or cancelled). */
    private var wanted = false

    /** [cancel] was called: ignore whatever the engine still sends. */
    private var cancelled = false

    /** The engine is recording or working on a result, so an event is still to come. */
    private var engineActive = false

    private var busyRetries = 0

    /** Goes up on every start/stop/cancel so delayed restarts can tell they are stale. */
    private var generation = 0

    init {
        engine.events = this
    }

    fun start() {
        if (wanted) return
        wanted = true
        cancelled = false
        busyRetries = 0
        generation++
        setListening(true)
        startEngine()
    }

    /** Stop recording. Text already heard is still delivered. */
    fun stop() {
        if (!wanted) return
        wanted = false
        generation++
        if (!engineActive) {
            // Between two sentences in continuous mode: nothing is recording.
            finish()
            return
        }
        engine.stop()
        // Some phones never answer a stop when nothing was said.
        val mine = generation
        later(STOP_TIMEOUT_MS) {
            if (mine == generation && isListening) {
                engineActive = false
                engine.cancel()
                finish()
            }
        }
    }

    /** Stop at once and deliver nothing more. */
    fun cancel() {
        wanted = false
        cancelled = true
        engineActive = false
        generation++
        engine.cancel()
        setListening(false)
    }

    override fun onReady() {
        busyRetries = 0
    }

    override fun onPartial(text: String) {
        if (cancelled || text.isBlank()) return
        onPartial.invoke(text.trim())
    }

    override fun onResult(text: String) {
        if (cancelled) return
        engineActive = false
        if (text.isNotBlank()) onFinal(text.trim())
        if (wanted && continuous) restart(RESTART_DELAY_MS) else finish()
    }

    override fun onFailure(error: VoiceError) {
        if (cancelled) return
        engineActive = false
        if (!wanted) {
            // The user already pressed stop: nothing heard is not worth an error.
            finish()
            return
        }
        when {
            error == VoiceError.BUSY && busyRetries < MAX_BUSY_RETRIES -> {
                busyRetries++
                engine.reset()
                restart(BUSY_DELAY_MS)
            }
            error == VoiceError.NOTHING_HEARD && continuous -> {
                // Silence between sentences: just keep listening.
                restart(RESTART_DELAY_MS)
            }
            else -> {
                wanted = false
                engine.reset()
                finish()
                onError(error)
            }
        }
    }

    override fun onLevel(level: Float) {
        if (isListening) onLevel.invoke(level.coerceIn(0f, 1f))
    }

    private fun restart(delayMs: Long) {
        val mine = generation
        later(delayMs) {
            if (wanted && mine == generation) startEngine()
        }
    }

    private fun startEngine() {
        engineActive = true
        engine.start(language, preferOffline)
    }

    private fun finish() {
        wanted = false
        generation++
        setListening(false)
    }

    private fun setListening(value: Boolean) {
        if (isListening == value) return
        isListening = value
        onListeningChanged(value)
    }

    companion object {
        const val RESTART_DELAY_MS = 120L
        const val BUSY_DELAY_MS = 400L
        const val STOP_TIMEOUT_MS = 2500L
        const val MAX_BUSY_RETRIES = 3
    }
}
