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
        emptyList<Voice>() to "ElevenLabs se rabta nahi ho saka. Internet check karein."
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
                if (attempt == 3) return Said(null, "ElevenLabs se rabta nahi ho saka. Internet check karein.", true)
                Thread.sleep(1500)
            }
        }
        return Said(null, "ElevenLabs bohat masroof hai, thori der baad dobara koshish karein.", true)
    }

    /** ElevenLabs' answer in plain words. */
    fun explain(code: Int, body: String): String {
        val status = runCatching { JSONObject(body).optJSONObject("detail")?.optString("status").orEmpty() }.getOrDefault("")
        return when {
            status == "quota_exceeded" -> "ElevenLabs ke is mahine ke huroof khatam ho gaye. Agle mahine ya plan barha kar dobara chalayein; jo ban chuke woh mehfooz hain."
            status == "invalid_api_key" || code == 401 && status.isEmpty() -> "ElevenLabs ki key ghalat hai. Dobara copy kar ke lagayein."
            status == "missing_permissions" -> "Is key ko ijazat nahi. ElevenLabs mein key banate waqt Text to Speech aur Voices ki ijazat dein."
            status == "detected_unusual_activity" -> "ElevenLabs ne free account rok diya (VPN ya naya account ho to aisa hota hai). VPN band karein ya paid plan lein."
            code == 402 -> "Yeh awaaz is plan par nahi chalti. Koi aur awaaz chunein ya plan barhayein."
            code == 404 -> "Yeh awaaz ElevenLabs par nahi mili. Dobara awaaz chunein."
            else -> "ElevenLabs ne mana kar diya ($code). " + body.take(160)
        }
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
