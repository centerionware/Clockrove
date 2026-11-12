package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.BigInteger

/** Exact task units. The released price_minor field is the single unit price. */
internal object TaskQuantityStore {
    const val one = 10000L
    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE task_quantities(card_id TEXT PRIMARY KEY NOT NULL REFERENCES kanban_cards(id) ON DELETE CASCADE,quantity_units INTEGER NOT NULL DEFAULT 10000 CHECK(typeof(quantity_units)='integer' AND quantity_units>0))")
        db.execSQL("CREATE TABLE template_quantities(template_id TEXT PRIMARY KEY NOT NULL REFERENCES task_templates(id) ON DELETE CASCADE,quantity_units INTEGER NOT NULL DEFAULT 10000 CHECK(typeof(quantity_units)='integer' AND quantity_units>0))")
    }
    fun amount(priceMinor: BigInteger, quantityUnits: Long): BigInteger {
        require(priceMinor.signum() >= 0 && quantityUnits > 0) { "INVALID_TASK_QUANTITY" }
        return priceMinor.multiply(BigInteger.valueOf(quantityUnits))
            .add(BigInteger.valueOf(5000)).divide(BigInteger.valueOf(one))
    }
    fun format(quantityUnits: Long): String {
        require(quantityUnits > 0) { "INVALID_TASK_QUANTITY" }
        return BigDecimal.valueOf(quantityUnits, 4).stripTrailingZeros().toPlainString()
    }
    fun save(db: SQLiteDatabase, cardId: String, jobId: String, templateId: String, units: Long, at: Long) {
        require(units > 0) { "INVALID_TASK_QUANTITY" }
        db.execSQL("INSERT OR REPLACE INTO task_quantities(card_id,quantity_units) VALUES(?,?)", arrayOf<Any?>(cardId, units))
        db.execSQL("INSERT OR REPLACE INTO template_quantities(template_id,quantity_units) VALUES(?,?)", arrayOf<Any?>(templateId, units))
        RevisionStore.record(db, "task_quantity", cardId, jobId, mapOf("card_id" to cardId, "job_id" to jobId, "quantity_units" to units), at)
        RevisionStore.record(db, "template_quantity", templateId, null, mapOf("template_id" to templateId, "quantity_units" to units), at)
    }
    fun removeCard(db: SQLiteDatabase, cardId: String, jobId: String, at: Long) {
        RevisionStore.record(db, "task_quantity", cardId, jobId, mapOf("card_id" to cardId, "job_id" to jobId), at, deleted = true)
    }
    fun removeTemplate(db: SQLiteDatabase, templateId: String, at: Long) {
        RevisionStore.record(db, "template_quantity", templateId, null, mapOf("template_id" to templateId), at, deleted = true)
    }
}
