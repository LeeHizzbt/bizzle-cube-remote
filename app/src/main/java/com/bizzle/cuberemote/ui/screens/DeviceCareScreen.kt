package com.bizzle.cuberemote.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.CubeApp
import com.bizzle.cube.core.DeviceInfo
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DeviceCareScreen(d: Deps) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val apps by d.app.cube.apps.collectAsState()
    val status by d.app.cube.status.collectAsState()
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var infoErr by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CubeApp?>(null) }
    var pickMenu by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var installMsg by remember { mutableStateOf<String?>(null) }
    var thirdOnly by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun loadInfo() { scope.launch { d.app.cube.call { it.deviceInfo() }.onSuccess { info = it; infoErr = null }.onFailure { infoErr = it.message } } }
    LaunchedEffect(status.state) { if (status.state.name == "Connected") loadInfo() }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        installMsg = "Copying APK…"
        scope.launch {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "app.apk"
            val f = withContext(Dispatchers.IO) {
                File(ctx.cacheDir, "install.apk").apply { ctx.contentResolver.openInputStream(uri)!!.use { i -> outputStream().use { i.copyTo(it) } } }
            }
            installMsg = "Installing $name on the Cube (${f.length() / 1024} KB)…"
            d.app.cube.call { it.install(f) }
                .onSuccess { r -> installMsg = "$name: $r"; f.delete(); d.app.cube.refreshApps() }
                .onFailure { installMsg = "Install failed: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Cube info") {
            val i = info
            if (i == null) Body(infoErr ?: if (status.state.name == "Connected") "Loading…" else "Connect to your Cube to see its info.")
            else {
                InfoRow("Model", i.model); InfoRow("Fire OS", i.fireOs); InfoRow("Android", i.android)
                InfoRow("Storage", i.storage); InfoRow("RAM", i.ram); InfoRow("Network", i.wifi); InfoRow("IP", i.ip)
                InfoRow("Screen", i.resolution); InfoRow("Uptime", i.uptime)
            }
            TextButton(onClick = { loadInfo() }) { Text("Refresh", color = pal.accent) }
        }
        SectionCard("Speed up") {
            Body("Clears every app's cache on the Cube (keeps logins and data).", alpha = 0.75f)
            NeonButton("One-tap clear all caches", icon = Icons.Default.CleaningServices) {
                d.app.cube.run<String>(onResult = { d.app.cube.emit("Caches cleared"); loadInfo() }) { it.clearAllCaches() }
            }
        }
        SectionCard("Install an APK from this phone") {
            Body("Pick an .apk file on the phone; it's sent straight to the Cube over ADB.", alpha = 0.75f)
            NeonButton("Choose APK…", icon = Icons.Default.InstallMobile) { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
            installMsg?.let { Body(it) }
        }
        SectionCard("App manager") {
            Box {
                NeonOutlineButton(selected?.let { "${it.label} (${it.pkg})" } ?: "Pick an app…", icon = Icons.Default.Apps) { pickMenu = true }
                DropdownMenu(pickMenu, { pickMenu = false }) {
                    apps.filter { !thirdOnly || !it.system }.forEach { a -> DropdownMenuItem(text = { Text(a.label + "  ·  " + a.pkg, fontSize = 13.sp) }, onClick = { selected = a; pickMenu = false }) }
                }
            }
            SettingSwitch("Only apps you installed", null, thirdOnly) { thirdOnly = it }
            selected?.let { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonOutlineButton("Force stop") { d.app.cube.run<Unit>("Stopped ${a.label}") { it.forceStop(a.pkg) } }
                    NeonOutlineButton("Clear data") {
                        confirm = Triple("Clear data for ${a.label}?", "This signs you out and deletes the app's settings and downloads on the Cube. It can't be undone.") {
                            d.app.cube.run<String>(onResult = { r -> d.app.cube.emit("Clear data: ${r.trim()}") }) { it.clearData(a.pkg) }
                        }
                    }
                }
            }
        }
        SectionCard("Advanced (careful)") {
            SettingSwitch("Show advanced tools", "Uninstall, disable apps, reboot", advanced) { advanced = it }
            if (advanced) {
                Text("These change your Cube. Each one asks to confirm first.", color = Color(0xFFFFC23D), fontSize = 12.sp)
                val a = selected
                if (a == null) Body("Pick an app above to uninstall or disable it.", alpha = 0.7f)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Uninstall", danger = true) {
                        confirm = Triple("Uninstall ${a.label}?", "Removes ${a.pkg} from the Cube. Built-in Amazon apps usually refuse.") {
                            d.app.cube.run<String>(onResult = { r -> d.app.cube.emit("Uninstall: ${r.trim()}"); d.app.cube.refreshApps() }) { it.uninstall(a.pkg) }
                        }
                    }
                    NeonOutlineButton("Disable") {
                        confirm = Triple("Disable ${a.label}?", "Hides and stops ${a.pkg} until you enable it again. Protected Amazon apps refuse.") {
                            d.app.cube.run<String>(onResult = { r -> d.app.cube.emit(r.trim()) }) { it.disable(a.pkg) }
                        }
                    }
                    NeonOutlineButton("Enable") { d.app.cube.run<String>(onResult = { r -> d.app.cube.emit(r.trim()) }) { it.enable(a.pkg) } }
                }
                Spacer(Modifier.height(8.dp))
                NeonButton("Reboot the Cube", icon = Icons.Default.RestartAlt, danger = true) {
                    confirm = Triple("Reboot the Cube?", "The Cube restarts (about a minute). The app reconnects by itself afterwards.") {
                        d.app.cube.run<Unit>("Rebooting…") { it.reboot() }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
    confirm?.let { (t, body, action) -> ConfirmDialog(t, body, onConfirm = action, onDismiss = { confirm = null }) }
}

@Composable
private fun InfoRow(k: String, v: String) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = pal.text.copy(alpha = 0.6f), modifier = Modifier.width(90.dp), fontSize = 13.sp)
        Text(v.ifBlank { "?" }, color = pal.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
