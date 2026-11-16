package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import java.io.File

/** Validates a revision DAG with bounded, file-backed SQLite state. */
internal object RevisionGraphValidator {
    class InvalidGraph : IllegalStateException("The revision graph references are invalid.")

    class CycleDetected : IllegalStateException("The revision graph contains a cycle.")

    class LimitExceeded : IllegalStateException("The revision graph exceeds its validation limit.")

    private const val BATCH_SIZE = 256
    private const val COPY_CHECK_INTERVAL = 4096L
    private const val MAX_VALIDATION_DATABASE_BYTES = 256L * 1024L * 1024L
    private const val PENDING = "backup_revision_check_pending"
    private const val BATCH = "backup_revision_check_batch"
    private const val PENDING_INDEX = "backup_revision_check_pending_count"
    private val identifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun validateReferences(db: SQLiteDatabase) {
        val invalidHeads = scalarLong(
            db,
            "SELECT COUNT(*) FROM entity_heads h LEFT JOIN entity_revisions r " +
                "ON r.id=h.revision_id WHERE r.id IS NULL OR h.kind!=r.kind OR h.entity_id!=r.entity_id",
        )
        val invalidParents = scalarLong(
            db,
            "SELECT COUNT(*) FROM revision_parents p " +
                "LEFT JOIN entity_revisions r ON r.id=p.revision_id " +
                "LEFT JOIN entity_revisions parent ON parent.id=p.parent_id " +
                "WHERE r.id IS NULL OR parent.id IS NULL OR p.revision_id=p.parent_id",
        )
        val invalidAncestry = scalarLong(
            db,
            "SELECT COUNT(*) FROM revision_parents p " +
                "JOIN entity_revisions child ON child.id=p.revision_id " +
                "JOIN entity_revisions parent ON parent.id=p.parent_id " +
                "WHERE NOT ((child.kind=parent.kind AND child.entity_id=parent.entity_id) OR " +
                "(child.kind='location' AND child.deleted=1 AND child.origin='GPS_RANGE_DELETE' " +
                "AND child.job_id IS NOT NULL AND parent.kind='location_deletion_range' " +
                "AND parent.deleted=0 AND (parent.job_id IS NULL OR parent.job_id=child.job_id)))",
        )
        val missingHeads = scalarLong(
            db,
            "SELECT COUNT(*) FROM entity_revisions r WHERE NOT EXISTS(" +
                "SELECT 1 FROM entity_heads h WHERE h.kind=r.kind AND h.entity_id=r.entity_id)",
        )
        if (invalidHeads != 0L ||
            invalidParents != 0L ||
            invalidAncestry != 0L ||
            missingHeads != 0L
        ) {
            throw InvalidGraph()
        }
    }

