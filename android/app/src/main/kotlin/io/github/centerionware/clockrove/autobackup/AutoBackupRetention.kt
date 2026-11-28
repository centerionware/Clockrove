package io.github.centerionware.clockrove.autobackup

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the newest backup in every bucket named by the owner:
 * each of the last 7 days, each of the last 4 weeks, each of the last
 * 12 months, and every represented year. A file can satisfy several buckets.
 */
internal object AutoBackupRetention {
    const val FILE_PREFIX = "Clockrove-auto-"
    const val FILE_SUFFIX = ".clockrove"
    const val FILE_PARTIAL_SUFFIX = ".partial"

    data class Entry(
        val documentId: String,
        val name: String,
        val modifiedMillis: Long,
        val partial: Boolean = false,
    )

    fun isManagedName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) &&
            (name.endsWith(FILE_SUFFIX) || name.endsWith(FILE_SUFFIX + FILE_PARTIAL_SUFFIX))

    fun isFinalName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    fun retainedDocumentIds(
        entries: List<Entry>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Set<String> {
        val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val dated = entries.mapNotNull { entry ->
            val millis = entryMillis(entry, timeZone) ?: return@mapNotNull null
            val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
            Dated(entry, calendar)
        }
        val retained = linkedSetOf<String>()
        val undated = entries.filter { entry -> dated.none { it.entry === entry } }
        undated.forEach { retained.add(it.documentId) }

        fun newest(predicate: (Dated) -> Boolean) {
            dated.asSequence()
                .filter(predicate)
                .sortedByDescending { it.calendar.timeInMillis }
                .firstOrNull()
                ?.let { retained.add(it.entry.documentId) }
        }

        for (daysAgo in 0..6) {
            val day = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -daysAgo)
            }
            val key = dayKey(day)
            newest { dayKey(it.calendar) == key }
        }
        for (weeksAgo in 0..3) {
            val week = weekStart(now.clone() as Calendar)
            week.add(Calendar.DAY_OF_YEAR, -7 * weeksAgo)
            val key = week.timeInMillis
            newest { weekStart(it.calendar.clone() as Calendar).timeInMillis == key }
        }
        for (monthsAgo in 0..11) {
            val month = (now.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -monthsAgo)
            }
            val key = monthKey(month)
            newest { monthKey(it.calendar) == key }
        }
        val years = dated.map { it.calendar.get(Calendar.YEAR) }.toSet()
        for (year in years) {
            newest { it.calendar.get(Calendar.YEAR) == year }
        }
        return retained
    }

    private data class Dated(val entry: Entry, val calendar: Calendar)

    private fun entryMillis(entry: Entry, timeZone: TimeZone): Long? {
        val body = entry.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        val named = try {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
                isLenient = false
                this.timeZone = timeZone
            }.parse(body)?.time
        } catch (_: Exception) {
            null
        }
        return named ?: entry.modifiedMillis.takeIf { it > 0L }
    }

    private fun dayKey(calendar: Calendar): Int =
        calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)

    private fun monthKey(calendar: Calendar): Int =
        calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)

    private fun weekStart(source: Calendar): Calendar {
        val day = source.clone() as Calendar
        day.set(Calendar.HOUR_OF_DAY, 0)
        day.set(Calendar.MINUTE, 0)
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)
        val offset = (day.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        day.add(Calendar.DAY_OF_YEAR, -offset)
        return day
    }
}
