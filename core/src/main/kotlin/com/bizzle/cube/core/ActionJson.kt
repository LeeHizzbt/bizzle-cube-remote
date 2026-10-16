package com.bizzle.cube.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Serialises actions into the same allow-listed JSON shape Bizzy uses (used for saved routines). */
object ActionJson {
    fun toJson(a: BizzyAction): JsonObject = JsonObject().apply {
        when (a) {
            is BizzyAction.Key -> { addProperty("type", "key"); addProperty("key", a.key); addProperty("times", a.times) }
            is BizzyAction.OpenApp -> { addProperty("type", "open_app"); addProperty("app", a.pkg) }
            is BizzyAction.DeepLink -> { addProperty("type", "deeplink"); addProperty("service", a.service); addProperty("url", a.target) }
            is BizzyAction.TypeText -> { addProperty("type", "type"); addProperty("text", a.text) }
            is BizzyAction.Search -> { addProperty("type", "search"); addProperty("service", a.service); addProperty("query", a.query) }
            is BizzyAction.RunMacro -> { addProperty("type", "macro"); addProperty("name", a.name) }
            is BizzyAction.Wait -> { addProperty("type", "wait"); addProperty("ms", a.ms) }
            is BizzyAction.SleepTimer -> { addProperty("type", "sleep_timer"); addProperty("minutes", a.minutes) }
        }
    }

    fun listToJson(actions: List<BizzyAction>): String =
        JsonObject().apply { add("actions", JsonArray().apply { actions.forEach { add(toJson(it)) } }) }.toString()

    /** Re-validates stored routines through the same allow-list on load. */
    fun listFromJson(json: String, macroNames: Set<String>): List<BizzyAction> =
        ActionParser.parse(json, ActionContext(emptySet(), macroNames)).actions
}
