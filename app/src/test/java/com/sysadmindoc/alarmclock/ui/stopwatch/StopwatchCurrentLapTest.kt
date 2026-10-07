package com.sysadmindoc.alarmclock.ui.stopwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class StopwatchCurrentLapTest {
    @Test fun currentSplitUsesLatestLapTotalAndKeepsTotalElapsed() {
        val first = Lap(number=1, splitMillis=10000, totalMillis=10000)
        val second = Lap(number=2, splitMillis=15000, totalMillis=25000)
        assertEquals(10000L, StopwatchUiState(elapsedMillis=10000).currentLapMillis)
        assertEquals(15000L, StopwatchUiState(elapsedMillis=25000,laps=listOf(first)).currentLapMillis)
        val state=StopwatchUiState(elapsedMillis=28000,laps=listOf(second,first))
        assertEquals(3000L,state.currentLapMillis)
        assertEquals(28000L,state.elapsedMillis)
        assertEquals(0L,StopwatchUiState(elapsedMillis=25000,laps=listOf(first,second)).currentLapMillis)
    }
}
