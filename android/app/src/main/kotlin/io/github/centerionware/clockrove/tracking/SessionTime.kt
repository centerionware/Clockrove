package io.github.centerionware.clockrove.tracking

import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import java.util.Date
import java.util.Locale

/** Pure native wall-time resolution. A form must explicitly choose an overlap. */
internal object SessionTime {
    data class Choice(val instant: Long, val offsetSeconds: Int, val label: String)
    fun resolve(local: String, timezone: String): List<Choice> {
        require(timezone in java.util.TimeZone.getAvailableIDs().toSet()) { "INVALID_TIMEZONE" }
        val parts = requireNotNull(Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})").matchEntire(local)) { "INVALID_LOCAL_TIME" }.groupValues.drop(1).map(String::toInt)
        require(BoardStore.validDate(local.substring(0, 10)) && parts[3] in 0..23 && parts[4] in 0..59 && parts[5] in 0..59) { "INVALID_LOCAL_TIME" }
        return listOf(Calendar.WALLTIME_FIRST, Calendar.WALLTIME_LAST).mapNotNull { option ->
            val calendar = GregorianCalendar(TimeZone.getTimeZone(timezone), Locale.ROOT).apply {
                gregorianChange = Date(Long.MIN_VALUE)
                isLenient = false
                repeatedWallTimeOption = option
                clear(); set(parts[0], parts[1] - 1, parts[2], parts[3], parts[4], parts[5]); set(Calendar.MILLISECOND, parts[6])
            }
            // Strict calendars reject a missing wall time instead of normalizing it.
            val instant = try { calendar.timeInMillis } catch (_: IllegalArgumentException) { return@mapNotNull null }
            val offset = TimeZone.getTimeZone(timezone).getOffset(instant) / 1000
            val absolute = kotlin.math.abs(offset)
            val display = String.format(Locale.ROOT, "%s%02d:%02d", if (offset < 0) "-" else "+", absolute / 3600, absolute % 3600 / 60) +
                if (absolute % 60 == 0) "" else String.format(Locale.ROOT, ":%02d", absolute % 60)
            Choice(instant, offset, "$local $display")
        }.distinctBy { it.instant }.sortedBy { it.instant }
    }
}
