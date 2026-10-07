package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AlarmEventResponseStatsTest {
    @Test fun weekdayAverageExcludesMissingFireTimestampWithoutDeletingHistory() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AlarmDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = db.alarmEventDao()
            val fired = 1_791_000_000_000L
            dao.insert(AlarmEvent(alarmId = 1, scheduledTime = fired, firedAt = fired,
                action = AlarmEvent.ACTION_DISMISSED, actionAt = fired + 22_000, dayOfWeek = 3))
            dao.insert(AlarmEvent(alarmId = 1, scheduledTime = fired, firedAt = 0,
                action = AlarmEvent.ACTION_DISMISSED, actionAt = fired + 30_000, dayOfWeek = 3))
            assertEquals(22_000L, dao.averageDismissTimeMs())
            assertEquals(22_000L, dao.avgResponseByDayOfWeek().single().avgMs)
            assertEquals(2, dao.count())
            assertEquals(2, dao.countByDayOfWeek().single().cnt)
        } finally { db.close() }
    }
}
