package com.bizzle.cube.core

import java.net.URLEncoder

/** Known Fire TV streaming apps, their package names and deep-link helpers. */
data class StreamingService(
    val id: String,
    val label: String,
    val packages: List<String>,
    val aliases: List<String>,
    /** Builds a VIEW URL for a search query, or null if the app has no known search link. */
    val searchUrl: ((String) -> String)? = null,
    /** Hosts/schemes a deep link for this service may use. */
    val linkPrefixes: List<String> = emptyList()
)

object StreamingApps {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    val SERVICES: List<StreamingService> = listOf(
        StreamingService(
            "netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf("netflix"),
            null, listOf("netflix://", "https://www.netflix.com/", "http://www.netflix.com/", "https://netflix.com/")
        ),
        StreamingService(
            "prime", "Prime Video", listOf("com.amazon.firebat", "com.amazon.avod", "com.amazon.avod.thirdpartyclient"),
            listOf("prime", "prime video", "amazon prime", "amazon video", "primevideo"),
            null, listOf("https://www.primevideo.com/", "https://app.primevideo.com/", "https://www.amazon.com/gp/video/", "https://watch.amazon.com/")
        ),
        StreamingService(
            "youtube", "YouTube", listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.amazon.firetv.youtube.tv", "com.google.android.youtube"),
            listOf("youtube", "you tube", "yt"),
            { q -> "https://www.youtube.com/results?search_query=${enc(q)}" },
            listOf("https://www.youtube.com/", "https://youtube.com/", "https://youtu.be/", "https://m.youtube.com/", "vnd.youtube:")
        ),
        StreamingService(
            "disney", "Disney+", listOf("com.disney.disneyplus"), listOf("disney", "disney+", "disney plus", "disneyplus"),
            null, listOf("https://www.disneyplus.com/", "disneyplus://")
        ),
        StreamingService(
            "hulu", "Hulu", listOf("com.hulu.plus", "com.hulu.livingroomplus"), listOf("hulu"),
            null, listOf("https://www.hulu.com/", "hulu://")
        ),
        StreamingService("max", "Max", listOf("com.wbd.stream", "com.hbo.hbonow"), listOf("max", "hbo", "hbo max")),
        StreamingService("plex", "Plex", listOf("com.plexapp.android"), listOf("plex")),
        StreamingService("peacock", "Peacock", listOf("com.peacocktv.peacockandroid"), listOf("peacock")),
        StreamingService("paramount", "Paramount+", listOf("com.cbs.ott"), listOf("paramount", "paramount+", "paramount plus")),
        StreamingService("tubi", "Tubi", listOf("com.tubitv", "com.tubitv.ott"), listOf("tubi")),
        StreamingService("pluto", "Pluto TV", listOf("tv.pluto.android"), listOf("pluto", "pluto tv")),
        StreamingService("spotify", "Spotify", listOf("com.spotify.tv.android"), listOf("spotify")),
        StreamingService("appletv", "Apple TV", listOf("com.apple.atve.amazon.appletv"), listOf("apple tv", "apple tv+", "appletv")),
        StreamingService("kodi", "Kodi", listOf("org.xbmc.kodi"), listOf("kodi")),
        StreamingService("twitch", "Twitch", listOf("tv.twitch.android.viewer", "tv.twitch.android.app"), listOf("twitch")),
        StreamingService("crunchyroll", "Crunchyroll", listOf("com.crunchyroll.crunchyroid"), listOf("crunchyroll")),
        StreamingService("silk", "Silk Browser", listOf("com.amazon.cloud9"), listOf("silk", "browser", "silk browser")),
        StreamingService("settings", "Fire TV Settings", listOf("com.amazon.tv.settings.v2", "com.amazon.tv.settings"), listOf("settings"))
    )

    fun byId(id: String): StreamingService? = SERVICES.firstOrNull { it.id == id }

    /** Finds a service by a free-form name ("Disney Plus", "prime"). */
    fun find(name: String): StreamingService? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        SERVICES.firstOrNull { it.id == n || it.label.lowercase() == n || n in it.aliases }?.let { return it }
        return SERVICES.firstOrNull { s -> s.aliases.any { n.contains(it) && it.length >= 3 } || n.contains(s.label.lowercase()) }
    }

    /** First installed package for a service, or null. */
    fun installedPackage(service: StreamingService, installed: Collection<String>): String? =
        service.packages.firstOrNull { it in installed }

    /** Friendly label for a package name. */
    fun labelFor(pkg: String): String {
        SERVICES.firstOrNull { pkg in it.packages }?.let { return it.label }
        KNOWN_LABELS[pkg]?.let { return it }
        val parts = pkg.split('.').filter { it !in setOf("com", "tv", "android", "app", "org", "net", "amazon", "firetv", "ott") }
        val base = parts.lastOrNull() ?: pkg
        return base.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private val KNOWN_LABELS = mapOf(
        "com.amazon.tv.launcher" to "Fire TV Home",
        "com.amazon.venezia" to "Appstore",
        "com.amazon.bueller.photos" to "Amazon Photos",
        "com.amazon.tv.music" to "Amazon Music",
        "com.amazon.awvflingreceiver" to "Fling receiver",
        "com.amazon.ssm" to "Sling",
        "com.sling" to "Sling TV",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.pbs.video" to "PBS",
        "com.fubo.firetv.screen" to "fuboTV",
        "com.philo.philo" to "Philo",
        "com.frontier.tve.foxnow" to "FOX",
        "com.espn.score_center" to "ESPN",
        "com.android.settings" to "Android Settings",
        "com.android.chrome" to "Chrome"
    )

    /** True when the given link is allowed for that service (prevents arbitrary intents from AI). */
    fun linkAllowed(service: StreamingService, link: String): Boolean {
        val l = link.trim()
        if (l.length > 500 || l.any { it.isWhitespace() || it == '\'' || it == '"' || it == '`' || it == '\\' }) return false
        return service.linkPrefixes.any { l.startsWith(it, ignoreCase = true) }
    }

    /** Identifies which service a shared URL belongs to. */
    fun serviceForLink(link: String): StreamingService? =
        SERVICES.firstOrNull { s -> s.linkPrefixes.isNotEmpty() && linkAllowed(s, link) }

    /** Netflix title ID from a share link like https://www.netflix.com/title/80057281. */
    fun netflixTitleId(link: String): String? =
        Regex("""(?:netflix\.com/(?:[a-z-]+/)?(?:title|watch)/|netflix://title/)(\d{5,12})""").find(link)?.groupValues?.get(1)
}
