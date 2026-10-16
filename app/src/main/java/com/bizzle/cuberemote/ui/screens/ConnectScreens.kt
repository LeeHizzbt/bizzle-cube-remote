package com.bizzle.cuberemote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.ConnectOutcome
import com.bizzle.cube.core.CubeAdb
import com.bizzle.cube.core.AdbKeys
import com.bizzle.cuberemote.cube.ConnState
import com.bizzle.cuberemote.cube.FoundTv
import com.bizzle.cuberemote.data.SavedDevice
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.Screen
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DevicesScreen(d: Deps) {
    val pal = LocalPalette.current
    val devices by d.app.store.devices.collectAsState()
    val status by d.app.cube.status.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<FoundTv>>(emptyList()) }
    var name by remember { mutableStateOf("Living room Cube") }
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun add(n: String, h: String, p: Int) {
        val existing = devices.firstOrNull { it.host == h && it.port == p }
        val dev = existing ?: SavedDevice(name = n.ifBlank { "Fire TV" }, host = h, port = p)
        if (existing == null) d.app.store.upsertDevice(dev)
        d.app.cube.connect(dev)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Status") {
            Body(status.message)
            if (status.state == ConnState.WaitingForTv) Text("👀 Check the TV now: tick 'Always allow from this computer', then OK.", color = Color(0xFFFFC23D), fontWeight = FontWeight.Bold)
            if (status.state == ConnState.Connected) Body("${status.model} · ${status.build}", alpha = 0.7f)
        }
        SectionCard("Saved Fire TVs") {
            if (devices.isEmpty()) Body("None yet. Find or add one below.", alpha = 0.7f)
            devices.forEach { dev ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text((if (status.device?.id == dev.id && status.state == ConnState.Connected) "● " else "") + dev.name, color = pal.accent, fontWeight = FontWeight.Bold)
                        Text("${dev.host}:${dev.port}", color = pal.text.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    NeonOutlineButton("Use") { d.app.cube.connect(dev) }
                    IconButton(onClick = { d.app.store.removeDevice(dev.id) }) { Icon(Icons.Default.Delete, "Forget ${dev.name}", tint = pal.text.copy(alpha = 0.6f)) }
                }
            }
        }
        SectionCard("Find Fire TVs on this Wi-Fi") {
            Body("Looks for devices with ADB debugging on (port 5555). Phone and Cube must be on the same Wi-Fi.", alpha = 0.75f)
            NeonButton(if (scanning) "Scanning…" else "Scan now", icon = Icons.Default.Radar, enabled = !scanning) {
                scanning = true; found = emptyList()
                scope.launch {
                    found = d.app.cube.discovery.discover { progress = it }
                    progress = if (found.isEmpty()) "No Fire TV found. Is ADB Debugging ON? Try the Connection checker or add the IP manually." else "Found ${found.size}"
                    scanning = false
                }
            }
            if (progress.isNotBlank()) Body(progress, alpha = 0.8f)
            found.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.label} (${f.host})", color = pal.text, modifier = Modifier.weight(1f))
                    NeonButton("Connect") { add("Fire TV ${f.host.substringAfterLast('.')}", f.host, f.port) }
                }
            }
        }
        SectionCard("Add by IP") {
            Body("On the Cube: Settings > My Fire TV > About > Network shows the IP address.", alpha = 0.75f)
            NeonField(name, { name = it }, "Nickname")
            NeonField(ip, { ip = it.trim() }, "IP address e.g. 192.168.1.42", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            NeonField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            NeonButton("Save & connect", enabled = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip)) { add(name, ip, port.toIntOrNull() ?: 5555) }
        }
        SectionCard("ADB key") {
            Body("This phone has its own permanent ADB key, so the TV only asks once. Reset it only if you want to revoke and re-pair.", alpha = 0.75f)
            NeonOutlineButton("Reset ADB key…") { confirmReset = true }
        }
        Spacer(Modifier.height(80.dp))
    }
    if (confirmReset) ConfirmDialog("Reset the ADB key?", "Every Fire TV will ask 'Allow USB debugging?' again for this phone.", onConfirm = { d.app.cube.resetKey() }, onDismiss = { confirmReset = false })
}

private data class Check(val name: String, val ok: Boolean?, val detail: String)

