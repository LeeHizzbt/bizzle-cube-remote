package com.bizzle.cube.core

import dadb.AdbAuthException
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import dadb.InstallResult
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Result of a connection attempt with a plain-English reason. */
sealed class ConnectOutcome {
    data class Connected(val model: String, val build: String) : ConnectOutcome()
    data class AuthRejected(val hint: String) : ConnectOutcome()
    data class AuthTimeout(val hint: String) : ConnectOutcome()
    data class Unreachable(val hint: String) : ConnectOutcome()
    data class Failed(val hint: String) : ConnectOutcome()
}

data class CubeApp(val pkg: String, val label: String, val system: Boolean)

data class DeviceInfo(
    val model: String, val fireOs: String, val android: String, val serialTail: String,
    val storage: String, val ram: String, val wifi: String, val ip: String, val uptime: String,
    val resolution: String
)

/**
 * Persistent RSA key stored in the app's private dir. The TV remembers this key after the user
 * ticks "Always allow", so there is no repeat prompt after restarts.
 */
object AdbKeys {
    fun loadOrCreate(dir: File): AdbKeyPair {
        dir.mkdirs()
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    fun reset(dir: File) {
        File(dir, "adbkey").delete(); File(dir, "adbkey.pub").delete()
    }
}

/**
 * One ADB-over-TCP session to one Fire TV. All commands are app-authored; nothing here takes
 * free-form shell from the AI.
 */
class CubeAdb(val host: String, val port: Int, private val keyPair: AdbKeyPair) : AutoCloseable {

    @Volatile private var dadb: Dadb? = null
    @Volatile private var keyShell: AdbShellStream? = null
    private val keyShellLock = Any()
    val isOpen: Boolean get() = dadb != null

    fun raw(): Dadb = dadb ?: throw IllegalStateException("Not connected")

