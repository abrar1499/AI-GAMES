package com.boltamunshi.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Google Cloud Text-to-Speech: natural Urdu and Hindi voices with a large free monthly
 * allowance. The key (an API key from a Google Cloud project with the Text-to-Speech API
 * turned on) is typed into the app and only ever sent to Google. Each line made is kept
 * on the phone, so a line said before plays again without internet.
 */
class GoogleVoice(context: Context) {
    private val dir = File(context.filesDir, "googlevoice").apply { mkdirs() }

    class Voice(val name: String, val lang: String, val kind: String, val gender: String)
    class Said(val pcm: ShortArray?, val problem: String)

    /** Urdu and Hindi voices on this key, the most natural kinds first. */
    fun voices(key: String): Pair<List<Voice>, String> = try {
        val c = URL("$API/voices?key=$key").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        val code = c.responseCode
        val body = read(c, code)
        if (code != 200) emptyList<Voice>() to explain(code, body)
        else {
            val arr = JSONObject(body).optJSONArray("voices")
            val out = mutableListOf<Voice>()
            for (i in 0 until (arr?.length() ?: 0)) {
                val v = arr!!.getJSONObject(i)
                val lang = v.optJSONArray("languageCodes")?.optString(0).orEmpty()
                if (!lang.startsWith("ur") && lang != "hi-IN") continue
                val name = v.optString("name")
                out += Voice(name, lang, kindOf(name), v.optString("ssmlGender").lowercase().replaceFirstChar { it.uppercase() })
            }
            out.sortedWith(compareBy({ if (it.lang.startsWith("ur")) 0 else 1 }, { rank(it.kind) }, { it.name })) to ""
        }
    } catch (e: Exception) {
        emptyList<Voice>() to "No internet"
    }

    private fun kindOf(name: String) = when {
        name.contains("Chirp3") -> "Chirp 3 HD"
        name.contains("Chirp") -> "Chirp HD"
        name.contains("Neural2") -> "Neural2"
        name.contains("Wavenet") -> "WaveNet"
        name.contains("Studio") -> "Studio"
        else -> "Standard"
    }

    private fun rank(kind: String) = listOf("Chirp 3 HD", "Chirp HD", "Neural2", "Studio", "WaveNet", "Standard").indexOf(kind)

    private fun file(voice: String, text: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$voice|$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.pcm")
    }

    /** The line as 16 kHz sound: the kept copy, or made by Google and kept. */
    fun say(key: String, voice: String, text: String): Said {
        val f = file(voice, text)
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            return Said(shorts(f.readBytes()), "")
        }
        return try {
            val lang = voice.split("-").take(2).joinToString("-")
            val body = JSONObject()
                .put("input", JSONObject().put("text", text))
                .put("voice", JSONObject().put("languageCode", lang).put("name", voice))
                .put("audioConfig", JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", 16000))
            val c = URL("$API/text:synthesize?key=$key").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000; c.readTimeout = 12000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text2 = read(c, code)
            if (code != 200) return Said(null, explain(code, text2))
            val wav = Base64.decode(JSONObject(text2).optString("audioContent"), Base64.DEFAULT)
            val pcm = pcmOf(wav)
            if (pcm.size < 400) return Said(null, "No sound came back")
            runCatching { f.writeBytes(pcm); prune() }
            Said(shorts(pcm), "")
        } catch (e: Exception) {
            Said(null, "No internet")
        }
    }

    private fun shorts(b: ByteArray) = ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }

    private fun prune() {
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 40L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }

    private fun read(c: HttpURLConnection, code: Int): String =
        runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: "" }.getOrDefault("")

    companion object {
        private const val API = "https://texttospeech.googleapis.com/v1"

        /** The sound inside a WAV file: everything after its "data" chunk header. */
        fun pcmOf(wav: ByteArray): ByteArray {
            if (wav.size < 12 || String(wav, 0, 4) != "RIFF") return wav
            var i = 12
            while (i + 8 <= wav.size) {
                val id = String(wav, i, 4)
                val size = ByteBuffer.wrap(wav, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") return wav.copyOfRange(i + 8, minOf(wav.size, if (size > 0) i + 8 + size else wav.size))
                i += 8 + maxOf(0, size)
            }
            return wav.copyOfRange(minOf(44, wav.size), wav.size)
        }

        /** Google's answer in plain words. */
        fun explain(code: Int, body: String): String {
            val b = body.lowercase()
            return when {
                b.contains("api key not valid") || b.contains("api_key_invalid") -> "Invalid key · copy it again"
                b.contains("service_disabled") || b.contains("has not been used") || b.contains("is disabled") -> "Turn on the Text-to-Speech API in Google Cloud"
                b.contains("billing") -> "Google Cloud needs billing turned on for this project"
                b.contains("api_key_service_blocked") || b.contains("are blocked") -> "This key is not allowed to use Text-to-Speech"
                code == 429 || b.contains("quota") -> "Limit reached · retries later"
                code == 403 -> "Google refused this key (403)"
                else -> "Not answering ($code)"
            }
        }
    }
}