@Composable
fun DoctorScreen(d: Deps) {
    val pal = LocalPalette.current
    val devices by d.app.store.devices.collectAsState()
    var target by remember { mutableStateOf(d.app.store.activeDevice()?.host ?: "") }
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runChecks() {
        running = true; checks = emptyList()
        scope.launch {
            val disc = d.app.cube.discovery
            val out = mutableListOf<Check>()
            fun push(c: Check) { out += c; checks = out.toList() }
            val wifi = disc.isOnWifi()
            push(Check("Phone on Wi-Fi", wifi, if (wifi) "Yes" else "Phone isn't on Wi-Fi. Turn Wi-Fi on and join the same network as the Cube (mobile data can't reach it)."))
            val me = disc.localIpv4()
            push(Check("Phone IP", me != null, me ?: "No IPv4 address"))
            if (target.isBlank()) { push(Check("Cube IP", false, "Enter the Cube's IP (Settings > My Fire TV > About > Network) or scan in My Fire TVs.")); running = false; return@launch }
            val same = disc.sameSubnet(me, target)
            push(Check("Same network", same, if (same) "$me and $target look like the same home network" else "Phone $me and Cube $target are on different networks (guest Wi-Fi? mesh/extender?). Put both on the same main Wi-Fi."))
            val open = disc.probe(target, 5555, 1500)
            if (open) push(Check("Port 5555 open", true, "The Cube is listening for ADB."))
            else {
                val reach = withContext(Dispatchers.IO) { runCatching { java.net.InetAddress.getByName(target).isReachable(1500) }.getOrDefault(false) }
                push(Check("Port 5555 open", false,
                    if (reach) "The Cube answers but ADB is off: Settings > My Fire TV > Developer Options > ADB Debugging ON. (If Developer Options is missing: About > press Select 7 times on the device name.)"
                    else "No answer at all. Wrong IP, the Cube is unplugged, or your router has AP/client isolation (common on guest networks and some mesh routers). Turn isolation off or use the main network."))
            }
            if (open) {
                push(Check("TV permission", null, "Asking the TV… if a prompt appears, tick 'Always allow' and press OK."))
                val keyPair = withContext(Dispatchers.IO) { AdbKeys.loadOrCreate(File(d.app.filesDir, "adbkey")) }
                val outcome = withContext(Dispatchers.IO) { CubeAdb(target, 5555, keyPair).let { s -> s.connect(40_000).also { s.close() } } }
                out.removeAt(out.lastIndex)
                when (outcome) {
                    is ConnectOutcome.Connected -> push(Check("TV permission", true, "Allowed. ${outcome.model} · ${outcome.build}"))
                    is ConnectOutcome.AuthRejected -> push(Check("TV permission", false, outcome.hint))
                    is ConnectOutcome.AuthTimeout -> push(Check("TV permission", false, outcome.hint))
                    is ConnectOutcome.Unreachable -> push(Check("TV permission", false, outcome.hint))
                    is ConnectOutcome.Failed -> push(Check("TV permission", false, outcome.hint))
                }
            }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Connection checker") {
            Body("Checks Wi-Fi, network, port 5555, the TV's permission prompt and router isolation.", alpha = 0.75f)
            NeonField(target, { target = it.trim() }, "Cube IP")
            if (devices.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { devices.take(3).forEach { dv -> AssistChip(onClick = { target = dv.host }, label = { Text(dv.name, fontSize = 11.sp) }) } }
            NeonButton(if (running) "Checking…" else "Run checks", icon = Icons.Default.HealthAndSafety, enabled = !running) { runChecks() }
        }
        checks.forEach { c ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (c.ok) { true -> "✅"; false -> "❌"; null -> "⏳" }, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(c.name, color = pal.accent, fontWeight = FontWeight.Bold)
                }
                Body(c.detail)
            }
        }
        if (checks.any { it.ok == false }) NeonOutlineButton("Open the setup guide") { d.nav.go(Screen.Setup) }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun SetupScreen(d: Deps) {
    val pal = LocalPalette.current
    val status by d.app.cube.status.collectAsState()
    var cecLog by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val steps = listOf(
        "On the Cube: Settings > My Fire TV > About. Highlight the Cube's name and press Select 7 times until it says you're a developer.",
        "Press Back > Developer Options > turn ADB Debugging ON.",
        "Settings > My Fire TV > About > Network: note the IP address (e.g. 192.168.1.42).",
        "Make sure this phone and the Cube are on the same Wi-Fi (not guest Wi-Fi).",
        "In this app: My Fire TVs > Scan (or type the IP) > Connect.",
        "On the TV a box asks 'Allow USB debugging?'. Tick 'Always allow from this computer' and press OK. You only do this once.",
        "Optional: Settings > Display & Sounds > HDMI CEC Device Control ON, so the app can turn your TV on/off and change TV volume.",
        "Optional: reserve the Cube's IP in your router so it never changes."
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("One-time setup on your Fire TV Cube") {
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text("${i + 1}", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Black,
                        modifier = Modifier.size(24.dp).background(pal.accent, androidx.compose.foundation.shape.CircleShape).wrapContentSize(Alignment.Center))
                    Spacer(Modifier.width(10.dp))
                    Body(s)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Find my Cube") { d.nav.go(Screen.Devices) }
                NeonOutlineButton("Connection checker") { d.nav.go(Screen.Doctor) }
            }
        }
        SectionCard("HDMI-CEC test (TV power & volume)") {
            Body("Sends real commands to the Cube. Watch the TV: it should go to sleep, wake, then show a volume change. If nothing happens, turn on HDMI CEC on the Cube and on the TV (Anynet+, Bravia Sync, SimpLink…).", alpha = 0.8f)
            NeonButton("Run CEC test", icon = Icons.Default.Tv, enabled = status.state == ConnState.Connected) {
                scope.launch {
                    cecLog = "Sleep (TV should turn off)…"; d.app.cube.key("SLEEP"); delay(8000)
                    cecLog = "Wake (TV should turn on)…"; d.app.cube.key("WAKE"); delay(8000)
                    cecLog = "Volume up ×2 then down ×2…"; repeat(2) { d.app.cube.key("VOLUME_UP"); delay(700) }; repeat(2) { d.app.cube.key("VOLUME_DOWN"); delay(700) }
                    cecLog = "Done. Did the TV turn off/on and show volume? If yes, CEC works."
                }
            }
            if (cecLog.isNotBlank()) Body(cecLog)
        }
        Spacer(Modifier.height(80.dp))
    }
}

