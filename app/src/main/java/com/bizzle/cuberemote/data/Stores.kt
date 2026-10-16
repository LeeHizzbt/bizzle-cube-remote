package com.bizzle.cuberemote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bizzle.cube.core.ActionJson
import com.bizzle.cube.core.AiProvider
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.ThemeSpec
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** API keys live ONLY here: EncryptedSharedPreferences (AES-256, Android Keystore master key). */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = try {
        create(context)
    } catch (e: Exception) {
        // Keystore corruption after restore: wipe and recreate rather than crash
        context.deleteSharedPreferences(FILE)
        create(context)
    }

    private fun create(context: Context) = EncryptedSharedPreferences.create(
        context, FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun aiKey(provider: String): String = prefs.getString("ai_key_$provider", "") ?: ""
    fun setAiKey(provider: String, key: String) = prefs.edit().putString("ai_key_$provider", key.trim()).apply()
    fun tmdbKey(): String = prefs.getString("tmdb_key", "") ?: ""
    fun setTmdbKey(key: String) = prefs.edit().putString("tmdb_key", key.trim()).apply()
    fun hasAnyAiKey() = listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OPENROUTER).any { aiKey(it).isNotBlank() }

    companion object { private const val FILE = "bizzle_cube_secure" }
}

data class SavedDevice(val id: String = UUID.randomUUID().toString(), val name: String, val host: String, val port: Int = 5555)
data class Snippet(val text: String)
data class Macro(val name: String, val actionsJson: String)
data class ChatLine(val role: String, val text: String, val time: Long = System.currentTimeMillis(), val provider: String? = null)

data class AiSettings(
    val order: List<String> = listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OPENROUTER),
    val models: Map<String, String> = emptyMap(),
    val visionModels: Map<String, String> = emptyMap(),
    val temperature: Double = 0.4,
    val confirmBeforeRun: Boolean = true,
    val allowScreenshots: Boolean = true,
    val memoryOn: Boolean = true,
    val tone: String = "fun, chill and helpful",
    val offlineFallback: Boolean = true
)

data class UiSettings(
    val theme: String = "Neon v4.20",
    val haptics: Int = 1,               // 0 off, 1 light, 2 strong
    val repeatMs: Int = 120,
    val trackpadSensitivity: Float = 1f,
    val naturalScroll: Boolean = false,
    val liveTyping: Boolean = true,
    val autoConnect: Boolean = true,
    val keepAliveNotification: Boolean = false,
    val turboKeys: Boolean = true,
    val sleepFade: Boolean = true,
    val defaultSleepMin: Int = 30,
    val leftHanded: Boolean = false,
    val bigButtons: Boolean = false,
    val region: String = "US",
    val mirrorMaxSize: Int = 1280,
    val mirrorBitrateMbps: Int = 4
)

