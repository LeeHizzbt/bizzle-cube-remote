package com.bizzle.cube.core

/** Named Fire TV remote keys mapped to Android KeyEvent keycodes. */
object Keys {
    val CODES: Map<String, Int> = linkedMapOf(
        "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23,
        "BACK" to 4, "HOME" to 3, "MENU" to 82,
        "PLAY_PAUSE" to 85, "PLAY" to 126, "PAUSE" to 127, "STOP" to 86,
        "FAST_FORWARD" to 90, "REWIND" to 89, "NEXT" to 87, "PREVIOUS" to 88,
        "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "MUTE" to 164,
        "SLEEP" to 223, "WAKE" to 224, "POWER" to 26, "TV_POWER" to 177,
        "SEARCH" to 84, "SETTINGS" to 176, "DEL" to 67, "ENTER" to 66,
        "CAPTIONS" to 175, "GUIDE" to 172, "CHANNEL_UP" to 166, "CHANNEL_DOWN" to 167
    )

    private val ALIASES: Map<String, String> = mapOf(
        "SELECT" to "OK", "ENTER_KEY" to "ENTER", "CENTER" to "OK", "DPAD_CENTER" to "OK",
        "DPAD_UP" to "UP", "DPAD_DOWN" to "DOWN", "DPAD_LEFT" to "LEFT", "DPAD_RIGHT" to "RIGHT",
        "VOL_UP" to "VOLUME_UP", "VOL_DOWN" to "VOLUME_DOWN", "VOLUP" to "VOLUME_UP", "VOLDOWN" to "VOLUME_DOWN",
        "PLAYPAUSE" to "PLAY_PAUSE", "TOGGLE" to "PLAY_PAUSE", "FF" to "FAST_FORWARD", "RW" to "REWIND",
        "FORWARD" to "FAST_FORWARD", "PREV" to "PREVIOUS", "SKIP" to "NEXT", "BACKSPACE" to "DEL",
        "DELETE" to "DEL", "WAKEUP" to "WAKE", "OPTIONS" to "MENU", "RETURN" to "BACK",
        "CC" to "CAPTIONS", "SUBTITLES" to "CAPTIONS"
    )

    /** Normalises a key name (case-insensitive, aliases) to a canonical name, or null if unknown. */
    fun normalize(name: String): String? {
        val n = name.trim().uppercase().replace(' ', '_').replace('-', '_').removePrefix("KEYCODE_")
        if (n in CODES) return n
        return ALIASES[n]
    }

    fun code(name: String): Int? = normalize(name)?.let { CODES[it] }
}
