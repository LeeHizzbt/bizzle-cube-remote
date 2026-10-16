package com.bizzle.cube.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Context used to validate actions: what's installed on the Cube and which routines exist. */
data class ActionContext(
    val installedPackages: Set<String> = emptySet(),
    val macroNames: Set<String> = emptySet()
)

data class ParsedReply(
    val reply: String,
    val actions: List<BizzyAction>,
    val rejected: List<String>
)

/**
 * Parses a model reply of the form
 * {"reply":"...", "actions":[{"type":"key","key":"HOME"}, ...]}
 * and keeps ONLY actions that pass the allow-list. Anything else is dropped and reported.
 */
object ActionParser {

    fun extractJson(raw: String): String? {
        var s = raw.trim()
        s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("```\\s*$"), "").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1)
    }

    fun parse(raw: String, ctx: ActionContext): ParsedReply {
        val json = extractJson(raw) ?: return ParsedReply(raw.trim(), emptyList(), emptyList())
        val obj: JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return ParsedReply(raw.trim(), emptyList(), listOf("Reply was not valid JSON"))
        }
        val reply = obj.str("reply") ?: obj.str("message") ?: ""
        val arr = obj.get("actions")
        val actions = mutableListOf<BizzyAction>()
        val rejected = mutableListOf<String>()
        if (arr != null && arr.isJsonArray) {
            for (el in arr.asJsonArray) {
                if (actions.size >= ActionAllowList.MAX_ACTIONS) {
                    rejected += "Too many actions (max ${ActionAllowList.MAX_ACTIONS})"
                    break
                }
                when (val r = validate(el, ctx)) {
                    is Result.Ok -> actions += r.action
                    is Result.Rejected -> rejected += r.reason
                }
            }
        }
        return ParsedReply(reply, actions, rejected)
    }

    private sealed class Result {
        data class Ok(val action: BizzyAction) : Result()
        data class Rejected(val reason: String) : Result()
    }

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble.toInt() }.getOrNull() }

    private fun validate(el: JsonElement, ctx: ActionContext): Result {
        if (!el.isJsonObject) return Result.Rejected("Action is not an object")
        val o = el.asJsonObject
        val type = o.str("type")?.lowercase()?.trim() ?: return Result.Rejected("Action without type")
        if (type !in ActionAllowList.TYPES) return Result.Rejected("Blocked action type '$type' (not on allow-list)")
        return when (type) {
            "key" -> {
                val name = o.str("key") ?: return Result.Rejected("key: missing key")
                val canon = Keys.normalize(name) ?: return Result.Rejected("key: unknown key '$name'")
                if (canon == "POWER") return Result.Rejected("key: POWER is not allowed from AI (use SLEEP/WAKE)")
                val times = (o.int("times") ?: 1)
                if (times < 1 || times > ActionAllowList.MAX_KEY_REPEAT) return Result.Rejected("key: times out of range")
                Result.Ok(BizzyAction.Key(canon, times))
            }
            "open_app" -> {
                val app = o.str("app") ?: o.str("package") ?: return Result.Rejected("open_app: missing app")
                val pkg = resolvePackage(app, ctx) ?: return Result.Rejected("open_app: '$app' is not installed on this Cube")
                Result.Ok(BizzyAction.OpenApp(pkg))
            }
            "deeplink" -> {
                val svcName = o.str("service") ?: return Result.Rejected("deeplink: missing service")
                val svc = StreamingApps.find(svcName) ?: return Result.Rejected("deeplink: unknown service '$svcName'")
                val target = o.str("url") ?: o.str("target") ?: o.str("id") ?: return Result.Rejected("deeplink: missing url")
                val url = if (svc.id == "netflix" && target.all { it.isDigit() }) "netflix://title/$target" else target
                if (!StreamingApps.linkAllowed(svc, url)) return Result.Rejected("deeplink: link not allowed for ${svc.label}")
                Result.Ok(BizzyAction.DeepLink(svc.id, url))
            }
            "type" -> {
                val text = o.str("text") ?: return Result.Rejected("type: missing text")
                if (text.isEmpty() || text.length > ActionAllowList.MAX_TEXT) return Result.Rejected("type: text length out of range")
                if (text.any { it.code < 32 }) return Result.Rejected("type: control characters not allowed")
                Result.Ok(BizzyAction.TypeText(text))
            }
            "search" -> {
                val svcName = o.str("service") ?: o.str("app") ?: return Result.Rejected("search: missing service")
                val svc = StreamingApps.find(svcName) ?: return Result.Rejected("search: unknown service '$svcName'")
                val q = o.str("query") ?: return Result.Rejected("search: missing query")
                if (q.isBlank() || q.length > ActionAllowList.MAX_TEXT || q.any { it.code < 32 }) return Result.Rejected("search: bad query")
                Result.Ok(BizzyAction.Search(svc.id, q.trim()))
            }
            "macro" -> {
                val name = o.str("name") ?: return Result.Rejected("macro: missing name")
                val match = ctx.macroNames.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
                    ?: return Result.Rejected("macro: no routine named '$name'")
                Result.Ok(BizzyAction.RunMacro(match))
            }
            "wait" -> {
                val ms = (o.get("ms")?.takeIf { it.isJsonPrimitive }?.asDouble?.toLong())
                    ?: o.int("seconds")?.let { it * 1000L }
                    ?: return Result.Rejected("wait: missing ms")
                if (ms < 0 || ms > ActionAllowList.MAX_WAIT_MS) return Result.Rejected("wait: out of range (max 15 s)")
                Result.Ok(BizzyAction.Wait(ms))
            }
            "sleep_timer" -> {
                val min = o.int("minutes") ?: return Result.Rejected("sleep_timer: missing minutes")
                if (min < 0 || min > ActionAllowList.MAX_SLEEP_MIN) return Result.Rejected("sleep_timer: out of range")
                Result.Ok(BizzyAction.SleepTimer(min))
            }
            else -> Result.Rejected("Blocked action type '$type'")
        }
    }

    /** Resolves an app name or package to an installed package. Never returns a package that isn't installed when the list is known. */
    fun resolvePackage(app: String, ctx: ActionContext): String? {
        val a = app.trim()
        val installed = ctx.installedPackages
        if (Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$").matches(a)) {
            return if (installed.isEmpty() || a in installed) a else null
        }
        val svc = StreamingApps.find(a)
        if (svc != null) {
            return if (installed.isEmpty()) svc.packages.first() else StreamingApps.installedPackage(svc, installed)
        }
        // fuzzy match against labels of installed packages
        val n = a.lowercase()
        return installed.firstOrNull { StreamingApps.labelFor(it).lowercase() == n }
            ?: installed.firstOrNull { it.lowercase().contains(n.replace(" ", "")) && n.length >= 3 }
    }
}
