package com.bizzle.cuberemote.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.cuberemote.R
import com.bizzle.cuberemote.bizzy.BizzyBrain
import com.bizzle.cuberemote.data.ChatLine
import com.bizzle.cuberemote.ui.Deps
import com.bizzle.cuberemote.ui.Screen
import com.bizzle.cuberemote.ui.theme.LocalPalette
import com.bizzle.cuberemote.voice.PushToTalkButton

@Composable
fun BizzyFab(onClick: () -> Unit) {
    val pal = LocalPalette.current
    FloatingActionButton(onClick = onClick, containerColor = pal.surface, contentColor = pal.accent, shape = CircleShape,
        modifier = Modifier.border(2.dp, pal.accent, CircleShape)) {
        Image(painterResource(R.drawable.bizzy_avatar), contentDescription = "Open Bizzy helper", modifier = Modifier.size(44.dp).clip(CircleShape))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BizzyPopup(d: Deps, startVoice: Boolean, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = pal.bg, contentColor = pal.text) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 12.dp)) {
            BizzyChatPanel(d, startVoice, compact = true, onOpenSettings = { onClose(); d.nav.go(Screen.AiSettings) })
        }
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bizzy", text))
}

private fun uriToDataUrl(ctx: Context, uri: Uri): String? = runCatching {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    BizzyBrain.bitmapToDataUrl(bmp, 1280)
}.getOrNull()

@Composable
fun ColumnScope.BizzyChatPanel(d: Deps, startVoice: Boolean, compact: Boolean, onOpenSettings: () -> Unit) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val chat by d.app.store.chat.collectAsState()
    val busy by d.brain.busy.collectAsState()
    val pending by d.brain.pending.collectAsState()
    val aiSet by d.app.store.ai.collectAsState()
    var input by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, busy) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1 + if (busy) 1 else 0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val url = uriToDataUrl(ctx, uri)
            if (url == null) d.app.cube.emit("Couldn't read that image")
            else d.brain.send(input.ifBlank { "What's on this screenshot and what should I do?" }, url).also { input = "" }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.bizzy_avatar), null, Modifier.size(36.dp).clip(CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Bizzy", color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(if (d.app.secure.hasAnyAiKey()) "AI on · ${aiSet.order.joinToString(" → ")} → offline" else "Offline rules (add a free key for full AI)",
                color = pal.text.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
        }
        TextButton(onClick = onOpenSettings) { Icon(Icons.Default.Tune, null, tint = pal.accent); Spacer(Modifier.width(4.dp)); Text("Advanced AI Settings", color = pal.accent, fontSize = 12.sp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        AssistChip(onClick = { d.brain.helpWithScreen(input).also { input = "" } }, label = { Text("📺 Help me with my screen", fontSize = 12.sp) },
            colors = AssistChipDefaults.assistChipColors(labelColor = pal.accent))
        AssistChip(onClick = { d.brain.whatToWatch(input).also { input = "" } }, label = { Text("🍿 What should I watch?", fontSize = 12.sp) },
            colors = AssistChipDefaults.assistChipColors(labelColor = pal.accent))
    }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chat.isEmpty()) item {
            Body("Hi, I'm Bizzy! Try: \"open YouTube and search lofi\", \"volume up 3\", \"sleep timer 45 minutes\", \"remember I like sci-fi\", or hold the mic.", alpha = 0.8f)
        }
        items(chat) { line -> ChatBubble(line) { copy(ctx, line.text); d.app.cube.emit("Copied") } }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = pal.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Body("Bizzy is thinking…", alpha = 0.7f) } }
    }
    if (pending != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("Run it", Modifier.weight(1f), icon = Icons.Default.PlayArrow) { d.brain.confirmPending() }
            NeonOutlineButton("Cancel", Modifier.weight(1f)) { d.brain.cancelPending() }
        }
    }
    if (partial.isNotBlank()) Text(partial, color = pal.accent, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        PushToTalkButton(size = 52.dp, autoStart = startVoice, onPartial = { partial = it }) { d.brain.send(it) }
        Spacer(Modifier.width(6.dp))
        NeonField(input, { input = it }, "Ask Bizzy or give a command", Modifier.weight(1f), singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { d.brain.send(input); input = "" }))
        Column {
            IconButton(onClick = { d.brain.send(input); input = "" }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.Send, "Send to Bizzy", tint = pal.accent) }
            IconButton(onClick = {
                if (!aiSet.allowScreenshots) d.app.cube.emit("Screenshot upload is off in Advanced AI Settings")
                else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Default.AddPhotoAlternate, "Upload a phone screenshot to Bizzy", tint = pal.accent) }
        }
    }
    if (!compact) TextButton(onClick = { d.app.store.clearChat() }) { Text("Clear chat history", color = pal.text.copy(alpha = 0.7f)) }
}

@Composable
fun ChatBubble(line: ChatLine, onCopy: () -> Unit) {
    val pal = LocalPalette.current
    val mine = line.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(14.dp))
                .background(if (mine) pal.accent.copy(alpha = 0.18f) else pal.surface)
                .border(1.dp, pal.accent.copy(alpha = if (mine) 0.5f else 0.25f), RoundedCornerShape(14.dp)).padding(10.dp)
        ) {
            Text(line.text, color = pal.text, fontSize = 14.sp, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mine && line.provider != null) Text(line.provider, color = pal.text.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, "Copy message", tint = pal.accent, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}
