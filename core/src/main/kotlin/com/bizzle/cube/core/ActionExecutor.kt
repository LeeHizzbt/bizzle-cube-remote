package com.bizzle.cube.core

/** The narrow set of TV operations the executor is allowed to use. */
interface CubeControl {
    fun keys(codes: List<Int>)
    fun text(s: String): Boolean
    fun launch(pkg: String)
    fun viewUrl(url: String, pkg: String?)
    fun netflixTitle(id: String, pkg: String)
}

/**
 * Runs allow-listed [BizzyAction]s. This is the only path from Bizzy to the TV, and it maps every
 * action to a fixed command; there is no raw-shell passthrough.
 */
class ActionExecutor(
    private val control: CubeControl,
    private val installed: () -> Set<String>,
    private val macros: (String) -> List<BizzyAction>?,
    private val sleepTimer: (Int) -> Unit,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    /** Returns human-readable notes for things the user should know (e.g. app not installed). */
    fun run(actions: List<BizzyAction>, depth: Int = 0): List<String> {
        val notes = mutableListOf<String>()
        for (a in actions) {
            when (a) {
                is BizzyAction.Key -> {
                    val code = Keys.code(a.key) ?: continue
                    control.keys(List(a.times.coerceIn(1, ActionAllowList.MAX_KEY_REPEAT)) { code })
                }
                is BizzyAction.OpenApp -> control.launch(a.pkg)
                is BizzyAction.TypeText -> if (!control.text(a.text)) notes += "Couldn't type that (only plain English letters, numbers and symbols work)."
                is BizzyAction.Wait -> sleeper(a.ms.coerceIn(0, ActionAllowList.MAX_WAIT_MS))
                is BizzyAction.SleepTimer -> sleepTimer(a.minutes)
                is BizzyAction.RunMacro -> {
                    if (depth >= 2) { notes += "Routine '${a.name}' skipped (routines can nest only 2 deep)."; continue }
                    val steps = macros(a.name)
                    if (steps == null) notes += "No routine named '${a.name}'." else notes += run(steps, depth + 1)
                }
                is BizzyAction.DeepLink -> {
                    val svc = StreamingApps.byId(a.service) ?: continue
                    val pkg = pkgFor(svc)
                    if (pkg == null) { notes += "${svc.label} isn't installed on this Cube."; continue }
                    if (!StreamingApps.linkAllowed(svc, a.target)) { notes += "Blocked a link that isn't a ${svc.label} link."; continue }
                    val nId = if (svc.id == "netflix") StreamingApps.netflixTitleId(a.target) else null
                    if (nId != null) control.netflixTitle(nId, pkg) else control.viewUrl(a.target, pkg)
                }
                is BizzyAction.Search -> {
                    val svc = StreamingApps.byId(a.service) ?: continue
                    val pkg = pkgFor(svc)
                    if (pkg == null) { notes += "${svc.label} isn't installed on this Cube."; continue }
                    val url = svc.searchUrl?.invoke(a.query)
                    if (url != null) control.viewUrl(url, pkg)
                    else {
                        control.launch(pkg)
                        notes += "Opened ${svc.label}. It has no search link, so open its Search box and tap \"Type it\" to send \"${a.query}\"."
                    }
                }
            }
        }
        return notes
    }

    private fun pkgFor(svc: StreamingService): String? {
        val inst = installed()
        return if (inst.isEmpty()) svc.packages.first() else StreamingApps.installedPackage(svc, inst)
    }
}

/** Adapter from a live [CubeAdb] session (optionally with a scrcpy turbo channel for keys). */
class AdbCubeControl(private val adb: () -> CubeAdb, private val turbo: () -> ScrcpySession? = { null }) : CubeControl {
    override fun keys(codes: List<Int>) {
        val t = turbo()
        if (t != null && !t.closed) {
            try { codes.forEach { t.key(it); Thread.sleep(60) }; return } catch (_: Exception) {}
        }
        adb().keyevents(codes)
    }
    override fun text(s: String): Boolean = adb().text(s)
    override fun launch(pkg: String) { adb().launch(pkg) }
    override fun viewUrl(url: String, pkg: String?) { adb().viewUrl(url, pkg) }
    override fun netflixTitle(id: String, pkg: String) {
        require(id.all { it.isDigit() })
        // Netflix's TV app wants its own extras for a cold-start deep link.
        adb().shell("am start -a android.intent.action.VIEW -d netflix://title/$id -f 0x10808000 -e source 30 $pkg")
    }
}
