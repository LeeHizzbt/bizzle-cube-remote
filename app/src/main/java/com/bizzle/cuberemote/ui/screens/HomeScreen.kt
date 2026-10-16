package com.bizzle.cuberemote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.StreamingApps
import com.bizzle.cuberemote.cube.ConnState
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.Screen
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun HomeScreen(d: Deps, shared: String?) {
    val pal = LocalPalette.current
    val status by d.app.cube.status.collectAsState()
    val ui by d.app.store.ui.collectAsState()
    val devices by d.app.store.devices.collectAsState()
    var trackpad by rememberSaveable { mutableStateOf(false) }
    var sharedText by remember(shared) { mutableStateOf(shared) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (status.state != ConnState.Connected) {
            SectionCard(if (devices.isEmpty()) "Let's hook up your Fire TV Cube" else "Not connected") {
                Body(status.message)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (devices.isEmpty()) {
                        NeonButton("Setup guide", icon = Icons.Default.MenuBook) { d.nav.go(Screen.Setup) }
                        NeonOutlineButton("Find my Cube", icon = Icons.Default.Search) { d.nav.go(Screen.Devices) }
                    } else {
                        NeonButton("Reconnect", icon = Icons.Default.Refresh) { d.app.store.activeDevice()?.let { d.app.cube.connect(it) } }
                        NeonOutlineButton("Checker", icon = Icons.Default.HealthAndSafety) { d.nav.go(Screen.Doctor) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        sharedText?.let { text ->
            val svc = StreamingApps.serviceForLink(text.trim())
            SectionCard("Send to Cube") {
                Body(text.take(200))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (svc != null) NeonButton("Open in ${svc.label}") {
                        scope.launch { d.app.cube.runActions(listOf(BizzyAction.DeepLink(svc.id, text.trim()))).forEach { d.app.cube.emit(it) } }
                        sharedText = null
                    }
                    NeonOutlineButton("Type it") { d.app.cube.text(text.take(300)); sharedText = null }
                    TextButton(onClick = { sharedText = null }) { Text("Dismiss", color = pal.text) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilterChip(selected = !trackpad, onClick = { trackpad = false }, label = { Text("Buttons") }, leadingIcon = { Icon(Icons.Default.Gamepad, null) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = pal.accent, selectedLabelColor = androidx.compose.ui.graphics.Color.Black, selectedLeadingIconColor = androidx.compose.ui.graphics.Color.Black, labelColor = pal.accent, iconColor = pal.accent))
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = trackpad, onClick = { trackpad = true }, label = { Text("Swipe pad") }, leadingIcon = { Icon(Icons.Default.TouchApp, null) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = pal.accent, selectedLabelColor = androidx.compose.ui.graphics.Color.Black, selectedLeadingIconColor = androidx.compose.ui.graphics.Color.Black, labelColor = pal.accent, iconColor = pal.accent))
        }
        Spacer(Modifier.height(10.dp))
        if (trackpad) Trackpad(d) else RemotePad(d)
        Spacer(Modifier.height(10.dp))

        val big = if (ui.bigButtons) 64.dp else 54.dp
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteKey("", "Rewind", icon = Icons.Default.FastRewind, size = big, repeat = true, repeatMs = ui.repeatMs * 3) { d.app.cube.key("REWIND") }
            RemoteKey("", "Play or pause", icon = Icons.Default.PlayArrow, size = big, filled = true) { d.app.cube.key("PLAY_PAUSE") }
            RemoteKey("", "Fast forward", icon = Icons.Default.FastForward, size = big, repeat = true, repeatMs = ui.repeatMs * 3) { d.app.cube.key("FAST_FORWARD") }
            RemoteKey("", "Captions", icon = Icons.Default.ClosedCaption, size = big) { d.app.cube.key("CAPTIONS") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteKey("TV ON", "Wake Cube and TV (HDMI-CEC)", size = big, width = 84.dp) { d.app.cube.key("WAKE") }
            RemoteKey("TV OFF", "Sleep Cube and TV (HDMI-CEC)", size = big, width = 84.dp) { d.app.cube.key("SLEEP") }
            RemoteKey("", "Search", icon = Icons.Default.Search, size = big) { d.app.cube.key("SEARCH") }
            RemoteKey("", "Fire TV settings", icon = Icons.Default.SettingsApplications, size = big) { d.app.cube.key("SETTINGS") }
        }
        Spacer(Modifier.height(16.dp))
        FeatureGrid(d)
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun RemotePad(d: Deps) {
    val pal = LocalPalette.current
    val ui by d.app.store.ui.collectAsState()
    val s = if (ui.bigButtons) 74.dp else 66.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteKey("", "Back", icon = Icons.AutoMirrored.Filled.ArrowBack, size = 54.dp, shape = CircleShape) { d.app.cube.key("BACK") }
            RemoteKey("", "Home", icon = Icons.Default.Home, size = 54.dp, shape = CircleShape) { d.app.cube.key("HOME") }
            RemoteKey("", "Menu", icon = Icons.Default.Menu, size = 54.dp, shape = CircleShape) { d.app.cube.key("MENU") }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(s * 3 + 16.dp).clip(CircleShape).background(pal.surface).border(2.dp, pal.accent.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteKey("", "Up", icon = Icons.Default.KeyboardArrowUp, size = s, shape = CircleShape, repeat = true, repeatMs = ui.repeatMs) { d.app.cube.key("UP") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteKey("", "Left", icon = Icons.Default.KeyboardArrowLeft, size = s, shape = CircleShape, repeat = true, repeatMs = ui.repeatMs) { d.app.cube.key("LEFT") }
                    RemoteKey("OK", "OK select", size = s, shape = CircleShape, filled = true) { d.app.cube.key("OK") }
                    RemoteKey("", "Right", icon = Icons.Default.KeyboardArrowRight, size = s, shape = CircleShape, repeat = true, repeatMs = ui.repeatMs) { d.app.cube.key("RIGHT") }
                }
                RemoteKey("", "Down", icon = Icons.Default.KeyboardArrowDown, size = s, shape = CircleShape, repeat = true, repeatMs = ui.repeatMs) { d.app.cube.key("DOWN") }
            }
        }
    }
}

/** Swipe pad: swipe = arrow (long swipes repeat), tap = OK, double-tap = Back, long-press = Menu. */
@Composable
fun Trackpad(d: Deps) {
    val pal = LocalPalette.current
    val ui by d.app.store.ui.collectAsState()
    val haptic = rememberHaptic()
    var last by remember { mutableStateOf("Swipe to move · tap = OK · double-tap = Back · hold = Menu") }
    val step = 90f / ui.trackpadSensitivity.coerceIn(0.3f, 3f)
    Box(
        Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)).background(pal.surface)
            .border(2.dp, pal.accent.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .semantics { contentDescription = "Swipe trackpad. Swipe to move, tap for OK, double tap for back, hold for menu" }
            .pointerInput(step, ui.naturalScroll) {
                var accX = 0f; var accY = 0f
                detectDragGestures(onDragStart = { accX = 0f; accY = 0f }) { change, drag ->
                    change.consume()
                    accX += drag.x; accY += drag.y
                    val inv = if (ui.naturalScroll) -1 else 1
                    while (abs(accX) > step && abs(accX) >= abs(accY)) {
                        val k = if (accX * inv > 0) "RIGHT" else "LEFT"
                        d.app.cube.key(k); haptic(); last = k
                        accX -= step * kotlin.math.sign(accX)
                    }
                    while (abs(accY) > step && abs(accY) > abs(accX)) {
                        val k = if (accY * inv > 0) "DOWN" else "UP"
                        d.app.cube.key(k); haptic(); last = k
                        accY -= step * kotlin.math.sign(accY)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { d.app.cube.key("OK"); haptic(); last = "OK" },
                    onDoubleTap = { d.app.cube.key("BACK"); haptic(); last = "BACK" },
                    onLongPress = { d.app.cube.key("MENU"); haptic(); last = "MENU" }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(last, color = pal.accent.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun FeatureGrid(d: Deps) {
    val items: List<Triple<String, ImageVector, Screen>> = listOf(
        Triple("Keyboard", Icons.Default.Keyboard, Screen.Keyboard),
        Triple("Apps", Icons.Default.Apps, Screen.Apps),
        Triple("Where to watch", Icons.Default.Movie, Screen.Watch),
        Triple("Bizzy AI", Icons.Default.SmartToy, Screen.Bizzy),
        Triple("Routines & timer", Icons.Default.Bedtime, Screen.Routines),
        Triple("TV screen / mirror", Icons.Default.Cast, Screen.ScreenView),
        Triple("Device care", Icons.Default.Memory, Screen.DeviceCare),
        Triple("My Fire TVs", Icons.Default.Tv, Screen.Devices),
        Triple("Checker", Icons.Default.HealthAndSafety, Screen.Doctor),
        Triple("Setup guide", Icons.Default.MenuBook, Screen.Setup),
        Triple("Advanced AI", Icons.Default.Tune, Screen.AiSettings),
        Triple("Can't do", Icons.Default.Block, Screen.CantDo)
    )
    val pal = LocalPalette.current
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, icon, screen) ->
                Column(
                    Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(14.dp)).background(pal.surface)
                        .border(1.dp, pal.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable { d.nav.go(screen) }.semantics { contentDescription = "Open $label" }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, null, tint = pal.accent)
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = pal.text, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, fontWeight = FontWeight.Medium)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
