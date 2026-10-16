package com.bizzle.cuberemote.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.bizzle.cube.core.ScrcpyProtocol
import com.bizzle.cube.core.ScrcpySession
import com.bizzle.cube.core.VideoEvent
import com.bizzle.cube.core.VideoStreamParser
import com.bizzle.cuberemote.CubeRemoteApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

sealed class MirrorState {
    object Idle : MirrorState()
    data class Starting(val step: String) : MirrorState()
    data class Live(val width: Int, val height: Int, val fps: Float) : MirrorState()
    data class Failed(val message: String) : MirrorState()
}

/**
 * Real-time mirroring: scrcpy-server (Apache-2.0) H.264 stream over ADB → MediaCodec → SurfaceView.
 * Touches on the picture are injected back through scrcpy's control socket.
 */
class MirrorController(private val app: CubeRemoteApp) {
    val state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    @Volatile private var session: ScrcpySession? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var running = false
    @Volatile private var size = 0 to 0

    fun start(surface: Surface) {
        if (running) return
        val adb = app.cube.adb ?: run { state.value = MirrorState.Failed("Not connected to the Cube"); return }
        running = true
        thread(name = "mirror", isDaemon = true) {
            try {
                state.value = MirrorState.Starting("Pushing scrcpy server to the TV…")
                val jar = app.assets.open("scrcpy-server.jar").use { it.readBytes() }
                val ui = app.store.ui.value
                val s = ScrcpySession.start(adb.raw(), jar, video = true, maxSize = ui.mirrorMaxSize, bitRate = ui.mirrorBitrateMbps * 1_000_000, maxFps = 30, timeoutMs = 20_000)
                session = s
                state.value = MirrorState.Starting("Waiting for the first video frame…")
                val parser = VideoStreamParser(s.video!!.source)
                if (parser.readCodec() != ScrcpyProtocol.CODEC_H264) throw IllegalStateException("Unexpected codec")
                val info = MediaCodec.BufferInfo()
                var frames = 0; var t0 = System.currentTimeMillis()
                while (running) {
                    when (val e = parser.next()) {
                        is VideoEvent.Session -> {
                            size = e.width to e.height
                            runCatching { codec?.stop(); codec?.release() }
                            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, e.width, e.height)
                            if (android.os.Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                            c.configure(fmt, surface, null, 0)
                            c.start()
                            codec = c
                            state.value = MirrorState.Live(e.width, e.height, 0f)
                        }
                        is VideoEvent.Packet -> {
                            val c = codec ?: continue
                            // Never drop a packet (H.264 frames depend on each other): drain output while waiting for an input slot.
                            var idx = c.dequeueInputBuffer(20_000)
                            var tries = 0
                            while (idx < 0 && tries < 50 && !Thread.currentThread().isInterrupted) {
                                while (true) {
                                    val out = c.dequeueOutputBuffer(info, 0)
                                    if (out >= 0) { c.releaseOutputBuffer(out, true); frames++ } else break
                                }
                                idx = c.dequeueInputBuffer(20_000); tries++
                            }
                            if (idx >= 0) {
                                val buf = c.getInputBuffer(idx)!!
                                buf.clear(); buf.put(e.data)
                                c.queueInputBuffer(idx, 0, e.data.size, e.ptsUs, if (e.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                            }
                            while (true) {
                                val out = c.dequeueOutputBuffer(info, 0)
                                if (out >= 0) { c.releaseOutputBuffer(out, true); frames++ } else break
                            }
                            val now = System.currentTimeMillis()
                            if (now - t0 >= 1000) {
                                val (w, h) = size
                                state.value = MirrorState.Live(w, h, frames * 1000f / (now - t0)); frames = 0; t0 = now
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("Mirror", "mirroring failed", e)
                if (running) state.value = MirrorState.Failed((e.message ?: e.javaClass.simpleName).take(600))
            } finally {
                cleanup()
            }
        }
    }

    /** Normalised (0..1) touch on the mirrored picture → injected touch on the TV. */
    fun touch(action: Int, nx: Float, ny: Float) {
        val s = session ?: return
        val (w, h) = size
        if (w == 0) return
        runCatching { s.touch(action, (nx * w).toInt().coerceIn(0, w - 1), (ny * h).toInt().coerceIn(0, h - 1), w, h) }
    }

    fun key(code: Int) { session?.let { runCatching { it.key(code) } } ?: app.cube.key(code) }

    fun stop() {
        running = false
        cleanup()
        if (state.value !is MirrorState.Failed) state.value = MirrorState.Idle
    }

    private fun cleanup() {
        runCatching { session?.close() }; session = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        running = false
    }
}
