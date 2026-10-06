package com.sysadmindoc.alarmclock.data.cloud

/** Scheduling effects are based on applied values, never just a cloud version. */
internal object CloudSettingsEffects {
    private val alarmKeys = setOf(
        "showAlarmClockIcon", "vacationModeEnabled", "vacationStartMillis", "vacationEndMillis",
        "holidayAutoSkipEnabled", "holidayCountryCode", "pauseUntilMillis"
    )
    private val bedtimeKeys = setOf(
        "bedtimeEnabled", "bedtimeHour", "bedtimeMinute", "bedtimeReminderMinutes",
        "bedtimeStayUpLateUntilMillis"
    )
    fun alarmsChanged(before: Map<String, Any?>, after: Map<String, Any?>): Boolean =
        alarmKeys.any { !equal(before[it], after[it]) }
    fun bedtimeChanged(before: Map<String, Any?>, after: Map<String, Any?>): Boolean =
        bedtimeKeys.any { !equal(before[it], after[it]) }
    private fun equal(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
}
