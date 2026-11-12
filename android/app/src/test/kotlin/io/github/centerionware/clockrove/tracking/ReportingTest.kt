package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class ReportingTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var sequence = 0
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app); job = repository.createJob("=Synthetic, \"quoted\"\njob")
        val settings = repository.businessSnapshot().settings
        assertNull(repository.saveBusinessSettings("rate", version, settings.copy(defaultHourlyMinor = java.math.BigInteger.valueOf(6000))).conflict)
    }
    @After fun close() { repository.close() }
    private fun at(value: String) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time
    private fun session(start: String, end: String?) {
        val id = "start-${sequence++}"
        assertNull(repository.execute(TimerRepository.Command(id, version, TimerRepository.Action.START, job, at(start), "UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
        if (end != null) assertNull(repository.execute(TimerRepository.Command("stop-${sequence++}", version, TimerRepository.Action.STOP, null, at(end), "UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
    }
    private fun selection(first: String = "2026-11-01", last: String = first, zone: String = "America/New_York") = ReportStore.Selection(first, last, zone, listOf(job))
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)

    @Test fun fallBackDayHas25HoursAndTwoDistinctRepeatedHours() {
        session("2026-11-01T04:00:00.000Z", "2026-11-02T05:00:00.000Z")
        val report = repository.report(selection()).report
        assertEquals(25L * 3600000, report.days.single().end - report.days.single().start)
        assertEquals(BigInteger.valueOf(25L * 3600000), report.totals.single().durationMillis)
        assertEquals(BigInteger.valueOf(150000), report.totals.single().amountMinor)
        assertEquals(1, report.intervals.size)
    }
    @Test fun springForwardDayHas23HoursAndUtcClippingDoesNotInventTime() {
        session("2026-03-08T00:00:00.000Z", "2026-03-10T00:00:00.000Z")
        val report = repository.report(selection("2026-03-08")).report
        val interval = report.intervals.single()
        assertEquals(at("2026-03-08T05:00:00.000Z"), interval.start)
        assertEquals(at("2026-03-09T04:00:00.000Z"), interval.end)
        assertEquals(BigInteger.valueOf(23L * 3600000), report.totals.single().durationMillis)
    }
    @Test fun multiDayReportSplitsDisplayButPreservesSourceAndRoundsOnlyTheJobTotal() {
        session("2026-10-31T23:59:59.700Z", "2026-11-01T00:00:00.300Z")
        val settings = repository.businessSnapshot().settings
        repository.saveBusinessSettings("tiny-rate", version, settings.copy(defaultHourlyMinor = java.math.BigInteger.valueOf(3000)))
        val report = repository.report(selection("2026-10-31", "2026-11-01", "UTC")).report
        assertEquals(2, report.intervals.size); assertEquals(600L, report.totals.single().durationMillis.toLong())
        assertEquals(BigInteger.ONE, report.totals.single().amountMinor)
        assertEquals(1, report.intervals.map { it.sessionId }.distinct().size)
        assertEquals(1, report.intervals.map { it.sourceStart }.distinct().size)
    }
    @Test fun exactRoundingHandlesHalfMinorUnitsAndValuesAboveJavascriptIntegerPrecision() {
        assertEquals(BigInteger.ZERO, ReportStore.hourlyAmount(BigInteger.valueOf(1799999), BigInteger.ONE))
        assertEquals(BigInteger.ONE, ReportStore.hourlyAmount(BigInteger.valueOf(1800000), BigInteger.ONE))
        val rate = 9007199254740993L
        assertEquals(BigInteger.valueOf(rate), ReportStore.hourlyAmount(BigInteger.valueOf(3600000), BigInteger.valueOf(rate)))
        assertTrue(ReportStore.hourlyAmount(BigInteger.valueOf(Long.MAX_VALUE), BigInteger.valueOf(Long.MAX_VALUE)) > BigInteger.valueOf(Long.MAX_VALUE))
    }
    @Test fun activeAndReviewHeldSessionsAreVisibleButExcludedFromAllFinalAmountsAndGps() {
        session("2026-11-01T06:00:00.000Z", "2026-11-01T07:00:00.000Z")
        val sessionId = repository.report(selection()).report.intervals.single().sessionId
        inspect { it.execSQL("INSERT INTO geofence_conflicts VALUES(?,?,?,?,?)", arrayOf<Any?>(sessionId, job, at("2026-11-01T06:00:00.000Z"), "synthetic", 1)) }
        session("2026-11-01T08:00:00.000Z", null)
        val report = repository.report(selection()).report
        assertEquals(setOf("NEEDS_REVIEW", "ACTIVE_OR_PROVISIONAL"), report.excluded.map { it.reason }.toSet())
        assertTrue(report.intervals.isEmpty()); assertTrue(report.points.isEmpty()); assertEquals(BigInteger.ZERO, report.totals.single().durationMillis)
        assertEquals(1, ReportCsv.work(report).split("\r\n").count { it.isNotEmpty() })
    }
    @Test fun taskModeUsesOnlyCompletedBillableTasksAndExposesMissingPrices() {
        val completion = at("2026-11-01T12:00:00.000Z")
        repository.close(); repository = TimerRepository(RuntimeEnvironment.getApplication(), wallClock = { completion })
        val board = repository.boardSnapshot(job).board
        fun task(id: String, column: String, price: Long?, billable: Boolean = true) {
            assertNull(repository.saveCard("task-$id", version, BoardStore.Draft(id, job, column, id, billable = billable, priceMinor = price?.let(java.math.BigInteger::valueOf), currency = board.currency)).conflict)
        }
        task("priced", board.columns.last().id, 12345); task("free", board.columns.last().id, 0)
        task("missing", board.columns.last().id, null); task("uncompleted", board.columns.first().id, 50000)
        task("nonbillable", board.columns.last().id, 50000, false)
        session("2026-11-01T06:00:00.000Z", "2026-11-01T07:00:00.000Z")
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        val missing = repository.report(selection()).report
        assertEquals(3, missing.tasks.size); assertEquals(1, missing.totals.single().missingPrices); assertNull(missing.totals.single().amountMinor)
        assertNull(repository.removeCard("remove-missing", version, job, "missing").conflict)
        val priced = repository.report(selection()).report
        assertEquals(BigInteger.valueOf(12345), priced.totals.single().amountMinor)
        assertEquals(BigInteger.valueOf(3600000), priced.totals.single().durationMillis)
        assertNull(repository.saveJobSettings("hour-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "HOURLY")).conflict)
        assertEquals(BigInteger.valueOf(6000), repository.report(selection()).report.totals.single().amountMinor)
    }
    @Test fun gpsAndCsvUseTheSameFinalizedClippedSelectionAndProtectUntrustedText() {
        session("2026-11-01T03:00:00.000Z", null)
        val sessionId = repository.snapshot().running!!.id
        val measured = at("2026-11-01T05:30:00.000Z")
        assertEquals(TimerRepository.SampleResult.RECORDED, repository.recordLocation(TimerRepository.LocationPoint("point", sessionId, measured, measured, -1.0, -2.0, 10.0)))
        assertNull(repository.execute(TimerRepository.Command("finish", version, TimerRepository.Action.STOP, null, at("2026-11-02T08:00:00.000Z"), "UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
        val report = repository.report(selection()).report
        assertEquals(1, report.points.size)
        val work = ReportCsv.work(report); val gps = ReportCsv.gps(report)
        assertTrue(work.contains("\"'=Synthetic, \"\"quoted\"\"\njob\"")); assertTrue(gps.contains("\"'=Synthetic, \"\"quoted\"\"\njob\""))
        assertTrue(work.contains("2026-11-01T00:00:00.000-04:00")); assertTrue(work.contains("2026-11-02T00:00:00.000-05:00"))
        assertTrue(gps.contains("\"-1.0\",\"-2.0\"")); assertTrue(gps.contains("2026-11-01T01:30:00.000-04:00")); assertTrue(work.endsWith("\r\n"))
    }
    @Test fun differentCurrenciesStayInSeparateJobTotalsAndMissingRateIsNotInvented() {
        val current = repository.businessSnapshot().settings
        val otherCurrency = if (current.currency == "JPY") "USD" else "JPY"
        repository.saveBusinessSettings("different-currency", version, current.copy(currency = otherCurrency, defaultHourlyMinor = java.math.BigInteger.valueOf(100)))
        val other = repository.createJob("Synthetic other currency")
        val report = repository.report(selection().copy(jobIds = listOf(job, other))).report
        assertEquals(2, report.totals.map { it.currency }.distinct().size)
        assertNull(report.totals.first { it.jobId == job }.amountMinor)
        assertEquals(BigInteger.ZERO, report.totals.first { it.jobId == other }.amountMinor)
    }
    @Test fun invalidRangeTimezoneJobsAndHugeSelectionsAreRejectedWithoutWrites() {
        val before = version
        for (value in listOf(selection("2026-02-30"), selection().copy(timezone = "Unknown/Zone"), selection().copy(jobIds = emptyList()),
            selection().copy(jobIds = listOf(job, job)), selection("2026-12-01", "2026-11-01"), selection("2000-01-01", "2026-11-01"))) {
            try { repository.report(value); fail("Invalid selection accepted") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(before, version)
    }
    @Test fun repeatedMidnightIncludesBothOccurrencesInItsCalendarDay() {
        session("2026-11-01T04:00:00.000Z", "2026-11-02T05:00:00.000Z")
        val report = repository.report(selection(zone = "America/Havana")).report
        assertEquals(at("2026-11-01T04:00:00.000Z"), report.days.single().start)
        assertEquals(BigInteger.valueOf(25L * 3600000), report.totals.single().durationMillis)
        assertEquals(BigInteger.valueOf(150000), report.totals.single().amountMinor)
        assertTrue(ReportCsv.work(report).contains("2026-11-01T00:00:00.000-04:00"))
    }
    @Test fun finalSupportedCalendarDateTerminatesAtTheFollowingExclusiveBoundary() {
        val day = ReportStore.days(selection("9999-12-31", zone = "UTC")).single()
        assertEquals(86400000L, day.end - day.start)
    }
    @Test fun skippedAndMidnightTransitionDaysDoNotCauseInfiniteLoopsOrDuplicateDuration() {
        val skipped = ReportStore.days(selection("2011-12-29", "2011-12-31", "Pacific/Apia"))
        assertEquals(3, skipped.size); assertEquals(0L, skipped[1].end - skipped[1].start)
        assertEquals(skipped[0].end, skipped[1].start); assertEquals(skipped[1].end, skipped[2].start)
        val midnight = ReportStore.days(selection("2015-10-18", zone = "America/Sao_Paulo")).single()
        assertEquals(23L * 3600000, midnight.end - midnight.start)
    }
}
