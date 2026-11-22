package com.lumo.app.call

import android.media.AudioFormat
import org.webrtc.audio.AudioRecordDataCallback
import java.nio.ByteBuffer

/**
 * AI noise cancelling: every 10 ms of microphone audio passes through RNNoise (a small neural
 * network trained to tell voice from noise) right before WebRTC compresses it, so fans, traffic,
 * keyboards and crowds are removed while the voice stays. Runs on the phone; costs a few % CPU.
 */
object Denoiser : AudioRecordDataCallback {
    @Volatile var enabled = false

    /** How likely the last audio held a voice (0..1), for a live indicator. */
    @Volatile var voice = 0f
        private set

    private val loaded = runCatching { System.loadLibrary("lumo_denoise") }.isSuccess
    private var handle = 0L

    val available get() = loaded

    override fun onAudioDataRecorded(audioFormat: Int, channelCount: Int, sampleRate: Int, buffer: ByteBuffer) {
        if (!enabled || !loaded) return
        // RNNoise works on 48 kHz mono 16-bit audio, which is what Lumo asks the microphone for.
        if (sampleRate != 48_000 || channelCount != 1 || audioFormat != AudioFormat.ENCODING_PCM_16BIT || !buffer.isDirect) return
        if (handle == 0L) handle = nativeCreate()
        val v = nativeProcess(handle, buffer, buffer.capacity() / 2)
        if (v >= 0) voice = v
    }

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeProcess(handle: Long, buffer: ByteBuffer, samples: Int): Float
}
