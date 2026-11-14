package com.boltamunshi.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one brain: an AI that hears every sentence, reads the memory book and answers.
 * The user's keys are tried in order (Claude, OpenRouter, Gemini, Groq); a service that
 * fails rests a few minutes and the next one answers. Each key's status (working, limit
 * reached, invalid...) is kept for the Brain screen.
 */
class Brain(private val prefs: SharedPreferences) {

    class Service(val name: String, val slot: String, val url: String, val models: List<String>, val prefix: String)

    private val resting = mutableMapOf<String, Long>()
    @Volatile var lastProblem = ""; private set

    /** Which keys are set and switched on. */
    fun ready(): Boolean = SERVICES.any { key(it).isNotEmpty() && prefs.getBoolean("on_" + it.slot, true) }

    fun key(s: Service) = prefs.getString(s.slot, "").orEmpty().trim()

    /**
     * Asks every service that is on and has a key until one answers with text.
     * [messages] is the conversation (role, content) after the system prompt.
     */
    fun ask(system: String, messages: List<Pair<String, String>>): String? {
        val why = StringBuilder()
        for (s in SERVICES) {
            val k = key(s)
            if (k.isEmpty() || !prefs.getBoolean("on_" + s.slot, true)) continue
            if ((resting[s.name] ?: 0L) > System.currentTimeMillis()) continue
            val got = askOne(s, k, system, messages)
            if (got != null) { resting.remove(s.name); lastProblem = ""; return got }
            resting[s.name] = System.currentTimeMillis() + 3 * 60 * 1000
            why.append(s.name).append(": ").append(statusText(s)).append('\n')
        }
        lastProblem = why.toString().trim().ifEmpty { "No AI key is on · Settings › Brain" }
        return null
    }

    /** One service; its status is saved either way. */
    fun askOne(s: Service, key: String, system: String, messages: List<Pair<String, String>>): String? {
        var code = -1
        var body = ""
        val models = (listOf(prefs.getString("stm_" + s.slot, "").orEmpty()) +
            (if (s.slot == "key_claude") listOf(prefs.getString("claudeModel", "").orEmpty().takeIf { it.startsWith("claude-") } ?: "") else emptyList()) +
            (if (s.slot == "key_openrouter") freeModels() else s.models)).filter { it.isNotBlank() }.distinct().take(6)
        for (model in models) {
            try {
                val c: HttpURLConnection
                val req: JSONObject
                if (s.slot == "key_claude") {
                    c = open(s.url); c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2026-10-21")
                    req = JSONObject().put("model", model).put("max_tokens", 4000).put("system", system)
                        .put("output_config", JSONObject().put("effort", "low"))
                        .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) } })
                } else {
                    c = open(s.url); c.setRequestProperty("Authorization", "Bearer $key")
                    req = JSONObject().put("model", model).put("max_tokens", 700).put("temperature", 0.3)
                        .put("response_format", JSONObject().put("type", "json_object"))
                        .put("messages", JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", system))
                            messages.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) }
                        })
                }
                c.outputStream.use { it.write(req.toString().toByteArray()) }
                code = c.responseCode
                body = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
                if (code == 200) {
                    val o = JSONObject(body)
                    val text = if (s.slot == "key_claude") {
                        if (o.optString("stop_reason") == "refusal") null
                        else (0 until (o.optJSONArray("content")?.length() ?: 0)).map { o.getJSONArray("content").getJSONObject(it) }
                            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                    } else o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                    if (!text.isNullOrBlank()) {
                        prefs.edit().putString("st_" + s.slot, "ok").putString("stm_" + s.slot, model).apply()
                        return text
                    }
                }
                if (code == 401 || code == 403 && !body.contains("model", true)) break    // the key itself: other models won't help
                if (code == 429) break
            } catch (e: Exception) {
                code = -1
                break
            }
        }
        prefs.edit().putString("st_" + s.slot, kindOf(code, body)).apply()
        return null
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 6000; readTimeout = 15000; doOutput = true
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

    fun statusText(s: Service): String {
        if (!prefs.getBoolean("on_" + s.slot, true)) return "Off"
        if (key(s).isEmpty()) return "No key"
        return when (prefs.getString("st_" + s.slot, "")) {
            "ok" -> "Working" + prefs.getString("stm_" + s.slot, "").orEmpty().let { if (it.isEmpty()) "" else " · $it" }
            "limit" -> "Limit reached · retries later"
            "bad" -> "Invalid key · copy it again"
            "blocked" -> "Blocked here · try a VPN or another key"
            "offline" -> "No internet"
            "checking" -> "Checking…"
            "wrong" -> "Not a key for this service"
            "error" -> "Not answering · retries later"
            else -> "Saved · not checked yet"
        }
    }

    fun statusColor(s: Service): Int {
        if (!prefs.getBoolean("on_" + s.slot, true) || key(s).isEmpty()) return Sheet.DIM
        return when (prefs.getString("st_" + s.slot, "")) {
            "ok" -> Sheet.GREEN
            "limit", "offline", "error" -> Sheet.AMBER
            "bad", "blocked", "wrong" -> Sheet.RED
            "checking" -> Sheet.BLUE
            else -> Sheet.SUB
        }
    }

    companion object {
        val SERVICES = listOf(
            Service("Claude", "key_claude", "https://api.anthropic.com/v1/messages", listOf("claude-haiku-5-5"), "sk-ant-"),
            Service("OpenRouter", "key_openrouter", "https://openrouter.ai/api/v1/chat/completions", emptyList(), "sk-or-"),
            Service("Gemini", "key_gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", listOf("gemini-2.5-flash", "gemini-2.0-flash"), "AIza"),
            Service("Groq", "key_groq", "https://api.groq.com/openai/v1/chat/completions", listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), "gsk_")
        )

        fun serviceFor(key: String): Service? = SERVICES.firstOrNull { key.startsWith(it.prefix) }

        fun kindOf(code: Int, body: String): String {
            val b = body.lowercase()
            return when {
                code == 429 || code == 402 || b.contains("quota") || b.contains("rate limit") || b.contains("credit") -> "limit"
                code == 401 || b.contains("invalid api key") || b.contains("invalid_api_key") || b.contains("api key not valid") -> "bad"
                code == 403 -> "blocked"
                code == -1 -> "offline"
                else -> "error"
            }
        }

        /** The JSON object in an AI's answer, even inside a code fence or after a sentence. */
        fun jsonIn(text: String): JSONObject? {
            val a = text.indexOf('{'); val b = text.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            return runCatching { JSONObject(text.substring(a, b + 1)) }.getOrNull()
        }
    }
}
