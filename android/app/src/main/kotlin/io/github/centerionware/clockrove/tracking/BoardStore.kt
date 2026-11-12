package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.util.Currency
import java.math.BigInteger
import java.util.UUID

/** SQL helpers called only inside the native repository's serialized boundary. */
internal object BoardStore {
    data class Column(val id: String, val jobId: String, val name: String, val position: Int, val completed: Boolean)
    data class Card(val id: String, val jobId: String, val columnId: String, val title: String, val description: String,
        val dueDate: String?, val position: Int, val completedMillis: Long?, val billable: Boolean,
        val priceMinor: BigInteger?, val currency: String, val templateId: String?, val quantityUnits: Long = TaskQuantityStore.one)
    data class Draft(val id: String, val jobId: String, val columnId: String, val title: String, val description: String = "",
        val dueDate: String? = null, val billable: Boolean = true, val priceMinor: BigInteger? = null, val currency: String, val quantityUnits: Long = TaskQuantityStore.one)
    data class Template(val id: String, val title: String, val description: String, val priceMinor: BigInteger?, val currency: String, val sourceJobName: String?, val quantityUnits: Long = TaskQuantityStore.one)
    data class Board(val jobId: String, val name: String, val currency: String, val archived: Boolean, val columns: List<Column>, val cards: List<Card>)
    val cardColumns = listOf("id", "job_id", "column_id", "title", "description", "due_date", "position", "completed_ms", "billable", "price_minor", "currency", "template_id")
    val templateColumns = listOf("id", "title", "description", "price_minor", "currency", "source_job_id", "source_card_id")

