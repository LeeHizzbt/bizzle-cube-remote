package com.bizzle.cube.core

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Live tests against a real adbd over TCP (the Android emulator stands in for the Cube).
 * Run with: CUBE_IT_HOST=127.0.0.1 CUBE_IT_PORT=5555 CUBE_IT_JAR=/path/scrcpy-server ./gradlew :core:test
 */
class LiveAdbIT {
    private val host = System.getenv("CUBE_IT_HOST").orEmpty()
    private val port = System.getenv("CUBE_IT_PORT")?.toIntOrNull() ?: 5555
    private val out = File(System.getenv("CUBE_IT_OUT") ?: "build/it-out").apply { mkdirs() }

    private fun session(): CubeAdb {
        assumeTrue("CUBE_IT_HOST not set", host.isNotBlank())
        val keyDir = File(System.getenv("CUBE_IT_KEYDIR") ?: (System.getProperty("user.home") + "/.android"))
        val adb = CubeAdb(host, port, AdbKeys.loadOrCreate(keyDir))
        val r = adb.connect(30_000) { println("IT: waiting for TV prompt…") }
        assertTrue("connect: $r", r is ConnectOutcome.Connected)
        println("IT connected: $r")
        return adb
    }

    @Test fun keyeventsTextAppsInfoScreencap() {
        val adb = session()
        adb.use {
            // keyevent through the persistent shell: open the settings app, then HOME and check focus changes
            it.shell("am start -a android.settings.SETTINGS")
            Thread.sleep(4000)
            val before = it.foregroundPackage()
            println("IT foreground before HOME: $before")
            it.keyevent(3)
            Thread.sleep(4000)
            val after = it.foregroundPackage()
            println("IT foreground after HOME: $after")
            assertNotEquals(before, after)

            val apps = it.launchableApps()
            println("IT launchable apps: ${apps.size} e.g. ${apps.take(5)}")
            assertTrue(apps.isNotEmpty())
            val third = it.packages(false)
            assertTrue(third.contains("com.android.settings"))

            val info = it.deviceInfo()
            println("IT device info: $info")
            assertTrue(info.model.isNotBlank())

            val png = it.screencapPng()
            File(out, "screencap.png").writeBytes(png)
            println("IT screencap bytes: ${png.size}")
            assertTrue(png.size > 1000)
            assertNotNull(it.screenSize())
        }
    }

    @Test fun textInputLandsInFocusedField() {
        val adb = session()
        adb.use {
            // Quick Search Box opens with its search field focused, like a TV search box
            it.shell("am force-stop com.android.quicksearchbox; am start -n com.android.quicksearchbox/.SearchActivity")
            Thread.sleep(6000)
            assertTrue(it.text("bizzle cube 420"))
            Thread.sleep(5000)
            val dump = it.shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
            File(out, "ui-after-text.xml").writeText(dump)
            println("IT ui dump has typed text: ${dump.contains("bizzle cube 420")}")
            assertTrue("typed text not found in focused field", dump.contains("bizzle cube 420"))
            it.keyevent(3)
        }
    }

    @Test fun scrcpyMirrorStreamAndControl() {
        val jarPath = System.getenv("CUBE_IT_JAR").orEmpty()
        assumeTrue("CUBE_IT_JAR not set", jarPath.isNotBlank())
        val adb = session()
        adb.use {
            val s = ScrcpySession.start(it.raw(), File(jarPath).readBytes(), video = true, maxSize = 720, bitRate = 2_000_000, maxFps = 15, timeoutMs = 60_000)
            s.use { sess ->
                val parser = VideoStreamParser(sess.video!!.source)
                assertEquals(ScrcpyProtocol.CODEC_H264, parser.readCodec())
                val h264 = File(out, "mirror.h264").outputStream()
                var session: VideoEvent.Session? = null
                var config = 0; var frames = 0; var keyFrames = 0
                val deadline = System.currentTimeMillis() + 90_000
                var pokedAt = 0
                while (System.currentTimeMillis() < deadline && frames < 30) {
                    when (val e = parser.next()) {
                        is VideoEvent.Session -> { session = e; println("IT session $e") }
                        is VideoEvent.Packet -> {
                            h264.write(e.data)
                            if (e.config) config++ else frames++
                            if (e.keyFrame) keyFrames++
                            if (frames > 0 && pokedAt == 0) {
                                // control socket: inject a HOME key and a tap → screen changes → more frames
                                sess.key(3); pokedAt = frames
                                val ss = session!!
                                sess.touch(ScrcpyProtocol.ACTION_DOWN, ss.width / 2, ss.height / 2, ss.width, ss.height)
                                sess.touch(ScrcpyProtocol.ACTION_UP, ss.width / 2, ss.height / 2, ss.width, ss.height)
                            }
                        }
                    }
                }
                h264.close()
                println("IT mirror: session=$session config=$config frames=$frames key=$keyFrames bytes=${File(out, "mirror.h264").length()}")
                println("IT server log: ${sess.serverLog.toString().take(400)}")
                assertNotNull(session); assertTrue(config >= 1); assertTrue(frames >= 1)
            }
        }
    }

    @Test fun turboControlOnlySession() {
        val jarPath = System.getenv("CUBE_IT_JAR").orEmpty()
        assumeTrue(jarPath.isNotBlank())
        val adb = session()
        adb.use {
            it.shell("am start -a android.settings.SETTINGS"); Thread.sleep(4000)
            val before = it.foregroundPackage()
            val s = ScrcpySession.start(it.raw(), File(jarPath).readBytes(), video = false, timeoutMs = 60_000)
            s.use { t ->
                Thread.sleep(10_000) // the software-emulated test device needs time to finish starting the server's controller
                val t0 = System.nanoTime(); t.key(3); val ms = (System.nanoTime() - t0) / 1e6
                println("IT turbo key send took ${"%.1f".format(ms)} ms")
                Thread.sleep(8000)
            }
            val after = it.foregroundPackage()
            println("IT turbo foreground $before -> $after")
            assertNotEquals(before, after)
        }
    }
}
