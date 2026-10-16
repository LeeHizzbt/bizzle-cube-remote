package com.bizzle.cuberemote.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bizzle.cuberemote.data.Snippet
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.components.*
import com.bizzle.cuberemote.ui.theme.LocalPalette

/** Live typing: each change in the phone box is mirrored to the TV's focused text field. */
@Composable
fun KeyboardScreen(d: Deps) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val ui by d.app.store.ui.collectAsState()
    val snippets by d.app.store.snippets.collectAsState()
    var text by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    var newSnippet by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun syncLive(new: String) {
        val old = sent
        when {
            new.startsWith(old) -> d.app.cube.text(new.substring(old.length))
            old.startsWith(new) -> repeat(old.length - new.length) { d.app.cube.key("DEL") }
            else -> {
                val common = old.commonPrefixWith(new)
                repeat(old.length - common.length) { d.app.cube.key("DEL") }
                d.app.cube.text(new.substring(common.length))
            }
        }
        sent = new
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Type on the TV") {
            Body(if (ui.liveTyping) "Live typing is ON: letters appear on the TV as you type. First select a search box on the TV." else "Live typing is OFF: type, then press Send.", alpha = 0.8f)
            Spacer(Modifier.height(8.dp))
            NeonField(text, { v ->
                text = v
                if (ui.liveTyping) syncLive(v)
            }, "Text for the TV", Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = if (ui.liveTyping) ImeAction.Go else ImeAction.Send),
                keyboardActions = KeyboardActions(onAny = {
                    if (!ui.liveTyping) { d.app.cube.text(text); sent = text }
                    d.app.cube.key("ENTER")
                }))
            if (text.any { it.code > 126 }) Body("Note: emoji and accented letters can't be typed over ADB on Fire OS; they're skipped.", alpha = 0.7f)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!ui.liveTyping) NeonButton("Send", icon = Icons.Default.Send) { d.app.cube.text(text); sent = text }
                RemoteKey("", "Backspace", icon = Icons.AutoMirrored.Filled.Backspace, size = 48.dp, repeat = true, repeatMs = ui.repeatMs) {
                    d.app.cube.key("DEL"); if (text.isNotEmpty()) { text = text.dropLast(1); sent = text }
                }
                RemoteKey("", "Enter", icon = Icons.AutoMirrored.Filled.KeyboardReturn, size = 48.dp) { d.app.cube.key("ENTER") }
                RemoteKey("SPACE", "Space", size = 48.dp, width = 80.dp) { d.app.cube.text(" "); text += " "; sent = text }
                RemoteKey("CLR", "Clear the TV text box", size = 48.dp) { repeat(maxOf(sent.length, 30)) { d.app.cube.key("DEL") }; text = ""; sent = "" }
            }
            Spacer(Modifier.height(8.dp))
            NeonOutlineButton("Paste phone clipboard to TV", icon = Icons.Default.ContentPaste) {
                val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                if (clip.isBlank()) d.app.cube.emit("Clipboard is empty") else { d.app.cube.text(clip.take(500)); d.app.cube.emit("Pasted ${clip.length.coerceAtMost(500)} characters") }
            }
            SettingSwitch("Live typing", "Mirror every keystroke", ui.liveTyping) { d.app.store.setUi(ui.copy(liveTyping = it)) }
        }
        SectionCard("Saved snippets") {
            Body("Tap to type it on the TV (emails, Wi-Fi passwords, usernames). Stored only on this phone.", alpha = 0.75f)
            snippets.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clickable { d.app.cube.text(s.text); d.app.cube.emit("Typed snippet") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ShortText, null, tint = pal.accent); Spacer(Modifier.width(8.dp))
                    Text(s.text, color = pal.text, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { d.app.store.setSnippets(snippets.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, "Delete snippet ${s.text}", tint = pal.text.copy(alpha = 0.6f)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonField(newSnippet, { newSnippet = it }, "New snippet", Modifier.weight(1f))
                IconButton(onClick = { if (newSnippet.isNotBlank()) { d.app.store.setSnippets(snippets + Snippet(newSnippet)); newSnippet = "" } }) { Icon(Icons.Default.Add, "Save snippet", tint = pal.accent) }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
