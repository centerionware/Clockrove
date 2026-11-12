package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import java.util.Date
import java.util.Locale

/** Calendar dates are local dates, never a fixed 168-hour approximation across DST. */
internal object WidgetTasksStore {
    data class Task(val id: String, val jobId: String, val jobName: String, val title: String, val due: String)
    data class Tasks(val today: String, val through: String, val items: List<Task>, val jobId: String?)
    private fun calendar(now: Long, timezone: String): GregorianCalendar {
        require(timezone in java.util.TimeZone.getAvailableIDs().toSet())
        return GregorianCalendar(TimeZone.getTimeZone(timezone), Locale.ROOT).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            timeInMillis = now
        }
    }
    private fun date(calendar: Calendar): String = String.format(Locale.ROOT, "%04d-%02d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
    fun localDate(now: Long, timezone: String): String = date(calendar(now, timezone))
    fun read(db: SQLiteDatabase, jobId: String?, now: Long, timezone: String): Tasks {
        val calendar = calendar(now, timezone)
        val today = date(calendar)
        calendar.add(Calendar.DAY_OF_MONTH, 6)
        val through = date(calendar)
        val filter = if (jobId == null) "" else " AND c.job_id=?"
        val args = if (jobId == null) arrayOf(through) else arrayOf(through, jobId)
        val items = db.rawQuery("SELECT c.id,c.job_id,j.name,c.title,c.due_date FROM kanban_cards c JOIN jobs j ON j.id=c.job_id WHERE j.archived=0 AND c.completed_ms IS NULL AND c.due_date IS NOT NULL AND c.due_date<=?$filter ORDER BY c.due_date,c.id", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Task(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))) }
        }
        return Tasks(today, through, items, jobId)
    }
}
