package com.bizzle.cuberemote.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.CubeApp
import com.bizzle.cube.core.StreamingApps
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(d: Deps) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val apps by d.app.cube.apps.collectAsState()
    val favMap by d.app.store.favorites.collectAsState()
    val status by d.app.cube.status.collectAsState()
    val devId = status.device?.id ?: "none"
    val favs = favMap[devId].orEmpty()
    var filter by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<CubeApp?>(null) }
    var linkText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(status.state) { if (apps.isEmpty()) d.app.cube.refreshApps() }

    val shown = apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }
        .sortedWith(compareBy({ it.pkg !in favs }, { it.system }, { it.label.lowercase() }))

    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard("Deep links") {
                    Body("Open a streaming app, or paste a share link (e.g. a Netflix or YouTube link from your phone) to open it right on the TV.", alpha = 0.8f)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("netflix", "prime", "youtube", "disney", "hulu").forEach { id ->
                            val svc = StreamingApps.byId(id)!!
                            val pkg = StreamingApps.installedPackage(svc, apps.map { it.pkg })
                            AssistChip(onClick = {
                                if (pkg != null) d.app.cube.run<String>("Opening ${svc.label}") { it.launch(pkg) } else d.app.cube.emit("${svc.label} isn't installed on this Cube")
                            }, label = { Text(svc.label, fontSize = 11.sp, maxLines = 1) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = if (pkg != null) pal.accent else pal.text.copy(alpha = 0.4f)))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NeonField(linkText, { linkText = it }, "Paste link (Netflix / YouTube / Prime / Disney+ / Hulu)", Modifier.weight(1f))
                        IconButton(onClick = {
                            linkText = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty().trim()
                        }) { Icon(Icons.Default.ContentPaste, "Paste from clipboard", tint = pal.accent) }
                    }
                    NeonButton("Open link on TV", icon = Icons.Default.OpenInNew, enabled = linkText.isNotBlank()) {
                        val l = linkText.trim()
                        val svc = StreamingApps.serviceForLink(l)
                        if (svc == null) d.app.cube.emit("That isn't a Netflix/Prime/YouTube/Disney+/Hulu link")
                        else scope.launch { d.app.cube.runActions(listOf(BizzyAction.DeepLink(svc.id, l))).forEach { d.app.cube.emit(it) } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeonField(filter, { filter = it }, "Filter ${apps.size} apps", Modifier.weight(1f))
                    IconButton(onClick = { d.app.cube.refreshApps() }) { Icon(Icons.Default.Refresh, "Reload app list", tint = pal.accent) }
                }
                Body("Tap to open · long-press for favorite / force-stop", alpha = 0.6f)
                if (apps.isEmpty()) Body(if (status.state.name == "Connected") "Loading apps…" else "Connect to your Cube to see its apps.")
            }
        }
        items(shown, key = { it.pkg }) { a ->
            val fav = a.pkg in favs
            Box {
                Column(
                    Modifier.height(104.dp).clip(RoundedCornerShape(16.dp)).background(pal.surface)
                        .border(1.dp, pal.accent.copy(alpha = if (fav) 0.9f else 0.3f), RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { d.app.cube.run<String>("Opening ${a.label}") { it.launch(a.pkg) } }, onLongClick = { menuFor = a })
                        .semantics { contentDescription = "${a.label}${if (fav) ", favorite" else ""}. Tap to open, long press for options" }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(pal.accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text(a.label.take(1).uppercase(), color = pal.accent, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text((if (fav) "★ " else "") + a.label, color = pal.text, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
                }
                DropdownMenu(expanded = menuFor == a, onDismissRequest = { menuFor = null }, modifier = Modifier.background(pal.surface)) {
                    Text(a.pkg, color = pal.text.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    DropdownMenuItem(text = { Text("Open", color = pal.text) }, onClick = { menuFor = null; d.app.cube.run<String>("Opening ${a.label}") { it.launch(a.pkg) } })
                    DropdownMenuItem(text = { Text(if (fav) "Remove favorite" else "Add to favorites", color = pal.text) }, onClick = { menuFor = null; d.app.store.toggleFavorite(devId, a.pkg) })
                    DropdownMenuItem(text = { Text("Force stop", color = Color(0xFFFFC23D)) }, onClick = { menuFor = null; d.app.cube.run<Unit>("Force-stopped ${a.label}") { it.forceStop(a.pkg) } })
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
    }
}
