package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyBriefingSchedulerTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun schedulesForLaterTodayWhenTargetHasNotPassed() {
        val now = ZonedDateTime.of(2026, 11, 22, 7, 30, 0, 0, zone)
        assertEquals(30L * 60L * 1000L, DailyBriefingScheduler.initialDelayMillis(now, 8, 0))
    }

    @Test fun schedulesForTomorrowWhenTargetTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 11, 22, 8, 1, 0, 0, zone)
        assertEquals(23L * 60L * 1000L, DailyBriefingScheduler.initialDelayMillis(now, 8, 24))
    }

    @Test fun schedulesAtNextDayWhenTargetEqualsCurrentMinute() {
        val now = ZonedDateTime.of(2026, 11, 22, 8, 0, 0, 0, zone)
        assertEquals(24L * 60L * 60L * 1000L, DailyBriefingScheduler.initialDelayMillis(now, 8, 0))
    }
}