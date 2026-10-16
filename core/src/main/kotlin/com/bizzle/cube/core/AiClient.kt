package com.bizzle.cube.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val visionModel: String,
    val key: String,
    /** Tried in order on the same provider when the main model is overloaded (503) or missing (404). */
    val fallbackModels: List<String> = emptyList()
) {
    companion object {
        const val GEMINI = "gemini"; const val GROQ = "groq"; const val OPENROUTER = "openrouter"
        val DEFAULTS = mapOf(
            GEMINI to AiProvider(GEMINI, "Google Gemini (free tier)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest", "gemini-flash-latest", "", listOf("gemini-flash-lite-latest", "gemini-3.5-flash")),
            GROQ to AiProvider(GROQ, "Groq (free tier)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct", "", listOf("llama-3.1-8b-instant")),
            OPENROUTER to AiProvider(OPENROUTER, "OpenRouter (free models)", "https://openrouter.ai/api/v1", "openrouter/free", "openrouter/free", "")
        )
    }
}

data class AiMessage(val role: String, val text: String, val imageDataUrl: String? = null)

data class AiResult(val provider: String?, val content: String?, val errors: List<String>)

/** OpenAI-compatible chat with free-first provider failover. Keys are supplied at runtime from encrypted storage. */
class AiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
) {
    fun chat(providers: List<AiProvider>, messages: List<AiMessage>, temperature: Double = 0.4, json: Boolean = false): AiResult {
        val errors = mutableListOf<String>()
        val needsVision = messages.any { it.imageDataUrl != null }
        for (p in providers) {
            if (p.key.isBlank()) { errors += "${p.label}: no key set"; continue }
            val models = (listOf(if (needsVision) p.visionModel else p.model) + if (needsVision) emptyList() else p.fallbackModels).distinct()
            for (model in models) {
                try {
                    val content = call(p, model, messages, temperature, json)
                    if (content.isNotBlank()) return AiResult("${p.label} · $model", content, errors)
                    errors += "${p.label} ($model): empty reply"
                } catch (e: Exception) {
                    val m = e.message ?: e.javaClass.simpleName
                    errors += "${p.label} ($model): ${friendly(m)}"
                    // only overload / missing-model errors are worth another model on the same provider
                    if (!(m.contains("HTTP 503") || m.contains("HTTP 404") || m.contains("HTTP 500"))) break
                }
            }
        }
        return AiResult(null, null, errors)
    }

    private fun friendly(m: String): String = when {
        m.contains("HTTP 429") -> "rate-limited (free quota used up, trying next)"
        m.contains("HTTP 401") || m.contains("HTTP 403") -> "key rejected (check the key in Advanced AI Settings)"
        m.contains("HTTP 404") -> "model not found (pick another model in Advanced AI Settings)"
        m.contains("HTTP 503") -> "overloaded right now (HTTP 503)"
        m.contains("HTTP 5") -> "service error (${m.substringAfter("HTTP ").take(3)})"
        m.contains("Unable to resolve host") || m.contains("UnknownHost") -> "offline / no internet"
        else -> m.take(160)
    }

    private fun call(p: AiProvider, model: String, messages: List<AiMessage>, temperature: Double, json: Boolean): String {
        val arr = JsonArray()
        for (m in messages) {
            val o = JsonObject(); o.addProperty("role", m.role)
            if (m.imageDataUrl != null) {
                val parts = JsonArray()
                parts.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", m.text) })
                parts.add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply { addProperty("url", m.imageDataUrl) })
                })
                o.add("content", parts)
            } else o.addProperty("content", m.text)
            arr.add(o)
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", arr)
            addProperty("temperature", temperature)
            if (json && p.id != AiProvider.OPENROUTER) add("response_format", JsonObject().apply { addProperty("type", "json_object") })
        }
        val req = Request.Builder().url(p.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${p.key.trim()}")
            .header("HTTP-Referer", "https://github.com/LeeHizzbt/bizzle-cube-remote")
            .header("X-Title", "Bizzle's Ultimate Cube Remote")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            val j = JsonParser.parseString(text).asJsonObject
            val msg = j.getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("message")
                ?: throw IllegalStateException("no choices")
            val c = msg.get("content")
            return if (c == null || c.isJsonNull) "" else c.asString
        }
    }
}

