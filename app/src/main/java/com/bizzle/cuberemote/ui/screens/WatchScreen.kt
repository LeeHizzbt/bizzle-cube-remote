package com.bizzle.cuberemote.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.StreamingApps
import com.bizzle.cube.core.WatchSearch
import com.bizzle.cube.core.WatchTitle
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.Screen
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val imgHttp = OkHttpClient()

@Composable
fun NetImage(url: String?, desc: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.IO) {
            runCatching { imgHttp.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } } }.getOrNull()
        }
    }
    bmp?.let { Image(it, desc, modifier, contentScale = ContentScale.Crop) } ?: Box(modifier)
}

@Composable
fun WatchScreen(d: Deps) {
    val pal = LocalPalette.current
    val apps by d.app.cube.apps.collectAsState()
    val installed = apps.map { it.pkg }.toSet()
    val ui by d.app.store.ui.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WatchTitle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val search = remember { WatchSearch() }
    val tmdbKey = d.app.secure.tmdbKey()

    fun go() {
        if (q.isBlank()) return
        loading = true; error = null
        scope.launch {
            val out = mutableListOf<WatchTitle>(); val errs = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                if (tmdbKey.isNotBlank()) runCatching { out += search.tmdb(q, tmdbKey, ui.region) }.onFailure { errs += "TMDB: ${it.message}" }
                runCatching { out += search.tvmaze(q).take(6) }.onFailure { errs += "TVmaze: ${it.message}" }
            }
            results = out; loading = false
            error = when {
                errs.isNotEmpty() && out.isEmpty() -> errs.joinToString("\n") + "\n(Offline? Check your internet.)"
                out.isEmpty() -> "Nothing found."
                else -> errs.joinToString("\n").ifBlank { null }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeonField(q, { q = it }, "Movie or show", Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }))
                    IconButton(onClick = { go() }) { Icon(Icons.Default.Search, "Search where to watch", tint = pal.accent) }
                }
                if (tmdbKey.isBlank()) SectionCard {
                    Body("Using TVmaze (TV shows, free, no key). For movies + exact streaming services, add a free TMDB API key in Settings > Where to watch.", alpha = 0.8f)
                    TextButton(onClick = { d.nav.go(Screen.Settings) }) { Text("Add TMDB key", color = pal.accent) }
                }
                NeonOutlineButton("🍿 Ask Bizzy what to watch (your installed apps only)") { d.brain.whatToWatch(q); d.nav.go(Screen.Bizzy) }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = pal.accent)
                error?.let { Body(it, alpha = 0.8f) }
            }
        }
        items(results, key = { it.source + it.id }) { t ->
            SectionCard {
                Row {
                    NetImage(t.posterUrl, "Poster for ${t.title}", Modifier.width(72.dp).height(104.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${t.title} ${if (t.year.isNotBlank()) "(${t.year})" else ""}", color = pal.accent, fontWeight = FontWeight.Bold)
                        Text("${t.mediaType.uppercase()} · ${t.source}", color = pal.text.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(t.overview, color = pal.text.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 4)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (t.offers.isEmpty()) Body("No streaming info in your region.", alpha = 0.6f)
                t.offers.forEach { o ->
                    val svc = o.serviceId?.let { StreamingApps.byId(it) }
                    val pkg = svc?.let { StreamingApps.installedPackage(it, installed) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${o.providerName} · ${o.kind}" + if (pkg != null) "  ✓ on your Cube" else "", color = if (pkg != null) pal.accent else pal.text.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (svc != null && pkg != null) TextButton(onClick = {
                            scope.launch { d.app.cube.runActions(listOf(BizzyAction.Search(svc.id, t.title))).forEach { d.app.cube.emit(it) } }
                        }) { Text("Open", color = pal.accent, fontWeight = FontWeight.Bold) }
                    }
                }
                TextButton(onClick = { d.app.cube.text(t.title) }) { Text("Type title on TV", color = pal.accent) }
            }
        }
        item {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Streaming availability data by JustWatch via TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.", color = pal.text.copy(alpha = 0.6f), fontSize = 11.sp)
                Text("TV show data from TVmaze.com (CC BY-SA 4.0).", color = pal.text.copy(alpha = 0.6f), fontSize = 11.sp)
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}
