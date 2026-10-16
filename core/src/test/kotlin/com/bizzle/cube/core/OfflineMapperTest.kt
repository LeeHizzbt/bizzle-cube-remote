package com.bizzle.cube.core

import org.junit.Assert.*
import org.junit.Test

class OfflineMapperTest {
    private val ctx = ActionContext(setOf("com.netflix.ninja", "com.amazon.firetv.youtube", "com.disney.disneyplus", "com.amazon.tv.settings.v2"), setOf("Movie night"))
    private fun m(s: String) = OfflineMapper.map(s, ctx)

    @Test fun basicKeys() {
        assertEquals(listOf(BizzyAction.Key("HOME")), m("go home"))
        assertEquals(listOf(BizzyAction.Key("BACK")), m("Go back"))
        assertEquals(listOf(BizzyAction.Key("PAUSE")), m("pause"))
        assertEquals(listOf(BizzyAction.Key("PLAY")), m("resume please"))
        assertEquals(listOf(BizzyAction.Key("OK")), m("select"))
        assertEquals(listOf(BizzyAction.Key("DOWN", 3)), m("down 3 times"))
        assertEquals(listOf(BizzyAction.Key("RIGHT", 2)), m("move right two"))
        assertEquals(listOf(BizzyAction.Key("MUTE")), m("mute"))
    }

    @Test fun volume() {
        assertEquals(listOf(BizzyAction.Key("VOLUME_UP", 2)), m("volume up"))
        assertEquals(listOf(BizzyAction.Key("VOLUME_DOWN", 5)), m("turn it down by 5"))
        assertEquals(listOf(BizzyAction.Key("VOLUME_UP", 3)), m("louder by three"))
    }

    @Test fun appsAndSearch() {
        assertEquals(listOf(BizzyAction.OpenApp("com.netflix.ninja")), m("open Netflix"))
        assertEquals(listOf(BizzyAction.OpenApp("com.disney.disneyplus")), m("launch disney plus"))
        assertEquals(listOf(BizzyAction.Search("youtube", "Lofi Beats")), m("search youtube for Lofi Beats"))
        assertEquals(listOf(BizzyAction.Search("netflix", "Stranger Things")), m("play Stranger Things on Netflix"))
        assertTrue(m("open hulu").isEmpty()) // not installed → no action
    }

    @Test fun powerTimersMacrosChains() {
        assertEquals(listOf(BizzyAction.Key("SLEEP")), m("turn off the tv"))
        assertEquals(listOf(BizzyAction.Key("WAKE")), m("turn on the TV"))
        assertEquals(listOf(BizzyAction.SleepTimer(30)), m("sleep timer 30 minutes"))
        assertEquals(listOf(BizzyAction.SleepTimer(45)), m("turn off in forty five minutes"))
        assertEquals(listOf(BizzyAction.SleepTimer(0)), m("cancel sleep timer"))
        assertEquals(listOf(BizzyAction.RunMacro("Movie night")), m("run movie night"))
        assertEquals(listOf(BizzyAction.TypeText("Hello World")), m("type Hello World"))
        assertEquals(
            listOf(BizzyAction.Key("HOME"), BizzyAction.Wait(2000), BizzyAction.OpenApp("com.amazon.firetv.youtube")),
            m("go home, wait 2 seconds, then open youtube")
        )
    }

    @Test fun neverPowerOrGarbage() {
        assertTrue(m("press power").isEmpty())
        assertTrue(m("format the hard drive").isEmpty())
        assertTrue(m("go home then do something weird").isEmpty()) // all-or-nothing
    }
}
