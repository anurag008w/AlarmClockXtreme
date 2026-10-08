package com.sysadmindoc.alarmclock.util

/**
 * User-facing release notes for the "What's new" dialog, one entry per shipped
 * version. Add an entry for every release (WhatsNewNotesTest fails the build if
 * the current versionName has none), and keep it in step with CHANGELOG.md.
 */
object WhatsNewNotes {

    const val REPO_URL = "https://github.com/anurag008w/AlarmClockXtreme"
    const val ROADMAP_URL = "$REPO_URL#roadmap"

    fun releaseNotesUrl(versionName: String): String = "$REPO_URL/releases/tag/v$versionName"

    private val notes: Map<String, List<String>> = mapOf(
        "1.16.1" to listOf(
            "The alarm list no longer shows the duplicate fire-time warning.",
            "Cloud alarms reach this phone in seconds through push sync (added in 1.16.0)."
        ),
        "1.16.0" to listOf(
            "Push sync: alarm, setting and utility changes made on the web dashboard now reach the phone within seconds.",
            "Sync still falls back to periodic checks if push is unavailable."
        )
    )

    fun has(versionName: String): Boolean = notes.containsKey(versionName)

    /** Notes for [versionName]; a single pointer to the release page when none are recorded. */
    fun forVersion(versionName: String): List<String> =
        notes[versionName] ?: listOf("See the full release notes for v$versionName on GitHub.")
}
