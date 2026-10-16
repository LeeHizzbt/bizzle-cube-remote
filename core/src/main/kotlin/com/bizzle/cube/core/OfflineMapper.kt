package com.bizzle.cube.core

/**
 * Offline, rule-based plain-English → allow-listed actions.
 * Used when no AI key is set, every provider failed, or for instant simple commands.
 */
object OfflineMapper {

    private val NUM_WORDS = mapOf(
        "a" to 1, "one" to 1, "once" to 1, "two" to 2, "twice" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty five" to 45, "sixty" to 60, "ninety" to 90
    )

    private fun num(s: String?): Int? {
        if (s == null) return null
        val t = s.trim().lowercase()
        return t.toIntOrNull() ?: NUM_WORDS[t]
    }

    private const val N = """(\d+|a|one|once|two|twice|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|forty|sixty|ninety)"""

    /** Returns actions for the whole sentence, or empty if nothing matched. */
    fun map(input: String, ctx: ActionContext = ActionContext()): List<BizzyAction> {
        val parts = input.trim().trimEnd('.', '!', '?')
            .split(Regex("""\s*(?:,\s*then|\band then\b|\bthen\b|;|,\s*and\b|,)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<BizzyAction>()
        for (p in parts) {
            val a = mapOne(p, ctx) ?: return emptyList() // all-or-nothing: don't half-run a sentence we didn't understand
            out += a
        }
        return out.take(ActionAllowList.MAX_ACTIONS)
    }

    fun mapOne(raw: String, ctx: ActionContext): List<BizzyAction>? {
        val s = raw.lowercase().trim().removePrefix("please ").removePrefix("bizzy ").removePrefix("hey bizzy ").trim()
            .removeSuffix(" please").trim()
        val orig = raw.trim()

        Regex("""^(?:cancel|stop|turn off|clear) (?:the )?sleep timer$""").find(s)?.let { return listOf(BizzyAction.SleepTimer(0)) }
        Regex("""^(?:set (?:a )?)?sleep timer (?:for |to )?$N\s*(?:min|mins|minutes)?$""").find(s)?.let { m ->
            return num(m.groupValues[1])?.let { listOf(BizzyAction.SleepTimer(it.coerceIn(1, ActionAllowList.MAX_SLEEP_MIN))) }
        }
        Regex("""^(?:turn (?:the )?(?:tv )?off|sleep|go to sleep|shut (?:it )?down) in $N\s*(?:min|mins|minutes)$""").find(s)?.let { m ->
            return num(m.groupValues[1])?.let { listOf(BizzyAction.SleepTimer(it.coerceIn(1, ActionAllowList.MAX_SLEEP_MIN))) }
        }

        // search X for Y  /  search for Y on X  /  find Y on X
        Regex("""^(?:search|look up) (.+?) for (.+)$""").find(s)?.let { m ->
            val svc = StreamingApps.find(m.groupValues[1])
            if (svc != null) return listOf(BizzyAction.Search(svc.id, extractOriginal(orig, m.groupValues[2])))
        }
        Regex("""^(?:search(?: for)?|find|look up|play) (.+?) on (.+)$""").find(s)?.let { m ->
            val svc = StreamingApps.find(m.groupValues[2])
            if (svc != null) return listOf(BizzyAction.Search(svc.id, extractOriginal(orig, m.groupValues[1])))
        }

        Regex("""^(?:open|launch|start|go to|put on|switch to|load|fire up) (?:the )?(.+?)(?: app)?$""").find(s)?.let { m ->
            val name = m.groupValues[1]
            ctx.macroNames.firstOrNull { it.equals(name, true) }?.let { return listOf(BizzyAction.RunMacro(it)) }
            val pkg = ActionParser.resolvePackage(name, ctx)
            if (pkg != null) return listOf(BizzyAction.OpenApp(pkg))
        }

        Regex("""^(?:run|start|do) (?:the )?(?:routine |macro )?(.+?)(?: routine| macro)?$""").find(s)?.let { m ->
            ctx.macroNames.firstOrNull { it.equals(m.groupValues[1], true) }?.let { return listOf(BizzyAction.RunMacro(it)) }
        }
        ctx.macroNames.firstOrNull { it.equals(s, true) }?.let { return listOf(BizzyAction.RunMacro(it)) }

        Regex("""^type (.+)$""").find(s)?.let { m -> return listOf(BizzyAction.TypeText(extractOriginal(orig, m.groupValues[1]).take(ActionAllowList.MAX_TEXT))) }

        Regex("""^wait $N\s*(?:s|sec|secs|seconds?)$""").find(s)?.let { m ->
            return num(m.groupValues[1])?.let { listOf(BizzyAction.Wait((it * 1000L).coerceAtMost(ActionAllowList.MAX_WAIT_MS))) }
        }

        // volume
        Regex("""^(?:turn (?:it|the volume|volume) up|volume up|louder|raise (?:the )?volume)(?: by $N)?(?: (?:times|steps|notches))?$""").find(s)?.let { m ->
            return listOf(BizzyAction.Key("VOLUME_UP", (num(m.groupValues[1].ifEmpty { null }) ?: 2).coerceIn(1, ActionAllowList.MAX_KEY_REPEAT)))
        }
        Regex("""^(?:turn (?:it|the volume|volume) down|volume down|quieter|softer|lower (?:the )?volume)(?: by $N)?(?: (?:times|steps|notches))?$""").find(s)?.let { m ->
            return listOf(BizzyAction.Key("VOLUME_DOWN", (num(m.groupValues[1].ifEmpty { null }) ?: 2).coerceIn(1, ActionAllowList.MAX_KEY_REPEAT)))
        }
        if (s in setOf("mute", "unmute", "mute it", "mute the tv", "toggle mute")) return listOf(BizzyAction.Key("MUTE"))

        // power via CEC
        if (s in setOf("turn off the tv", "turn the tv off", "tv off", "turn off", "sleep", "go to sleep", "power off", "turn it off"))
            return listOf(BizzyAction.Key("SLEEP"))
        if (s in setOf("turn on the tv", "turn the tv on", "tv on", "turn on", "wake", "wake up", "power on", "turn it on"))
            return listOf(BizzyAction.Key("WAKE"))

        // media
        when (s) {
            "pause", "pause it", "pause the show", "pause the movie", "hold on" -> return listOf(BizzyAction.Key("PAUSE"))
            "play", "resume", "play it", "continue", "unpause" -> return listOf(BizzyAction.Key("PLAY"))
            "play pause", "play/pause", "toggle play" -> return listOf(BizzyAction.Key("PLAY_PAUSE"))
            "stop", "stop it" -> return listOf(BizzyAction.Key("STOP"))
            "fast forward", "skip ahead", "forward" -> return listOf(BizzyAction.Key("FAST_FORWARD"))
            "rewind", "go back a bit", "skip back" -> return listOf(BizzyAction.Key("REWIND"))
            "next", "next episode", "next track", "skip" -> return listOf(BizzyAction.Key("NEXT"))
            "previous", "previous episode", "previous track" -> return listOf(BizzyAction.Key("PREVIOUS"))
            "home", "go home", "home screen", "go to home", "go to the home screen" -> return listOf(BizzyAction.Key("HOME"))
            "back", "go back", "return" -> return listOf(BizzyAction.Key("BACK"))
            "menu", "options", "open menu" -> return listOf(BizzyAction.Key("MENU"))
            "select", "ok", "okay", "enter", "click", "press ok", "press select", "choose it" -> return listOf(BizzyAction.Key("OK"))
            "captions", "subtitles", "turn on subtitles", "turn on captions", "cc" -> return listOf(BizzyAction.Key("CAPTIONS"))
            "settings", "open settings" -> {
                ActionParser.resolvePackage("settings", ctx)?.let { return listOf(BizzyAction.OpenApp(it)) }
                return listOf(BizzyAction.Key("SETTINGS"))
            }
        }
        Regex("""^(?:go |move |press |scroll )?(up|down|left|right)(?: $N(?: times)?| x\s?(\d+))?$""").find(s)?.let { m ->
            val times = num(m.groupValues[2].ifEmpty { null }) ?: m.groupValues[3].toIntOrNull() ?: 1
            return listOf(BizzyAction.Key(m.groupValues[1].uppercase(), times.coerceIn(1, ActionAllowList.MAX_KEY_REPEAT)))
        }
        Regex("""^press (.+?)(?: $N times)?$""").find(s)?.let { m ->
            val k = Keys.normalize(m.groupValues[1]) ?: return null
            if (k == "POWER") return null
            return listOf(BizzyAction.Key(k, (num(m.groupValues[2].ifEmpty { null }) ?: 1).coerceIn(1, ActionAllowList.MAX_KEY_REPEAT)))
        }
        return null
    }

    /** Recover original casing for a lowercase-matched fragment. */
    private fun extractOriginal(orig: String, lowerFragment: String): String {
        val idx = orig.lowercase().indexOf(lowerFragment)
        return if (idx >= 0) orig.substring(idx, idx + lowerFragment.length).trim() else lowerFragment.trim()
    }
}
