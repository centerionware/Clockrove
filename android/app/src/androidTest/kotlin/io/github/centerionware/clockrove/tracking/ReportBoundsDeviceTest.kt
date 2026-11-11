package io.github.centerionware.clockrove.tracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportBoundsDeviceTest {
    private fun day(date: String, zone: String) = ReportStore.days(ReportStore.Selection(date,date,zone,listOf("synthetic-job"))).single()
    private fun at(value: String) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time
    @Test fun actualFrameworkRepeatedMidnightIncludesTheEntire25HourDay() {
        val day = day("2026-11-01","America/Havana")
        assertEquals(at("2026-11-01T04:00:00Z"),day.start); assertEquals(at("2026-11-02T05:00:00Z"),day.end)
    }
    @Test fun frameworkSkippedMidnightAndSkippedDateUseNextValidInstant() {
        val gap = day("2015-10-18","America/Sao_Paulo"); assertEquals(23L * 3600000,gap.end-gap.start)
        val skipped = day("2011-12-30","Pacific/Apia"); assertEquals(skipped.start,skipped.end)
    }
    @Test fun finalSupportedYearDoesNotLoopAfterItsExclusiveEnd() {
        val day = day("9999-12-31","UTC"); assertEquals(86400000L,day.end-day.start)
    }
}
