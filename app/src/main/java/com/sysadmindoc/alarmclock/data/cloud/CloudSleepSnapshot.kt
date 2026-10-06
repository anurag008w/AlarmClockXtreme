package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.sysadmindoc.alarmclock.data.health.HealthConnectSleepRepository
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.repository.*
import com.sysadmindoc.alarmclock.service.BedtimeNoiseBaselineSampler
import com.sysadmindoc.alarmclock.service.SonarSleepService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/** Read existing records only. Never start sensors or request OS permissions. */
class CloudSleepSnapshot @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthConnectSleepRepository,
    private val actigraphy: ActigraphyRepository,
    private val snores: SnoreEventRepository,
    private val tags: PreSleepTagRepository
) {
    suspend fun build(settings: AppSettings): Map<String, Any?> {
        val summary = if(settings.healthConnectEnabled) health.readRecentSleepSummary() else com.sysadmindoc.alarmclock.data.health.HealthConnectSleepSummary()
        val baseline = BedtimeNoiseBaselineSampler.readSnapshot(context)
        val sonar = SonarSleepService.readSnapshot(context)
        return mapOf(
            "health" to mapOf("enabled" to settings.healthConnectEnabled,"availability" to summary.availability.name,"permissionGranted" to summary.permissionGranted,"refreshedAtMillis" to summary.refreshedAtMillis,
                "sessionsRead" to summary.sessionsRead,"errorMessage" to summary.errorMessage?.take(500),"sessions" to summary.recentSessions.take(50).map { mapOf("startMillis" to it.startMillis,"endMillis" to it.endMillis,"durationMinutes" to it.durationMinutes,"asleepStageMinutes" to it.asleepStageMinutes,"lightStageMinutes" to it.lightStageMinutes,"deepStageMinutes" to it.deepStageMinutes,"remStageMinutes" to it.remStageMinutes,"awakeStageMinutes" to it.awakeStageMinutes,"unknownStageMinutes" to it.unknownStageMinutes) }),
            "actigraphy" to actigraphy.getRecent(10).map { mapOf("id" to it.id,"alarmId" to it.alarmId,"startedAt" to it.startedAt,"endedAt" to it.endedAt,"targetTime" to it.targetTime,"totalMinutes" to it.totalMinutes,"awakeMinutes" to it.awakeMinutes,"lightMinutes" to it.lightMinutes,"deepMinutes" to it.deepMinutes,"averageSleepIndex" to it.averageSleepIndex,"firedEarly" to it.firedEarly,"algorithm" to it.algorithm,"decisionReason" to it.decisionReason,"observedMinutesBeforeDecision" to it.observedMinutesBeforeDecision,"smartWakeMode" to it.smartWakeMode) },
            "snores" to snores.getRecent(50).map { mapOf("id" to it.id,"sessionStartedAt" to it.sessionStartedAt,"startedAt" to it.startedAt,"endedAt" to it.endedAt,"durationMillis" to it.durationMillis,"peakDb" to it.peakDb,"averageDb" to it.averageDb,"windowCount" to it.windowCount,"source" to it.source) },
            "tags" to tags.observeForDate(LocalDate.now()).first().take(20).map { mapOf("localDate" to it.localDate,"tagKey" to it.tagKey,"loggedAt" to it.loggedAt) },
            "correlations" to tags.readCorrelations(LocalDate.now()).take(20).map { mapOf("key" to it.key,"label" to it.label,"loggedNights" to it.loggedNights,"nightsWithSessions" to it.nightsWithSessions,"averageRestlessMinutes" to it.averageRestlessMinutes,"baselineRestlessMinutes" to it.baselineRestlessMinutes,"deltaRestlessMinutes" to it.deltaRestlessMinutes) },
            "bedtimePreferences" to mapOf("chronotypeAnswers" to settings.chronotypeAnswers,"jetLagTargetWakeMinutes" to settings.jetLagTargetWakeMinutes,"jetLagAdjustmentDays" to settings.jetLagAdjustmentDays,"jetLagDirection" to settings.jetLagDirection),
            "noiseBaseline" to mapOf("measuredAtMillis" to baseline.measuredAtMillis,"dbfs" to baseline.baseline?.dbfs,"level" to baseline.baseline?.level?.name),
            "sonar" to mapOf("active" to sonar.active,"sessionStartedAt" to sonar.startedAt,"lastEndedAt" to sonar.lastEndedAt,"lastTotalMinutes" to sonar.lastTotalMinutes,"lastAwakeMinutes" to sonar.lastAwakeMinutes,"lastLightMinutes" to sonar.lastLightMinutes,"lastDeepMinutes" to sonar.lastDeepMinutes,"lastSnoreEventCount" to sonar.lastSnoreEventCount,"lastSnorePeakDb" to sonar.lastSnorePeakDb)
        )
    }
}
