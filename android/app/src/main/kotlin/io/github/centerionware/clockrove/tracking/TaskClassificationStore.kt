package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase

/** Accounting classification is separate from price and from incurred costs. */
internal object TaskClassification {
    const val LABOR = "LABOR"
    const val SUPPLIES = "SUPPLIES"
    const val UNCLASSIFIED = "UNCLASSIFIED"
    private val values = setOf(LABOR, SUPPLIES, UNCLASSIFIED)

    fun valid(value: String): Boolean = value in values
}

/** Adds explicit historical review state without assuming old charges were labor. */
internal object TaskClassificationStore {
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == column) found = true
            found
        }

    fun install(db: SQLiteDatabase, at: Long) {
        val cardChanged = !hasColumn(db, "kanban_cards", "charge_type")
        if (cardChanged) {
            db.execSQL("ALTER TABLE kanban_cards ADD COLUMN charge_type TEXT NOT NULL DEFAULT 'UNCLASSIFIED' CHECK(charge_type IN('LABOR','SUPPLIES','UNCLASSIFIED'))")
            val jobs = db.rawQuery("SELECT DISTINCT job_id FROM kanban_cards ORDER BY job_id", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            jobs.forEach { jobId ->
                RevisionStore.snapshotRows(db, "kanban_cards", "card", BoardStore.cardColumns, at,
                    "job_id", "job_id=?", arrayOf(jobId), origin = "MIGRATION")
            }
        }

        val templateChanged = !hasColumn(db, "task_templates", "charge_type")
        if (templateChanged) {
            db.execSQL("ALTER TABLE task_templates ADD COLUMN charge_type TEXT NOT NULL DEFAULT 'UNCLASSIFIED' CHECK(charge_type IN('LABOR','SUPPLIES','UNCLASSIFIED'))")
            RevisionStore.snapshotRows(db, "task_templates", "template", BoardStore.templateColumns, at,
                "source_job_id", origin = "MIGRATION")
        }
    }
}
