package com.boltamunshi.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ElevenLabs makes the munshi's words once, in a voice the user picks there (one of theirs,
 * or their own cloned voice). Each word comes back as 16 kHz sound, the same as a recording
 * made on the phone, and is kept with the recordings, so after that no internet is needed.
 * The key is typed into the app by the user and only ever sent to ElevenLabs.
 */
object ElevenLabs {
    private const val API = "https://api.elevenlabs.io/v1"
    const val MODEL = "eleven_multilingual_v2"

    class Voice(val id: String, val name: String)
    class Said(val pcm: ShortArray?, val problem: String, val fatal: Boolean)

    /**
     * The text sent for a word: Hindi letters, which the voice reads with the right accent
     * (Roman letters would come out English). The words around it make it sound like part
     * of a sentence instead of a word said on its own.
     */
    fun request(word: String): JSONObject = JSONObject()
        .put("text", Devanagari.fromRoman(word))
        .put("model_id", MODEL)
        .put("previous_text", "अच्छा तो")
        .put("next_text", "और बस।")
        .put("voice_settings", JSONObject().put("stability", 0.6).put("similarity_boost", 0.8).put("style", 0.0).put("use_speaker_boost", true))

    fun voices(key: String): Pair<List<Voice>, String> = try {
        val c = open("$API/voices", key, "GET")
        val code = c.responseCode
        val body = read(c, code)
        if (code != 200) emptyList<Voice>() to explain(code, body)
        else {
            val arr = JSONObject(body).optJSONArray("voices") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { Voice(it.optString("voice_id"), it.optString("name")) } to ""
        }
    } catch (e: Exception) {
        emptyList<Voice>() to "No internet"
    }

    /** One word or phrase as sound. Retries a few times when ElevenLabs says to slow down. */
    fun say(key: String, voice: String, word: String): Said = speak(key, voice, request(word), "pcm_16000")

    /**
     * A whole sentence for training the offline voice: Hindi letters, the model the user picked,
     * a steady voice so every sentence sounds alike, 22 kHz sound (what the offline voice is trained on).
     */
    fun sentence(key: String, voice: String, model: String, text: String): Said = speak(key, voice, JSONObject()
        .put("text", text)
        .put("model_id", model.ifBlank { MODEL })
        .put("voice_settings", JSONObject().put("stability", 0.7).put("similarity_boost", 0.8).put("style", 0.0).put("use_speaker_boost", true)),
        "pcm_22050")

    /** A whole reply, said live: 16 kHz like the word recordings, a natural (less steady) voice. */
    fun line(key: String, voice: String, model: String, text: String): Said = speak(key, voice, JSONObject()
        .put("text", text)
        .put("model_id", model.ifBlank { MODEL })
        .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.8).put("style", 0.0).put("use_speaker_boost", true)),
        "pcm_16000")

    /** Where a streamed line's sound goes, chunk by chunk, as it arrives. */
    fun interface Sink { fun write(bytes: ByteArray, count: Int) }

    /**
     * A reply spoken as it is made: ElevenLabs' streaming endpoint with its lowest-latency
     * setting, 16 kHz sound handed to [sink] the moment each piece arrives. "" when it worked,
     * else why not (nothing was written then).
     */
    fun stream(key: String, voice: String, model: String, text: String, sink: Sink): String = try {
        val body = JSONObject().put("text", text).put("model_id", model.ifBlank { MODEL })
            .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.8).put("style", 0.0).put("use_speaker_boost", true))
        val c = open("$API/text-to-speech/$voice/stream?output_format=pcm_16000&optimize_streaming_latency=3", key, "POST")
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code != 200) explain(code, read(c, code))
        else {
            c.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) { val n = input.read(buf); if (n < 0) break; if (n > 0) sink.write(buf, n) }
            }
            ""
        }
    } catch (e: Exception) {
        "No internet"
    }

    private fun speak(key: String, voice: String, first: JSONObject, format: String): Said {
        var body = first
        repeat(4) { attempt ->
            try {
                val c = open("$API/text-to-speech/$voice?output_format=$format", key, "POST")
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code == 200) {
                    val bytes = c.inputStream.use { it.readBytes() }
                    val pcm = ShortArray(bytes.size / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    return Said(pcm, "", false)
                }
                val err = read(c, code)
                when {
                    code == 429 -> Thread.sleep(2000L * (attempt + 1))
                    code == 422 && body.has("previous_text") -> { body = JSONObject(body.toString()).apply { remove("previous_text"); remove("next_text") } }
                    else -> return Said(null, explain(code, err), code == 401 || code == 402 || code == 404)
                }
            } catch (e: Exception) {
                if (attempt == 3) return Said(null, "No internet", true)
                Thread.sleep(1500)
            }
        }
        return Said(null, "Busy · try again later", true)
    }

    /** ElevenLabs' answer in plain words. */
    fun explain(code: Int, body: String): String {
        val status = runCatching { JSONObject(body).optJSONObject("detail")?.optString("status").orEmpty() }.getOrDefault("")
        return when {
            status == "quota_exceeded" -> "No credits left this month"
            status == "invalid_api_key" || code == 401 && status.isEmpty() -> "Invalid key · copy it again"
            status == "missing_permissions" -> "Key lacks permission · allow Text to Speech and Voices"
            status == "detected_unusual_activity" -> "Free account blocked (VPN or new account)"
            code == 402 -> "This voice needs a paid plan"
            code == 404 -> "Voice not found · choose it again"
            code == 429 -> "Busy · retries later"
            else -> "Not answering ($code)"
        }
    }

    /** Characters left this month, or why it cannot be told; null and a reason when the key does not work. */
    fun credits(key: String): Pair<Long?, String> = try {
        val c = open("$API/user/subscription", key, "GET")
        val code = c.responseCode
        val body = read(c, code)
        if (code == 200) {
            val o = JSONObject(body)
            (o.optLong("character_limit") - o.optLong("character_count")) to ""
        } else if (code == 401 && body.contains("missing_permissions")) {
            -1L to "Working · credits hidden (key cannot read the account)"
        } else null to explain(code, body)
    } catch (e: Exception) {
        null to "No internet"
    }

    private fun open(url: String, key: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000; readTimeout = 60000
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Content-Type", "application/json")
        }

    private fun read(c: HttpURLConnection, code: Int): String =
        runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: "" }.getOrDefault("")
}