    fun read(db: SQLiteDatabase, jobId: String): Board = db.rawQuery("SELECT name,currency,archived FROM jobs WHERE id=?", arrayOf(jobId)).use {
        require(it.moveToFirst()) { "JOB_UNAVAILABLE" }
        Board(jobId, it.getString(0), it.getString(1), it.getInt(2) != 0, columns(db, jobId), cards(db, jobId))
    }
    fun columns(db: SQLiteDatabase, jobId: String): List<Column> = db.rawQuery("SELECT id,job_id,name,position,completed FROM kanban_columns WHERE job_id=? ORDER BY position,id", arrayOf(jobId)).use { rows ->
        buildList { while (rows.moveToNext()) add(Column(rows.getString(0), rows.getString(1), rows.getString(2), rows.getInt(3), rows.getInt(4) != 0)) }
    }
    fun cards(db: SQLiteDatabase, jobId: String, id: String? = null): List<Card> = db.rawQuery("SELECT ${cardColumns.joinToString(",") { if (it == "price_minor") "CASE WHEN m.id IS NOT NULL THEN m.minor_text ELSE CAST(c.price_minor AS TEXT) END" else "c.$it" }},COALESCE(q.quantity_units,10000) FROM kanban_cards c LEFT JOIN task_quantities q ON q.card_id=c.id LEFT JOIN card_money m ON m.id=c.id WHERE c.job_id=?${if (id == null) "" else " AND c.id=?"} ORDER BY c.column_id,c.position,c.id", if (id == null) arrayOf(jobId) else arrayOf(jobId, id)).use { rows ->
        buildList { while (rows.moveToNext()) add(Card(rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
            if (rows.isNull(5)) null else rows.getString(5), rows.getInt(6), if (rows.isNull(7)) null else rows.getLong(7), rows.getInt(8) != 0,
            if (rows.isNull(9)) null else MoneyMath.parse(rows.getString(9)), rows.getString(10), if (rows.isNull(11)) null else rows.getString(11), rows.getLong(12))) }
    }
    fun templates(db: SQLiteDatabase, search: String = ""): List<Template> = db.rawQuery(
        "SELECT t.id,t.title,t.description,CASE WHEN m.id IS NOT NULL THEN m.minor_text ELSE CAST(t.price_minor AS TEXT) END,t.currency,j.name,COALESCE(q.quantity_units,10000) FROM task_templates t LEFT JOIN jobs j ON j.id=t.source_job_id LEFT JOIN template_quantities q ON q.template_id=t.id LEFT JOIN template_money m ON m.id=t.id WHERE t.title LIKE ? OR t.description LIKE ? ORDER BY t.title,t.id",
        arrayOf("%$search%", "%$search%")).use { rows -> buildList { while (rows.moveToNext()) add(Template(rows.getString(0), rows.getString(1), rows.getString(2),
            if (rows.isNull(3)) null else MoneyMath.parse(rows.getString(3)), rows.getString(4), if (rows.isNull(5)) null else rows.getString(5), rows.getLong(6))) } }
    private fun available(db: SQLiteDatabase, jobId: String) = db.rawQuery("SELECT id FROM jobs WHERE id=? AND archived=0", arrayOf(jobId)).use { it.moveToFirst() }
    private fun snapshotCards(db: SQLiteDatabase, jobId: String, at: Long) = RevisionStore.snapshotRows(db, "kanban_cards", "card", cardColumns, at, "job_id", "job_id=?", arrayOf(jobId))
    private fun snapshotColumns(db: SQLiteDatabase, jobId: String, at: Long) = RevisionStore.snapshotRows(db, "kanban_columns", "column", BusinessSchema.columnColumns, at, "job_id", "job_id=?", arrayOf(jobId))
    private fun ordered(db: SQLiteDatabase, columnId: String): List<String> = db.rawQuery("SELECT id FROM kanban_cards WHERE column_id=? ORDER BY position,id", arrayOf(columnId)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
    private fun positions(db: SQLiteDatabase, ids: List<String>) { ids.forEachIndexed { index, id -> db.execSQL("UPDATE kanban_cards SET position=? WHERE id=?", arrayOf<Any?>(index, id)) } }
    private fun completed(db: SQLiteDatabase, card: Card, destination: Column, at: Long) {
        db.execSQL("UPDATE kanban_cards SET column_id=?,completed_ms=? WHERE id=?",
            arrayOf<Any?>(destination.id, if (destination.completed) card.completedMillis ?: at else null, card.id))
    }
    fun validDate(value: String?): Boolean {
        if (value == null) return true
        val parts = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(value)?.groupValues ?: return false
        val year = parts[1].toInt(); val month = parts[2].toInt(); val day = parts[3].toInt()
        if (year !in 1..9999 || month !in 1..12) return false
        val days = intArrayOf(31, if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day in 1..days[month - 1]
    }
    fun saveColumn(db: SQLiteDatabase, id: String, jobId: String, name: String, isCompleted: Boolean, at: Long): String? {
        if (!available(db, jobId)) return "JOB_UNAVAILABLE"
        if (id.isBlank() || name.isBlank() || name.length > 120) return "INVALID_COLUMN"
        val previousJob = db.rawQuery("SELECT job_id FROM kanban_columns WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
        if (previousJob != null && previousJob != jobId) return "COLUMN_JOB_IMMUTABLE"
        if (previousJob == null) db.execSQL("INSERT INTO kanban_columns VALUES(?,?,?,?,?)", arrayOf<Any?>(id, jobId, name.trim(), columns(db, jobId).size, if (isCompleted) 1 else 0))
        else db.execSQL("UPDATE kanban_columns SET name=?,completed=? WHERE id=?", arrayOf<Any?>(name.trim(), if (isCompleted) 1 else 0, id))
        val destination = columns(db, jobId).first { it.id == id }
        cards(db, jobId).filter { it.columnId == id }.forEach { completed(db, it, destination, at) }
        snapshotColumns(db, jobId, at); snapshotCards(db, jobId, at)
        return null
    }
    fun reorderColumns(db: SQLiteDatabase, jobId: String, ids: List<String>, at: Long): String? {
        if (!available(db, jobId)) return "JOB_UNAVAILABLE"
        val current = columns(db, jobId).map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) return "INVALID_ORDER"
        ids.forEachIndexed { index, id -> db.execSQL("UPDATE kanban_columns SET position=? WHERE id=?", arrayOf<Any?>(index, id)) }
        snapshotColumns(db, jobId, at)
        return null
    }
    fun removeColumn(db: SQLiteDatabase, jobId: String, id: String, destinationId: String?, at: Long): String? {
        if (!available(db, jobId)) return "JOB_UNAVAILABLE"
        val columns = columns(db, jobId)
        if (columns.none { it.id == id }) return "COLUMN_UNAVAILABLE"
        if (columns.size == 1) return "LAST_COLUMN"
        val moving = cards(db, jobId).filter { it.columnId == id }
        val destination = columns.firstOrNull { it.id == destinationId && it.id != id }
        if (moving.isNotEmpty() && destination == null) return "DESTINATION_REQUIRED"
        if (moving.isNotEmpty()) {
            val target = requireNotNull(destination)
            val targetIds = ordered(db, target.id)
            moving.forEach { completed(db, it, target, at) }
            positions(db, targetIds + moving.map { it.id })
        }
        db.execSQL("DELETE FROM kanban_columns WHERE id=?", arrayOf(id))
        columns.filter { it.id != id }.forEachIndexed { index, column -> db.execSQL("UPDATE kanban_columns SET position=? WHERE id=?", arrayOf<Any?>(index, column.id)) }
        RevisionStore.record(db, "column", id, jobId, mapOf("id" to id, "job_id" to jobId), at, deleted = true)
        snapshotColumns(db, jobId, at); snapshotCards(db, jobId, at)
        return null
    }
    fun saveCard(db: SQLiteDatabase, value: Draft, at: Long): String? {
        if (value.quantityUnits <= 0) return "INVALID_TASK_QUANTITY"
        if (!available(db, value.jobId)) return "JOB_UNAVAILABLE"
        if (value.id.isBlank() || value.title.isBlank() || value.title.length > 240 || value.description.length > 20000 || !validDate(value.dueDate) ||
            !MoneyMath.valid(value.priceMinor)) return "INVALID_TASK"
        val currency = db.rawQuery("SELECT currency FROM jobs WHERE id=?", arrayOf(value.jobId)).use { it.moveToFirst(); it.getString(0) }
        if (value.currency != currency || try { Currency.getInstance(value.currency).defaultFractionDigits < 0 } catch (_: IllegalArgumentException) { true }) return "CURRENCY_MISMATCH"
        val destination = columns(db, value.jobId).firstOrNull { it.id == value.columnId } ?: return "COLUMN_UNAVAILABLE"
        val previousJob = db.rawQuery("SELECT job_id FROM kanban_cards WHERE id=?", arrayOf(value.id)).use { if (it.moveToFirst()) it.getString(0) else null }
        if (previousJob != null && previousJob != value.jobId) return "TASK_JOB_IMMUTABLE"
        val previous = cards(db, value.jobId, value.id).firstOrNull()
        val templateId = previous?.templateId ?: UUID.randomUUID().toString()
        val position = if (previous?.columnId == destination.id) previous.position else db.rawQuery("SELECT COUNT(*) FROM kanban_cards WHERE column_id=?", arrayOf(destination.id)).use { it.moveToFirst(); it.getInt(0) }
        val completion = if (destination.completed) previous?.completedMillis ?: at else null
        if (previous?.templateId == null) db.execSQL("INSERT INTO task_templates(id,title,description,price_minor,currency,source_job_id,source_card_id) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(templateId, value.title.trim(), value.description, MoneyMath.legacy(value.priceMinor), value.currency, value.jobId, value.id))
        else db.execSQL("UPDATE task_templates SET title=?,description=?,price_minor=?,currency=?,source_job_id=?,source_card_id=? WHERE id=?",
            arrayOf<Any?>(value.title.trim(), value.description, MoneyMath.legacy(value.priceMinor), value.currency, value.jobId, value.id, templateId))
        // UPDATE preserves references; REPLACE of a card could violate future dependent rows.
        if (previous == null) db.execSQL("INSERT INTO kanban_cards VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(value.id, value.jobId, value.columnId,
            value.title.trim(), value.description, value.dueDate, position, completion, if (value.billable) 1 else 0, MoneyMath.legacy(value.priceMinor), value.currency, templateId))
        else db.execSQL("UPDATE kanban_cards SET column_id=?,title=?,description=?,due_date=?,position=?,completed_ms=?,billable=?,price_minor=?,currency=?,template_id=? WHERE id=?",
            arrayOf<Any?>(value.columnId, value.title.trim(), value.description, value.dueDate, position, completion, if (value.billable) 1 else 0, MoneyMath.legacy(value.priceMinor), value.currency, templateId, value.id))
        TaskQuantityStore.save(db, value.id, value.jobId, templateId, value.quantityUnits, at)
        MoneyStore.save(db, "card_money", value.id, value.jobId, value.priceMinor, at)
        MoneyStore.save(db, "template_money", templateId, null, value.priceMinor, at)
        previous?.takeIf { it.columnId != value.columnId }?.let { positions(db, ordered(db, it.columnId)) }
        RevisionStore.snapshotRows(db, "task_templates", "template", templateColumns, at, where = "id=?", args = arrayOf(templateId))
        // Appending or editing within one column changes only this card. A
        // column move also renumbers peers, whose revisions must be recorded.
        if (previous == null || previous.columnId == destination.id) RevisionStore.snapshotRows(db, "kanban_cards", "card", cardColumns, at, "job_id", "id=?", arrayOf(value.id))
        else snapshotCards(db, value.jobId, at)
        return null
    }
    fun moveCard(db: SQLiteDatabase, jobId: String, id: String, destinationId: String, position: Int, at: Long): String? {
        if (!available(db, jobId)) return "JOB_UNAVAILABLE"
        val card = cards(db, jobId).firstOrNull { it.id == id } ?: return "TASK_UNAVAILABLE"
        val destination = columns(db, jobId).firstOrNull { it.id == destinationId } ?: return "COLUMN_UNAVAILABLE"
        val target = ordered(db, destinationId).filter { it != id }.toMutableList()
        if (position !in 0..target.size) return "INVALID_POSITION"
        target.add(position, id)
        completed(db, card, destination, at); positions(db, target)
        if (card.columnId != destinationId) positions(db, ordered(db, card.columnId))
        snapshotCards(db, jobId, at)
        return null
    }
    fun removeCard(db: SQLiteDatabase, jobId: String, id: String, at: Long): String? {
        if (!available(db, jobId)) return "JOB_UNAVAILABLE"
        val card = cards(db, jobId).firstOrNull { it.id == id } ?: return "TASK_UNAVAILABLE"
        db.execSQL("DELETE FROM kanban_cards WHERE id=?", arrayOf(id))
        positions(db, ordered(db, card.columnId))
        RevisionStore.record(db, "card", id, jobId, mapOf("id" to id, "job_id" to jobId), at, deleted = true)
        TaskQuantityStore.removeCard(db, id, jobId, at)
        MoneyStore.remove(db, "card_money", id, jobId, at)
        snapshotCards(db, jobId, at)
        return null
    }
    fun copyTemplate(db: SQLiteDatabase, templateId: String, id: String, jobId: String, columnId: String, copyPrice: Boolean, at: Long): String? {
        if (db.rawQuery("SELECT id FROM kanban_cards WHERE id=?", arrayOf(id)).use { it.moveToFirst() }) return "TASK_ALREADY_EXISTS"
        val template = templates(db).firstOrNull { it.id == templateId } ?: return "TEMPLATE_UNAVAILABLE"
        val currency = db.rawQuery("SELECT currency FROM jobs WHERE id=? AND archived=0", arrayOf(jobId)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return "JOB_UNAVAILABLE"
        return saveCard(db, Draft(id, jobId, columnId, template.title, template.description, priceMinor = if (copyPrice) template.priceMinor else null,
            currency = if (copyPrice && template.priceMinor != null) template.currency else currency, quantityUnits = template.quantityUnits), at)
    }
    fun removeTemplate(db: SQLiteDatabase, id: String, at: Long): String? {
        if (templates(db).none { it.id == id }) return "TEMPLATE_UNAVAILABLE"
        val jobs = db.rawQuery("SELECT DISTINCT job_id FROM kanban_cards WHERE template_id=?", arrayOf(id)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        db.execSQL("UPDATE kanban_cards SET template_id=NULL WHERE template_id=?", arrayOf(id))
        db.execSQL("DELETE FROM task_templates WHERE id=?", arrayOf(id))
        TaskQuantityStore.removeTemplate(db, id, at)
        MoneyStore.remove(db, "template_money", id, null, at)
        RevisionStore.record(db, "template", id, null, mapOf("id" to id), at, deleted = true)
        jobs.forEach { snapshotCards(db, it, at) }
        return null
    }
}
