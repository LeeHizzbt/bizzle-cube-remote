package com.bizzle.cuberemote.ui.screens

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.bizzle.cube.core.ScrcpyProtocol
import com.bizzle.cuberemote.mirror.MirrorState
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ScreenViewScreen(d: Deps) {
    val pal = LocalPalette.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = pal.bg, contentColor = pal.accent) {
            listOf("Live mirror", "1 fps viewer", "Screenshot").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, color = if (tab == i) pal.accent else pal.text.copy(alpha = 0.7f)) })
            }
        }
        Text("Note: Netflix, Prime Video, Disney+ and other DRM-protected video shows as BLACK in mirrors and screenshots. That's Fire OS protection, not a bug.",
            color = Color(0xFFFFC23D), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        when (tab) {
            0 -> LiveMirror(d) { tab = 1 }
            1 -> SlowViewer(d)
            else -> ScreenshotTab(d)
        }
    }
}

@Composable
private fun LiveMirror(d: Deps, onFallback: () -> Unit) {
    val pal = LocalPalette.current
    val state by d.mirror.state.collectAsState()
    val status by d.app.cube.status.collectAsState()
    DisposableEffect(Unit) { onDispose { d.mirror.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val ratio = (state as? MirrorState.Live)?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        if (status.state.name != "Connected") Body("Connect to your Cube first.")
        else Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).border(2.dp, pal.accent, RoundedCornerShape(12.dp)).background(Color.Black)
            .semantics { contentDescription = "Live TV mirror. Tap or swipe on it to control the TV." }) {
            AndroidView(factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { d.mirror.start(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { d.mirror.stop() }
                    })
                    setOnTouchListener { v, e ->
                        val nx = e.x / v.width; val ny = e.y / v.height
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> d.mirror.touch(ScrcpyProtocol.ACTION_DOWN, nx, ny)
                            MotionEvent.ACTION_MOVE -> d.mirror.touch(ScrcpyProtocol.ACTION_MOVE, nx, ny)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { d.mirror.touch(ScrcpyProtocol.ACTION_UP, nx, ny); v.performClick() }
                        }
                        true
                    }
                }
            }, modifier = Modifier.fillMaxSize())
            when (val s = state) {
                is MirrorState.Starting -> Text(s.step, color = pal.accent, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                is MirrorState.Failed -> Column(Modifier.align(Alignment.Center).padding(16.dp)) {
                    Text("Mirroring failed: ${s.message.take(200)}", color = Color(0xFFFF5370), fontSize = 12.sp)
                    NeonButton("Use 1 fps viewer instead") { onFallback() }
                }
                else -> {}
            }
        }
        (state as? MirrorState.Live)?.let { Text("Live ${it.width}×${it.height} · ${"%.0f".format(it.fps)} fps (frames only arrive when the picture changes)", color = pal.text.copy(alpha = 0.6f), fontSize = 11.sp) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteKey("", "Back", icon = Icons.AutoMirrored.Filled.ArrowBack, size = 48.dp) { d.mirror.key(4) }
            RemoteKey("", "Up", icon = Icons.Default.KeyboardArrowUp, size = 48.dp, repeat = true) { d.mirror.key(19) }
            RemoteKey("", "Home", icon = Icons.Default.Home, size = 48.dp) { d.mirror.key(3) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            RemoteKey("", "Left", icon = Icons.Default.KeyboardArrowLeft, size = 48.dp, repeat = true) { d.mirror.key(21) }
            RemoteKey("OK", "OK select", size = 48.dp, filled = true) { d.mirror.key(23) }
            RemoteKey("", "Right", icon = Icons.Default.KeyboardArrowRight, size = 48.dp, repeat = true) { d.mirror.key(22) }
        }
        RemoteKey("", "Down", icon = Icons.Default.KeyboardArrowDown, size = 48.dp, repeat = true) { d.mirror.key(20) }
        Spacer(Modifier.height(8.dp))
        Text("Powered by scrcpy (Genymobile, Apache-2.0). Tap/swipe on the picture to touch the TV.", color = pal.text.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(Modifier.height(80.dp))
    }
}

/** Fallback viewer: one screenshot per second; tap the picture to tap the TV. */
@Composable
private fun SlowViewer(d: Deps) {
    val pal = LocalPalette.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(Unit) {
        screen = d.app.cube.call { it.screenSize() }.getOrNull()
        while (isActive) {
            val r = d.app.cube.call { it.screencapPng() }
            r.onSuccess { png -> bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }; err = null }
                .onFailure { err = it.message }
            delay(800)
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val b = bmp
        if (b == null) Body(err ?: "Grabbing the first frame…")
        else Image(b.asImageBitmap(), "TV screen, refreshed about once per second. Tap to tap the TV.",
            Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).border(2.dp, pal.accent).pointerInput(screen) {
                detectTapGestures { o ->
                    val (w, h) = screen ?: (b.width to b.height)
                    val x = (o.x / size.width * w).toInt(); val y = (o.y / size.height * h).toInt()
                    d.app.cube.run<Unit> { it.tap(x, y) }
                }
            })
        err?.let { Body("Error: $it", alpha = 0.7f) }
        Spacer(Modifier.height(8.dp))
        RemotePad(d)
    }
}

@Composable
private fun ScreenshotTab(d: Deps) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    var png by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NeonButton(if (busy) "Grabbing…" else "Take TV screenshot", icon = Icons.Default.Screenshot, enabled = !busy) {
            busy = true
            scope.launch { d.app.cube.call { it.screencapPng() }.onSuccess { png = it }.onFailure { d.app.cube.emit("Screenshot failed: ${it.message}") }; busy = false }
        }
        png?.let { bytes ->
            val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            Image(bmp.asImageBitmap(), "TV screenshot", Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).border(2.dp, pal.accent))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Save to phone", icon = Icons.Default.Download) {
                    val name = "cube_${System.currentTimeMillis()}.png"
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CubeRemote")
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; d.app.cube.emit("Saved to Pictures/CubeRemote") }
                }
                NeonOutlineButton("Share", icon = Icons.Default.Share) {
                    val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
                    val f = File(dir, "cube_screenshot.png").apply { writeBytes(bytes) }
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share TV screenshot"))
                }
            }
            NeonOutlineButton("Ask Bizzy about this screen", icon = Icons.Default.SmartToy) { d.brain.helpWithScreen("") }
        }
        Spacer(Modifier.height(80.dp))
    }
}
