package com.Lia.assistant.ui.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * A predictable seven-day visual cycle for Shraddha AI's 3D orb.
 * Uses the device's local calendar day and refreshes while the app remains open.
 */
data class DailyOrbTheme(
    val dayName: String,
    val style: NovaOrbStyle
)

object DailyOrbThemeSchedule {
    fun forCalendarDay(day: Int): DailyOrbTheme = when (day) {
        Calendar.SUNDAY -> DailyOrbTheme("Sunday", NovaOrbStyle.SUN)
        Calendar.MONDAY -> DailyOrbTheme("Monday", NovaOrbStyle.MOON)
        Calendar.TUESDAY -> DailyOrbTheme("Tuesday", NovaOrbStyle.MARS)
        Calendar.WEDNESDAY -> DailyOrbTheme("Wednesday", NovaOrbStyle.MERCURY)
        Calendar.THURSDAY -> DailyOrbTheme("Thursday", NovaOrbStyle.JUPITER)
        Calendar.FRIDAY -> DailyOrbTheme("Friday", NovaOrbStyle.VENUS)
        Calendar.SATURDAY -> DailyOrbTheme("Saturday", NovaOrbStyle.SATURN)
        else -> DailyOrbTheme("Sunday", NovaOrbStyle.SUN)
    }

    fun today(): DailyOrbTheme =
        forCalendarDay(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
}

@Composable
fun rememberDailyOrbTheme(): DailyOrbTheme {
    var current by remember { mutableStateOf(DailyOrbThemeSchedule.today()) }
    LaunchedEffect(Unit) {
        while (true) {
            val latest = DailyOrbThemeSchedule.today()
            if (latest != current) current = latest
            delay(60_000L)
        }
    }
    return current
}
