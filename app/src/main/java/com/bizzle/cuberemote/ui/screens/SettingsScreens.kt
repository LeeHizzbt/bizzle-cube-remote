package com.bizzle.cuberemote.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.AiProvider
import com.bizzle.cube.core.ThemeSpec
import com.bizzle.cuberemote.service.RemoteService
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.Screen
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(d: Deps) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val ui by d.app.store.ui.collectAsState()
    val remoteOn by RemoteService.running.collectAsState()
    var tmdb by remember { mutableStateOf("") }
    val hasTmdb = remember { mutableStateOf(d.app.secure.tmdbKey().isNotBlank()) }
    fun set(f: (com.bizzle.cuberemote.data.UiSettings) -> com.bizzle.cuberemote.data.UiSettings) = d.app.store.setUi(f(ui))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Shortcuts") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Advanced AI Settings", icon = Icons.Default.Tune) { d.nav.go(Screen.AiSettings) }
                NeonOutlineButton("Themes", icon = Icons.Default.Palette) { d.nav.go(Screen.Themes) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonOutlineButton("My Fire TVs") { d.nav.go(Screen.Devices) }
                NeonOutlineButton("About") { d.nav.go(Screen.About) }
            }
        }
        SectionCard("Connection") {
            SettingSwitch("Auto-connect & auto-reconnect", "Reconnects to the last Fire TV whenever the app is open", ui.autoConnect) { v -> set { it.copy(autoConnect = v) } }
            SettingSwitch("Turbo keys", "Instant key presses via a tiny scrcpy control channel (falls back to normal ADB input)", ui.turboKeys) { v -> set { it.copy(turboKeys = v) } }
            SettingSwitch("Lock-screen notification remote", "Keeps the connection warm too", remoteOn) { if (it) RemoteService.start(ctx) else RemoteService.stop(ctx) }
        }
        SectionCard("Remote") {
            Text("Haptics", color = pal.text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Off", "Light", "Strong").forEachIndexed { i, l ->
                    FilterChip(selected = ui.haptics == i, onClick = { set { it.copy(haptics = i) } }, label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = pal.accent, selectedLabelColor = Color.Black, labelColor = pal.accent))
                }
            }
            Text("Hold-to-repeat speed: ${ui.repeatMs} ms", color = pal.text)
            Slider(ui.repeatMs.toFloat(), { v -> set { it.copy(repeatMs = v.toInt()) } }, valueRange = 60f..400f, colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent),
                modifier = Modifier.semantics { contentDescription = "Hold to repeat speed" })
            Text("Swipe pad sensitivity: ${"%.1f".format(ui.trackpadSensitivity)}×", color = pal.text)
            Slider(ui.trackpadSensitivity, { v -> set { it.copy(trackpadSensitivity = v) } }, valueRange = 0.4f..2.5f, colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent),
                modifier = Modifier.semantics { contentDescription = "Swipe pad sensitivity" })
            SettingSwitch("Natural scrolling", "Swipe up moves the selection down", ui.naturalScroll) { v -> set { it.copy(naturalScroll = v) } }
            SettingSwitch("Big buttons", null, ui.bigButtons) { v -> set { it.copy(bigButtons = v) } }
            SettingSwitch("Live typing", null, ui.liveTyping) { v -> set { it.copy(liveTyping = v) } }
        }
        SectionCard("Sleep timer") {
            Text("Default (Quick Settings tile): ${ui.defaultSleepMin} min", color = pal.text)
            Slider(ui.defaultSleepMin.toFloat(), { v -> set { it.copy(defaultSleepMin = (v / 5).toInt() * 5) } }, valueRange = 5f..180f, colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent))
            SettingSwitch("Fade volume before sleep", null, ui.sleepFade) { v -> set { it.copy(sleepFade = v) } }
        }
        SectionCard("Mirroring") {
            Text("Max size: ${ui.mirrorMaxSize}px · bitrate ${ui.mirrorBitrateMbps} Mbps", color = pal.text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(720 to 2, 1024 to 3, 1280 to 4, 1920 to 8).forEach { (s, b) ->
                    FilterChip(selected = ui.mirrorMaxSize == s, onClick = { set { it.copy(mirrorMaxSize = s, mirrorBitrateMbps = b) } }, label = { Text("${s}p") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = pal.accent, selectedLabelColor = Color.Black, labelColor = pal.accent))
                }
            }
        }
        SectionCard("Where to watch") {
            Body(if (hasTmdb.value) "TMDB key saved (encrypted on this phone)." else "Optional free TMDB key (themoviedb.org > Settings > API). Adds movies + exact streaming services (JustWatch data). Without it, TVmaze is used.", alpha = 0.8f)
            NeonField(tmdb, { tmdb = it }, if (hasTmdb.value) "Replace TMDB key" else "TMDB API key or read token", password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Save key", enabled = tmdb.isNotBlank()) { d.app.secure.setTmdbKey(tmdb); tmdb = ""; hasTmdb.value = true; d.app.cube.emit("TMDB key saved (encrypted)") }
                if (hasTmdb.value) NeonOutlineButton("Remove") { d.app.secure.setTmdbKey(""); hasTmdb.value = false }
            }
            NeonField(ui.region, { v -> set { it.copy(region = v.uppercase().take(2)) } }, "Region (2 letters)")
        }
        SectionCard("Backup") {
            Body("Export devices, routines, snippets, themes and settings as JSON. API keys are never exported.", alpha = 0.8f)
            NeonOutlineButton("Share settings JSON", icon = Icons.Default.Share) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, d.app.store.exportJson()), "Export settings"))
            }
        }
        SectionCard("Help") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonOutlineButton("Setup guide") { d.nav.go(Screen.Setup) }
                NeonOutlineButton("Checker") { d.nav.go(Screen.Doctor) }
                NeonOutlineButton("Can't do") { d.nav.go(Screen.CantDo) }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun AiSettingsScreen(d: Deps) {
    val pal = LocalPalette.current
    val s by d.app.store.ai.collectAsState()
    val memory by d.app.store.memory.collectAsState()
    val keyInputs = remember { mutableStateMapOf<String, String>() }
    var refresh by remember { mutableIntStateOf(0) }
    var newMem by remember { mutableStateOf("") }
    fun set(f: (com.bizzle.cuberemote.data.AiSettings) -> com.bizzle.cuberemote.data.AiSettings) = d.app.store.setAi(f(s))
    val links = mapOf(AiProvider.GEMINI to "aistudio.google.com/apikey", AiProvider.GROQ to "console.groq.com/keys", AiProvider.OPENROUTER to "openrouter.ai/keys")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("How Bizzy thinks") {
            Body("Free-first order: tries each provider with a key, then falls back to offline rules. Keys are stored encrypted on this phone only (Android Keystore), never in the app code or sent anywhere except that provider.", alpha = 0.8f)
            Text("Order: " + s.order.joinToString(" → ") + " → offline", color = pal.accent, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.order.forEachIndexed { i, id ->
                    if (i > 0) AssistChip(onClick = { set { it.copy(order = it.order.toMutableList().apply { add(i - 1, removeAt(i)) }) } }, label = { Text("⬆ $id", fontSize = 11.sp) })
                }
            }
        }
        key(refresh) {
            AiProvider.DEFAULTS.values.forEach { p ->
                val has = d.app.secure.aiKey(p.id).isNotBlank()
                SectionCard(p.label) {
                    Body(if (has) "✅ Key saved (encrypted)" else "No key. Get a free one at ${links[p.id]}", alpha = 0.85f)
                    NeonField(keyInputs[p.id] ?: "", { keyInputs[p.id] = it }, if (has) "Replace key" else "Paste API key", password = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton("Save", enabled = !keyInputs[p.id].isNullOrBlank()) { d.app.secure.setAiKey(p.id, keyInputs[p.id]!!); keyInputs[p.id] = ""; refresh++; d.app.cube.emit("${p.label} key saved") }
                        if (has) NeonOutlineButton("Remove") { d.app.secure.setAiKey(p.id, ""); refresh++ }
                    }
                    NeonField(s.models[p.id] ?: "", { v -> set { it.copy(models = it.models + (p.id to v.trim())) } }, "Model (default ${p.model})")
                    NeonField(s.visionModels[p.id] ?: "", { v -> set { it.copy(visionModels = it.visionModels + (p.id to v.trim())) } }, "Vision model (default ${p.visionModel})")
                }
            }
        }
        SectionCard("Behaviour") {
            Text("Creativity (temperature): ${"%.1f".format(s.temperature)}", color = pal.text)
            Slider(s.temperature.toFloat(), { v -> set { it.copy(temperature = (v * 10).toInt() / 10.0) } }, valueRange = 0f..1.2f, colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent))
            SettingSwitch("Confirm before running AI actions", "Shows the steps and waits for 'Run it'", s.confirmBeforeRun) { v -> set { it.copy(confirmBeforeRun = v) } }
            SettingSwitch("Offline fallback", "Use built-in rules when AI is unavailable", s.offlineFallback) { v -> set { it.copy(offlineFallback = v) } }
            SettingSwitch("Allow screenshots to AI", "TV screen help and phone screenshot upload", s.allowScreenshots) { v -> set { it.copy(allowScreenshots = v) } }
            NeonField(s.tone, { v -> set { it.copy(tone = v.take(80)) } }, "Bizzy's personality / tone")
            Body("Safety: Bizzy can only press keys, open apps, open official deep links, type, search, run your routines, wait and set the sleep timer. It can never run raw ADB/shell commands, install, uninstall or reboot.", alpha = 0.7f)
        }
        SectionCard("Bizzy memory") {
            SettingSwitch("Memory on", "Say \"remember …\" to Bizzy", s.memoryOn) { v -> set { it.copy(memoryOn = v) } }
            memory.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $m", color = pal.text, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    IconButton(onClick = { d.app.store.setMemory(memory.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, "Forget: $m", tint = pal.text.copy(alpha = 0.6f)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonField(newMem, { newMem = it }, "Add a memory", Modifier.weight(1f))
                IconButton(onClick = { if (newMem.isNotBlank()) { d.app.store.setMemory(memory + newMem.trim()); newMem = "" } }) { Icon(Icons.Default.Add, "Add memory", tint = pal.accent) }
            }
            if (memory.isNotEmpty()) TextButton(onClick = { d.app.store.setMemory(emptyList()) }) { Text("Forget everything", color = Color(0xFFFF5370)) }
        }
        SectionCard("Rate limits") {
            Body("Free tiers have daily/minute limits (Gemini, Groq and OpenRouter :free models). When one says 'rate-limited', Bizzy automatically tries the next provider, then offline rules.", alpha = 0.75f)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun ThemesScreen(d: Deps) {
    val pal = LocalPalette.current
    val ui by d.app.store.ui.collectAsState()
    val custom by d.app.store.customThemes.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Themes") {
            (ThemeSpec.BUILT_IN + custom).forEach { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { d.app.store.setUi(ui.copy(theme = t.name)) }
                    .background(Color(t.background)).border(if (ui.theme == t.name) 3.dp else 1.dp, Color(t.primary), RoundedCornerShape(12.dp)).padding(12.dp)
                    .semantics { contentDescription = "Theme ${t.name}${if (ui.theme == t.name) ", selected" else ""}" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(t.primary)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name + if (t.ai) "  ✨AI" else "", color = Color(t.primary), fontWeight = FontWeight.Bold)
                        Text("Sample text", color = Color(t.onSurface), fontSize = 12.sp)
                    }
                    if (t.ai) IconButton(onClick = { d.app.store.removeTheme(t.name); if (ui.theme == t.name) d.app.store.setUi(ui.copy(theme = ThemeSpec.BUILT_IN.first().name)) }) {
                        Icon(Icons.Default.Delete, "Delete theme ${t.name}", tint = Color(t.onSurface))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        SectionCard("✨ AI-made theme") {
            Body("Describe a vibe and Bizzy designs a palette (contrast is checked so text stays readable). Needs an AI key.", alpha = 0.8f)
            NeonField(prompt, { prompt = it }, "e.g. alien cat disco, cozy cabin, Jinxy orange")
            NeonButton(if (busy) "Designing…" else "Make theme", enabled = prompt.isNotBlank() && !busy) {
                busy = true
                scope.launch {
                    val t = d.brain.makeTheme(prompt)
                    busy = false
                    if (t == null) d.app.cube.emit(if (!d.app.secure.hasAnyAiKey()) "Add an AI key first (Advanced AI Settings)" else "Couldn't make a readable theme, try another idea")
                    else { d.app.store.addTheme(t); d.app.store.setUi(ui.copy(theme = t.name)); d.app.cube.emit("Theme \"${t.name}\" applied") }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun CantDoScreen(d: Deps) {
    val items = listOf(
        "Talk to Alexa" to "Alexa only listens to the real remote's microphone. ADB can open the voice screen but can't send your words or typed Alexa commands.",
        "IR blaster / Equipment Control" to "The Cube's IR is inside Amazon's private service. ADB key presses don't fire IR, and your phone has no IR blaster. TV power/volume works over HDMI-CEC instead.",
        "Private listening (headphones via phone)" to "Fire TV has no audio-to-phone feature we can use.",
        "Your Amazon watchlist & profiles" to "Amazon doesn't offer any public API for these.",
        "Block Fire OS updates" to "Amazon protects the update service; a future update could even hide Developer Options or switch ADB off.",
        "Vega OS Fire TVs (Stick 4K Select 2025, Stick HD 2026, Stick 4K 3rd gen)" to "Vega OS has no ADB or sideloading, so no app like this can control them. Your Cube (Fire OS 7) is fine.",
        "Real parental content-rating filters" to "Only the Fire TV's own parental controls can do this.",
        "Control from outside your home (directly)" to "ADB must never be exposed to the internet. Only via your own VPN into your home network.",
        "See Netflix/Prime/Disney+ video in the mirror or screenshots" to "DRM-protected video is shown black by Fire OS on purpose.",
        "Type emoji or accented letters" to "Fire OS's ADB text input only supports plain English letters, numbers and symbols.",
        "Google TV Remote protocol" to "Fire OS doesn't have Google's remote service; this app uses ADB, which is the only way."
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Body("Being honest: these aren't possible on a Fire TV Cube over Wi-Fi, so the app doesn't pretend to do them.")
        items.forEach { (t, why) -> SectionCard("🚫 $t") { Body(why, alpha = 0.85f) } }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun AboutScreen(d: Deps) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Bizzle's Ultimate Cube Remote") {
            Text("v4.20", color = pal.accent, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Body("A neon remote for the Amazon Fire TV Cube over ADB (port 5555) on your Wi-Fi. Built for Nunya.")
        }
        SectionCard("Open-source & data credits") {
            Body("• dadb by mobile.dev: Apache License 2.0 (ADB protocol client)")
            Body("• scrcpy server by Genymobile: Apache License 2.0. Bundled unmodified (v5.0.1) and pushed to the TV for live mirroring and turbo keys. github.com/Genymobile/scrcpy")
            Body("• OkHttp, Gson, AndroidX / Jetpack Compose: Apache License 2.0")
            Body("• Streaming availability data by JustWatch via TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.")
            Body("• TV show data from TVmaze.com (CC BY-SA 4.0)")
            Body("• AI: Google Gemini, Groq, OpenRouter (your own free keys)")
        }
        SectionCard("Privacy") {
            Body("No accounts, no analytics, no ads. Your ADB key, API keys (encrypted), chats and memories stay on this phone. AI requests go only to the provider you set up; TV screenshots are only sent when you ask Bizzy for screen help.")
        }
        Spacer(Modifier.height(80.dp))
    }
}
