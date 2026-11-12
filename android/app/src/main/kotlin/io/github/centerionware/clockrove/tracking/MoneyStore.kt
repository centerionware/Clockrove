package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger

/** Exact minor units, parsed from canonical strings. Limits bound text size, not arithmetic precision. */
internal object MoneyMath {
    const val maxDigits = 1024
    private val canonical = Regex("0|[1-9][0-9]{0,1023}")
    private val legacyMax = BigInteger.valueOf(Long.MAX_VALUE)
    fun parse(value: String): BigInteger {
        require(value.length <= maxDigits && canonical.matches(value)) { "INVALID_MONEY" }
        return BigInteger(value)
    }
    fun valid(value: BigInteger?): Boolean = value == null || value.signum() >= 0 && value.toString().length <= maxDigits
    /** Only the legacy compatibility column is bounded; exact text is always authoritative. */
    fun legacy(value: BigInteger?): Long? = value?.takeIf { it.signum() >= 0 && it <= legacyMax }?.toLong()
    /** Released receipts hashed Long JSON values. Never rely on org.json wrapping BigInteger. */
    fun fingerprint(value: BigInteger?): Any? = if (value == null) null else legacy(value) ?: value.toString()
}

/** Canonical TEXT sidecars preserve released tables/foreign keys and never put wide money in INTEGER affinity. */
internal object MoneyStore {
    private const val amount = "minor_text TEXT CHECK(minor_text IS NULL OR (typeof(minor_text)='text' AND length(minor_text) BETWEEN 1 AND 1024 AND (minor_text='0' OR (substr(minor_text,1,1) BETWEEN '1' AND '9' AND minor_text NOT GLOB '*[^0-9]*'))))"
    private val tables = setOf("settings_money", "job_money", "card_money", "template_money")
    fun install(db: SQLiteDatabase, at: Long) {
        db.execSQL("CREATE TABLE settings_money(id INTEGER PRIMARY KEY CHECK(id=1),$amount)")
        db.execSQL("CREATE TABLE job_money(id TEXT PRIMARY KEY NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,$amount)")
        db.execSQL("CREATE TABLE card_money(id TEXT PRIMARY KEY NOT NULL REFERENCES kanban_cards(id) ON DELETE CASCADE,$amount)")
        db.execSQL("CREATE TABLE template_money(id TEXT PRIMARY KEY NOT NULL REFERENCES task_templates(id) ON DELETE CASCADE,$amount)")
        listOf(Triple("settings_money", "app_settings", "default_hourly_minor"), Triple("job_money", "jobs", "hourly_rate_minor"),
            Triple("card_money", "kanban_cards", "price_minor"), Triple("template_money", "task_templates", "price_minor")).forEach { (table, source, column) ->
            db.rawQuery("SELECT id,CAST($column AS TEXT)${if (source == "kanban_cards") ",job_id" else ""} FROM $source", null).use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0)
                    val value = if (rows.isNull(1)) null else MoneyMath.parse(rows.getString(1))
                    val jobId = when (table) { "job_money" -> id; "card_money" -> rows.getString(2); else -> null }
                    save(db, table, id, jobId, value, at, "MIGRATION")
                }
            }
        }
    }
    fun read(db: SQLiteDatabase, table: String, id: String, legacy: String?): BigInteger? {
        require(table in tables)
        val text = db.rawQuery("SELECT minor_text FROM $table WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) legacy else if (it.isNull(0)) null else it.getString(0)
        }
        return text?.let(MoneyMath::parse)
    }
    fun save(db: SQLiteDatabase, table: String, id: String, jobId: String?, value: BigInteger?, at: Long, origin: String = "MANUAL") {
        require(table in tables && MoneyMath.valid(value)) { "INVALID_MONEY" }
        db.execSQL("INSERT OR REPLACE INTO $table(id,minor_text) VALUES(?,?)", arrayOf<Any?>(id, value?.toString()))
        RevisionStore.record(db, table, id, jobId, mapOf("id" to id, "minor_text" to value?.toString(), "job_id" to jobId), at, origin = origin)
    }
    fun remove(db: SQLiteDatabase, table: String, id: String, jobId: String?, at: Long) {
        require(table in tables)
        RevisionStore.record(db, table, id, jobId, mapOf("id" to id, "job_id" to jobId), at, deleted = true)
    }
}
