package com.bizzle.cube.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class WatchOffer(val providerName: String, val kind: String, val serviceId: String?)

data class WatchTitle(
    val source: String,          // "TMDB" or "TVmaze"
    val id: String,
    val title: String,
    val year: String,
    val mediaType: String,       // movie / tv
    val overview: String,
    val posterUrl: String?,
    val offers: List<WatchOffer>,
    val link: String?
)

/**
 * Where-to-watch search. TMDB (watch-provider data by JustWatch, user's own free key) plus TVmaze (no key).
 * Attribution is shown in the app: "Streaming data by JustWatch via TMDB" and "TV data from TVmaze (CC BY-SA)".
 */
class WatchSearch(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val tmdbBase: String = "https://api.themoviedb.org/3",
    private val tvmazeBase: String = "https://api.tvmaze.com"
) {
    private fun get(url: String, bearer: String? = null): JsonObjectOrArray {
        val rb = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "BizzleCubeRemote/4.20")
        if (bearer != null) rb.header("Authorization", "Bearer $bearer")
        http.newCall(rb.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("TMDB key rejected. Check it in Settings > Where to watch.")
            if (r.code == 429) throw IllegalStateException("Rate-limited, try again in a few seconds.")
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return JsonObjectOrArray(JsonParser.parseString(body))
        }
    }

    class JsonObjectOrArray(val el: com.google.gson.JsonElement)

    fun tvmaze(query: String): List<WatchTitle> {
        val url = "$tvmazeBase/search/shows".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
        val arr = get(url).el.asJsonArray
        return arr.mapNotNull { it.asJsonObject.getAsJsonObject("show") }.map { s ->
            val network = s.objOrNull("network")?.strOrNull("name")
            val web = s.objOrNull("webChannel")?.strOrNull("name")
            val offers = listOfNotNull(web, network).distinct().map { WatchOffer(it, if (it == web) "streaming" else "TV network", StreamingApps.find(it)?.id) }
            WatchTitle(
                "TVmaze", s.get("id").asString, s.strOrNull("name") ?: "?",
                s.strOrNull("premiered")?.take(4) ?: "", "tv",
                (s.strOrNull("summary") ?: "").replace(Regex("<[^>]+>"), "").take(300),
                s.objOrNull("image")?.strOrNull("medium")?.replace("http://", "https://"),
                offers, s.strOrNull("url")
            )
        }
    }

    private fun tmdbUrl(path: String, key: String, params: Map<String, String> = emptyMap()): Pair<String, String?> {
        val b = (tmdbBase + path).toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val isV4Token = key.length > 60
        if (!isV4Token) b.addQueryParameter("api_key", key)
        return b.build().toString() to (if (isV4Token) key else null)
    }

    fun tmdb(query: String, key: String, region: String = "US", limit: Int = 8): List<WatchTitle> {
        val (u, bearer) = tmdbUrl("/search/multi", key, mapOf("query" to query, "include_adult" to "false"))
        val results = get(u, bearer).el.asJsonObject.getAsJsonArray("results") ?: return emptyList()
        return results.map { it.asJsonObject }
            .filter { it.strOrNull("media_type") in setOf("movie", "tv") }
            .take(limit)
            .map { r ->
                val type = r.strOrNull("media_type")!!
                val id = r.get("id").asString
                val (offers, link) = runCatching { providers(type, id, key, region) }.getOrDefault(emptyList<WatchOffer>() to null)
                WatchTitle(
                    "TMDB", id, r.strOrNull("title") ?: r.strOrNull("name") ?: "?",
                    (r.strOrNull("release_date") ?: r.strOrNull("first_air_date") ?: "").take(4), type,
                    (r.strOrNull("overview") ?: "").take(300),
                    r.strOrNull("poster_path")?.let { "https://image.tmdb.org/t/p/w185$it" }, offers, link
                )
            }
    }

    fun providers(type: String, id: String, key: String, region: String): Pair<List<WatchOffer>, String?> {
        val (u, bearer) = tmdbUrl("/$type/$id/watch/providers", key)
        val reg = get(u, bearer).el.asJsonObject.getAsJsonObject("results")?.getAsJsonObject(region) ?: return emptyList<WatchOffer>() to null
        val out = mutableListOf<WatchOffer>()
        for ((kind, label) in listOf("flatrate" to "stream", "free" to "free", "ads" to "free with ads", "rent" to "rent", "buy" to "buy")) {
            reg.getAsJsonArray(kind)?.forEach { p ->
                val name = p.asJsonObject.strOrNull("provider_name") ?: return@forEach
                if (out.none { it.providerName == name }) out += WatchOffer(name, label, StreamingApps.find(name)?.id)
            }
        }
        return out to reg.strOrNull("link")
    }

    private fun JsonObject.strOrNull(n: String): String? = get(n)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.objOrNull(n: String): JsonObject? = get(n)?.takeIf { it.isJsonObject }?.asJsonObject
}