/** Non-secret app data (JSON in SharedPreferences) exposed as StateFlows. */
class AppStore(context: Context) {
    private val p = context.getSharedPreferences("bizzle_cube_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    private inline fun <reified T> load(key: String, def: T): T =
        p.getString(key, null)?.let { runCatching { gson.fromJson<T>(it, object : TypeToken<T>() {}.type) }.getOrNull() } ?: def

    private fun save(key: String, v: Any) = p.edit().putString(key, gson.toJson(v)).apply()

    val devices = MutableStateFlow(load("devices", emptyList<SavedDevice>()))
    val activeDeviceId = MutableStateFlow(p.getString("active_device", null))
    val snippets = MutableStateFlow(load("snippets", listOf(Snippet("Bizzle"), Snippet("password123?"))))
    val macros = MutableStateFlow(load("macros", defaultMacros()))
    val favorites = MutableStateFlow(load("favorites", emptyMap<String, List<String>>()))
    val memory = MutableStateFlow(load("memory", emptyList<String>()))
    val chat = MutableStateFlow(load("chat", emptyList<ChatLine>()))
    val customThemes = MutableStateFlow(load("custom_themes", emptyList<ThemeSpec>()))
    val ai = MutableStateFlow(load("ai_settings", AiSettings()))
    val ui = MutableStateFlow(load("ui_settings", UiSettings()))
    val onboarded = MutableStateFlow(p.getBoolean("onboarded", false))

    fun setOnboarded() { onboarded.value = true; p.edit().putBoolean("onboarded", true).apply() }

    fun upsertDevice(d: SavedDevice) {
        val list = devices.value.filterNot { it.id == d.id || (it.host == d.host && it.port == d.port && it.id != d.id && false) }.toMutableList()
        val existing = devices.value.indexOfFirst { it.id == d.id }
        if (existing >= 0) list.add(existing.coerceAtMost(list.size), d) else list.add(d)
        devices.value = list; save("devices", list)
    }
    fun removeDevice(id: String) { devices.value = devices.value.filterNot { it.id == id }; save("devices", devices.value) }
    fun setActive(id: String?) { activeDeviceId.value = id; p.edit().putString("active_device", id).apply() }
    fun activeDevice(): SavedDevice? = devices.value.firstOrNull { it.id == activeDeviceId.value } ?: devices.value.firstOrNull()

    fun setSnippets(v: List<Snippet>) { snippets.value = v; save("snippets", v) }
    fun setMacros(v: List<Macro>) { macros.value = v; save("macros", v) }
    fun macroActions(name: String): List<BizzyAction>? {
        val m = macros.value.firstOrNull { it.name.equals(name, true) } ?: return null
        return ActionJson.listFromJson(m.actionsJson, macros.value.map { it.name }.toSet())
    }
    fun toggleFavorite(deviceId: String, pkg: String) {
        val cur = favorites.value[deviceId].orEmpty()
        val next = if (pkg in cur) cur - pkg else cur + pkg
        favorites.value = favorites.value + (deviceId to next); save("favorites", favorites.value)
    }
    fun setMemory(v: List<String>) { memory.value = v.takeLast(100); save("memory", memory.value) }
    fun addChat(line: ChatLine) { chat.value = (chat.value + line).takeLast(200); save("chat", chat.value) }
    fun clearChat() { chat.value = emptyList(); save("chat", chat.value) }
    fun addTheme(t: ThemeSpec) { customThemes.value = customThemes.value.filterNot { it.name == t.name } + t; save("custom_themes", customThemes.value) }
    fun removeTheme(name: String) { customThemes.value = customThemes.value.filterNot { it.name == name }; save("custom_themes", customThemes.value) }
    fun setAi(v: AiSettings) { ai.value = v; save("ai_settings", v) }
    fun setUi(v: UiSettings) { ui.value = v; save("ui_settings", v) }

    /** Export everything except keys (keys never leave encrypted storage). */
    fun exportJson(): String = gson.toJson(mapOf(
        "app" to "Bizzle's Ultimate Cube Remote", "version" to "4.20",
        "devices" to devices.value, "snippets" to snippets.value, "macros" to macros.value,
        "favorites" to favorites.value, "customThemes" to customThemes.value, "ai" to ai.value, "ui" to ui.value
    ))

    private fun defaultMacros() = listOf(
        Macro("Movie night", ActionJson.listToJson(listOf(
            BizzyAction.Key("WAKE"), BizzyAction.Wait(3000), BizzyAction.Key("HOME"), BizzyAction.Wait(1500),
            BizzyAction.OpenApp("com.netflix.ninja")
        ))),
        Macro("Lofi on YouTube", ActionJson.listToJson(listOf(BizzyAction.Search("youtube", "lofi hip hop radio")))),
        Macro("Lights out", ActionJson.listToJson(listOf(BizzyAction.Key("PAUSE"), BizzyAction.Wait(500), BizzyAction.Key("SLEEP"))))
    )
}
