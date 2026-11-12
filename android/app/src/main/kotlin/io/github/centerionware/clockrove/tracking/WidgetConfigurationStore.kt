package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/** Device-local launcher bindings. Never import widget IDs onto another device. */
internal object WidgetConfigurationStore {
    data class Configuration(val widgetId: Int, val jobId: String?, val token: String?)
    fun install(db: SQLiteDatabase) {
        // No job FK: an unavailable default must remain distinguishable from an
        // intentionally selected picker. Store no retained job name or contacts.
        db.execSQL("CREATE TABLE widget_configurations(widget_id INTEGER PRIMARY KEY CHECK(widget_id>0),job_id TEXT,token TEXT NOT NULL UNIQUE)")
    }
    fun read(db: SQLiteDatabase, widgetId: Int): Configuration {
        require(widgetId > 0) { "INVALID_WIDGET" }
        return db.rawQuery("SELECT job_id,token FROM widget_configurations WHERE widget_id=?", arrayOf(widgetId.toString())).use {
            if (it.moveToFirst()) Configuration(widgetId, if (it.isNull(0)) null else it.getString(0), it.getString(1))
            else Configuration(widgetId, null, null)
        }
    }
    fun save(db: SQLiteDatabase, widgetId: Int, jobId: String?): String? {
        if (widgetId <= 0) return "INVALID_WIDGET"
        if (jobId != null && !db.rawQuery("SELECT id FROM jobs WHERE id=? AND archived=0", arrayOf(jobId)).use { it.moveToFirst() }) return "JOB_UNAVAILABLE"
        db.execSQL("INSERT OR REPLACE INTO widget_configurations(widget_id,job_id,token) VALUES(?,?,?)", arrayOf<Any?>(widgetId, jobId, UUID.randomUUID().toString()))
        return null
    }
    fun remove(db: SQLiteDatabase, widgetIds: List<Int>): Boolean {
        require(widgetIds.size <= 1000 && widgetIds.all { it > 0 }) { "INVALID_WIDGET" }
        var changed = false
        widgetIds.distinct().forEach { if (db.delete("widget_configurations", "widget_id=?", arrayOf(it.toString())) > 0) changed = true }
        return changed
    }
}
