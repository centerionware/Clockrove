package io.github.centerionware.clockrove.autobackup

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupRetentionTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun name(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0) =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = utc
        }.format(
            java.util.Calendar.getInstance(utc).apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.time,
        )

    private fun entry(id: String, year: Int, month: Int, day: Int, hour: Int = 12) =
        AutoBackupRetention.Entry(
            documentId = id,
            name = "${AutoBackupRetention.FILE_PREFIX}${name(year, month, day, hour)}" +
                AutoBackupRetention.FILE_SUFFIX,
            modifiedMillis = 0L,
        )

    private fun now(): Long = java.util.Calendar.getInstance(utc).apply {
        clear()
        set(2026, 10, 27, 12, 0, 0)
    }.timeInMillis

    @Test
    fun `keeps the newest backup in every requested bucket`() {
        val entries = listOf(
            entry("today", 2026, 11, 27),
            entry("today-older", 2026, 11, 27, hour = 11),
            entry("yesterday", 2026, 11, 26),
            entry("eight-days-ago", 2026, 11, 19),
            entry("same-week-older", 2026, 11, 21),
            entry("previous-week", 2026, 11, 12),
            entry("previous-month", 2026, 10, 15),
            entry("year-start", 2026, 1, 3),
            entry("last-year", 2025, 12, 31),
            entry("older-year", 2019, 6, 1),
        )

        val retained = AutoBackupRetention.retainedDocumentIds(entries, now(), utc)

        assertTrue(retained.contains("today"))
        assertFalse(retained.contains("today-older"))
        assertTrue(retained.contains("yesterday"))
        assertFalse(retained.contains("eight-days-ago"))
        assertTrue(retained.contains("same-week-older"))
        assertTrue(retained.contains("previous-week"))
        assertTrue(retained.contains("previous-month"))
        assertTrue(retained.contains("year-start"))
        assertTrue(retained.contains("last-year"))
        assertTrue(retained.contains("older-year"))
    }

    @Test
    fun `recognizes only automatic backup names`() {
        assertTrue(
            AutoBackupRetention.isManagedName(
                "Clockrove-auto-20261127-120000.clockrove",
            ),
        )
        assertTrue(
            AutoBackupRetention.isManagedName(
                "Clockrove-auto-20261127-120000.clockrove.partial",
            ),
        )
        assertTrue(
            AutoBackupRetention.isFinalName(
                "Clockrove-auto-20261127-120000.clockrove",
            ),
        )
        assertFalse(
            AutoBackupRetention.isFinalName(
                "Clockrove-auto-20261127-120000.clockrove.partial",
            ),
        )
        assertFalse(AutoBackupRetention.isManagedName("Clockrove-backup.clockrove"))
        assertFalse(AutoBackupRetention.isManagedName("manual.clockrove"))
    }
}
