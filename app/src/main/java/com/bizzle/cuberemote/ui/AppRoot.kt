package com.bizzle.cuberemote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.ThemeSpec
import com.bizzle.cuberemote.CubeRemoteApp
import com.bizzle.cuberemote.R
import com.bizzle.cuberemote.bizzy.BizzyBrain
import com.bizzle.cuberemote.cube.ConnState
import com.bizzle.cuberemote.mirror.MirrorController
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.screens.*
import com.bizzle.cuberemote.ui.theme.BizzleTheme
import com.bizzle.cuberemote.ui.theme.LocalPalette

enum class Screen(val title: String) {
    Home("Remote"), Keyboard("Keyboard"), Apps("Apps"), Watch("Where to watch"), Bizzy("Bizzy AI"),
    Routines("Routines & timer"), ScreenView("TV screen"), DeviceCare("Device care"), Devices("My Fire TVs"),
    Doctor("Connection checker"), Setup("Setup guide"), Settings("Settings"), AiSettings("Advanced AI Settings"),
    Themes("Themes"), CantDo("What it can't do"), About("About & credits")
}

class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    fun go(s: Screen) { if (current != s) stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { stack.clear(); stack.add(Screen.Home) }
}

data class Deps(val app: CubeRemoteApp, val brain: BizzyBrain, val mirror: MirrorController, val nav: Nav)

@Composable
fun AppRoot(app: CubeRemoteApp, brain: BizzyBrain, mirror: MirrorController, startScreen: Screen, startVoice: Boolean, shared: String?) {
    val uiSettings by app.store.ui.collectAsState()
    val custom by app.store.customThemes.collectAsState()
    val spec = (ThemeSpec.BUILT_IN + custom).firstOrNull { it.name == uiSettings.theme } ?: ThemeSpec.BUILT_IN.first()
    val nav = remember { Nav(startScreen) }
    LaunchedEffect(startScreen) { if (startScreen != Screen.Home) nav.go(startScreen) }
    val deps = remember { Deps(app, brain, mirror, nav) }
    var bizzyOpen by remember { mutableStateOf(startVoice) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.cube.events.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Short) } }

    BizzleTheme(spec) {
        CompositionLocalProvider(LocalHaptics provides uiSettings.haptics) {
            val pal = LocalPalette.current
            BackHandler(enabled = nav.stack.size > 1) { nav.back() }
            Scaffold(
                containerColor = pal.bg,
                topBar = { TopBar(deps) },
                bottomBar = { MediaBar(deps) },
                snackbarHost = { SnackbarHost(snackbar) },
                floatingActionButton = { if (nav.current != Screen.Bizzy) BizzyFab { bizzyOpen = true } }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize().background(pal.bg)) {
                    when (nav.current) {
                        Screen.Home -> HomeScreen(deps, shared)
                        Screen.Keyboard -> KeyboardScreen(deps)
                        Screen.Apps -> AppsScreen(deps)
                        Screen.Watch -> WatchScreen(deps)
                        Screen.Bizzy -> BizzyScreen(deps, startVoice = false)
                        Screen.Routines -> RoutinesScreen(deps)
                        Screen.ScreenView -> ScreenViewScreen(deps)
                        Screen.DeviceCare -> DeviceCareScreen(deps)
                        Screen.Devices -> DevicesScreen(deps)
                        Screen.Doctor -> DoctorScreen(deps)
                        Screen.Setup -> SetupScreen(deps)
                        Screen.Settings -> SettingsScreen(deps)
                        Screen.AiSettings -> AiSettingsScreen(deps)
                        Screen.Themes -> ThemesScreen(deps)
                        Screen.CantDo -> CantDoScreen(deps)
                        Screen.About -> AboutScreen(deps)
                    }
                }
            }
            if (bizzyOpen) BizzyPopup(deps, startVoice) { bizzyOpen = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(d: Deps) {
    val pal = LocalPalette.current
    val status by d.app.cube.status.collectAsState()
    val devices by d.app.store.devices.collectAsState()
    val turbo by d.app.cube.turboActive.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val dot = when (status.state) {
        ConnState.Connected -> pal.accent
        ConnState.Connecting, ConnState.WaitingForTv -> Color(0xFFFFC23D)
        else -> Color(0xFFFF5370)
    }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = pal.bg, titleContentColor = pal.accent),
        navigationIcon = {
            if (d.nav.stack.size > 1) {
                IconButton(onClick = { d.nav.back() }, modifier = Modifier.padding(start = 4.dp).clip(CircleShape).background(pal.surface)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = pal.accent)
                }
            } else {
                Image(painterResource(R.drawable.logo_round), contentDescription = "Bizzle and Jinxy logo",
                    modifier = Modifier.padding(start = 8.dp).size(38.dp).clip(CircleShape))
            }
        },
        title = {
            Column(Modifier.clickable { menu = true }.semantics { contentDescription = "Switch Fire TV. Current: ${status.device?.name ?: "none"}, ${status.state}" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (d.nav.current == Screen.Home) "Cube Remote " else d.nav.current.title + " ", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = pal.accent,
                        modifier = Modifier.semantics { heading() }, maxLines = 1)
                    Text("v4.20", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(CircleShape).background(pal.accent).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dot, status.state.name)
                    Spacer(Modifier.width(5.dp))
                    Text((status.device?.name ?: "No Fire TV yet") + (if (turbo && status.state == ConnState.Connected) " · turbo" else "") + " ▾",
                        fontSize = 12.sp, color = pal.text.copy(alpha = 0.8f), maxLines = 1)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(pal.surface)) {
                devices.forEach { dev ->
                    DropdownMenuItem(
                        text = { Text((if (dev.id == status.device?.id) "● " else "○ ") + "${dev.name}  ${dev.host}", color = pal.text) },
                        onClick = { menu = false; d.app.cube.connect(dev) }
                    )
                }
                DropdownMenuItem(text = { Text("+ Add / find Fire TVs", color = pal.accent) }, onClick = { menu = false; d.nav.go(Screen.Devices) })
                if (status.state == ConnState.Connected) DropdownMenuItem(text = { Text("Disconnect", color = pal.text) }, onClick = { menu = false; d.app.cube.disconnect() })
            }
        },
        actions = {
            if (d.nav.current != Screen.Home) IconButton(onClick = { d.nav.home() }) { Icon(Icons.Default.Home, "Back to remote", tint = pal.accent) }
            IconButton(onClick = { d.nav.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings", tint = pal.accent) }
        }
    )
}

/** Play/pause and volume are always one tap away, on every screen. */
@Composable
fun MediaBar(d: Deps) {
    val pal = LocalPalette.current
    val ui by d.app.store.ui.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(pal.bg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteKey("", "Volume down", icon = Icons.AutoMirrored.Filled.VolumeDown, size = 48.dp, repeat = true, repeatMs = ui.repeatMs * 2) { d.app.cube.key("VOLUME_DOWN") }
        RemoteKey("", "Mute", icon = Icons.Default.VolumeOff, size = 48.dp) { d.app.cube.key("MUTE") }
        RemoteKey("", "Play or pause", icon = Icons.Default.PlayArrow, size = 54.dp, filled = true) { d.app.cube.key("PLAY_PAUSE") }
        RemoteKey("", "Volume up", icon = Icons.AutoMirrored.Filled.VolumeUp, size = 48.dp, repeat = true, repeatMs = ui.repeatMs * 2) { d.app.cube.key("VOLUME_UP") }
        Spacer(Modifier.width(56.dp)) // room for the Bizzy button
    }
}
