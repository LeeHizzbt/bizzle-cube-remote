package com.bizzle.cube.core

/**
 * The ONLY things Bizzy (AI or offline rules) may ask the app to do.
 * There is deliberately no "shell" / raw ADB action: the executor maps each of these to a
 * fixed, app-authored command, so an AI reply can never run arbitrary commands on the TV.
 */
sealed class BizzyAction {
    data class Key(val key: String, val times: Int = 1) : BizzyAction()
    data class OpenApp(val pkg: String) : BizzyAction()
    data class DeepLink(val service: String, val target: String) : BizzyAction()
    data class TypeText(val text: String) : BizzyAction()
    data class Search(val service: String, val query: String) : BizzyAction()
    data class RunMacro(val name: String) : BizzyAction()
    data class Wait(val ms: Long) : BizzyAction()
    data class SleepTimer(val minutes: Int) : BizzyAction()

    fun describe(): String = when (this) {
        is Key -> if (times > 1) "Press $key ×$times" else "Press $key"
        is OpenApp -> "Open app $pkg"
        is DeepLink -> "Open $service link: $target"
        is TypeText -> "Type \"$text\""
        is Search -> "Search $service for \"$query\""
        is RunMacro -> "Run routine \"$name\""
        is Wait -> "Wait ${ms} ms"
        is SleepTimer -> if (minutes <= 0) "Cancel sleep timer" else "Sleep timer ${minutes} min"
    }
}

/** Allow-list of action types the AI may emit. */
object ActionAllowList {
    val TYPES = setOf("key", "open_app", "deeplink", "type", "search", "macro", "wait", "sleep_timer")
    const val MAX_ACTIONS = 20
    const val MAX_KEY_REPEAT = 25
    const val MAX_WAIT_MS = 15_000L
    const val MAX_TEXT = 200
    const val MAX_SLEEP_MIN = 480
}