    /**
     * Copies node IDs and parent edges from the caller's read transaction into
     * a bounded private database using cursors and prepared inserts. A batched
     * Kahn traversal runs there without attaching the live database to a
     * second SQLite connection.
     */
    fun validateSnapshotAcyclicity(
        sourceDb: SQLiteDatabase,
        scratchFile: File,
        revisionTable: String,
        parentTable: String,
        maxRows: Long,
    ) {
        require(identifierPattern.matches(revisionTable))
        require(identifierPattern.matches(parentTable))
        require(maxRows > 0)

        val graphRowCount = scalarLong(
            sourceDb,
            "SELECT (SELECT COUNT(*) FROM ${quote(revisionTable)})+" +
                "(SELECT COUNT(*) FROM ${quote(parentTable)})",
        )
        if (graphRowCount > maxRows) throw LimitExceeded()

        var graphDb: SQLiteDatabase? = null
        var transactionStarted = false
        var failure: Throwable? = null
        try {
            if (scratchFile.exists() && !deleteDatabaseFiles(scratchFile)) {
                throw IllegalStateException("A revision validation file could not be replaced.")
            }
            if (!scratchFile.createNewFile()) {
                throw IllegalStateException("A revision validation file could not be created.")
            }
            val validationDb = SQLiteDatabase.openDatabase(
                scratchFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            graphDb = validationDb
            validationDb.setMaximumSize(MAX_VALIDATION_DATABASE_BYTES)
            val journalMode = validationDb.rawQuery("PRAGMA journal_mode=OFF", null).use { rows ->
                if (!rows.moveToFirst()) "" else rows.getString(0)
            }
            if (!journalMode.equals("off", ignoreCase = true)) {
                throw IllegalStateException("The revision validation database is not disposable.")
            }
            validationDb.beginTransaction()
            transactionStarted = true

            validationDb.execSQL(
                "CREATE TABLE ${quote(PENDING)}(" +
                    "revision_id TEXT PRIMARY KEY,remaining_parents INTEGER NOT NULL) WITHOUT ROWID",
            )
            validationDb.execSQL(
                "CREATE TABLE graph_parents(" +
                    "revision_id TEXT NOT NULL,parent_id TEXT NOT NULL," +
                    "PRIMARY KEY(revision_id,parent_id)) WITHOUT ROWID",
            )
            validationDb.execSQL(
                "CREATE INDEX graph_parent_lookup ON graph_parents(parent_id,revision_id)",
            )
            copyGraphRows(sourceDb, validationDb, revisionTable, parentTable)
            validateAcyclicity(validationDb, "graph_parents")
            validationDb.setTransactionSuccessful()
        } catch (error: Throwable) {
            failure = if (error is SQLiteFullException) LimitExceeded() else error
        } finally {
            if (transactionStarted) {
                try {
                    graphDb?.endTransaction()
                } catch (error: Throwable) {
                    failure = combineFailures(failure, error)
                }
            }
            try {
                graphDb?.close()
            } catch (error: Throwable) {
                failure = combineFailures(failure, error)
            }
            if (!deleteDatabaseFiles(scratchFile)) {
                failure = combineFailures(
                    failure,
                    IllegalStateException("A revision validation file could not be removed."),
                )
            }
        }

        failure?.let { throw it }
    }

    private fun validateAcyclicity(
        db: SQLiteDatabase,
        parentTable: String,
    ) {
        val pending = quote(PENDING)
        val batch = quote(BATCH)
        val pendingIndex = quote(PENDING_INDEX)
        db.execSQL("DROP TABLE IF EXISTS $batch")
        try {
            db.execSQL("CREATE INDEX $pendingIndex ON $pending(remaining_parents,revision_id)")
            db.execSQL("CREATE TABLE $batch(revision_id TEXT PRIMARY KEY) WITHOUT ROWID")
            requireWithinLimit(db)
            while (true) {
                db.execSQL("DELETE FROM $batch")
                db.execSQL(
                    "INSERT INTO $batch SELECT revision_id FROM $pending " +
                        "WHERE remaining_parents=0 ORDER BY revision_id LIMIT $BATCH_SIZE",
                )
                if (scalarLong(db, "SELECT COUNT(*) FROM $batch") == 0L) break
                db.execSQL(
                    "DELETE FROM $pending WHERE revision_id IN (SELECT revision_id FROM $batch)",
                )
                db.execSQL(
                    "UPDATE $pending SET remaining_parents=remaining_parents-(" +
                        "SELECT COUNT(*) FROM $parentTable p JOIN $batch b " +
                        "ON b.revision_id=p.parent_id WHERE p.revision_id=$PENDING.revision_id) " +
                        "WHERE EXISTS(SELECT 1 FROM $parentTable p JOIN $batch b " +
                        "ON b.revision_id=p.parent_id WHERE p.revision_id=$PENDING.revision_id)",
                )
                requireWithinLimit(db)
            }
            if (scalarLong(db, "SELECT COUNT(*) FROM $pending") != 0L) {
                throw CycleDetected()
            }
        } finally {
            db.execSQL("DROP TABLE IF EXISTS $batch")
            db.execSQL("DROP TABLE IF EXISTS $pending")
        }
    }

    private fun copyGraphRows(
        sourceDb: SQLiteDatabase,
        validationDb: SQLiteDatabase,
        revisionTable: String,
        parentTable: String,
    ) {
        val insertNode = validationDb.compileStatement(
            "INSERT INTO ${quote(PENDING)}(revision_id,remaining_parents) VALUES (?,0)",
        )
        try {
            sourceDb.rawQuery(
                "SELECT id FROM ${quote(revisionTable)} ORDER BY id",
                null,
            ).use { rows ->
                var copied = 0L
                while (rows.moveToNext()) {
                    insertNode.bindString(1, rows.getString(0))
                    insertNode.execute()
                    insertNode.clearBindings()
                    copied++
                    if (copied % COPY_CHECK_INTERVAL == 0L) requireWithinLimit(validationDb)
                }
            }
        } finally {
            insertNode.close()
        }

        val insertParent = validationDb.compileStatement(
            "INSERT INTO graph_parents(revision_id,parent_id) VALUES (?,?)",
        )
        val incrementParents = validationDb.compileStatement(
            "UPDATE ${quote(PENDING)} SET remaining_parents=remaining_parents+1 WHERE revision_id=?",
        )
        try {
            sourceDb.rawQuery(
                "SELECT revision_id,parent_id FROM ${quote(parentTable)} ORDER BY revision_id,parent_id",
                null,
            ).use { rows ->
                var copied = 0L
                while (rows.moveToNext()) {
                    val revisionId = rows.getString(0)
                    insertParent.bindString(1, revisionId)
                    insertParent.bindString(2, rows.getString(1))
                    insertParent.execute()
                    insertParent.clearBindings()

                    incrementParents.bindString(1, revisionId)
                    if (incrementParents.executeUpdateDelete() != 1) throw InvalidGraph()
                    incrementParents.clearBindings()
                    copied++
                    if (copied % COPY_CHECK_INTERVAL == 0L) requireWithinLimit(validationDb)
                }
            }
        } finally {
            insertParent.close()
            incrementParents.close()
        }
        requireWithinLimit(validationDb)
    }

    private fun requireWithinLimit(db: SQLiteDatabase) {
        val pageCount = scalarLong(db, "PRAGMA page_count")
        val pageSize = scalarLong(db, "PRAGMA page_size")
        if (pageCount * pageSize > MAX_VALIDATION_DATABASE_BYTES) throw LimitExceeded()
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use {
        if (!it.moveToFirst()) throw IllegalStateException("The revision graph could not be checked.")
        it.getLong(0)
    }

    private fun deleteDatabaseFiles(file: File): Boolean {
        val suffixes = listOf("", "-journal", "-wal", "-shm")
        var deleted = true
        suffixes.forEach { suffix ->
            val candidate = File(file.absolutePath + suffix)
            if (candidate.exists() && !candidate.delete() && candidate.exists()) deleted = false
        }
        return deleted
    }

    private fun combineFailures(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        current.addSuppressed(next)
        return current
    }

    private fun quote(identifier: String): String = "\"$identifier\""
}
