package com.boltamunshi.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Training data for an offline voice: every sentence in voice-model/sentences.txt said
 * by ElevenLabs, kept as a WAV file, and packed with its text into one zip in Downloads
 * (the form the voice training expects: wavs/0001.wav and metadata.csv "0001|text").
 * Sentences already made are kept, so stopping and starting again carries on.
 */
class VoiceData(private val context: Context) {
    private val dir = File(context.filesDir, "voicedata").apply { mkdirs() }

    val sentences: List<String> by lazy {
        runCatching { context.assets.open("sentences.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())
    }

    fun id(i: Int) = String.format("%04d", i + 1)
    private fun wav(i: Int) = File(dir, id(i) + ".wav")
    fun done(i: Int) = wav(i).exists()
    fun doneCount() = sentences.indices.count { done(it) }

    /** What is sent to ElevenLabs and written in metadata.csv: Hindi letters, so the accent and the training text agree. */
    fun text(i: Int) = Devanagari.fromRoman(sentences[i])

    fun save(i: Int, pcm: ShortArray) { wav(i).writeBytes(wavBytes(trim(pcm), RATE)) }

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    /** Silence at the start and end cut to a short, even 150 ms, the way voice training likes it. */
    private fun trim(pcm: ShortArray): ShortArray {
        val frame = RATE / 100
        val n = pcm.size / frame
        if (n < 3) return pcm
        val lv = DoubleArray(n) { MyVoice.level(pcm, it * frame, frame) }
        val gate = (lv.maxOrNull() ?: 0.0) * 0.02
        val first = lv.indexOfFirst { it > gate }.coerceAtLeast(0)
        val last = lv.indexOfLast { it > gate }.let { if (it < 0) n - 1 else it }
        val pad = ShortArray(RATE * 150 / 1000)
        return pad + pcm.copyOfRange(first * frame, minOf(pcm.size, (last + 1) * frame)) + pad
    }

    /** Everything made so far, as bolta-munshi-awaaz-data.zip in Downloads. Returns where it went, or null. */
    fun export(): String? {
        val name = "bolta-munshi-awaaz-data.zip"
        val out: OutputStream
        val where: String
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = context.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            out = cr.openOutputStream(uri, "wt") ?: return null
            where = "Downloads/$name"
        } else {
            val f = File(context.getExternalFilesDir(null), name)
            out = f.outputStream()
            where = f.absolutePath
        }
        ZipOutputStream(out.buffered()).use { zip ->
            val meta = StringBuilder()
            val roman = StringBuilder()
            for (i in sentences.indices) {
                if (!done(i)) continue
                zip.putNextEntry(ZipEntry("wavs/" + id(i) + ".wav"))
                zip.write(wav(i).readBytes())
                zip.closeEntry()
                meta.append(id(i)).append('|').append(text(i)).append('\n')
                roman.append(id(i)).append('|').append(sentences[i]).append('\n')
            }
            zip.putNextEntry(ZipEntry("metadata.csv")); zip.write(meta.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("roman.csv")); zip.write(roman.toString().toByteArray()); zip.closeEntry()
        }
        return where
    }

    companion object {
        const val RATE = 22050

        fun wavBytes(pcm: ShortArray, rate: Int): ByteArray {
            val data = pcm.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            b.asShortBuffer().put(pcm)
            return b.array()
        }
    }
}
