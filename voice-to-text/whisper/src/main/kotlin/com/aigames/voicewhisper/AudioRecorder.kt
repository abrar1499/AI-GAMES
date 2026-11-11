package com.aigames.voicewhisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Records from the microphone in the format the speech model needs
 * (16 kHz, mono). The app must hold the RECORD_AUDIO permission.
 *
 * ```
 * recorder.start()
 * ...
 * val samples = recorder.stop()     // FloatArray for WhisperEngine.transcribe
 * ```
 */
class AudioRecorder(private val maxSeconds: Int = 300) {

    /** Loudness from 0 to 1 while recording, on the main thread. */
    var onLevel: (Float) -> Unit = {}

    /** Called on the main thread if recording stops by itself at [maxSeconds]. */
    var onLimitReached: () -> Unit = {}

    @Volatile
    var isRecording = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    private var total = 0

    /** Seconds recorded so far. */
    val seconds: Float get() = synchronized(chunks) { total } / SAMPLE_RATE.toFloat()

    /** @return false if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE))
        } catch (e: IllegalArgumentException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        synchronized(chunks) {
            chunks.clear()
            total = 0
        }
        isRecording = true
        record.startRecording()
        thread = Thread({ capture(record) }, "voice-recorder").also { it.start() }
        return true
    }

    /** Stops and returns everything recorded, as values from -1 to 1. */
    fun stop(): FloatArray {
        isRecording = false
        thread?.join(1500)
        thread = null
        return synchronized(chunks) { toFloats(chunks, total) }
    }

    private fun capture(record: AudioRecord) {
        val buffer = ShortArray(SAMPLE_RATE / 10)
        val limit = maxSeconds * SAMPLE_RATE
        try {
            while (isRecording) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                val full = synchronized(chunks) {
                    chunks.add(buffer.copyOf(n))
                    total += n
                    total >= limit
                }
                val level = loudness(buffer, n)
                main.post { onLevel(level) }
                if (full) {
                    isRecording = false
                    main.post { onLimitReached() }
                }
            }
        } finally {
            isRecording = false
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Joins 16-bit chunks into one array of values from -1 to 1. */
        @JvmStatic
        fun toFloats(chunks: List<ShortArray>, total: Int): FloatArray {
            val out = FloatArray(total)
            var at = 0
            for (chunk in chunks) {
                for (s in chunk) {
                    if (at >= total) return out
                    out[at++] = s / 32768f
                }
            }
            return out
        }

        /** Root-mean-square loudness scaled so normal speech lands around 0.3 to 0.8. */
        @JvmStatic
        fun loudness(buffer: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val v = buffer[i] / 32768.0
                sum += v * v
            }
            return (sqrt(sum / count) * 6.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
