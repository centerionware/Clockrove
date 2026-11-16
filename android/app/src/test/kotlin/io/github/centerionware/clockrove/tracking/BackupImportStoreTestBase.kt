package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config


abstract class BackupImportStoreTestBase {
    internal class ManualScheduler(private val elapsed: () -> Long) : BackupScheduler {
        private data class Entry(val dueAt: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, action: () -> Unit): BackupScheduledTask {
            val entry = Entry(elapsed() + delayMillis, action)
            entries.add(entry)
            return BackupScheduledTask { entry.cancelled = true }
        }

        fun runDueTasks() {
            while (true) {
                val next = entries.filter { !it.cancelled && it.dueAt <= elapsed() }
                    .minByOrNull { it.dueAt } ?: return
                next.cancelled = true
                next.action()
            }
        }
    }

    internal val app get() = RuntimeEnvironment.getApplication()
    internal lateinit var repository: TimerRepository
    internal lateinit var database: SQLiteDatabase
    internal lateinit var exportStore: BackupExportStore
    internal lateinit var importStore: BackupImportStore
    internal lateinit var jobId: String
    internal var now = 1_793_494_800_000L

    @Before
    fun setUp() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now })
        jobId = repository.createJob("Synthetic import job")
        database = SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        exportStore = BackupExportStore(app, wallClock = { now })
        importStore = BackupImportStore(app, catalogProvider = exportStore::importCatalog)
    }

    @After
    fun tearDown() {
        try { importStore.closeAll() } catch (_: Exception) { }
        try { exportStore.closeAll() } catch (_: Exception) { }
        if (::database.isInitialized) database.close()
        if (::repository.isInitialized) repository.close()
    }

    internal fun transferRows(snapshotId: String, importId: String) {
        var ordinal = 0L
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(snapshotId, cursor, BackupImportStore.MAX_PAGE_BYTES)
            ordinal = importStore.appendPage(importId, ordinal, page.records,
                encodedAppendRequest(importId, ordinal, page.records))
            page.records.forEach { record ->
                @Suppress("UNCHECKED_CAST")
                val blobs = record["blobs"] as? List<Map<String, Any?>> ?: emptyList()
                blobs.forEach { blob ->
                    val blobId = blob["id"] as String
                    var offset = 0L
                    var blobDone = false
                    while (!blobDone) {
                        val chunk = exportStore.readBlob(
                            snapshotId, blobId, offset, BackupExportStore.MAX_BLOB_CHUNK_BYTES,
                        )
                        importStore.writeBlob(importId, blobId, offset, chunk.bytes, chunk.bytes.size)
                        offset = chunk.nextOffset
                        blobDone = chunk.done
                    }
                }
            }
            cursor = page.nextCursor
            done = page.done
        }
    }

    internal fun stageSnapshot(snapshot: BackupExportStore.Started): BackupImportStore.Started {
        val started = importStore.begin(
            database, snapshot.archiveFormatVersion, snapshot.schemaVersion, "d".repeat(64),
            snapshot.recordCount, snapshot.mediaCount, snapshot.mediaBytes,
        )
        transferRows(snapshot.snapshotId, started.importId)
        return started
    }

    internal fun stageImport(
        records: List<Map<String, Any?>>,
        synthesizeMissingRevisionHeads: Boolean = true,
        mediaCount: Long = 0,
        mediaBytes: Long = 0,
    ): BackupImportStore.Started {
        require(records.isNotEmpty())
        val archiveRecords = prepareSyntheticArchiveRecords(records, synthesizeMissingRevisionHeads)
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "d".repeat(64),
            archiveRecords.size.toLong(),
            mediaCount,
            mediaBytes,
        )
        var ordinal = 0L
        var page = mutableListOf<Map<String, Any?>>()
        fun flush() {
            if (page.isEmpty()) return
            ordinal = importStore.appendPage(
                started.importId, ordinal, page, encodedAppendRequest(started.importId, ordinal, page),
            )
            page = mutableListOf()
        }
        archiveRecords.forEach { record ->
            val candidate = page + record
            if (candidate.size > BackupImportStore.MAX_ROW_COUNT_PER_PAGE ||
                encodedAppendRequest(started.importId, ordinal, candidate) > BackupImportStore.MAX_REQUEST_BYTES) {
                flush()
            }
            page.add(record)
        }
        flush()
        return started
    }

    internal fun prepareSyntheticArchiveRecords(
        records: List<Map<String, Any?>>,
        synthesizeMissingRevisionHeads: Boolean = true,
    ): List<Map<String, Any?>> {
        val revisionCompleteRecords = if (synthesizeMissingRevisionHeads) {
            completeSyntheticRevisionHeads(records)
        } else {
            records
        }
        return completeLocalRevisionAncestors(revisionCompleteRecords)
    }

    /** Synthetic row fixtures need the revision metadata real exports already include. */
    private fun completeSyntheticRevisionHeads(records: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val result = records.toMutableList()
        val dataKinds = BackupImportStore.revisionDataTables.entries.associate { (kind, table) -> table to kind }
        val existingHeads = records.asSequence()
            .filter { it["table"] == "entity_heads" }
            .map { values(it)["kind"].toString() to values(it)["entity_id"].toString() }
            .toMutableSet()
        val existingRevisions = records.asSequence()
            .filter { it["table"] == "entity_revisions" }
            .map { values(it)["kind"].toString() to values(it)["entity_id"].toString() }
            .toMutableSet()

        fun stagedValue(table: String, keyColumn: String, key: Any?, valueColumn: String): Any? {
            if (key == null) return null
            val keyValue = key.toString()
            records.firstOrNull {
                it["table"] == table && values(it)[keyColumn]?.toString() == keyValue
            }?.let { return values(it)[valueColumn] }
            require(table.matches(Regex("[a-z_]+")) && keyColumn.matches(Regex("[a-z_]+")) &&
                valueColumn.matches(Regex("[a-z_]+")))
            return database.rawQuery(
                "SELECT $valueColumn FROM $table WHERE $keyColumn=? LIMIT 1", arrayOf(keyValue),
            ).use { rows -> if (!rows.moveToFirst() || rows.isNull(0)) null else rows.getString(0) }
        }

        records.forEach { record ->
            val table = record["table"] as? String ?: return@forEach
            val kind = dataKinds[table] ?: return@forEach
            val rowValues = values(record).entries.associate { it.key.toString() to it.value }
            val entityId = BackupImportStore.revisionEntityIdForDataRow(table, rowValues) ?: return@forEach
            val entityKey = kind to entityId
            if (entityKey in existingHeads || entityKey in existingRevisions) return@forEach

            val payloadValues = when (kind) {
                "settings_money", "job_money", "card_money", "template_money" -> {
                    val id = rowValues["id"] ?: return@forEach
                    val linkedJobId = when (kind) {
                        "job_money" -> id.toString()
                        "card_money" -> stagedValue("kanban_cards", "id", id, "job_id")
                        else -> null
                    }
                    mapOf(
                        "id" to if (kind == "settings_money") id.toString() else id,
                        "minor_text" to rowValues["minor_text"],
                        "job_id" to linkedJobId,
                    )
                }
                "task_quantity" -> {
                    val cardId = rowValues["card_id"] ?: return@forEach
                    mapOf(
                        "card_id" to cardId,
                        "job_id" to stagedValue("kanban_cards", "id", cardId, "job_id"),
                        "quantity_units" to rowValues["quantity_units"],
                    )
                }
                "template_quantity" -> mapOf(
                    "template_id" to (rowValues["template_id"] ?: return@forEach),
                    "quantity_units" to rowValues["quantity_units"],
                )
                "payment_destination" -> mapOf(
                    "payment_id" to (rowValues["payment_id"] ?: return@forEach),
                    "invoice_id" to (rowValues["invoice_id"] ?: return@forEach),
                )
                "quote_draft" -> mapOf(
                    "id" to (rowValues["quote_id"] ?: return@forEach),
                    "payload" to (rowValues["payload"] ?: return@forEach),
                )
                else -> {
                    val columns = BackupImportStore.revisionDataColumns[kind] ?: return@forEach
                    if (columns.any { it !in rowValues }) return@forEach
                    columns.associateWith { rowValues[it] }
                }
            }
            val jobId = when {
                "job_id" in payloadValues -> payloadValues["job_id"]?.toString()
                kind == "job" -> rowValues["id"]?.toString()
                kind == "quote_draft" -> rowValues["job_id"]?.toString()
                kind == "invoice_coverage" || kind == "payment_destination" -> stagedValue(
                    "invoices", "id", rowValues["invoice_id"], "job_id",
                )?.toString()
                kind == "quote_invoice_link" -> stagedValue(
                    "invoices", "id", rowValues["invoice_id"], "job_id",
                )?.toString()
                kind == "quote_decision" -> {
                    val quoteId = rowValues["quote_id"]?.toString()
                    val revision = rowValues["revision"]?.toString()
                    if (quoteId == null || revision == null) null else {
                        val staged = records.firstOrNull { row ->
                            row["table"] == "quote_revisions" &&
                                values(row)["quote_id"]?.toString() == quoteId &&
                                values(row)["revision"]?.toString() == revision
                        }
                        if (staged != null) values(staged)["job_id"]?.toString() else database.rawQuery(
                            "SELECT job_id FROM quote_revisions WHERE quote_id=? AND revision=? LIMIT 1",
                            arrayOf(quoteId, revision),
                        ).use { rows -> if (!rows.moveToFirst() || rows.isNull(0)) null else rows.getString(0) }
                    }
                }
                kind == "task_comment" || kind == "task_attachment" -> stagedValue(
                    "kanban_cards", "id", rowValues["task_id"], "job_id",
                )?.toString()
                else -> null
            }
            val payload = JSONObject(payloadValues.toSortedMap()).toString()
            val revisionId = UUID.nameUUIDFromBytes("$kind:$entityId".toByteArray()).toString()
            result.add(revisionRecord(revisionId, kind, entityId, jobId, payload = payload))
            result.add(headRecord(kind, entityId, revisionId))
            existingHeads.add(entityKey)
            existingRevisions.add(entityKey)
        }
        return result
    }

    /** Synthetic archives that parent a local head must carry its full ancestry. */
    internal fun completeLocalRevisionAncestors(records: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val result = records.toMutableList()
        val revisionIds = records.asSequence()
            .filter { it["table"] == "entity_revisions" }
            .mapNotNull { (values(it)["id"] as? String) }
            .toMutableSet()
        val parentPairs = records.asSequence()
            .filter { it["table"] == "revision_parents" }
            .map { values(it)["revision_id"].toString() to values(it)["parent_id"].toString() }
            .toMutableSet()
        val pending = ArrayDeque(parentPairs.map { it.second })
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            if (parentId in visited || parentId in revisionIds) continue
            visited.add(parentId)
            val revision = database.rawQuery(
                "SELECT id,kind,entity_id,job_id,payload,deleted,changed_ms,origin " +
                    "FROM entity_revisions WHERE id=?", arrayOf(parentId),
            ).use { rows ->
                if (!rows.moveToFirst()) null else mapOf<String, Any?>(
                    "id" to rows.getString(0), "kind" to rows.getString(1),
                    "entity_id" to rows.getString(2),
                    "job_id" to if (rows.isNull(3)) null else rows.getString(3),
                    "payload" to if (rows.isNull(4)) null else rows.getString(4),
                    "deleted" to rows.getLong(5), "changed_ms" to rows.getLong(6),
                    "origin" to rows.getString(7),
                )
            } ?: continue
            revisionIds.add(parentId)
            result.add(stagedRow("entity_revisions", revision))
            database.rawQuery(
                "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
                arrayOf(parentId),
            ).use { rows ->
                while (rows.moveToNext()) {
                    val ancestor = rows.getString(0)
                    if (parentPairs.add(parentId to ancestor)) result.add(parentRecord(parentId, ancestor))
                    if (ancestor !in revisionIds) pending.addLast(ancestor)
                }
            }
        }
        return result
    }

    internal fun customerName(id: String): String = database.rawQuery(
        "SELECT name FROM customers WHERE id=?", arrayOf(id),
    ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

    internal fun values(record: Map<String, Any?>): Map<*, *> = record["values"] as Map<*, *>

    internal fun readRowValues(table: String, keyColumn: String, keyValue: String): Map<String, Any?> {
        require(table.matches(Regex("[a-z_]+")) && keyColumn.matches(Regex("[a-z_]+")))
        return database.rawQuery(
            "SELECT * FROM $table WHERE $keyColumn=? LIMIT 1",
            arrayOf(keyValue),
        ).use { cursor ->
            assertTrue("Missing $table row", cursor.moveToFirst())
            cursor.columnNames.mapIndexed { index, column ->
                column to when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                    else -> cursor.getString(index)
                }
            }.toMap()
        }
    }

    internal fun readAllRows(snapshotId: String): List<Map<String, Any?>> {
        val records = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(snapshotId, cursor, BackupImportStore.MAX_PAGE_BYTES)
            records.addAll(page.records)
            cursor = page.nextCursor
            done = page.done
        }
        return records
    }

    internal fun revisionRecord(
        id: String,
        kind: String,
        entityId: String,
        jobId: String?,
        deleted: Long = 0L,
        origin: String = "MUTATION",
        payload: String? = if (kind == "location") {
            JSONObject().put("id", entityId).put("session_id", "synthetic-session").toString()
        } else "{}",
    ): Map<String, Any?> = stagedRow("entity_revisions", mapOf(
        "id" to id,
        "kind" to kind,
        "entity_id" to entityId,
        "job_id" to jobId,
        "payload" to payload,
        "deleted" to deleted,
        "changed_ms" to now,
        "origin" to origin,
    ))

    internal fun insertSyntheticRevision(
        id: String,
        kind: String,
        entityId: String,
        parentId: String?,
        payload: String = "{}",
        jobId: String? = null,
    ) {
        database.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,?,?,?,?,'MUTATION')",
            arrayOf<Any?>(id, kind, entityId, jobId, payload, 0L, now),
        )
        if (parentId != null) {
            database.execSQL(
                "INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)",
                arrayOf(id, parentId),
            )
        }
    }

    internal fun parentRecord(revisionId: String, parentId: String): Map<String, Any?> =
        stagedRow("revision_parents", mapOf("revision_id" to revisionId, "parent_id" to parentId))

    internal fun headRecord(kind: String, entityId: String, revisionId: String): Map<String, Any?> =
        stagedRow("entity_heads", mapOf("kind" to kind, "entity_id" to entityId, "revision_id" to revisionId))

    internal fun stagedRow(table: String, values: Map<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "row",
        "table" to table,
        "values" to values,
        "blobs" to emptyList<Any>(),
    )

    internal fun blobRecord(
        table: String,
        values: Map<String, Any?>,
        column: String,
        sizeBytes: Int,
    ): Map<String, Any?> = mapOf(
        "type" to "row",
        "table" to table,
        "values" to values,
        "blobs" to listOf(mapOf(
            "id" to "a".repeat(43),
            "column" to column,
            "sizeBytes" to sizeBytes,
        )),
    )

    internal fun invoiceRecord(id: String, invoiceJobId: String, number: String): Map<String, Any?> =
        stagedRow("invoices", mapOf(
            "id" to id,
            "job_id" to invoiceJobId,
            "customer_id" to null,
            "number" to number,
            "created_ms" to now,
            "payload" to "{}",
            "pdf" to null,
            "logo_id" to null,
        ))

    internal fun invoiceTimeClaimRecord(
        id: String,
        invoiceId: String,
        claimJobId: String,
        start: Long,
        end: Long,
        releasedMs: Long? = null,
        sessionId: String = "synthetic-session-$id",
    ): Map<String, Any?> = stagedRow("invoice_time_claims", mapOf(
        "id" to id,
        "invoice_id" to invoiceId,
        "job_id" to claimJobId,
        "session_id" to sessionId,
        "start_ms" to start,
        "end_ms" to end,
        "timezone" to "Etc/UTC",
        "released_ms" to releasedMs,
    ))

    internal fun sessionWithoutLivePoint(commandPrefix: String): Pair<String, Long> {
        val (sessionId, originalPointTime) = startAndStopSessionWithPoint("$commandPrefix-original")
        val end = database.rawQuery("SELECT end_ms FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }
        assertNull(repository.correctSession(
            "$commandPrefix-remove-original-point",
            repository.snapshot().version,
            SessionHistoryStore.Draft(
                sessionId, jobId, originalPointTime + 1L, end, "Etc/UTC", 0,
            ),
        ).conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE session_id='$sessionId'"))
        return sessionId to originalPointTime + 10L
    }

    internal fun backupRangeAndUnparentedPointRecords(
        rangeId: String,
        rangeRevisionId: String,
        pointId: String,
        pointRevisionId: String,
        sessionId: String,
        measuredAt: Long,
    ): List<Map<String, Any?>> {
        val rangeStart = measuredAt
        val rangeEnd = measuredAt + 1L
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to rangeStart,
            "end_ms" to rangeEnd,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to rangeRevisionId,
        )
        return listOf(
            revisionRecord(
                rangeRevisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", rangeStart).put("end_ms", rangeEnd)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, rangeRevisionId),
            stagedRow("location_deletion_ranges", range),
            revisionRecord(
                pointRevisionId, "location", pointId, jobId,
                payload = JSONObject().put("id", pointId).put("session_id", sessionId).toString(),
            ),
            headRecord("location", pointId, pointRevisionId),
            stagedRow("location_points", mapOf(
                "id" to pointId,
                "session_id" to sessionId,
                "measured_ms" to measuredAt,
                "received_ms" to measuredAt,
                "latitude" to 12.0,
                "longitude" to -34.0,
                "accuracy" to 5.0,
            )),
        )
    }

    internal fun startAndStopSessionWithPoint(pointId: String): Pair<String, Long> {
        assertNull(repository.execute(TimerRepository.Command(
            "start-$pointId",
            repository.snapshot().version,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val sessionId = requireNotNull(repository.snapshot().running).id
        val measuredAt = now + 1_000L
        assertEquals(
            TimerRepository.SampleResult.RECORDED,
            repository.recordLocation(TimerRepository.LocationPoint(
                pointId, sessionId, measuredAt, measuredAt, 12.0, -34.0, 5.0,
            )),
        )
        assertNull(repository.execute(TimerRepository.Command(
            "stop-$pointId",
            repository.snapshot().version,
            TimerRepository.Action.STOP,
            null,
            measuredAt + 1_000L,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        return sessionId to measuredAt
    }

    internal fun findConflict(importId: String, preview: BackupImportStore.Preview, kind: String): Map<String, Any?>? {
        var cursor: String? = null
        while (true) {
            val page = importStore.readConflictPage(database, importId, preview.previewToken, cursor, null)
            page.conflicts.firstOrNull { it["kind"] == kind }?.let { return it }
            if (page.done) return null
            cursor = page.nextCursor
        }
    }

    internal fun resolveSafeConflictChoices(
        importId: String,
        preview: BackupImportStore.Preview,
        conflicts: List<Map<String, Any?>>,
    ) {
        conflicts.forEach { conflict ->
            @Suppress("UNCHECKED_CAST")
            val choices = conflict["allowedChoices"] as? List<String> ?: emptyList()
            val preferred = when (conflict["kind"]) {
                "JOB_DELETION_TOMBSTONE", "ENTITY_DELETION_TOMBSTONE" -> "PRESERVE_LOCAL_DELETION"
                "ACTIVE_JOB_DELETION", "BACKUP_ENTITY_DELETION" -> "KEEP_LOCAL"
                "GPS_TOMBSTONE" -> "PRESERVE_LOCAL_DELETION"
                "GPS_DELETION_COVERAGE" -> "PRESERVE_DELETION"
                "DEPENDENT_ON_CONFLICT" -> "OMIT_BACKUP_RECORD"
                else -> conflict["defaultChoice"] as? String
            }
            val choice = preferred?.takeIf(choices::contains)
                ?: choices.firstOrNull { it == "KEEP_LOCAL" }
                ?: choices.firstOrNull { it == "OMIT_BACKUP_RECORD" }
                ?: choices.firstOrNull()
            assertNotNull("No explicit safe choice for ${conflict["kind"]} / ${conflict["table"]}", choice)
            importStore.resolveConflict(
                database,
                importId,
                preview.previewToken,
                preview.localStateVersion,
                conflict["conflictId"] as String,
                requireNotNull(choice),
            )
        }
    }

    internal fun number(sql: String): Long = database.rawQuery(sql, null).use { rows ->
        assertTrue(rows.moveToFirst())
        rows.getLong(0)
    }

    internal fun importRelevantCounts(): Map<String, Long> = listOf(
        "sessions", "location_points", "entity_revisions", "invoices",
        "invoice_task_claims", "invoice_time_claims",
    ).associateWith { table -> number("SELECT COUNT(*) FROM $table") }

    internal fun validationTableCount(): Long {
        val activeField = BackupImportStore::class.java.getDeclaredField("active").apply { isAccessible = true }
        val handle = requireNotNull(activeField.get(importStore))
        val databaseField = handle.javaClass.getDeclaredField("database").apply { isAccessible = true }
        val stagedDatabase = databaseField.get(handle) as SQLiteDatabase
        return stagedDatabase.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND " +
                "(name LIKE 'validation_%' OR name='preview_unique_keys')",
            null,
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            rows.getLong(0)
        }
    }

    internal fun assertNoCoordinates(conflict: Map<String, Any?>) {
        listOf("localSummary", "backupSummary").forEach { key ->
            val summary = conflict[key] as? Map<*, *> ?: return@forEach
            assertFalse(summary.containsKey("latitude"))
            assertFalse(summary.containsKey("longitude"))
        }
    }

    internal fun assertInvalidRevisionGraph(
        records: List<Map<String, Any?>>,
        forbiddenDiagnostic: String? = null,
    ) {
        val before = repository.snapshot()
        val countsBefore = importRelevantCounts()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "c".repeat(64),
            records.size.toLong(),
            0,
            0,
        )
        importStore.appendPage(
            started.importId,
            0,
            records,
            encodedAppendRequest(started.importId, 0, records),
        )
        val failure = try {
            importStore.finish(database, started.importId)
            fail("Expected import failure INVALID_REVISION_GRAPH")
            null
        } catch (failure: BackupImportStore.Failure) {
            failure
        }
        assertEquals("INVALID_REVISION_GRAPH", failure?.code)
        if (forbiddenDiagnostic != null) {
            assertFalse(failure?.message.orEmpty().contains(forbiddenDiagnostic))
        }
        assertEquals(before, repository.snapshot())
        assertEquals(countsBefore, importRelevantCounts())
        assertTrue(importStore.close(started.importId))
    }

    internal fun assertInvalidReference(records: List<Map<String, Any?>>) {
        val before = repository.snapshot()
        val countsBefore = importRelevantCounts()
        val archiveRecords = completeLocalRevisionAncestors(completeSyntheticRevisionHeads(records))
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
            archiveRecords.size.toLong(),
            0,
            0,
        )
        importStore.appendPage(
            started.importId,
            0,
            archiveRecords,
            encodedAppendRequest(started.importId, 0, archiveRecords),
        )
        assertFailure("INVALID_REFERENCE") { importStore.finish(database, started.importId) }
        assertEquals(before, repository.snapshot())
        assertEquals(countsBefore, importRelevantCounts())
        assertTrue(importStore.close(started.importId))
    }

    internal fun assertInvalidArchive(records: List<Map<String, Any?>>) {
        val before = repository.snapshot()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            records.size.toLong(),
            0,
            0,
        )
        importStore.appendPage(
            started.importId,
            0,
            records,
            encodedAppendRequest(started.importId, 0, records),
        )
        assertFailure("INVALID_ARCHIVE") { importStore.finish(database, started.importId) }
        assertEquals(before, repository.snapshot())
        assertTrue(importStore.close(started.importId))
    }

    internal fun encodedAppendRequest(importId: String, ordinal: Long, rows: List<*>): Int =
        StandardMethodCodec.INSTANCE.encodeMethodCall(MethodCall(
            "appendBackupImportPage",
            mapOf("importId" to importId, "firstOrdinal" to ordinal, "records" to rows),
        )).position()

    internal fun assertFailure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected import failure " + code)
        } catch (failure: BackupImportStore.Failure) {
            assertEquals(code, failure.code)
        }
    }
}
