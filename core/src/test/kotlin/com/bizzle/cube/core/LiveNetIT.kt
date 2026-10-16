package com.bizzle.cube.core

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Live network tests (TVmaze, Gemini). Enabled with CUBE_NET_IT=1. Keys come from env only and are never printed. */
class LiveNetIT {
    private val on = System.getenv("CUBE_NET_IT") == "1"

    @Test fun tvmazeSearch() {
        assumeTrue(on)
        val r = WatchSearch().tvmaze("Stranger Things")
        println("IT tvmaze: ${r.take(2).map { it.title + " " + it.offers }}")
        assertTrue(r.any { it.title.contains("Stranger Things") && it.offers.any { o -> o.serviceId == "netflix" } })
    }

    @Test fun geminiReturnsAllowListedActions() {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(on && key.isNotBlank())
        val providers = listOf(
            AiProvider.DEFAULTS[AiProvider.GEMINI]!!.copy(key = key),
            AiProvider.DEFAULTS[AiProvider.GROQ]!! // no key → skipped
        )
        val sys = BizzyPrompt.system(listOf("Netflix", "YouTube", "Hulu"), listOf("Movie night"), emptyList(), "fun")
        val res = AiClient().chat(providers, listOf(AiMessage("system", sys), AiMessage("user", "go home, then open youtube and search for lofi hip hop")), json = true)
        println("IT gemini provider=${res.provider} errors=${res.errors} content=${res.content?.take(300)}")
        assertNotNull(res.content)
        val parsed = ActionParser.parse(res.content!!, ActionContext(setOf("com.netflix.ninja", "com.amazon.firetv.youtube", "com.hulu.plus"), setOf("Movie night")))
        println("IT parsed: ${parsed.actions} rejected=${parsed.rejected}")
        assertTrue(parsed.actions.isNotEmpty())
        assertTrue(parsed.actions.all { it is BizzyAction.Key || it is BizzyAction.OpenApp || it is BizzyAction.Search || it is BizzyAction.Wait })
    }

    @Test fun geminiThemeIsParseable() {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue(on && key.isNotBlank())
        val res = AiClient().chat(listOf(AiProvider.DEFAULTS[AiProvider.GEMINI]!!.copy(key = key)), listOf(AiMessage("user", BizzyPrompt.themePrompt("alien cat disco"))), json = true)
        val t = ThemeSpec.parseAi(res.content ?: "")
        println("IT ai theme: $t")
        assertNotNull(t)
    }
}
