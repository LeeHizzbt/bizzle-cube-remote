package com.bizzle.cube.core

import org.junit.Assert.*
import org.junit.Test

class ActionParserTest {
    private val ctx = ActionContext(
        installedPackages = setOf("com.netflix.ninja", "com.amazon.firetv.youtube", "com.hulu.plus", "com.amazon.tv.settings.v2", "org.xbmc.kodi"),
        macroNames = setOf("Movie night")
    )

    @Test fun parsesValidActions() {
        val raw = """{"reply":"On it!","actions":[{"type":"key","key":"home"},{"type":"open_app","app":"Netflix"},
            {"type":"wait","ms":1500},{"type":"key","key":"VOL_UP","times":3},{"type":"type","text":"Stranger Things"},
            {"type":"search","service":"YouTube","query":"lofi beats"},{"type":"macro","name":"movie night"},{"type":"sleep_timer","minutes":30}]}"""
        val r = ActionParser.parse(raw, ctx)
        assertEquals("On it!", r.reply)
        assertEquals(emptyList<String>(), r.rejected)
        assertEquals(
            listOf(
                BizzyAction.Key("HOME"), BizzyAction.OpenApp("com.netflix.ninja"), BizzyAction.Wait(1500),
                BizzyAction.Key("VOLUME_UP", 3), BizzyAction.TypeText("Stranger Things"),
                BizzyAction.Search("youtube", "lofi beats"), BizzyAction.RunMacro("Movie night"), BizzyAction.SleepTimer(30)
            ), r.actions
        )
    }

    @Test fun blocksShellAndUnknownTypes() {
        val raw = """{"reply":"x","actions":[{"type":"shell","command":"rm -rf /sdcard"},{"type":"adb","cmd":"reboot"},
            {"type":"install","url":"http://evil/apk"},{"type":"uninstall","package":"com.netflix.ninja"},{"type":"key","key":"HOME"}]}"""
        val r = ActionParser.parse(raw, ctx)
        assertEquals(listOf(BizzyAction.Key("HOME")), r.actions)
        assertEquals(4, r.rejected.size)
        assertTrue(r.rejected.all { it.contains("allow-list") })
    }

    @Test fun shellFieldsInsideAllowedTypesAreIgnored() {
        // extra fields never become commands; the executor only reads known fields
        val r = ActionParser.parse("""{"actions":[{"type":"key","key":"OK","command":"reboot"}]}""", ctx)
        assertEquals(listOf(BizzyAction.Key("OK")), r.actions)
    }

    @Test fun rejectsPowerKeyUnknownKeyAndBadRepeat() {
        val r = ActionParser.parse("""{"actions":[{"type":"key","key":"POWER"},{"type":"key","key":"LAUNCH_MISSILES"},{"type":"key","key":"UP","times":500}]}""", ctx)
        assertTrue(r.actions.isEmpty()); assertEquals(3, r.rejected.size)
    }

    @Test fun rejectsAppsNotInstalled() {
        val r = ActionParser.parse("""{"actions":[{"type":"open_app","app":"Disney+"},{"type":"open_app","app":"com.evil.app"},{"type":"open_app","app":"kodi"}]}""", ctx)
        assertEquals(listOf(BizzyAction.OpenApp("org.xbmc.kodi")), r.actions)
        assertEquals(2, r.rejected.size)
    }

    @Test fun deeplinkOnlyOfficialHosts() {
        val r = ActionParser.parse(
            """{"actions":[{"type":"deeplink","service":"youtube","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"},
               {"type":"deeplink","service":"youtube","url":"https://evil.com/x"},
               {"type":"deeplink","service":"netflix","url":"80057281"},
               {"type":"deeplink","service":"netflix","url":"https://www.netflix.com/title/1'; reboot"}]}""", ctx)
        assertEquals(
            listOf(BizzyAction.DeepLink("youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"), BizzyAction.DeepLink("netflix", "netflix://title/80057281")),
            r.actions
        )
        assertEquals(2, r.rejected.size)
    }

    @Test fun limitsWaitTextSleepAndCount() {
        val many = (1..30).joinToString(",") { """{"type":"key","key":"DOWN"}""" }
        val r = ActionParser.parse("""{"actions":[$many]}""", ctx)
        assertEquals(ActionAllowList.MAX_ACTIONS, r.actions.size)
        val r2 = ActionParser.parse("""{"actions":[{"type":"wait","ms":999999},{"type":"type","text":"${"a".repeat(500)}"},{"type":"sleep_timer","minutes":9999},{"type":"macro","name":"nope"}]}""", ctx)
        assertTrue(r2.actions.isEmpty()); assertEquals(4, r2.rejected.size)
    }

    @Test fun handlesCodeFencesAndPlainText() {
        val fenced = "```json\n{\"reply\":\"hi\",\"actions\":[{\"type\":\"key\",\"key\":\"BACK\"}]}\n```"
        assertEquals(listOf(BizzyAction.Key("BACK")), ActionParser.parse(fenced, ctx).actions)
        val plain = ActionParser.parse("Just chatting, no JSON here", ctx)
        assertEquals("Just chatting, no JSON here", plain.reply); assertTrue(plain.actions.isEmpty())
        val broken = ActionParser.parse("{not json", ctx)
        assertTrue(broken.actions.isEmpty())
    }
}
