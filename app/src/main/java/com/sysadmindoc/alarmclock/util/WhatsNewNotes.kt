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
        "1.16.11" to listOf(
            "Remote alarm changes (off by default): a paired app can list alarms, change an alarm time, turn one on or off, or add one. Turn it on in Settings > Integrations with its own switch.",
            "Every change shows a notice with an Undo button. Nothing can be deleted, and an alarm with a cancellation lock cannot be turned off remotely.",
            "Alarm scheduling and reliability are unchanged."
        ),
        "1.16.10" to listOf(
            "External alerts (off by default): a paired app can vibrate or ring this phone for a short alert. Turn it on in Settings > Integrations. The alert has a Stop button.",
            "Only apps that send the pairing code shown in Settings can trigger an alert. Do Not Disturb, silent volume and strict battery saving can still mute or delay it.",
            "Alarm scheduling and reliability are unchanged."
        ),
        "1.16.9" to listOf(
            "Edit alarm: AM/PM is now a clear toggle next to the time, and the weekday buttons are round chips.",
            "Settings: sub-pages have a back arrow to return to Settings.",
            "Alarm scheduling and reliability are unchanged."
        ),
        "1.16.8" to listOf(
            "Air quality: a colour scale from good to hazardous now shows where today's reading sits.",
            "YouTube alarm sounds: every search result shows its video picture.",
            "Crash reports: the Play build now sends anonymous crash reports so crashes can be fixed faster. Details are in the privacy policy.",
            "Alarm scheduling and reliability are unchanged."
        ),
        "1.16.7" to listOf(
            "Updates: the app now checks for a new version every time it opens and shows the update popup right away.",
            "Updates: the popup lists what changed in plain text, without the raw link and markdown symbols.",
            "Settings > Updates no longer says Not checked yet after a fresh launch.",
            "Settings switches no longer flip back by themselves after a cloud sync.",
            "Alarm list: the time stays on one line on narrow phones and the row is tidier.",
            "Alarm scheduling and reliability are unchanged."
        ),
        "1.16.6" to listOf(
            "News: articles now show a small picture when the feed provides one.",
            "World clock: each city has a day or night sky tile with a sun or moon.",
            "Settings: every category has a tinted icon badge and a one-line description.",
            "Alarm scheduling and reliability are unchanged."
        ),
        "1.16.5" to listOf(
            "New look begins: deeper navy backgrounds, a stronger blue accent and richer card surfaces across the whole app.",
            "Alarm list: every alarm now has a round time-of-day icon (sun, twilight, moon) next to the time.",
            "Timer: the time you type now sits inside a progress ring.",
            "More screens follow in the next updates. Alarm scheduling and reliability are unchanged."
        ),
        "1.16.4" to listOf(
            "Polish pass across the app: every screen now uses the same corner-radius scale, so cards, sheets and buttons look consistent.",
            "Settings > Updates now shows what is new in an available update, and the version line is a plain info row instead of a fake button.",
            "Screen readers: settings choices announce once as a single selectable row, the timer count is read correctly (1 timer / 2 timers), and the Stop timer button is translatable.",
            "World clock search hints are now translatable. Alarm scheduling and reliability are unchanged."
        ),
        "1.16.3" to listOf(
            "Today tab: your next alarm now sits at the top, so the most important thing is the first thing you see.",
            "The next-alarm card is announced as a button for screen readers and has more comfortable padding.",
            "A hard-coded weather message is now a translatable string. Alarm scheduling and reliability are unchanged."
        ),
        "1.16.2" to listOf(
            "In-app updates: the app checks the GitHub releases and shows a popup only when a newer version really exists, with that version's notes.",
            "Download and install updates inside the app, no browser. New Settings > Updates (below Backup) with Check now, popup on/off and auto-download on Wi-Fi.",
            "What's New now shows the real notes for the version you installed, with a link to the full release notes.",
            "Calmer look: softer corners, tighter cards, a clearer page-title size and more compact alarm rows.",
            "All project links now point to anurag008w/AlarmClockXtreme. Alarm scheduling and reliability are unchanged."
        ),
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
