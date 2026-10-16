package com.bizzle.cube.core

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ExecutorAndProtocolTest {
    class FakeControl : CubeControl {
        val log = mutableListOf<String>()
        override fun keys(codes: List<Int>) { log += "keys ${codes.joinToString(",")}" }
        override fun text(s: String): Boolean { log += "text $s"; return true }
        override fun launch(pkg: String) { log += "launch $pkg" }
        override fun viewUrl(url: String, pkg: String?) { log += "view $url $pkg" }
        override fun netflixTitle(id: String, pkg: String) { log += "netflix $id $pkg" }
    }

    @Test fun executorMapsEveryActionToFixedCommands() {
        val c = FakeControl(); var timer = -1; val waits = mutableListOf<Long>()
        val ex = ActionExecutor(c, { setOf("com.netflix.ninja", "com.amazon.firetv.youtube") },
            { if (it == "Night") listOf(BizzyAction.Key("WAKE"), BizzyAction.OpenApp("com.netflix.ninja")) else null },
            { timer = it }, { waits += it })
        val notes = ex.run(listOf(
            BizzyAction.Key("VOLUME_UP", 3), BizzyAction.Search("youtube", "cats"), BizzyAction.Search("netflix", "Dark"),
            BizzyAction.DeepLink("netflix", "https://www.netflix.com/title/80100172"), BizzyAction.DeepLink("hulu", "https://www.hulu.com/x"),
            BizzyAction.RunMacro("Night"), BizzyAction.Wait(500), BizzyAction.SleepTimer(20), BizzyAction.TypeText("hi")
        ))
        assertEquals(listOf(
            "keys 24,24,24", "view https://www.youtube.com/results?search_query=cats com.amazon.firetv.youtube",
            "launch com.netflix.ninja", "netflix 80100172 com.netflix.ninja", "keys 224", "launch com.netflix.ninja", "text hi"
        ), c.log)
        assertEquals(20, timer); assertEquals(listOf(500L), waits)
        assertTrue(notes.any { it.contains("Hulu isn't installed") })
        assertTrue(notes.any { it.contains("Type it") })
    }

    @Test fun macroRecursionIsBounded() {
        val c = FakeControl()
        val ex = ActionExecutor(c, { emptySet() }, { listOf(BizzyAction.RunMacro("Loop"), BizzyAction.Key("OK")) }, {}, {})
        val notes = ex.run(listOf(BizzyAction.RunMacro("Loop")))
        assertTrue(notes.any { it.contains("nest") })
        assertEquals(2, c.log.size)
    }

    @Test fun keycodeAndTouchSerialization() {
        val k = ScrcpyProtocol.keycode(ScrcpyProtocol.ACTION_DOWN, 19)
        assertEquals(14, k.size); assertEquals(0, k[0].toInt()); assertEquals(0, k[1].toInt())
        assertEquals(19, ByteBuffer.wrap(k, 2, 4).int)
        val t = ScrcpyProtocol.touch(ScrcpyProtocol.ACTION_DOWN, 100, 200, 1920, 1080)
        assertEquals(32, t.size)
        val bb = ByteBuffer.wrap(t)
        assertEquals(2, bb.get().toInt()); assertEquals(0, bb.get().toInt()); assertEquals(-2L, bb.long)
        assertEquals(100, bb.int); assertEquals(200, bb.int); assertEquals(1920, bb.short.toInt() and 0xffff); assertEquals(1080, bb.short.toInt() and 0xffff)
        assertEquals(0xffff, bb.short.toInt() and 0xffff)
        val tx = ScrcpyProtocol.text("hello")
        assertEquals(1, tx[0].toInt()); assertEquals(5, ByteBuffer.wrap(tx, 1, 4).int)
    }

    @Test fun videoParserHandlesSessionConfigAndFrames() {
        val b = Buffer()
        b.writeInt(ScrcpyProtocol.CODEC_H264)
        b.writeInt(0x80000000.toInt()); b.writeInt(1280); b.writeInt(720)          // session
        b.writeLong(1L shl 62); b.writeInt(3); b.write(byteArrayOf(0, 0, 1))         // config
        b.writeLong((1L shl 61) or 33333L); b.writeInt(2); b.write(byteArrayOf(9, 9)) // key frame
        val p = VideoStreamParser(b)
        assertEquals(ScrcpyProtocol.CODEC_H264, p.readCodec())
        assertEquals(VideoEvent.Session(1280, 720), p.next())
        val c = p.next() as VideoEvent.Packet; assertTrue(c.config); assertEquals(3, c.data.size)
        val f = p.next() as VideoEvent.Packet; assertTrue(f.keyFrame); assertFalse(f.config); assertEquals(33333L, f.ptsUs)
    }

    @Test fun serverArgsAreForwardTunnelNoAudio() {
        val cmd = ScrcpyProtocol.startCommand(0x1234, true, 1280, 4_000_000, 30)
        assertTrue(cmd.contains("com.genymobile.scrcpy.Server 5.0.1"))
        assertTrue(cmd.contains("scid=00001234")); assertTrue(cmd.contains("tunnel_forward=true")); assertTrue(cmd.contains("audio=false"))
    }

    @Test fun shellTextQuoting() {
        assertEquals("'hello%sworld'", ShellText.forInputText("hello world"))
        assertEquals("'it'\\''s'", ShellText.forInputText("it's"))
        assertEquals("'a;%srm%s-rf'", ShellText.forInputText("a; rm -rf"))
        assertNull(ShellText.forInputText("😀"))
        assertTrue(ShellText.isPackageName("com.netflix.ninja")); assertFalse(ShellText.isPackageName("com.x; reboot"))
    }

    @Test fun aiThemeParsingRejectsLowContrast() {
        val ok = ThemeSpec.parseAi("""{"name":"Ocean","primary":"#00E5FF","background":"#001018","surface":"#002233","onSurface":"#CCF7FF"}""")
        assertNotNull(ok); assertEquals("Ocean", ok!!.name); assertTrue(ok.ai)
        assertNull(ThemeSpec.parseAi("""{"name":"Mud","primary":"#222222","background":"#202020","surface":"#111111","onSurface":"#252525"}"""))
        assertNull(ThemeSpec.parseAi("nope"))
    }

    @Test fun streamingLookup() {
        assertEquals("prime", StreamingApps.find("Amazon Prime Video")?.id)
        assertEquals("disney", StreamingApps.find("Disney Plus")?.id)
        assertEquals("netflix", StreamingApps.find("Netflix basic with Ads")?.id)
        assertEquals("80057281", StreamingApps.netflixTitleId("https://www.netflix.com/title/80057281?s=a"))
        assertEquals("youtube", StreamingApps.serviceForLink("https://youtu.be/abc")?.id)
        assertNull(StreamingApps.serviceForLink("https://example.com"))
    }
}
