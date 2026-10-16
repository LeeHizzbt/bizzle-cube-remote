package com.bizzle.cuberemote.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bizzle.cuberemote.ui.theme.LocalPalette

/**
 * Hold to talk, release to send. Uses the phone's on-device/Google speech recognizer.
 * Falls back to the system voice dialog if the recognizer service isn't available.
 */
@Composable
fun PushToTalkButton(size: Dp = 64.dp, autoStart: Boolean = false, onPartial: (String) -> Unit = {}, onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    var listening by remember { mutableStateOf(false) }
    var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    val result by rememberUpdatedState(onResult)
    val partial by rememberUpdatedState(onPartial)
    val dialogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { result(it) }
    }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Bizzy what to do")
    }
    LaunchedEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { listening = false; partial("") }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { result(it) }
                partial("")
            }
            override fun onPartialResults(p: Bundle?) { p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial(it) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    fun start() {
        if (!hasPerm) { permLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (recognizer == null) { runCatching { dialogLauncher.launch(intent) }; return }
        listening = true; partial("Listening…")
        recognizer.startListening(intent)
    }
    fun stop() { if (listening) recognizer?.stopListening() }
    LaunchedEffect(autoStart) { if (autoStart) runCatching { dialogLauncher.launch(intent) } }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (listening) pal.accent else pal.surface)
                .border(2.dp, pal.accent, CircleShape)
                .semantics {
                    contentDescription = "Hold to talk to Bizzy"
                    onClick { if (recognizer == null || !hasPerm) start() else runCatching { dialogLauncher.launch(intent) }; true }
                }
                .pointerInput(hasPerm, recognizer) {
                    awaitEachGesture {
                        awaitFirstDown()
                        start()
                        waitForUpOrCancellation()
                        stop()
                    }
                },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Mic, null, tint = if (listening) Color.Black else pal.accent, modifier = Modifier.size(size * 0.5f)) }
        Text(if (listening) "Release to send" else "Hold to talk", color = pal.text.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}
