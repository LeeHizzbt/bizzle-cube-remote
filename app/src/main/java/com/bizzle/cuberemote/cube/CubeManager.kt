package com.bizzle.cuberemote.cube

import android.content.Context
import android.util.Log
import com.bizzle.cube.core.ActionContext
import com.bizzle.cube.core.ActionExecutor
import com.bizzle.cube.core.AdbCubeControl
import com.bizzle.cube.core.AdbKeys
import com.bizzle.cube.core.BizzyAction
import com.bizzle.cube.core.ConnectOutcome
import com.bizzle.cube.core.CubeAdb
import com.bizzle.cube.core.CubeApp
import com.bizzle.cube.core.Keys
import com.bizzle.cube.core.ScrcpySession
import com.bizzle.cuberemote.data.AppStore
import com.bizzle.cuberemote.data.SavedDevice
import com.bizzle.cuberemote.service.SleepTimerService
import dadb.AdbKeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class ConnState { Disconnected, Connecting, WaitingForTv, Connected, Error }

data class ConnStatus(val state: ConnState, val message: String, val device: SavedDevice? = null, val model: String = "", val build: String = "")

/** Owns the single live ADB session to the active Fire TV, auto-reconnect and the turbo key channel. */
class CubeManager(private val ctx: Context, private val store: AppStore) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyDir = File(ctx.filesDir, "adbkey")
    private var keyPair: AdbKeyPair? = null
    private val connectMutex = Mutex()

    @Volatile var adb: CubeAdb? = null
        private set
    @Volatile var turbo: ScrcpySession? = null
        private set

    val status = MutableStateFlow(ConnStatus(ConnState.Disconnected, "Not connected"))
    val apps = MutableStateFlow<List<CubeApp>>(emptyList())
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    val discovery = LanDiscovery(ctx)
    val turboActive = MutableStateFlow(false)

    private val users = AtomicInteger(0)
    private var watchdog: Job? = null

    private data class KeyCmd(val code: Int, val longPress: Boolean)
    private val keyQueue = Channel<KeyCmd>(capacity = 6, onBufferOverflow = BufferOverflow.DROP_LATEST)

    init {
        scope.launch {
            for (k in keyQueue) {
                val a = adb ?: continue
                try {
                    val t = turbo
                    if (t != null && !t.closed && !k.longPress) {
                        try { t.key(k.code); continue } catch (e: Exception) { dropTurbo() }
                    }
                    a.keyevent(k.code, k.longPress)
                } catch (e: Exception) {
                    onIoError(e)
                }
            }
        }
    }

    fun emit(msg: String) { _events.tryEmit(msg) }

    private fun keys(): AdbKeyPair = keyPair ?: AdbKeys.loadOrCreate(keyDir).also { keyPair = it }

    fun resetKey() {
        disconnect()
        AdbKeys.reset(keyDir); keyPair = null
        emit("New ADB key made. The TV will ask to allow this phone again.")
    }

    val isConnected get() = status.value.state == ConnState.Connected && adb != null

    // ---------- lifecycle / auto-reconnect ----------

    /** Called by activities/services that need the connection; keeps the watchdog alive while > 0. */
    fun acquire() {
        if (users.incrementAndGet() == 1) startWatchdog()
    }
    fun release() {
        if (users.decrementAndGet() <= 0) { users.set(0); watchdog?.cancel(); watchdog = null }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            var backoff = 3000L
            while (isActive) {
                val dev = store.activeDevice()
                val st = status.value.state
                if (dev != null && store.ui.value.autoConnect && st != ConnState.Connecting && st != ConnState.WaitingForTv) {
                    if (adb == null || st != ConnState.Connected) {
                        val ok = connectInternal(dev, quiet = true)
                        backoff = if (ok) 3000L else (backoff * 2).coerceAtMost(30_000L)
                    } else if (!(withTimeoutOrNullSafe { adb?.ping() } ?: false)) {
                        status.value = ConnStatus(ConnState.Disconnected, "Lost connection, reconnecting…", dev)
                        closeSession()
                        continue
                    }
                }
                delay(if (status.value.state == ConnState.Connected) 12_000L else backoff)
            }
        }
    }

    private suspend fun <T> withTimeoutOrNullSafe(block: () -> T?): T? =
        kotlinx.coroutines.withTimeoutOrNull(6000) { withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() } }

    // ---------- connect ----------

    fun connect(device: SavedDevice) {
        store.setActive(device.id)
        scope.launch { connectInternal(device, quiet = false) }
    }

    /** Connects now (blocking caller coroutine). Used by services/widgets. */
    suspend fun ensureConnected(): Boolean {
        if (isConnected) return true
        val dev = store.activeDevice() ?: return false
        return connectInternal(dev, quiet = true)
    }

    private suspend fun connectInternal(device: SavedDevice, quiet: Boolean): Boolean = connectMutex.withLock {
        if (isConnected && status.value.device?.id == device.id) return true
        closeSession()
        status.value = ConnStatus(ConnState.Connecting, "Connecting to ${device.name} (${device.host})…", device)
        val session = CubeAdb(device.host, device.port, keys())
        val outcome = withContext(Dispatchers.IO) {
            session.connect(45_000) {
                status.value = ConnStatus(ConnState.WaitingForTv, "Look at your TV: choose 'Always allow from this computer' and press OK.", device)
            }
        }
        when (outcome) {
            is ConnectOutcome.Connected -> {
                adb = session
                status.value = ConnStatus(ConnState.Connected, "Connected to ${device.name}", device, outcome.model, outcome.build)
                if (!quiet) emit("Connected to ${device.name}")
                scope.launch { refreshApps() }
                if (store.ui.value.turboKeys) scope.launch { startTurbo() }
                true
            }
            is ConnectOutcome.AuthRejected -> fail(device, outcome.hint)
            is ConnectOutcome.AuthTimeout -> fail(device, outcome.hint)
            is ConnectOutcome.Unreachable -> fail(device, outcome.hint)
            is ConnectOutcome.Failed -> fail(device, outcome.hint)
        }
    }

    private fun fail(device: SavedDevice, msg: String): Boolean {
        status.value = ConnStatus(ConnState.Error, msg, device)
        return false
    }

    fun disconnect() {
        closeSession()
        status.value = ConnStatus(ConnState.Disconnected, "Disconnected", store.activeDevice())
    }

    private fun closeSession() {
        dropTurbo()
        runCatching { adb?.close() }
        adb = null
    }

    private fun dropTurbo() {
        runCatching { turbo?.close() }
        turbo = null; turboActive.value = false
    }

    private fun startTurbo() {
        val a = adb ?: return
        try {
            val jar = ctx.assets.open("scrcpy-server.jar").use { it.readBytes() }
            val s = ScrcpySession.start(a.raw(), jar, video = false, timeoutMs = 12_000)
            if (adb === a) { turbo = s; turboActive.value = true } else s.close()
        } catch (e: Exception) {
            Log.w("Cube", "turbo keys unavailable, using shell input: ${e.message}")
            turboActive.value = false
        }
    }

    private fun onIoError(e: Exception) {
        Log.w("Cube", "adb error", e)
        if (e is java.io.IOException || e is IllegalStateException) {
            val dev = status.value.device
            closeSession()
            status.value = ConnStatus(ConnState.Disconnected, "Connection dropped, reconnecting…", dev)
            if (users.get() > 0 && dev != null) scope.launch { delay(500); connectInternal(dev, quiet = true) }
        }
    }

    // ---------- commands ----------

    fun key(name: String, longPress: Boolean = false) { Keys.code(name)?.let { key(it, longPress) } }
    fun key(code: Int, longPress: Boolean = false) {
        if (adb == null) { emit("Not connected yet"); return }
        keyQueue.trySend(KeyCmd(code, longPress))
    }

    /** Runs a block against the live session on IO, reporting errors as friendly toasts. */
    fun <T> run(label: String? = null, onResult: (T) -> Unit = {}, block: (CubeAdb) -> T) {
        val a = adb
        if (a == null) { emit("Not connected to a Fire TV"); return }
        scope.launch {
            try {
                val r = block(a)
                withContext(Dispatchers.Main) { onResult(r) }
                if (label != null) emit(label)
            } catch (e: Exception) {
                emit("Failed: ${e.message ?: e.javaClass.simpleName}")
                onIoError(e)
            }
        }
    }

    suspend fun <T> call(block: (CubeAdb) -> T): Result<T> = withContext(Dispatchers.IO) {
        val a = adb ?: return@withContext Result.failure(IllegalStateException("Not connected to a Fire TV"))
        try { Result.success(block(a)) } catch (e: Exception) { onIoError(e); Result.failure(e) }
    }

    fun text(s: String) {
        if (s.isEmpty()) return
        val t = turbo
        run<Unit> { a ->
            if (t != null && !t.closed && s.all { it.code in 32..126 }) {
                try { t.text(s); return@run } catch (_: Exception) { dropTurbo() }
            }
            if (!a.text(s)) emit("Only plain letters, numbers and symbols can be typed on Fire OS")
        }
    }

    fun refreshApps() {
        scope.launch {
            call { it.launchableApps() }.onSuccess { apps.value = it }
        }
    }

    fun installedSet(): Set<String> = apps.value.map { it.pkg }.toSet()

    fun actionContext() = ActionContext(installedSet(), store.macros.value.map { it.name }.toSet())

    /** Executes allow-listed actions (from Bizzy, routines, widgets). Returns notes. */
    suspend fun runActions(actions: List<BizzyAction>): List<String> = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext listOf("Not connected to a Fire TV")
        val exec = ActionExecutor(
            AdbCubeControl({ adb ?: throw IllegalStateException("Not connected") }, { turbo?.takeIf { !it.closed } }),
            { installedSet() },
            { store.macroActions(it) },
            { min -> if (min <= 0) SleepTimerService.cancel(ctx) else SleepTimerService.start(ctx, min) }
        )
        try { exec.run(actions) } catch (e: Exception) { onIoError(e); listOf("Stopped: ${e.message}") }
    }
}
