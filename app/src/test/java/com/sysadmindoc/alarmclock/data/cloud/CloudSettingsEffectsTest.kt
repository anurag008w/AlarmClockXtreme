package com.sysadmindoc.alarmclock.data.cloud
import org.junit.Assert.*
import org.junit.Test
class CloudSettingsEffectsTest {
    @Test fun worldAndAppearanceChangesDoNotTouchSchedules() {
        val before = mapOf<String, Any?>("worldClockZones" to "Asia/Tokyo", "accentColor" to "blue")
        val after = before + mapOf("worldClockZones" to "", "accentColor" to "red")
        assertFalse(CloudSettingsEffects.alarmsChanged(before, after))
        assertFalse(CloudSettingsEffects.bedtimeChanged(before, after))
    }
    @Test fun vacationAndPauseRequireAlarmReschedule() {
        assertTrue(CloudSettingsEffects.alarmsChanged(mapOf("pauseUntilMillis" to 0L), mapOf("pauseUntilMillis" to 10L)))
        assertTrue(CloudSettingsEffects.alarmsChanged(mapOf("vacationModeEnabled" to false), mapOf("vacationModeEnabled" to true)))
    }
    @Test fun bedtimeOnlyTouchesBedtimeSchedule() {
        val before = mapOf<String, Any?>("bedtimeHour" to 22)
        val after = mapOf<String, Any?>("bedtimeHour" to 23)
        assertTrue(CloudSettingsEffects.bedtimeChanged(before, after))
        assertFalse(CloudSettingsEffects.alarmsChanged(before, after))
    }
    @Test fun serializationNumberTypeDoesNotTriggerEffect() {
        assertFalse(CloudSettingsEffects.bedtimeChanged(mapOf("bedtimeHour" to 23), mapOf("bedtimeHour" to 23.0)))
    }
}
