package com.Lia.assistant.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DailyOrbThemeScheduleTest {
    @Test
    fun mapsAllSevenDaysToDistinctOrbStyles() {
        val expected = listOf(
            NovaOrbStyle.SUN,
            NovaOrbStyle.MOON,
            NovaOrbStyle.MARS,
            NovaOrbStyle.MERCURY,
            NovaOrbStyle.JUPITER,
            NovaOrbStyle.VENUS,
            NovaOrbStyle.SATURN
        )
        val actual = (Calendar.SUNDAY..Calendar.SATURDAY).map {
            DailyOrbThemeSchedule.forCalendarDay(it).style
        }
        assertEquals(expected, actual)
        assertEquals(7, actual.toSet().size)
    }

    @Test
    fun invalidCalendarDayFallsBackToSunday() {
        assertEquals(
            DailyOrbThemeSchedule.forCalendarDay(Calendar.SUNDAY),
            DailyOrbThemeSchedule.forCalendarDay(0)
        )
    }
}
