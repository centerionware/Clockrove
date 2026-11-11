package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.UUID

/** Native transaction helper. Payload erasure is explicit; this is not tamper-proof history. */
internal object RevisionStore {
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entity_revisions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_id TEXT NOT NULL,job_id TEXT,payload TEXT,deleted INTEGER NOT NULL CHECK(deleted IN(0,1)),changed_ms INTEGER NOT NULL,origin TEXT NOT NULL)")
        db.execSQL("CREATE INDEX revision_entity ON entity_revisions(kind,entity_id)")
        db.execSQL("CREATE INDEX revision_job ON entity_revisions(job_id)")
        db.execSQL("CREATE TABLE revision_parents(revision_id TEXT NOT NULL REFERENCES entity_revisions(id),parent_id TEXT NOT NULL REFERENCES entity_revisions(id),PRIMARY KEY(revision_id,parent_id),CHECK(revision_id!=parent_id))")
        db.execSQL("CREATE TABLE entity_heads(kind TEXT NOT NULL,entity_id TEXT NOT NULL,revision_id TEXT NOT NULL REFERENCES entity_revisions(id),PRIMARY KEY(kind,entity_id))")
    }

    fun record(db: SQLiteDatabase, kind: String, id: String, jobId: String?, values: Map<String, Any?>,
        at: Long, deleted: Boolean = false, origin: String = "MUTATION"): String {
        val payload = JSONObject(values.toSortedMap()).toString()
        var parent: String? = null
        db.rawQuery("SELECT r.id,r.payload,r.deleted FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind=? AND h.entity_id=?", arrayOf(kind, id)).use {
            if (it.moveToFirst()) {
                parent = it.getString(0)
                if (it.getString(1) == payload && it.getInt(2) == if (deleted) 1 else 0) return requireNotNull(parent)
            }
        }
        val revision = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO entity_revisions VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(revision, kind, id, jobId, payload, if (deleted) 1 else 0, at, origin))
        parent?.let { db.execSQL("INSERT INTO revision_parents VALUES (?,?)", arrayOf(revision, it)) }
        db.execSQL("INSERT OR REPLACE INTO entity_heads VALUES (?,?,?)", arrayOf(kind, id, revision))
        return revision
    }

    fun snapshotRows(db: SQLiteDatabase, table: String, kind: String, columns: List<String>, at: Long,
        jobColumn: String? = null, where: String? = null, args: Array<String>? = null, origin: String = "MUTATION") {
        // Table/column names are internal constants, never public transport input.
        db.query(table, columns.toTypedArray(), where, args, null, null, "id").use { rows ->
            while (rows.moveToNext()) {
                val values = columns.mapIndexed { index, name -> name to when (rows.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> rows.getLong(index)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> rows.getDouble(index)
                    else -> rows.getString(index)
                } }.toMap()
                val id = requireNotNull(values["id"]).toString()
                record(db, kind, id, if (kind == "job") id else jobColumn?.let { values[it] as String? }, values, at, origin = origin)
            }
        }
    }
}
