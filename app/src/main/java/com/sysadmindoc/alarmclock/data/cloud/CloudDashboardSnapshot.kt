package com.sysadmindoc.alarmclock.data.cloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.CalendarRepository
import java.time.LocalDate
import java.time.ZoneId

/** Phone-owned Today/alarm history data. Sleep is collected separately. */
internal object CloudDashboardSnapshot {
    suspend fun build(context: Context, settings: AppSettings, alarms: AlarmRepository, history: AlarmEventRepository, mapping: Map<String, Long> = emptyMap()): Map<String, Any?> {
        val zone = ZoneId.systemDefault()
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val calendar = if (settings.showCalendarOnDashboard && permitted) CalendarRepository(context).getTodayEvents() else null
        val next = alarms.getNextAlarm()
        val stats = history.getStats()
        val recent = history.getRecent(50)
        val location = if (settings.locationName.isNotBlank() && settings.lastKnownLatitude.isFinite() && settings.lastKnownLongitude.isFinite())
            mapOf("name" to settings.locationName, "latitude" to settings.lastKnownLatitude, "longitude" to settings.lastKnownLongitude, "manual" to settings.useManualLocation, "kind" to "saved_weather_location") else null
        val capabilities = com.sysadmindoc.alarmclock.ui.alarmedit.deviceChallengeCapabilities(context)
        val alarmDetails = alarms.getAll().take(100).map { alarm ->
            val per = history.getPerAlarmStats(alarm.id)
            val verdict = com.sysadmindoc.alarmclock.ui.alarmedit.evaluateActiveChallengeReadiness(alarm.challengeType,alarm.challengeChain,capabilities,com.sysadmindoc.alarmclock.ui.alarmedit.ChallengeReferences(alarm.nfcTagId,alarm.barcodeValue,alarm.photoMatchUri,alarm.wifiDismissSsid))
            mapOf("cloudAlarmId" to mapping.entries.firstOrNull { it.value == alarm.id }?.key, "label" to alarm.label.take(500), "nextTriggerTime" to alarm.nextTriggerTime, "canSkipNext" to (alarm.isEnabled && alarm.isRecurringSchedule && !alarm.smartAlarmEnabled && alarm.nextTriggerTime > System.currentTimeMillis()+60000), "lookbackDays" to 30, "fireCount" to per.fireCount, "avgSnoozesPerFire" to per.avgSnoozesPerFire, "avgDismissTimeSec" to per.avgDismissTimeSec, "missedCount" to per.missedCount, "readiness" to (verdict?.status?.name ?: "NO_HARDWARE_REQUIRED"), "readinessMessage" to verdict?.let { context.getString(it.messageRes).take(500) }, "blocksSave" to (verdict?.blocksSave ?: false))
        }
        return mapOf(
            "phoneObservedMillis" to System.currentTimeMillis(), "timezone" to zone.id, "calendarDate" to LocalDate.now(zone).toString(),
            "location" to location, "calendarStatus" to when { !settings.showCalendarOnDashboard -> "disabled"; !permitted -> "permission_required"; calendar?.isFailure == true -> "unavailable"; else -> "available" },
            "calendar" to calendar?.getOrNull().orEmpty().take(100).map { mapOf("id" to it.id, "title" to it.title.take(500), "startTime" to it.startTime, "endTime" to it.endTime, "allDay" to it.allDay, "location" to it.location.take(500)) },
            "nextAlarm" to next?.takeIf { it.nextTriggerTime > System.currentTimeMillis() }?.let { mapOf("label" to it.label.take(500), "nextTriggerTime" to it.nextTriggerTime) },
            "alarmDetails" to alarmDetails,
            "stats" to mapOf("totalDismissed" to stats.totalDismissed, "totalSnoozed" to stats.totalSnoozed, "totalSkipped" to stats.totalSkipped, "totalMissed" to stats.totalMissed,
                "averageDismissTimeSec" to stats.averageDismissTimeSec, "snoozeRate" to stats.snoozeRate, "currentStreak" to stats.currentStreak, "bestStreak" to stats.bestStreak,
                "streakIncludesToday" to stats.streakIncludesToday, "nextStreakGoal" to stats.nextStreakGoal, "alarmsThisWeek" to stats.alarmsThisWeek,
                "dayOfWeekCounts" to stats.dayOfWeekCounts.mapKeys { it.key.value.toString() }, "dayOfWeekAvgResponseSec" to stats.dayOfWeekAvgResponseSec.mapKeys { it.key.value.toString() }),
            "events" to recent.map { mapOf("id" to it.id,"alarmLabel" to it.alarmLabel.take(500),"scheduledTime" to it.scheduledTime,"firedAt" to it.firedAt,"action" to it.action,"actionAt" to it.actionAt,"challengeType" to it.challengeType,"challengeSolveTimeMs" to it.challengeSolveTimeMs,"challengeRetryCount" to it.challengeRetryCount,"snoozeCount" to it.snoozeCount) }
        )
    }
}
