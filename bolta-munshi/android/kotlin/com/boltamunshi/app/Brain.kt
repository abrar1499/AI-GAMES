package com.boltamunshi.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The one brain: an AI that hears every sentence, reads the memory book and answers.
 * The user's keys are tried in order (Claude, OpenRouter, Gemini, Groq). Free keys have
 * limits per model, so when one model is at its limit the next model on the same key is
 * tried; a key that failed is tried after the others for a while, but never left out, so
 * a single key keeps working. Each key's status (working, limit reached and until when,
 * invalid...) is kept for the Brain screen, with the service's own words about the problem.
 */
class Brain(private val prefs: SharedPreferences) {

    class Service(val name: String, val slot: String, val url: String, val models: List<String>, val prefix: String, val modelsUrl: String)

    private val resting = mutableMapOf<String, Long>()
    @Volatile var lastProblem = ""; private set

    /** Which keys are set and switched on. */
    fun ready(): Boolean = SERVICES.any { key(it).isNotEmpty() && prefs.getBoolean("on_" + it.slot, true) }

    fun key(s: Service) = prefs.getString(s.slot, "").orEmpty().trim()

    /**
     * Asks the services that are on and have a key until one answers with text: the ones
     * that failed lately go last, and nothing new is started after [budgetMs].
     * [messages] is the conversation (role, content) after the system prompt.
     */
    fun ask(system: String, messages: List<Pair<String, String>>, budgetMs: Long = 15_000): String? {
        val start = System.currentTimeMillis()
        val why = StringBuilder()
        val usable = SERVICES.filter { key(it).isNotEmpty() && prefs.getBoolean("on_" + it.slot, true) }
        val order = usable.sortedBy { if ((resting[it.name] ?: 0L) > start) 1 else 0 }       // stable: the user's order otherwise
        for (s in order) {
            if (System.currentTimeMillis() - start > budgetMs) break
            val got = askOne(s, key(s), system, messages)
            if (got != null) { resting.remove(s.name); lastProblem = ""; return got }
            resting[s.name] = System.currentTimeMillis() + restFor(s)
            why.append(s.name).append(": ").append(statusText(s)).append('\n')
        }
        lastProblem = why.toString().trim().ifEmpty { "No AI key is on · Settings › Brain" }
        return null
    }

    private fun restFor(s: Service): Long = when (prefs.getString("st_" + s.slot, "")) {
        "limit" -> (prefs.getLong("until_" + s.slot, 0L) - System.currentTimeMillis()).coerceIn(30_000L, 10 * 60_000L)
        "bad", "blocked", "wrong" -> 10 * 60_000L
        "offline" -> 0L
        else -> 60_000L
    }

