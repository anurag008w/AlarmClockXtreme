package com.sysadmindoc.alarmclock.platform

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * State for the opt-in external alert receiver: on/off switch, pairing code
 * and a tiny last-alert record. Off by default. Kept in its own preferences
 * file so the exported receiver never touches the main settings store.
 */
object ExternalAlertStore {
    private const val PREFS = "external_alert"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_SUMMARY = "last_summary"
    private const val KEY_LAST_ACCEPTED_AT = "last_accepted_at"
    private const val KEY_ALARM_CONTROL = "alarm_control_enabled"
    private const val KEY_LAST_ALARM_CHANGE_AT = "last_alarm_change_at"

    /** Minimum gap between two accepted alarm changes. */
    const val MIN_ALARM_CHANGE_INTERVAL_MS = 5_000L

    /** Minimum gap between two accepted alerts. */
    const val MIN_INTERVAL_MS = 15_000L

    // No 0/O/1/I/L so the code is easy to read and type.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) pairingCode(context)
    }

    /** The pairing code in groups of four, created on first use. */
    fun pairingCode(context: Context): String {
        val existing = prefs(context).getString(KEY_TOKEN, null)
        val raw = if (existing.isNullOrBlank()) regenerate(context) else existing
        return raw.chunked(4).joinToString("-")
    }

    fun regenerate(context: Context): String {
        val random = SecureRandom()
        val raw = buildString {
            repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        prefs(context).edit().putString(KEY_TOKEN, raw).apply()
        return raw
    }

    internal fun normalize(code: String?): String =
        code.orEmpty().filter { it.isLetterOrDigit() }.uppercase()

    /** Constant-time comparison of a presented code with the stored one. */
    fun tokenMatches(context: Context, presented: String?): Boolean {
        val stored = prefs(context).getString(KEY_TOKEN, null)
        if (stored.isNullOrBlank()) return false
        val a = normalize(presented).toByteArray(Charsets.UTF_8)
        val b = normalize(stored).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }

    /** True when enough time passed since the last accepted alert; records it when so. */
    @Synchronized
    fun tryAcceptNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = prefs(context).getLong(KEY_LAST_ACCEPTED_AT, 0L)
        if (nowMs - last in 0 until MIN_INTERVAL_MS) return false
        prefs(context).edit().putLong(KEY_LAST_ACCEPTED_AT, nowMs).apply()
        return true
    }

    fun recordLast(context: Context, summary: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_AT, nowMs)
            .putString(KEY_LAST_SUMMARY, summary.take(120))
            .apply()
    }

    fun lastAt(context: Context): Long = prefs(context).getLong(KEY_LAST_AT, 0L)

    fun lastSummary(context: Context): String =
        prefs(context).getString(KEY_LAST_SUMMARY, "").orEmpty()

    /** Separate opt-in: lets the paired app list and change alarms. Off by default. */
    fun isAlarmControlEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALARM_CONTROL, false)

    fun setAlarmControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALARM_CONTROL, enabled).apply()
        pairingCode(context)
    }

    /** True when enough time passed since the last accepted alarm change; records it when so. */
    @Synchronized
    fun tryAcceptAlarmChangeNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = prefs(context).getLong(KEY_LAST_ALARM_CHANGE_AT, 0L)
        if (nowMs - last in 0 until MIN_ALARM_CHANGE_INTERVAL_MS) return false
        prefs(context).edit().putLong(KEY_LAST_ALARM_CHANGE_AT, nowMs).apply()
        return true
    }
}
