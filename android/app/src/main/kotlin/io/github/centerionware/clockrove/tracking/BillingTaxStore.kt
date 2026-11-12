package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase

/** Tax preferences are revisioned separately from existing currency/rate fields. */
internal object BillingTaxStore {
    fun install(db: SQLiteDatabase, at: Long) {
        db.execSQL("CREATE TABLE billing_tax_settings(id INTEGER PRIMARY KEY CHECK(id=1),default_tax_units INTEGER NOT NULL DEFAULT 0 CHECK(typeof(default_tax_units)='integer' AND default_tax_units BETWEEN 0 AND 1000000))")
        db.execSQL("INSERT INTO billing_tax_settings(id) VALUES(1)")
        db.execSQL("CREATE TABLE job_tax_rates(job_id TEXT PRIMARY KEY NOT NULL REFERENCES jobs(id),tax_units INTEGER CHECK(tax_units IS NULL OR (typeof(tax_units)='integer' AND tax_units BETWEEN 0 AND 1000000)))")
        snapshotDefault(db, at, "MIGRATION")
    }
    fun defaultUnits(db: SQLiteDatabase): Int = db.rawQuery("SELECT default_tax_units FROM billing_tax_settings WHERE id=1", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    fun jobUnits(db: SQLiteDatabase, jobId: String): Int? = db.rawQuery("SELECT tax_units FROM job_tax_rates WHERE job_id=?", arrayOf(jobId)).use {
        if (!it.moveToFirst() || it.isNull(0)) null else it.getInt(0)
    }
    fun saveDefault(db: SQLiteDatabase, units: Int, at: Long) {
        require(TaxRate.valid(units)) { "INVALID_TAX_RATE" }
        db.execSQL("UPDATE billing_tax_settings SET default_tax_units=? WHERE id=1", arrayOf<Any?>(units))
        snapshotDefault(db, at)
    }
    fun saveJob(db: SQLiteDatabase, jobId: String, units: Int?, at: Long) {
        require(units == null || TaxRate.valid(units)) { "INVALID_TAX_RATE" }
        db.execSQL("INSERT OR REPLACE INTO job_tax_rates(job_id,tax_units) VALUES(?,?)", arrayOf<Any?>(jobId, units))
        RevisionStore.record(db, "job_tax_rate", jobId, jobId, mapOf("job_id" to jobId, "tax_units" to units), at)
    }
    private fun snapshotDefault(db: SQLiteDatabase, at: Long, origin: String = "MUTATION") {
        RevisionStore.record(db, "billing_tax_settings", "1", null, mapOf("id" to 1, "default_tax_units" to defaultUnits(db)), at, origin = origin)
    }
}
