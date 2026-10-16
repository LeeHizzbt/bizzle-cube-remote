package com.bizzle.cube.core

import com.google.gson.JsonParser

data class ThemeSpec(val name: String, val primary: Long, val background: Long, val surface: Long, val onSurface: Long, val ai: Boolean = false) {
    companion object {
        val BUILT_IN = listOf(
            ThemeSpec("Neon v4.20", 0xFF39FF14, 0xFF000000, 0xFF0F140F, 0xFFB8FFA8),
            ThemeSpec("Cinema", 0xFFFFC23D, 0xFF0B0705, 0xFF1C140C, 0xFFFFE7B8),
            ThemeSpec("Midnight", 0xFF6EA8FF, 0xFF050814, 0xFF0F1630, 0xFFD6E4FF),
            ThemeSpec("AMOLED", 0xFFFFFFFF, 0xFF000000, 0xFF0A0A0A, 0xFFE0E0E0),
            ThemeSpec("Jinxy Orange", 0xFFFF8A1F, 0xFF0A0602, 0xFF1A120A, 0xFFFFD9B0)
        )

        private fun hex(s: String?): Long? {
            val h = s?.trim()?.removePrefix("#") ?: return null
            if (!Regex("^[0-9a-fA-F]{6}$").matches(h)) return null
            return 0xFF000000L or h.toLong(16)
        }

        private fun lum(c: Long): Double {
            fun ch(v: Long): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xff) + 0.7152 * ch((c shr 8) and 0xff) + 0.0722 * ch(c and 0xff)
        }

        fun contrast(a: Long, b: Long): Double { val l1 = lum(a); val l2 = lum(b); return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05) }

        /** Parses an AI theme reply; rejects low-contrast palettes so text stays readable. */
        fun parseAi(raw: String): ThemeSpec? {
            val json = ActionParser.extractJson(raw) ?: return null
            return try {
                val o = JsonParser.parseString(json).asJsonObject
                val name = o.get("name")?.asString?.take(24)?.ifBlank { null } ?: "AI theme"
                val t = ThemeSpec(
                    name, hex(o.get("primary")?.asString) ?: return null, hex(o.get("background")?.asString) ?: return null,
                    hex(o.get("surface")?.asString) ?: return null, hex(o.get("onSurface")?.asString) ?: return null, ai = true
                )
                if (contrast(t.primary, t.background) < 3.0 || contrast(t.onSurface, t.background) < 4.5) null else t
            } catch (e: Exception) { null }
        }
    }
}
