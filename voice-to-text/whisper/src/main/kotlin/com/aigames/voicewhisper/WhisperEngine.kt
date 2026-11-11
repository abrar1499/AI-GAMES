package com.aigames.voicewhisper

import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Turns recorded speech into text on the phone itself with whisper.cpp.
 *
 * ```
 * val engine = WhisperEngine(modelFile)            // slow: loads the model
 * val text = engine.transcribe(samples, "ur")      // slow: call off the main thread
 * engine.close()
 * ```
 *
 * [transcribe] must not be called from two threads at once.
 */
class WhisperEngine @Throws(IOException::class) constructor(modelFile: File) : Closeable {

    private var handle: Long = nativeLoad(modelFile.absolutePath)

    init {
        if (handle == 0L) throw IOException("Could not load the speech model: ${modelFile.name}")
    }

    /**
     * @param samples mono audio at 16 kHz, each value from -1 to 1 (what [AudioRecorder] gives).
     * @param language a two-letter code such as "ur", "hi" or "en".
     * @return the text, or null if it failed or [cancel] was called.
     */
    fun transcribe(samples: FloatArray, language: String): String? {
        if (handle == 0L || samples.isEmpty()) return null
        return nativeTranscribe(handle, samples, language, threads())?.let { WhisperText.clean(it) }
    }

    /** Stops a [transcribe] that is running on another thread. */
    fun cancel() {
        if (handle != 0L) nativeAbort(handle)
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int): String?
    private external fun nativeAbort(handle: Long)
    private external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("voicewhisper")
        }
    }
}
