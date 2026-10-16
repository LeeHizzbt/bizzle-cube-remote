package com.bizzle.cuberemote.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.ActionJson
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.Keys
import com.bizzle.cube.core.StreamingApps
import com.bizzle.cuberemote.data.Macro
import com.bizzle.cuberemote.service.RemoteService
import com.bizzle.cuberemote.service.SleepTimerService
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BizzyScreen(d: Deps, startVoice: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        BizzyChatPanel(d, startVoice, compact = false, onOpenSettings = { d.nav.go(com.bizzle.cuberemote.ui.Screen.AiSettings) })
    }
}

@Composable
fun RoutinesScreen(d: Deps) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val macros by d.app.store.macros.collectAsState()
    val endsAt by SleepTimerService.endsAt.collectAsState()
    val remoteOn by RemoteService.running.collectAsState()
    val ui by d.app.store.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(endsAt) { while (endsAt > 0) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    var editing by remember { mutableStateOf<Pair<String, List<BizzyAction>>?>(null) }
    val names = macros.map { it.name }.toSet()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Sleep timer") {
            if (endsAt > 0) {
                val left = ((endsAt - now) / 1000).coerceAtLeast(0)
                Text("%d:%02d left".format(left / 60, left % 60), color = pal.accent, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Body("Fades volume down, pauses, then puts the Cube (and TV via CEC) to sleep. Also in your notifications with +10 / Cancel.", alpha = 0.75f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("+10 min") { SleepTimerService.add10(ctx) }
                    NeonOutlineButton("Cancel") { SleepTimerService.cancel(ctx) }
                }
            } else {
                Body("Pick a time. The countdown lives in a notification with +10 min and Cancel.", alpha = 0.75f)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60, 90).forEach { m -> NeonOutlineButton("$m", Modifier.weight(1f)) { SleepTimerService.start(ctx, m) } }
                }
            }
            SettingSwitch("Fade volume before sleep", null, ui.sleepFade) { d.app.store.setUi(ui.copy(sleepFade = it)) }
        }
        SectionCard("Lock-screen remote") {
            Body("A notification remote that works from the lock screen. Also available as a Quick Settings tile and a home-screen widget.", alpha = 0.75f)
            SettingSwitch("Show notification remote", null, remoteOn) { if (it) RemoteService.start(ctx) else RemoteService.stop(ctx) }
        }
        SectionCard("Routines (macros)") {
            Body("Run a series of steps with one tap, or say \"run movie night\" to Bizzy.", alpha = 0.75f)
            macros.forEach { m ->
                val steps = ActionJson.listFromJson(m.actionsJson, names)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = pal.accent, fontWeight = FontWeight.Bold)
                        Text(steps.joinToString(" → ") { it.describe() }, color = pal.text.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2)
                    }
                    IconButton(onClick = { scope.launch { d.app.cube.emit("Running ${m.name}…"); d.app.cube.runActions(steps).forEach { d.app.cube.emit(it) } } }) { Icon(Icons.Default.PlayArrow, "Run ${m.name}", tint = pal.accent) }
                    IconButton(onClick = { editing = m.name to steps }) { Icon(Icons.Default.Edit, "Edit ${m.name}", tint = pal.accent) }
                    IconButton(onClick = { d.app.store.setMacros(macros.filterNot { it.name == m.name }) }) { Icon(Icons.Default.Delete, "Delete ${m.name}", tint = pal.text.copy(alpha = 0.6f)) }
                }
            }
            NeonOutlineButton("New routine", icon = Icons.Default.Add) { editing = "" to emptyList() }
        }
        editing?.let { (origName, steps0) ->
            MacroEditor(d, origName, steps0, onSave = { name, steps ->
                d.app.store.setMacros(macros.filterNot { it.name == origName || it.name == name } + Macro(name, ActionJson.listToJson(steps)))
                editing = null
            }, onCancel = { editing = null })
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun MacroEditor(d: Deps, origName: String, initial: List<BizzyAction>, onSave: (String, List<BizzyAction>) -> Unit, onCancel: () -> Unit) {
    val pal = LocalPalette.current
    val apps by d.app.cube.apps.collectAsState()
    var name by remember { mutableStateOf(origName) }
    val steps = remember { mutableStateListOf<BizzyAction>().apply { addAll(initial) } }
    var keyMenu by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    SectionCard(if (origName.isBlank()) "New routine" else "Edit routine") {
        NeonField(name, { name = it }, "Routine name")
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${s.describe()}", color = pal.text, modifier = Modifier.weight(1f), fontSize = 13.sp)
                IconButton(onClick = { steps.removeAt(i) }) { Icon(Icons.Default.Close, "Remove step ${i + 1}", tint = pal.text.copy(alpha = 0.6f)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                NeonOutlineButton("+ Key") { keyMenu = true }
                DropdownMenu(keyMenu, { keyMenu = false }) {
                    Keys.CODES.keys.filter { it != "POWER" }.forEach { k -> DropdownMenuItem(text = { Text(k) }, onClick = { steps += BizzyAction.Key(k); keyMenu = false }) }
                }
            }
            Box {
                NeonOutlineButton("+ App") { appMenu = true }
                DropdownMenu(appMenu, { appMenu = false }) {
                    val list = apps.ifEmpty { StreamingApps.SERVICES.map { com.bizzle.cube.core.CubeApp(it.packages.first(), it.label, false) } }
                    list.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { steps += BizzyAction.OpenApp(a.pkg); appMenu = false }) }
                }
            }
            NeonOutlineButton("+ Wait 2s") { steps += BizzyAction.Wait(2000) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonField(text, { text = it }, "Text to type / YouTube search", Modifier.weight(1f))
            TextButton(onClick = { if (text.isNotBlank()) { steps += BizzyAction.TypeText(text.take(200)); text = "" } }) { Text("Type", color = pal.accent) }
            TextButton(onClick = { if (text.isNotBlank()) { steps += BizzyAction.Search("youtube", text.take(200)); text = "" } }) { Text("YT", color = pal.accent) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("Save", enabled = name.isNotBlank() && steps.isNotEmpty()) { onSave(name.trim(), steps.toList()) }
            NeonOutlineButton("Cancel") { onCancel() }
        }
    }
}
