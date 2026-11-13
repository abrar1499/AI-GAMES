package com.boltamunshi.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The user's recordings on the phone: recording one (it stops by itself when the user
 * goes quiet), keeping it, and playing several joined into one line.
 */
class VoiceClips(context: Context) {
    private val dir = File(context.filesDir, "myvoice").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private var keys: MutableSet<String>? = null
    private var track: AudioTrack? = null
    @Volatile private var stopAsked = false
    @Volatile var recording = false; private set

    fun recorded(): Set<String> = keys ?: (dir.list().orEmpty().filter { it.endsWith(".pcm") }.map { MyVoice.keyOf(it) }.toMutableSet()).also { keys = it }
    fun has(key: String) = key in recorded()

    fun load(key: String): ShortArray? = runCatching {
        val bytes = File(dir, MyVoice.fileName(key)).readBytes()
        ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }.getOrNull()

    fun save(key: String, pcm: ShortArray) {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(pcm)
        File(dir, MyVoice.fileName(key)).writeBytes(buf.array())
        recorded(); keys?.add(key)
    }

    fun delete(key: String) { File(dir, MyVoice.fileName(key)).delete(); keys?.remove(key) }
    fun deleteAll() { dir.listFiles()?.forEach { it.delete() }; keys = mutableSetOf() }

    // ------------------------------------------------------------------ recording ---

    /**
     * Records until the user has spoken and then stayed quiet for a moment (or [stopRecording]).
     * [done] gets the cleaned recording on the main thread: empty if nothing was heard.
     */
    fun record(done: (ShortArray) -> Unit) {
        if (recording) return
        recording = true; stopAsked = false
        Thread {
            val pcm = runCatching { capture() }.getOrDefault(ShortArray(0))
            val clean = MyVoice.clean(pcm, RATE)
            main.post { recording = false; done(clean) }
        }.start()
    }

    fun stopRecording() { stopAsked = true }

    @Suppress("MissingPermission")    // asked for before the recording screen opens
    private fun capture(): ShortArray {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        val all = ShortArray(RATE * MAX_SECONDS)
        var size = 0
        val frame = RATE / 100
        var floor = 0.0; var heard = false; var quiet = 0
        try {
            rec.startRecording()
            while (!stopAsked && size + frame <= all.size) {
                val n = rec.read(all, size, frame)
                if (n <= 0) break
                val lvl = MyVoice.level(all, size, n)
                size += n
                val ms = size * 1000 / RATE
                if (ms <= 250) { floor = maxOf(floor, lvl); continue }      // the room before the user speaks
                val loud = lvl > maxOf(floor * 3, 700.0)
                if (loud) { heard = true; quiet = 0 } else if (heard) quiet += n * 1000 / RATE
                if (heard && quiet >= 900) break
                if (!heard && ms >= 7000) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
        return all.copyOf(size)
    }

    // ------------------------------------------------------------------- playing ---

    /** Plays recordings and pauses as one sound; [done] runs on the main thread when it has finished. */
    fun play(parts: List<MyVoice.Part>, done: Runnable) {
        stopPlaying()
        val all = MyVoice.join(parts, { load(it) }, RATE)
        val total = all.size
        if (total == 0) { main.post(done); return }
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(total * 2)
                .build()
            t.write(all, 0, total)
            t.play()
            track = t
            val ms = total * 1000L / RATE
            main.postDelayed({ if (track === t) { stopPlaying(); done.run() } }, ms + 80)
        } catch (e: Exception) {
            main.post(done)
        }
    }

    fun stopPlaying() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        t.release()
    }

    companion object {
        const val RATE = 16000
        private const val MAX_SECONDS = 10
    }
}
