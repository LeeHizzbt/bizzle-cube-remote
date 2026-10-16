package com.bizzle.cuberemote.bizzy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.bizzle.cube.core.ActionParser
import com.bizzle.cube.core.AiClient
import com.bizzle.cube.core.AiMessage
import com.bizzle.cube.core.AiProvider
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.BizzyPrompt
import com.bizzle.cube.core.OfflineMapper
import com.bizzle.cube.core.StreamingApps
import com.bizzle.cube.core.ThemeSpec
import com.bizzle.cuberemote.CubeRemoteApp
import com.bizzle.cuberemote.data.ChatLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class PendingActions(val actions: List<BizzyAction>, val reply: String)

/** Bizzy's brain: AI (free-first failover) or offline rules → allow-listed actions only. */
class BizzyBrain(private val app: CubeRemoteApp) {
    private val ai = AiClient()
    val busy = MutableStateFlow(false)
    val pending = MutableStateFlow<PendingActions?>(null)

    fun providers(): List<AiProvider> {
        val s = app.store.ai.value
        return s.order.mapNotNull { id ->
            AiProvider.DEFAULTS[id]?.let { d ->
                d.copy(
                    model = s.models[id]?.ifBlank { null } ?: d.model,
                    visionModel = s.visionModels[id]?.ifBlank { null } ?: d.visionModel,
                    key = app.secure.aiKey(id)
                )
            }
        }
    }

    private fun systemPrompt(): String {
        val s = app.store.ai.value
        return BizzyPrompt.system(
            app.cube.apps.value.map { "${it.label} (${it.pkg})" },
            app.store.macros.value.map { it.name },
            if (s.memoryOn) app.store.memory.value else emptyList(),
            s.tone
        )
    }

    private fun history(): List<AiMessage> =
        app.store.chat.value.takeLast(10).map { AiMessage(if (it.role == "user") "user" else "assistant", it.text) }

    /** Main entry for typed/voice messages. */
    fun send(text: String, imageDataUrl: String? = null) {
        val t = text.trim()
        if (t.isEmpty() && imageDataUrl == null) return
        app.store.addChat(ChatLine("user", if (imageDataUrl != null) "📷 $t" else t))
        app.cube.scope.launch { handle(t, imageDataUrl) }
    }

