package tech.kousozo.tahoetv.core

/** When to show "What's new" after an update. A fresh install records its version silently (nothing is "new" to someone who just
 *  installed), a later, higher version shows the release notes once, and the same or an older version (a downgrade) shows nothing.
 *  The caller stores the version it has *shown*, so a failed notes download is simply retried on the next start. */
object WhatsNew {
    enum class Decision { NOTHING, RECORD_ONLY, SHOW }

    @JvmStatic fun decide(seenVersion: String?, currentVersion: String): Decision = when {
        seenVersion.isNullOrBlank() -> Decision.RECORD_ONLY
        Updates.compare(currentVersion, seenVersion) > 0 -> Decision.SHOW
        else -> Decision.NOTHING
    }

    /** The notes belong to the installed version only when the release's version matches it (a newer release isn't what was just installed). */
    @JvmStatic fun notesApply(releaseVersion: String, currentVersion: String): Boolean =
        Updates.compare(releaseVersion, currentVersion) == 0
}
