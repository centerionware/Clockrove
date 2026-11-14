package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger

/** Versioned global defaults and nullable per-job overrides for invoice generation. */
internal object InvoiceBillingPolicyStore {
    data class Defaults(val hourlyMode: String, val minimumHours: String, val taskMinimumMinor: BigInteger)
    data class Overrides(val hourlyMode: String?, val minimumHours: String?, val taskMinimumMinor: BigInteger?)
    data class Effective(val hourlyMode: String, val minimumHours: String, val taskMinimumMinor: BigInteger)

    private val zero = BigInteger.ZERO

    fun install(db: SQLiteDatabase, at: Long) {
        db.execSQL("CREATE TABLE invoice_billing_defaults(id INTEGER PRIMARY KEY CHECK(id=1),hourly_mode TEXT NOT NULL CHECK(hourly_mode IN('MINIMUM_THEN_EXACT','ROUND_TOTAL_UP')),minimum_hours TEXT NOT NULL,task_minimum_minor TEXT NOT NULL)")
        db.execSQL("INSERT INTO invoice_billing_defaults(id,hourly_mode,minimum_hours,task_minimum_minor) VALUES(1,'MINIMUM_THEN_EXACT','0','0')")
        db.execSQL("CREATE TABLE job_invoice_billing_policy(job_id TEXT PRIMARY KEY NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,hourly_mode TEXT CHECK(hourly_mode IS NULL OR hourly_mode IN('MINIMUM_THEN_EXACT','ROUND_TOTAL_UP')),minimum_hours TEXT,task_minimum_minor TEXT)")
        snapshotDefaults(db, at, "MIGRATION")
    }

    fun defaults(db: SQLiteDatabase): Defaults = db.rawQuery("SELECT hourly_mode,minimum_hours,task_minimum_minor FROM invoice_billing_defaults WHERE id=1", null).use {
        check(it.moveToFirst())
        val mode = it.getString(0)
        val hours = InvoiceBillingPolicy.canonicalHours(it.getString(1))
        val fee = MoneyMath.parse(it.getString(2))
        check(InvoiceBillingPolicy.validMode(mode))
        Defaults(mode, hours, fee)
    }

    fun overrides(db: SQLiteDatabase, jobId: String): Overrides = db.rawQuery("SELECT hourly_mode,minimum_hours,task_minimum_minor FROM job_invoice_billing_policy WHERE job_id=?", arrayOf(jobId)).use {
        if (!it.moveToFirst()) Overrides(null, null, null) else Overrides(
            if (it.isNull(0)) null else it.getString(0),
            if (it.isNull(1)) null else InvoiceBillingPolicy.canonicalHours(it.getString(1)),
            if (it.isNull(2)) null else MoneyMath.parse(it.getString(2)))
    }

    fun effective(db: SQLiteDatabase, jobId: String, currency: String, defaultCurrency: String): Effective {
        val base = defaults(db)
        val job = overrides(db, jobId)
        return Effective(job.hourlyMode ?: base.hourlyMode, job.minimumHours ?: base.minimumHours,
            job.taskMinimumMinor ?: base.taskMinimumMinor.takeIf { currency == defaultCurrency } ?: zero)
    }

    fun saveDefaults(db: SQLiteDatabase, value: Defaults, at: Long) {
        require(InvoiceBillingPolicy.validMode(value.hourlyMode) && InvoiceBillingPolicy.validHours(value.minimumHours) && MoneyMath.valid(value.taskMinimumMinor))
        val hours = InvoiceBillingPolicy.canonicalHours(value.minimumHours)
        db.execSQL("UPDATE invoice_billing_defaults SET hourly_mode=?,minimum_hours=?,task_minimum_minor=? WHERE id=1",
            arrayOf<Any?>(value.hourlyMode, hours, value.taskMinimumMinor.toString()))
        snapshotDefaults(db, at)
    }

    fun saveJob(db: SQLiteDatabase, jobId: String, value: Overrides, at: Long) {
        require(value.hourlyMode == null || InvoiceBillingPolicy.validMode(value.hourlyMode))
        require(value.minimumHours == null || InvoiceBillingPolicy.validHours(value.minimumHours))
        require(MoneyMath.valid(value.taskMinimumMinor))
        val hours = value.minimumHours?.let(InvoiceBillingPolicy::canonicalHours)
        db.execSQL("INSERT OR REPLACE INTO job_invoice_billing_policy(job_id,hourly_mode,minimum_hours,task_minimum_minor) VALUES(?,?,?,?)",
            arrayOf<Any?>(jobId, value.hourlyMode, hours, value.taskMinimumMinor?.toString()))
        RevisionStore.record(db, "job_invoice_billing_policy", jobId, jobId,
            mapOf("job_id" to jobId, "hourly_mode" to value.hourlyMode, "minimum_hours" to hours, "task_minimum_minor" to value.taskMinimumMinor?.toString()), at)
    }

    private fun snapshotDefaults(db: SQLiteDatabase, at: Long, origin: String = "MUTATION") {
        val value = defaults(db)
        RevisionStore.record(db, "invoice_billing_defaults", "1", null,
            mapOf("id" to 1, "hourly_mode" to value.hourlyMode, "minimum_hours" to value.minimumHours,
                "task_minimum_minor" to value.taskMinimumMinor.toString()), at, origin = origin)
    }
}
