package com.boltamunshi.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Replies said by ElevenLabs as whole sentences, so they sound natural, while there is
 * internet. The sound is played as it streams in, so speech starts within a moment, not
 * after the whole line is made. Every line made is kept on the phone, so a line said
 * before ("Theek hai.") plays again at once, without internet or credits. Old lines are
 * dropped once the store passes about 40 MB.
 */
class LiveVoice(context: Context) {
    private val dir = File(context.filesDir, "livevoice").apply { mkdirs() }
    @Volatile var problem = ""; private set
    @Volatile private var pausedUntil = 0L
    @Volatile private var current: Play? = null

    interface Listener {
        /** The first sound is playing: show the line now. */
        fun started()
        /** The line has been heard to its end. */
        fun done()
        /** Nothing could be played (no internet, no credits...): say it another way. */
        fun failed(why: String)
    }

    /** What ElevenLabs is sent: numbers as spoken Urdu words, then Hindi letters (Roman would come out English). */
    fun text(roman: String): String = Devanagari.fromRoman(spokenNumbers(roman))

    private fun file(voice: String, model: String, text: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$voice|$model|$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.pcm")
    }

    /** Speak a line: the kept copy if there is one, else streamed from ElevenLabs and kept. Stops any line before it. */
    fun speak(key: String, voice: String, model: String, text: String, l: Listener) {
        stop()
        val p = Play(l)
        current = p
        Thread {
            val f = file(voice, model, text)
            if (f.exists()) {
                f.setLastModified(System.currentTimeMillis())
                val bytes = f.readBytes()
                p.write(bytes, bytes.size)
                p.finish()
                return@Thread
            }
            if (System.currentTimeMillis() < pausedUntil) { p.fail(problem); return@Thread }
            val kept = ByteArrayOutputStream()
            val why = ElevenLabs.stream(key, voice, model, text) { b, n -> if (!p.stopped) { kept.write(b, 0, n); p.write(b, n) } }
            if (why.isNotEmpty() || kept.size() == 0) {
                problem = why.ifEmpty { "No sound came back" }
                if (problem != "No internet") pausedUntil = System.currentTimeMillis() + 10 * 60 * 1000
                if (!p.anySound) { p.fail(problem); return@Thread }
            } else {
                problem = ""
                if (!p.stopped) runCatching { f.writeBytes(kept.toByteArray()); prune() }
            }
            p.finish()
        }.start()
    }

    fun stop() { current?.stop(); current = null }

    /** One line playing: an AudioTrack fed as the sound arrives. */
    private class Play(val l: Listener) {
        @Volatile var stopped = false
        @Volatile var anySound = false
        private var track: AudioTrack? = null
        private var samples = 0L
        private var odd: Byte? = null

        fun write(bytes: ByteArray, count: Int) {
            if (stopped || count <= 0) return
            var data = bytes; var n = count
            odd?.let { b -> data = ByteArray(count + 1).also { it[0] = b; System.arraycopy(bytes, 0, it, 1, count) }; n = count + 1; odd = null }
            if (n % 2 == 1) { odd = data[n - 1]; n -= 1 }
            if (n == 0) return
            val t = track ?: open() ?: return
            try { t.write(data, 0, n) } catch (e: Exception) { return }      // stopped meanwhile
            samples += n / 2
            if (!anySound) { anySound = true; l.started() }
        }

        private fun open(): AudioTrack? = try {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, RATE))
                .build().also { it.play(); track = it }
        } catch (e: Exception) { null }

        /** Waits for the last sound to be heard, then says so. */
        fun finish() {
            val t = track
            if (t == null) { if (!stopped) l.done(); return }
            while (!stopped && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < samples) Thread.sleep(30)
            release()
            if (!stopped) l.done()
        }

        fun fail(why: String) { if (!stopped) l.failed(why) }

        fun stop() { stopped = true; release() }

        @Synchronized private fun release() {
            val t = track ?: return
            track = null
            runCatching { t.pause(); t.flush() }
            t.release()
        }
    }

    private fun prune() {
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 40L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }

    companion object {
        const val RATE = 16000

        /** "Petrol ke 1,500 rupay" → "Petrol ke ek hazaar paanch sau rupay". */
        fun spokenNumbers(line: String): String =
            Regex("""\d[\d,]*(?:\.\d+)?""").replace(line) { MyVoice.numberWords(it.value) }
    }
}
