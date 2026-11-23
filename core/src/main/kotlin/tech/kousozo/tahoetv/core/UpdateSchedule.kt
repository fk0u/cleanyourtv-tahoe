package tech.kousozo.tahoetv.core

/** When an app asks for the latest release: at most once a day, unless the clock went backwards (then it asks again rather than wait for a time that never comes). */
object UpdateSchedule {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    @JvmStatic fun due(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= INTERVAL_MS

    /** A stored "latest version" still counts as an available update only while it is newer than the installed one. */
    @JvmStatic fun available(storedLatest: String?, installed: String): Boolean = !storedLatest.isNullOrBlank() && Updates.newer(storedLatest, installed)
}
