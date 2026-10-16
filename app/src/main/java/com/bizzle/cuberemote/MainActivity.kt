package com.bizzle.cuberemote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.bizzle.cuberemote.bizzy.BizzyBrain
import com.bizzle.cuberemote.mirror.MirrorController
import com.bizzle.cuberemote.ui.AppRoot
import com.bizzle.cuberemote.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val app get() = application as CubeRemoteApp
    private lateinit var brain: BizzyBrain
    private lateinit var mirror: MirrorController
    private val shared = mutableStateOf<String?>(null)
    private val startVoice = mutableStateOf(false)
    private var renderKey = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        brain = BizzyBrain(app)
        mirror = MirrorController(app)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val start = when (intent?.getStringExtra("open")) {
            "routines" -> Screen.Routines
            else -> Screen.Home
        }
        setContent {
            androidx.compose.runtime.key(renderKey.value) {
                AppRoot(app, brain, mirror, start, startVoice.value, shared.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        renderKey.value++
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        startVoice.value = i.getStringExtra("open") == "voice"
        if (i.action == Intent.ACTION_SEND) {
            if (i.type == "text/plain") {
                shared.value = i.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                    // pull the first URL out of share text like "Watch X on Netflix https://…"
                    Regex("""https?://\S+""").find(t)?.value ?: t
                }
            } else {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { installApk(it) }
            }
        }
    }

    private fun installApk(uri: Uri) {
        app.cube.scope.launch {
            app.cube.emit("Sending APK to the Cube…")
            if (!app.cube.ensureConnected()) { app.cube.emit("Connect to the Cube first"); return@launch }
            val f = withContext(Dispatchers.IO) { File(cacheDir, "shared.apk").apply { contentResolver.openInputStream(uri)!!.use { s -> outputStream().use { s.copyTo(it) } } } }
            app.cube.call { it.install(f) }.onSuccess { app.cube.emit("Install: $it"); app.cube.refreshApps() }.onFailure { app.cube.emit("Install failed: ${it.message}") }
            f.delete()
        }
    }

    override fun onStart() { super.onStart(); app.cube.acquire() }
    override fun onStop() { app.cube.release(); super.onStop() }
}