/** Builds Bizzy's system prompt. */
object BizzyPrompt {
    fun system(installedLabels: List<String>, macroNames: List<String>, memory: List<String>, tone: String): String = buildString {
        appendLine("You are Bizzy, the friendly neon-green helper inside Bizzle's Ultimate Cube Remote v4.20, controlling an Amazon Fire TV Cube (Fire OS 7) over ADB. Tone: $tone. Keep replies short.")
        appendLine("ALWAYS answer with ONE JSON object: {\"reply\":\"text for the user\",\"actions\":[...]}. Use an empty actions list when no TV action is needed.")
        appendLine("Allowed action objects ONLY (anything else is discarded by the app):")
        appendLine("{\"type\":\"key\",\"key\":\"UP|DOWN|LEFT|RIGHT|OK|BACK|HOME|MENU|PLAY_PAUSE|PLAY|PAUSE|STOP|FAST_FORWARD|REWIND|NEXT|PREVIOUS|VOLUME_UP|VOLUME_DOWN|MUTE|SLEEP|WAKE|SEARCH|SETTINGS|DEL|ENTER|CAPTIONS\",\"times\":1}")
        appendLine("{\"type\":\"open_app\",\"app\":\"<app name or package from the installed list>\"}")
        appendLine("{\"type\":\"deeplink\",\"service\":\"netflix|prime|youtube|disney|hulu\",\"url\":\"<official https link for that service, or a Netflix numeric title id>\"}")
        appendLine("{\"type\":\"type\",\"text\":\"<text to type into the focused box>\"}")
        appendLine("{\"type\":\"search\",\"service\":\"youtube|netflix|prime|disney|hulu|...\",\"query\":\"<title>\"}")
        appendLine("{\"type\":\"macro\",\"name\":\"<existing routine name>\"}")
        appendLine("{\"type\":\"wait\",\"ms\":1500}")
        appendLine("{\"type\":\"sleep_timer\",\"minutes\":30}   (0 cancels)")
        appendLine("Never output shell commands, ADB commands, package installs, uninstalls or reboots. You cannot speak to Alexa, use IR, or see DRM video.")
        appendLine("Installed apps on this Cube: " + installedLabels.take(80).joinToString(", ").ifBlank { "unknown (not connected)" })
        if (macroNames.isNotEmpty()) appendLine("Routines: " + macroNames.joinToString(", "))
        if (memory.isNotEmpty()) appendLine("Things the user asked you to remember: " + memory.takeLast(30).joinToString(" | "))
        appendLine("For 'what should I watch', recommend 3-5 titles that are on the user's installed services only, say which app, and add a search action for the top pick only if the user asked to play it.")
        appendLine("If the user says 'remember ...', confirm in reply; the app stores it.")
    }

    fun screenHelp(question: String): String =
        "This is a screenshot of my Fire TV screen. ${question.ifBlank { "What am I looking at and what should I press next?" }} " +
            "Explain simply which remote buttons to press (UP/DOWN/LEFT/RIGHT/OK/BACK/HOME). Note: video from Netflix etc. shows black due to DRM. " +
            "Answer as JSON {\"reply\":\"...\",\"actions\":[]} and only add key actions if I asked you to do it for me."

    fun themePrompt(description: String): String =
        "Design a dark-mode Android app color theme for: \"$description\". Reply ONLY JSON: " +
            "{\"name\":\"short name\",\"primary\":\"#RRGGBB\",\"background\":\"#RRGGBB\",\"surface\":\"#RRGGBB\",\"onSurface\":\"#RRGGBB\"}. " +
            "Ensure strong contrast between primary/onSurface and background."
}
