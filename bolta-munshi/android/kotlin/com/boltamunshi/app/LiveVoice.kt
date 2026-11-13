package com.boltamunshi.app

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Replies said by ElevenLabs as whole sentences, so they sound natural, while there is
 * internet. Every line made is kept on the phone, so a line said before ("Theek hai,
 * likh liya.") plays again without internet or credits. Old lines are dropped once the
 * store passes about 40 MB.
 */
class LiveVoice(context: Context) {
    private val dir = File(context.filesDir, "livevoice").apply { mkdirs() }
    @Volatile var problem = ""; private set
    @Volatile private var pausedUntil = 0L

    /** What ElevenLabs is sent: numbers as spoken Urdu words, then Hindi letters (Roman would come out English). */
    fun text(roman: String): String = Devanagari.fromRoman(spokenNumbers(roman))

    private fun file(voice: String, model: String, text: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$voice|$model|$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.pcm")
    }

    fun cached(voice: String, model: String, text: String): ShortArray? {
        val f = file(voice, model, text)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return read(f)
    }

    /** The line as sound: kept copy, or ElevenLabs. Null when it cannot be had now (no internet, no credits). */
    fun get(key: String, voice: String, model: String, text: String): ShortArray? {
        cached(voice, model, text)?.let { return it }
        if (System.currentTimeMillis() < pausedUntil) return null
        val said = ElevenLabs.line(key, voice, model, text)
        val pcm = said.pcm
        if (pcm == null) {
            problem = said.problem
            // a problem that would repeat (key, credits, voice): stop asking for a while, the phone's voice carries on
            if (said.fatal) pausedUntil = System.currentTimeMillis() + 10 * 60 * 1000
            return null
        }
        problem = ""
        val clean = MyVoice.tight(pcm, VoiceClips.RATE)
        runCatching { write(file(voice, model, text), clean); prune() }
        return clean
    }

    private fun prune() {
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 40L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }

    private fun read(f: File): ShortArray? = runCatching {
        val b = f.readBytes()
        ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }.getOrNull()

    private fun write(f: File, pcm: ShortArray) {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(pcm)
        f.writeBytes(buf.array())
    }

    companion object {
        /** "Petrol ke 1,500 rupay" → "Petrol ke ek hazaar paanch sau rupay". */
        fun spokenNumbers(line: String): String =
            Regex("""\d[\d,]*(?:\.\d+)?""").replace(line) { MyVoice.numberWords(it.value) }
    }
}