    private suspend fun handle(text: String, imageDataUrl: String?) {
        busy.value = true
        try {
            val s = app.store.ai.value
            // memory commands are handled locally
            Regex("^(?:remember|bizzy remember)(?: that)?\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                if (s.memoryOn) {
                    app.store.setMemory(app.store.memory.value + m.groupValues[1].trim())
                    reply("Got it, I'll remember: \"${m.groupValues[1].trim()}\"", "memory")
                } else reply("Memory is off. Turn it on in Advanced AI Settings.", "memory")
                return
            }
            val ctx = app.cube.actionContext()
            val offline = if (imageDataUrl == null) OfflineMapper.map(text, ctx) else emptyList()
            val haveKey = app.secure.hasAnyAiKey()
            // Instant path: simple commands that the offline rules fully understand don't need the cloud
            if (offline.isNotEmpty() && (!haveKey || offline.size <= 3)) {
                propose(offline, "On it: " + offline.joinToString(", ") { it.describe() }, "offline rules")
                return
            }
            if (!haveKey) {
                reply(
                    if (offline.isEmpty()) "I couldn't map that offline. Add a free Gemini or Groq key in Advanced AI Settings for full plain-English commands, or try things like \"open Netflix\", \"volume up 3\", \"search YouTube for cats\"." else "", "offline rules"
                )
                return
            }
            val msgs = listOf(AiMessage("system", systemPrompt())) + history().dropLast(1) + AiMessage("user", text.ifBlank { "Help me with this screenshot." }, imageDataUrl)
            val res = withContext(Dispatchers.IO) { ai.chat(providers(), msgs, s.temperature, json = true) }
            if (res.content == null) {
                if (offline.isNotEmpty() && s.offlineFallback) propose(offline, "AI unavailable (${res.errors.lastOrNull() ?: "?"}), used offline rules.", "offline rules")
                else reply("All AI providers failed:\n• " + res.errors.joinToString("\n• ") + "\nOffline rules also didn't understand that.", "error")
                return
            }
            val parsed = ActionParser.parse(res.content!!, ctx)
            if (memoryHint(parsed.reply)) { /* no-op: memory handled above */ }
            val note = if (parsed.rejected.isNotEmpty()) "\n(Blocked ${parsed.rejected.size} unsafe/unknown action(s): ${parsed.rejected.joinToString("; ").take(200)})" else ""
            if (parsed.actions.isEmpty()) reply(parsed.reply.ifBlank { res.content!!.take(1500) } + note, res.provider)
            else propose(parsed.actions, parsed.reply + note, res.provider)
        } catch (e: Exception) {
            reply("Bizzy hit an error: ${e.message}", "error")
        } finally {
            busy.value = false
        }
    }

    private fun memoryHint(s: String) = false

    private suspend fun propose(actions: List<BizzyAction>, replyText: String, provider: String?) {
        if (app.store.ai.value.confirmBeforeRun) {
            reply(replyText.ifBlank { "Ready to run:" } + "\n→ " + actions.joinToString("\n→ ") { it.describe() }, provider)
            pending.value = PendingActions(actions, replyText)
        } else {
            reply(replyText, provider)
            execute(actions)
        }
    }

    fun confirmPending() {
        val p = pending.value ?: return
        pending.value = null
        app.cube.scope.launch { execute(p.actions) }
    }

    fun cancelPending() { pending.value = null; reply("Cancelled.", null) }

    private suspend fun execute(actions: List<BizzyAction>) {
        val notes = app.cube.runActions(actions)
        if (notes.isNotEmpty()) reply(notes.joinToString("\n"), "result")
    }

    private fun reply(text: String, provider: String?) {
        if (text.isBlank()) return
        app.store.addChat(ChatLine("assistant", text, provider = provider))
    }

    /** "Help me with my screen": grab the TV screen and ask a vision model. */
    fun helpWithScreen(question: String) {
        app.store.addChat(ChatLine("user", "📺 Help me with my screen" + if (question.isNotBlank()) ": $question" else ""))
        app.cube.scope.launch {
            busy.value = true
            try {
                if (!app.store.ai.value.allowScreenshots) { reply("Screenshots to AI are turned off in Advanced AI Settings.", "settings"); return@launch }
                if (!app.secure.hasAnyAiKey()) { reply("Add a free Gemini key in Advanced AI Settings so I can look at your TV screen.", "settings"); return@launch }
                val png = app.cube.call { it.screencapPng() }.getOrElse { reply("Couldn't grab the TV screen: ${it.message}", "error"); return@launch }
                val dataUrl = pngToJpegDataUrl(png, 1280)
                val res = withContext(Dispatchers.IO) {
                    ai.chat(providers(), listOf(AiMessage("system", systemPrompt()), AiMessage("user", BizzyPrompt.screenHelp(question), dataUrl)), 0.3, json = true)
                }
                if (res.content == null) { reply("Vision AI failed:\n• " + res.errors.joinToString("\n• "), "error"); return@launch }
                val parsed = ActionParser.parse(res.content!!, app.cube.actionContext())
                if (parsed.actions.isEmpty()) reply(parsed.reply.ifBlank { res.content!! }, res.provider)
                else propose(parsed.actions, parsed.reply, res.provider)
            } finally { busy.value = false }
        }
    }

    /** "What should I watch" limited to services installed on the Cube. */
    fun whatToWatch(mood: String) {
        val installed = app.cube.apps.value.mapNotNull { a -> StreamingApps.SERVICES.firstOrNull { a.pkg in it.packages }?.label }.distinct()
        val list = if (installed.isEmpty()) "(I don't know your apps yet: connect to the Cube first)" else installed.joinToString(", ")
        send("What should I watch tonight? ${mood.ifBlank { "Surprise me" }}. Only suggest titles on these installed services: $list. Don't add actions unless I ask.")
    }

    suspend fun makeTheme(description: String): ThemeSpec? {
        if (!app.secure.hasAnyAiKey()) return null
        val res = withContext(Dispatchers.IO) { ai.chat(providers(), listOf(AiMessage("user", BizzyPrompt.themePrompt(description))), 0.9, json = true) }
        return res.content?.let { ThemeSpec.parseAi(it) }
    }

    companion object {
        fun pngToJpegDataUrl(png: ByteArray, maxW: Int): String {
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw IllegalStateException("bad screenshot")
            return bitmapToDataUrl(bmp, maxW)
        }
        fun bitmapToDataUrl(src: Bitmap, maxW: Int): String {
            val bmp = if (src.width > maxW) Bitmap.createScaledBitmap(src, maxW, src.height * maxW / src.width, true) else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
