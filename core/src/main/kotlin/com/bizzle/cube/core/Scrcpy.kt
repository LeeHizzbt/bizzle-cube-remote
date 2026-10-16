package com.bizzle.cube.core

import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import okio.Buffer
import okio.BufferedSource
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Client for the scrcpy server (Genymobile/scrcpy, Apache-2.0). The server jar is bundled
 * unmodified and pushed to the TV over ADB; video is H.264 over an ADB local-abstract tunnel.
 * Protocol per scrcpy v5.0.1 doc/develop.md.
 */
object ScrcpyProtocol {
    const val SERVER_VERSION = "5.0.1"
    const val REMOTE_PATH = "/data/local/tmp/bizzle-scrcpy-server.jar"
    const val CODEC_H264 = 0x68323634

    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH = 2
    const val TYPE_BACK_OR_SCREEN_ON = 4

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    /** Pointer id scrcpy uses for a generic finger/mouse. */
    const val POINTER_ID_GENERIC_FINGER = -2L

    fun keycode(action: Int, keycode: Int, repeat: Int = 0, meta: Int = 0): ByteArray =
        ByteBuffer.allocate(14).put(TYPE_INJECT_KEYCODE.toByte()).put(action.toByte())
            .putInt(keycode).putInt(repeat).putInt(meta).array()

    fun text(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8).let { if (it.size > 300) it.copyOf(300) else it }
        return ByteBuffer.allocate(5 + bytes.size).put(TYPE_INJECT_TEXT.toByte()).putInt(bytes.size).put(bytes).array()
    }

    fun touch(action: Int, x: Int, y: Int, screenW: Int, screenH: Int, pressure: Float = 1f, pointerId: Long = POINTER_ID_GENERIC_FINGER): ByteArray {
        val p = (pressure.coerceIn(0f, 1f) * 0xffff).toInt().let { if (it > 0xffff) 0xffff else it }
        return ByteBuffer.allocate(32).put(TYPE_INJECT_TOUCH.toByte()).put(action.toByte()).putLong(pointerId)
            .putInt(x).putInt(y).putShort(screenW.toShort()).putShort(screenH.toShort())
            .putShort(p.toShort()).putInt(1 /* AMOTION_EVENT_BUTTON_PRIMARY */).putInt(if (action == ACTION_UP) 0 else 1)
            .array()
    }

    fun backOrScreenOn(action: Int): ByteArray = byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    fun serverArgs(scid: Int, video: Boolean, maxSize: Int, bitRate: Int, maxFps: Int): String =
        listOf(
            "scid=%08x".format(scid), "log_level=info", "tunnel_forward=true", "audio=false",
            "video=$video", "control=true", "cleanup=true", "send_device_meta=false",
            "power_on=false", "clipboard_autosync=false", "stay_awake=false",
            "video_codec=h264", "max_size=$maxSize", "video_bit_rate=$bitRate", "max_fps=$maxFps",
            "video_codec_options=i-frame-interval=2"
        ).joinToString(" ")

    fun startCommand(scid: Int, video: Boolean, maxSize: Int, bitRate: Int, maxFps: Int): String =
        "CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION " +
            serverArgs(scid, video, maxSize, bitRate, maxFps)
}

/** Events parsed off the video socket. */
sealed class VideoEvent {
    data class Session(val width: Int, val height: Int) : VideoEvent()
    data class Packet(val data: ByteArray, val ptsUs: Long, val config: Boolean, val keyFrame: Boolean) : VideoEvent()
}

/** Parses the scrcpy video stream after the dummy byte: codec id, then session/media packets. */
class VideoStreamParser(private val src: BufferedSource) {
    var codecId: Int = 0
        private set

    fun readCodec(): Int {
        codecId = src.readInt()
        if (codecId == 0) throw IOException("Server disabled the video stream")
        if (codecId == 1) throw IOException("Server video configuration error")
        return codecId
    }

    fun next(): VideoEvent {
        val head = src.readLong()
        if (head < 0) { // MSB set → session packet: 4 bytes flags, 4 width, 4 height
            val width = (head and 0xffffffffL).toInt()
            val height = src.readInt()
            return VideoEvent.Session(width, height)
        }
        val size = src.readInt()
        if (size < 0 || size > 16 * 1024 * 1024) throw IOException("bad packet size $size")
        val config = (head and (1L shl 62)) != 0L
        val key = (head and (1L shl 61)) != 0L
        val pts = head and ((1L shl 61) - 1)
        return VideoEvent.Packet(src.readByteArray(size.toLong()), if (config) 0 else pts, config, key)
    }
}