    /** One service, trying its models in turn; its status is saved either way. */
    fun askOne(s: Service, key: String, system: String, messages: List<Pair<String, String>>): String? {
        var code = -1
        var body = ""
        var limited = false
        var limitUntil = 0L
        var timedOut = false
        val claude = s.slot == "key_claude"
        val models = (listOf(prefs.getString("stm_" + s.slot, "").orEmpty()) +
            (if (claude) listOf(prefs.getString("claudeModel", "").orEmpty().takeIf { it.startsWith("claude-") } ?: "") else emptyList()) +
            (if (s.slot == "key_openrouter") freeModels() else s.models) + liveModels(s, key)).filter { it.isNotBlank() }.distinct().take(7)
        for (model in models) {
            try {
                val c = open(s.url)
                val req: JSONObject
                if (claude) {
                    c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2026-10-21")
                    req = JSONObject().put("model", model).put("max_tokens", 4000).put("system", system)
                        .put("output_config", JSONObject().put("effort", "low"))
                        .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) } })
                } else {
                    c.setRequestProperty("Authorization", "Bearer $key")
                    req = JSONObject().put("model", model).put("max_tokens", 1500).put("temperature", 0.3)
                        .put("response_format", JSONObject().put("type", "json_object"))
                        .put("messages", JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", system))
                            messages.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) }
                        })
                    // thinking models: think a little, so the answer is quick and not cut off
                    if (model.contains("gpt-oss") || s.slot == "key_gemini" && !model.contains("2.0")) req.put("reasoning_effort", "low")
                }
                c.outputStream.use { it.write(req.toString().toByteArray()) }
                code = c.responseCode
                body = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
                if (code == 200) {
                    val text = textOf(claude, body)
                    if (!text.isNullOrBlank()) return worked(s, model, text)
                    continue
                }
                // Groq's JSON mode refuses an answer that is not quite JSON, but sends it back: use it if it holds the object
                failedGeneration(body)?.let { return worked(s, model, it) }
                if (code == 401) break                                                    // the key itself: other models won't help
                if (code == 403 && !body.contains("model", true)) break
                if (code == 429 || code == 402 || code == 413 || kindOf(code, body) == "limit") {
                    limited = true
                    limitUntil = maxOf(limitUntil, System.currentTimeMillis() + retryAfter(c.getHeaderField("retry-after"), body))
                    if (claude) break                                                     // one limit for the whole key
                }
                // a model that is gone, busy or at its own limit: the next model on this key
            } catch (e: SocketTimeoutException) {
                timedOut = true
                break
            } catch (e: java.io.IOException) {
                code = -1
                break
            } catch (e: Exception) {
                continue                                                                  // an answer that could not be read
            }
        }
        val kind = when {
            timedOut -> "slow"
            limited && code != 401 -> "limit"
            else -> kindOf(code, body)
        }
        prefs.edit().putString("st_" + s.slot, kind).putString("std_" + s.slot, detailOf(body))
            .putLong("until_" + s.slot, if (kind == "limit") limitUntil else 0L).apply()
        return null
    }

    private fun worked(s: Service, model: String, text: String): String {
        prefs.edit().putString("st_" + s.slot, "ok").putString("stm_" + s.slot, model).remove("std_" + s.slot).remove("until_" + s.slot).apply()
        return text
    }

    private fun textOf(claude: Boolean, body: String): String? {
        val o = JSONObject(body)
        return if (claude) {
            if (o.optString("stop_reason") == "refusal") null
            else (0 until (o.optJSONArray("content")?.length() ?: 0)).map { o.getJSONArray("content").getJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } else o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 7000; readTimeout = 18000; doOutput = true
        setRequestProperty("Content-Type", "application/json")
    }

    /** OpenRouter's free models, fetched once a day. */
    private fun freeModels(): List<String> {
        val cached = prefs.getString("freeModels", "").orEmpty()
        if (cached.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("freeModelsAt", 0) < 86_400_000L) return cached.split(",").filter { it.isNotBlank() }
        return try {
            val c = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 10000
            val all = JSONObject(c.inputStream.use { String(it.readBytes()) }).getJSONArray("data")
            val liked = listOf("deepseek", "llama", "qwen", "gpt-oss", "gemini", "gemma", "mistral")
            val free = (0 until all.length()).map { all.getJSONObject(it).optString("id") }.filter { it.endsWith(":free") }
                .sortedBy { id -> liked.indexOfFirst { id.contains(it) }.let { if (it < 0) 99 else it } }
            prefs.edit().putString("freeModels", free.joinToString(",")).putLong("freeModelsAt", System.currentTimeMillis()).apply()
            free
        } catch (e: Exception) { emptyList() }
    }

    /**
     * The chat models this key can use right now (Groq, Gemini), asked once a day: models
     * are retired from time to time, and a retired model must not leave the key silent.
     */
    private fun liveModels(s: Service, key: String): List<String> {
        if (s.modelsUrl.isEmpty()) return emptyList()
        val slot = "models_" + s.slot
        val cached = prefs.getString(slot, "").orEmpty()
        if (cached.isNotEmpty() && System.currentTimeMillis() - prefs.getLong(slot + "_at", 0) < 86_400_000L) return cached.split(",").filter { it.isNotBlank() }
        return try {
            val c = URL(s.modelsUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $key")
            if (c.responseCode != 200) return emptyList()
            val ids = JSONObject(c.inputStream.use { String(it.readBytes()) }).getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id").removePrefix("models/") } }
            val list = chatModels(s.slot, ids)
            prefs.edit().putString(slot, list.joinToString(",")).putLong(slot + "_at", System.currentTimeMillis()).apply()
            list
        } catch (e: Exception) { emptyList() }
    }

    fun statusText(s: Service): String {
        if (!prefs.getBoolean("on_" + s.slot, true)) return "Off"
        if (key(s).isEmpty()) return "No key"
        return when (prefs.getString("st_" + s.slot, "")) {
            "ok" -> "Working" + prefs.getString("stm_" + s.slot, "").orEmpty().let { if (it.isEmpty()) "" else " · $it" }
            "limit" -> "Limit reached · " + backIn(prefs.getLong("until_" + s.slot, 0L))
            "bad" -> "Invalid key · copy it again"
            "blocked" -> "Blocked here · try a VPN or another key"
            "offline" -> "No internet"
            "slow" -> "Too slow to answer · tries again"
            "checking" -> "Checking…"
            "wrong" -> "Not a key for this service"
            "error" -> "Not answering · tries again"
            else -> "Saved · not checked yet"
        }
    }

    /** What the service itself said about the problem, in a line. */
    fun detail(s: Service): String = if (prefs.getString("st_" + s.slot, "") == "ok") "" else prefs.getString("std_" + s.slot, "").orEmpty()

    fun statusColor(s: Service): Int {
        if (!prefs.getBoolean("on_" + s.slot, true) || key(s).isEmpty()) return Sheet.DIM
        return when (prefs.getString("st_" + s.slot, "")) {
            "ok" -> Sheet.GREEN
            "limit", "offline", "error", "slow" -> Sheet.AMBER
            "bad", "blocked", "wrong" -> Sheet.RED
            "checking" -> Sheet.BLUE
            else -> Sheet.SUB
        }
    }

    companion object {
        val SERVICES = listOf(
            Service("Claude", "key_claude", "https://api.anthropic.com/v1/messages", listOf("claude-haiku-5-5"), "sk-ant-", ""),
            Service("OpenRouter", "key_openrouter", "https://openrouter.ai/api/v1/chat/completions", emptyList(), "sk-or-", ""),
            Service("Gemini", "key_gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash"), "AIza", "https://generativelanguage.googleapis.com/v1beta/openai/models"),
            Service("Groq", "key_groq", "https://api.groq.com/openai/v1/chat/completions",
                listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"), "gsk_", "https://api.groq.com/openai/v1/models")
        )

        fun serviceFor(key: String): Service? = SERVICES.firstOrNull { key.startsWith(it.prefix) }

        fun kindOf(code: Int, body: String): String {
            val b = body.lowercase()
            return when {
                code == 429 || code == 402 || code == 413 || b.contains("quota") || b.contains("rate limit") || b.contains("rate_limit") || b.contains("credit") -> "limit"
                code == 401 || b.contains("invalid api key") || b.contains("invalid_api_key") || b.contains("api key not valid") -> "bad"
                code == 403 -> "blocked"
                code == -1 -> "offline"
                else -> "error"
            }
        }

        /** Chat models from a service's model list, the ones that do this job best first. */
        fun chatModels(slot: String, ids: List<String>): List<String> {
            val skip = listOf("whisper", "tts", "guard", "embed", "image", "audio", "live", "vision", "aqa", "imagen", "veo", "learnlm", "orpheus", "compound", "native")
            val liked = if (slot == "key_groq") listOf("gpt-oss-120b", "llama-3.3-70b", "kimi", "qwen3", "llama-4", "gpt-oss-20b", "llama-3.1-8b")
                        else listOf("flash-lite", "flash")
            return ids.filter { id -> skip.none { id.contains(it, true) } && (slot != "key_gemini" || id.startsWith("gemini") && id.contains("flash")) }
                .sortedWith(compareBy<String> { id -> liked.indexOfFirst { id.contains(it) }.let { if (it < 0) 99 else it } }.thenByDescending { it })
                .take(6)
        }

        /** How long a limit lasts: the Retry-After header, or "try again in 7m12s" in the message, else a minute. */
        fun retryAfter(header: String?, body: String): Long {
            header?.trim()?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
            val m = Regex("""try again in\s+(?:(\d+)h)?(?:(\d+)m(?!s))?(?:([\d.]+)s)?(?:([\d.]+)ms)?""", RegexOption.IGNORE_CASE).find(body)
                ?: Regex("""retry in\s+([\d.]+)\s*s""", RegexOption.IGNORE_CASE).find(body)?.let { r -> return (r.groupValues[1].toDouble() * 1000).toLong() }
                ?: return 60_000L
            val h = m.groupValues[1].toLongOrNull() ?: 0; val min = m.groupValues[2].toLongOrNull() ?: 0
            val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0; val ms = m.groupValues[4].toDoubleOrNull() ?: 0.0
            val total = h * 3_600_000 + min * 60_000 + (sec * 1000).toLong() + ms.toLong()
            return if (total > 0) total else 60_000L
        }

        private fun backIn(until: Long): String {
            val left = until - System.currentTimeMillis()
            return when {
                left <= 0 -> "tries again"
                left < 90_000 -> "back in a minute"
                left < 90 * 60_000 -> "back in ~" + (left / 60_000 + 1) + " min"
                else -> "back in ~" + (left / 3_600_000 + 1) + " h"
            }
        }

        /** The service's own message about an error, in one short line. */
        fun detailOf(body: String): String {
            val t = body.trim()
            val o = runCatching { if (t.startsWith("[")) JSONArray(t).getJSONObject(0) else JSONObject(t) }.getOrNull() ?: return ""
            val e = o.optJSONObject("error") ?: o
            return e.optString("message").replace(Regex("\\s+"), " ").trim().take(140)
        }

        /** Groq's json_validate_failed error carries the answer it would have given. */
        fun failedGeneration(body: String): String? {
            if (!body.contains("failed_generation")) return null
            val text = runCatching { JSONObject(body).getJSONObject("error").optString("failed_generation") }.getOrNull() ?: return null
            return if (jsonIn(text)?.has("reply") == true) text else null
        }

        /** The JSON object in an AI's answer, even inside a code fence or after a sentence. */
        fun jsonIn(text: String): JSONObject? {
            val a = text.indexOf('{'); val b = text.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            return runCatching { JSONObject(text.substring(a, b + 1)) }.getOrNull()
        }
    }
}
