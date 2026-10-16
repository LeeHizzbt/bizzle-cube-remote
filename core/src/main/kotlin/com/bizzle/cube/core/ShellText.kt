package com.bizzle.cube.core

/** Safe quoting for the handful of app-authored shell commands. */
object ShellText {
    /** POSIX single-quote a value so the device shell treats it as one literal word. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Prepare text for `input text`: only printable ASCII is supported by the stock
     * `input` tool on Fire OS 7. Spaces must be sent as %s. Returns null if nothing typeable remains.
     */
    fun forInputText(s: String): String? {
        val ascii = s.filter { it.code in 32..126 }
        if (ascii.isEmpty()) return null
        val withSpaces = ascii.replace("%", "\\%").replace(" ", "%s")
        return quote(withSpaces)
    }

    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    fun isPackageName(s: String) = PKG.matches(s)

    private val COMPONENT = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+$")
    fun isComponent(s: String) = COMPONENT.matches(s)
}