    /**
     * Opens the connection and proves it with a real shell round-trip.
     * [onWaitingForTv] fires if the handshake stalls (TV is showing "Allow USB debugging?").
     */
    fun connect(timeoutMs: Long = 45_000, onWaitingForTv: () -> Unit = {}): ConnectOutcome {
        close()
        val d = try {
            Dadb.create(host, port, keyPair, connectTimeout = 4000, socketTimeout = 0, keepAlive = true)
        } catch (e: Exception) {
            return ConnectOutcome.Failed(e.message ?: "bad address")
        }
        var result: ConnectOutcome? = null
        val done = AtomicBoolean(false)
        val t = thread(name = "adb-connect", isDaemon = true) {
            result = try {
                val out = d.shell("echo bizzle_ok; getprop ro.product.model; getprop ro.build.version.name; getprop ro.build.version.release").allOutput
                if (!out.contains("bizzle_ok")) ConnectOutcome.Failed("Shell check failed: ${out.take(120)}")
                else {
                    val lines = out.lines().map { it.trim() }
                    val i = lines.indexOf("bizzle_ok")
                    ConnectOutcome.Connected(lines.getOrElse(i + 1) { "" }, (lines.getOrElse(i + 2) { "" }).ifBlank { "Android " + lines.getOrElse(i + 3) { "" } })
                }
            } catch (e: AdbAuthException) {
                ConnectOutcome.AuthRejected("The TV refused this phone. On the TV choose OK and tick 'Always allow from this computer'.")
            } catch (e: java.net.ConnectException) {
                ConnectOutcome.Unreachable("Nothing answered on $host:$port. Is ADB Debugging ON and the IP right?")
            } catch (e: java.net.SocketTimeoutException) {
                ConnectOutcome.Unreachable("Timed out reaching $host:$port. Same Wi-Fi? Router AP/client isolation off?")
            } catch (e: java.net.NoRouteToHostException) {
                ConnectOutcome.Unreachable("No route to $host. Check the phone is on the same Wi-Fi as the Cube.")
            } catch (e: Exception) {
                val cause = generateSequence<Throwable>(e) { it.cause }.last()
                when (cause) {
                    is java.net.ConnectException -> ConnectOutcome.Unreachable("Nothing answered on $host:$port. Is ADB Debugging ON and the IP right?")
                    is java.net.SocketTimeoutException -> ConnectOutcome.Unreachable("Timed out reaching $host:$port. Same Wi-Fi?")
                    else -> ConnectOutcome.Failed(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                done.set(true)
            }
        }
        t.join(1500)
        if (!done.get()) {
            onWaitingForTv()
            t.join(timeoutMs)
        }
        if (!done.get()) {
            runCatching { d.close() }
            return ConnectOutcome.AuthTimeout("No answer from the TV prompt. Look at the TV, tick 'Always allow' and press OK, then retry.")
        }
        val r = result ?: ConnectOutcome.Failed("unknown")
        if (r is ConnectOutcome.Connected) dadb = d else runCatching { d.close() }
        return r
    }

    override fun close() {
        synchronized(keyShellLock) { runCatching { keyShell?.close() }; keyShell = null }
        runCatching { dadb?.close() }
        dadb = null
    }

    // ---------- shell helpers ----------

    fun shell(cmd: String): String = raw().shell(cmd).allOutput

    data class ShellResult(val exit: Int, val out: String)
    fun shellResult(cmd: String): ShellResult = raw().shell(cmd).let { ShellResult(it.exitCode, it.allOutput) }

    fun ping(): Boolean = runCatching { shell("echo p").contains("p") }.getOrDefault(false)

    /**
     * Persistent interactive shell: we write `input ...` lines into one long-lived shell instead of
     * opening a new ADB stream per key. Output is drained on a background thread.
     */
    private fun keyShell(): AdbShellStream = synchronized(keyShellLock) {
        keyShell?.let { return it }
        val s = raw().openShell("")
        keyShell = s
        thread(name = "keyshell-drain", isDaemon = true) {
            try {
                while (true) {
                    val p = s.read()
                    if (p is AdbShellPacket.Exit) break
                }
            } catch (_: Exception) {
            } finally {
                synchronized(keyShellLock) { if (keyShell === s) keyShell = null }
            }
        }
        s
    }

    fun writeShellLine(line: String) {
        try {
            keyShell().write(line + "\n")
        } catch (e: Exception) {
            synchronized(keyShellLock) { runCatching { keyShell?.close() }; keyShell = null }
            // retry once on a fresh shell
            keyShell().write(line + "\n")
        }
    }

    fun keyevent(code: Int, longPress: Boolean = false) {
        writeShellLine(if (longPress) "input keyevent --longpress $code" else "input keyevent $code")
    }

    /** Several keys in one `input` process (much faster than one per key). */
    fun keyevents(codes: List<Int>) {
        if (codes.isEmpty()) return
        codes.chunked(20).forEach { writeShellLine("input keyevent " + it.joinToString(" ")) }
    }

    fun text(s: String): Boolean {
        val q = ShellText.forInputText(s) ?: return false
        writeShellLine("input text $q")
        return true
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = writeShellLine("input swipe $x1 $y1 $x2 $y2 $ms")
    fun tap(x: Int, y: Int) = writeShellLine("input tap $x $y")

    // ---------- apps ----------

    fun packages(thirdPartyOnly: Boolean): List<String> =
        shell(if (thirdPartyOnly) "pm list packages -3" else "pm list packages")
            .lines().mapNotNull { it.trim().removePrefix("package:").takeIf { p -> ShellText.isPackageName(p) } }.sorted()

    /** Apps with a TV (leanback) or phone launcher entry. */
    fun launchableApps(): List<CubeApp> {
        val third = packages(true).toSet()
        val launchable = linkedSetOf<String>()
        for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")) {
            val out = runCatching { shell("cmd package query-activities --brief -a android.intent.action.MAIN -c $cat") }.getOrDefault("")
            out.lines().map { it.trim() }.filter { it.contains('/') }.forEach { launchable += it.substringBefore('/') }
        }
        if (launchable.isEmpty()) launchable += third
        val hidden = setOf("com.amazon.tv.launcher", "com.android.tv.settings", "com.android.provision")
        return launchable.filter { ShellText.isPackageName(it) && it !in hidden }
            .map { CubeApp(it, StreamingApps.labelFor(it), it !in third) }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    private fun resolveLauncher(pkg: String): String? {
        for (cat in listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")) {
            val out = runCatching { shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c $cat $pkg") }.getOrDefault("")
            out.lines().map { it.trim() }.lastOrNull { ShellText.isComponent(it) }?.let { return it }
        }
        return null
    }

    fun launch(pkg: String): String {
        require(ShellText.isPackageName(pkg)) { "bad package" }
        val comp = resolveLauncher(pkg)
        return if (comp != null) shell("am start -n $comp")
        else shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }

    /** Opens a validated URL in a specific app package. */
    fun viewUrl(url: String, pkg: String?): String {
        require(url.none { it.isWhitespace() || it == '\'' }) { "bad url" }
        val p = pkg?.takeIf { ShellText.isPackageName(it) }
        return shell("am start -a android.intent.action.VIEW -d ${ShellText.quote(url)}" + (p?.let { " $it" } ?: ""))
    }

    fun forceStop(pkg: String) { require(ShellText.isPackageName(pkg)); shell("am force-stop $pkg") }
    fun clearData(pkg: String): String { require(ShellText.isPackageName(pkg)); return shell("pm clear $pkg") }
    fun uninstall(pkg: String): String { require(ShellText.isPackageName(pkg)); return shell("pm uninstall $pkg") }
    fun disable(pkg: String): String { require(ShellText.isPackageName(pkg)); return shell("pm disable-user --user 0 $pkg") }
    fun enable(pkg: String): String { require(ShellText.isPackageName(pkg)); return shell("pm enable $pkg") }
    fun clearAllCaches(): String = shell("pm trim-caches 999G && echo done")
    fun reboot() { runCatching { raw().open("reboot:").close() } }

    fun install(apk: File): String = when (val r = raw().install(apk, "-r")) {
        is InstallResult.Success -> "Success"
        is InstallResult.Failure -> "Failed: ${r.reason.trim()}"
        else -> r.toString()
    }

    fun foregroundPackage(): String? {
        val out = runCatching { shell("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -2") }.getOrDefault("")
        return Regex("""([a-zA-Z][\w.]+)/[\w.$]+""").find(out)?.groupValues?.get(1)
    }

    // ---------- screen ----------

    /** PNG bytes of the TV screen (raw binary over exec:, no base64 overhead). */
    fun screencapPng(): ByteArray {
        raw().open("exec:screencap -p").use { s ->
            val bytes = s.source.readByteArray()
            if (bytes.size < 8 || bytes[1] != 'P'.code.toByte()) throw IllegalStateException("screencap failed (${bytes.size} bytes)")
            return bytes
        }
    }

    fun screenSize(): Pair<Int, Int>? {
        val out = runCatching { shell("wm size") }.getOrDefault("")
        val m = Regex("""(?:Override|Physical) size: (\d+)x(\d+)""").findAll(out).lastOrNull() ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    // ---------- device info ----------

    fun deviceInfo(): DeviceInfo {
        val sep = "@@@"
        val out = shell(
            listOf(
                "getprop ro.product.model", "getprop ro.build.version.name", "getprop ro.build.version.release",
                "getprop ro.serialno", "df -h /data | tail -1", "cat /proc/meminfo | head -3",
                "dumpsys wifi | grep -m1 'mWifiInfo'", "ip -f inet addr show wlan0 | grep inet; ip -f inet addr show eth0 | grep inet",
                "uptime", "wm size"
            ).joinToString("; echo $sep; ")
        )
        val p = out.split(sep).map { it.trim() }
        fun g(i: Int) = p.getOrElse(i) { "" }
        val mem = g(5).lines().associate { l -> l.substringBefore(':').trim() to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L) }
        val ram = if (mem.isNotEmpty()) "%.1f GB free of %.1f GB".format((mem["MemAvailable"] ?: mem["MemFree"] ?: 0) / 1048576.0, (mem["MemTotal"] ?: 0) / 1048576.0) else "?"
        val df = g(4).split(Regex("\\s+"))
        val storage = if (df.size >= 4) "${df[3]} free of ${df[1]} (${df.getOrElse(4) { "" }} used)" else g(4)
        val wifiLine = g(6)
        val ssid = Regex("""SSID: ([^,]+)""").find(wifiLine)?.groupValues?.get(1)?.trim('"', ' ') ?: ""
        val rssi = Regex("""RSSI: (-?\d+)""").find(wifiLine)?.groupValues?.get(1) ?: ""
        val speed = Regex("""Link speed: (\d+\w*)""").find(wifiLine)?.groupValues?.get(1) ?: ""
        val wifi = if (wifiLine.isBlank()) "Ethernet or unknown" else listOf(ssid.ifBlank { "Wi-Fi" }, rssi.takeIf { it.isNotBlank() }?.let { "$it dBm" }, speed.takeIf { it.isNotBlank() }?.let { "${it}" }).filterNotNull().joinToString(" · ")
        val ip = Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(g(7))?.groupValues?.get(1) ?: host
        val up = Regex("""up\s+([^,]+(?:,\s*\d+:\d+)?)""").find(g(8))?.groupValues?.get(1)?.trim() ?: g(8)
        val res = Regex("""(\d+x\d+)""").findAll(g(9)).lastOrNull()?.value ?: ""
        return DeviceInfo(g(0), g(1).ifBlank { "Android ${g(2)}" }, g(2), g(3).takeLast(4), storage, ram, wifi, ip, up, res)
    }
}