/**
 * A running scrcpy session (server process + sockets).
 * video=false gives a control-only "turbo keys" channel: instant key/text/touch injection.
 */
class ScrcpySession private constructor(
    private val shellStream: AdbShellStream,
    val video: AdbStream?,
    private val control: AdbStream,
    val scid: Int
) : Closeable {

    @Volatile var closed = false
        private set
    val serverLog = StringBuilder()
    private val writeLock = Any()

    fun send(bytes: ByteArray) {
        synchronized(writeLock) {
            control.sink.write(bytes)
            control.sink.flush()
        }
    }

    fun key(keycode: Int) {
        send(ScrcpyProtocol.keycode(ScrcpyProtocol.ACTION_DOWN, keycode) + ScrcpyProtocol.keycode(ScrcpyProtocol.ACTION_UP, keycode))
    }

    fun keyDown(keycode: Int, repeat: Int = 0) = send(ScrcpyProtocol.keycode(ScrcpyProtocol.ACTION_DOWN, keycode, repeat))
    fun keyUp(keycode: Int) = send(ScrcpyProtocol.keycode(ScrcpyProtocol.ACTION_UP, keycode))
    fun text(t: String) = t.chunked(250).forEach { send(ScrcpyProtocol.text(it)) }
    fun touch(action: Int, x: Int, y: Int, w: Int, h: Int) = send(ScrcpyProtocol.touch(action, x, y, w, h, if (action == ScrcpyProtocol.ACTION_UP) 0f else 1f))

    override fun close() {
        if (closed) return
        closed = true
        runCatching { video?.close() }
        runCatching { control.close() }
        runCatching { shellStream.close() }
    }

    companion object {
        /**
         * Pushes the bundled server jar, starts it and opens the tunnel sockets.
         * Throws with the server's own log if anything fails.
         */
        fun start(
            dadb: Dadb, serverJar: ByteArray, video: Boolean,
            maxSize: Int = 1280, bitRate: Int = 4_000_000, maxFps: Int = 30,
            timeoutMs: Long = 15_000
        ): ScrcpySession {
            val buf = Buffer().write(serverJar)
            dadb.push(buf, ScrcpyProtocol.REMOTE_PATH, 420 /* 0644 */, System.currentTimeMillis())
            val scid = Random.nextInt(0, Int.MAX_VALUE)
            val log = StringBuilder()
            val shell = dadb.openShell(ScrcpyProtocol.startCommand(scid, video, maxSize, bitRate, maxFps))
            val exited = java.util.concurrent.atomic.AtomicBoolean(false)
            thread(name = "scrcpy-log", isDaemon = true) {
                try {
                    while (true) {
                        when (val p = shell.read()) {
                            is AdbShellPacket.StdOut, is AdbShellPacket.StdError -> synchronized(log) { log.append(String(p.payload)) }
                            is AdbShellPacket.Exit -> { exited.set(true); break }
                        }
                    }
                } catch (_: Exception) { exited.set(true) }
            }
            val socketName = "localabstract:scrcpy_%08x".format(scid)
            val deadline = System.currentTimeMillis() + timeoutMs

            fun openWithRetry(): AdbStream {
                var last: Exception? = null
                while (System.currentTimeMillis() < deadline) {
                    if (exited.get()) break
                    try {
                        return dadb.open(socketName)
                    } catch (e: Exception) {
                        last = e
                        Thread.sleep(150)
                    }
                }
                runCatching { shell.close() }
                throw IOException("scrcpy server did not start: ${last?.message ?: "exited"}\n" + synchronized(log) { log.toString() }.takeLast(800))
            }

            val first = openWithRetry()
            // Forward tunnel: dummy byte on the first socket proves the server is really there.
            val dummy = try { first.source.readByte() } catch (e: Exception) {
                runCatching { first.close() }; runCatching { shell.close() }
                throw IOException("scrcpy socket closed before handshake\n" + synchronized(log) { log.toString() }.takeLast(800))
            }
            if (dummy.toInt() != 0) throw IOException("unexpected handshake byte $dummy")
            val videoStream: AdbStream?
            val controlStream: AdbStream
            if (video) {
                videoStream = first
                controlStream = openWithRetry()
            } else {
                videoStream = null
                controlStream = first
            }
            val s = ScrcpySession(shell, videoStream, controlStream, scid)
            synchronized(log) { s.serverLog.append(log) }
            // keep draining device→client messages (clipboard etc.) on the control socket
            thread(name = "scrcpy-ctrl-drain", isDaemon = true) {
                try { while (!s.closed) controlStream.source.readByte() } catch (_: Exception) {}
            }
            return s
        }
    }
}
