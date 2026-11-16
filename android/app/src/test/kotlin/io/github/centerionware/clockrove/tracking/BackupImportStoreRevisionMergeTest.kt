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


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupImportStoreRevisionMergeTest : BackupImportStoreTestBase() {
    @Test
    fun rejectsARevisionIdReusedForDifferentImmutableContent() {
        val kind = "quote_decision"
        val entityId = "revision-id-collision-entity"
        insertSyntheticRevision("shared-revision-id", kind, entityId, null, payload = "{}")
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
            arrayOf(kind, entityId, "shared-revision-id"),
        )
        val started = stageImport(listOf(
            revisionRecord(
                "shared-revision-id", kind, entityId, null,
                payload = "{\"different\":true}",
            ),
            headRecord(kind, entityId, "shared-revision-id"),
        ))

        assertFailure("INVALID_REVISION_GRAPH") {
            importStore.finish(database, started.importId)
        }
        assertTrue(importStore.close(started.importId))
        assertEquals("shared-revision-id", database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
    }

    @Test
    fun redactedRevisionIdCollisionWithoutCausalAncestryRemainsInvalid() {
        val kind = "customer"
        val entityId = "redacted-revision-id-collision-entity"
        val redactedRevision = "redacted-unrelated-revision"
        val customerValues = mapOf<String, Any?>(
            "id" to entityId,
            "name" to "Unrelated local customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )
        database.execSQL(
            "INSERT INTO customers VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(entityId, "Unrelated local customer", "", "", "", "", 0),
        )
        val payload = JSONObject(customerValues.toSortedMap()).toString()
        insertSyntheticRevision(redactedRevision, kind, entityId, null, payload = payload)
        database.execSQL("UPDATE entity_revisions SET payload=NULL WHERE id=?", arrayOf(redactedRevision))
        val unrelatedHead = RevisionStore.record(database, kind, entityId, null, customerValues, now + 1L)
        val started = stageImport(listOf(
            revisionRecord(redactedRevision, kind, entityId, null, payload = payload),
            headRecord(kind, entityId, redactedRevision),
            stagedRow("customers", customerValues),
        ))

        assertFailure("INVALID_REVISION_GRAPH") {
            importStore.finish(database, started.importId)
        }
        assertEquals(unrelatedHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertTrue(database.rawQuery(
            "SELECT payload FROM entity_revisions WHERE id=?",
            arrayOf(redactedRevision),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.isNull(0) })
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun keepLocalChoiceOmitsCyclicBackupGraphWithoutChangingTheDatabase() {
        val kind = "quote_decision"
        val entityId = "post-choice-cycle-entity"
        val parentFirstRevision = "post-choice-cycle-a"
        val parentSecondRevision = "post-choice-cycle-b"
        insertSyntheticRevision(parentSecondRevision, kind, entityId, null)
        insertSyntheticRevision(parentFirstRevision, kind, entityId, parentSecondRevision)
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
            arrayOf(kind, entityId, parentFirstRevision),
        )
        val started = stageImport(listOf(
            revisionRecord(parentFirstRevision, kind, entityId, null),
            revisionRecord(parentSecondRevision, kind, entityId, null),
            parentRecord(parentSecondRevision, parentFirstRevision),
            headRecord(kind, entityId, parentSecondRevision),
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        assertEquals("BACKUP_DESCENDANT", conflict["revisionRelationship"])
        assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "KEEP_LOCAL",
        )

        val beforeApply = repository.snapshot()
        val parentCountBeforeApply = number("SELECT COUNT(*) FROM revision_parents")
        assertTrue(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(parentFirstRevision, parentSecondRevision),
        ).use { it.moveToFirst() })
        assertFalse(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(parentSecondRevision, parentFirstRevision),
        ).use { it.moveToFirst() })

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )

        assertEquals(beforeApply, repository.snapshot())
        assertEquals(preview.localStateVersion, applied.stateVersion)
        assertEquals(parentCountBeforeApply, number("SELECT COUNT(*) FROM revision_parents"))
        assertFalse(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(parentSecondRevision, parentFirstRevision),
        ).use { it.moveToFirst() })
        assertEquals(parentFirstRevision, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun unchangedBackupDescendantFastForwardsItsHeadWithoutAConflict() {
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()
        val localHead = database.rawQuery(
            "SELECT h.revision_id,r.payload FROM entity_heads h " +
                "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='job' AND h.entity_id=?",
            arrayOf(jobId),
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            rows.getString(0) to if (rows.isNull(1)) null else rows.getString(1)
        }
        val backupHead = UUID.fromString("d0000000-0000-4000-8000-000000000001").toString()
        records.removeAll { record ->
            record["table"] == "entity_heads" &&
                values(record)["kind"] == "job" && values(record)["entity_id"] == jobId
        }
        records.add(revisionRecord(
            backupHead,
            "job",
            jobId,
            jobId,
            payload = localHead.second,
        ))
        records.add(parentRecord(backupHead, localHead.first))
        records.add(headRecord("job", jobId, backupHead))

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        val versionBeforeApply = repository.snapshot().version

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        val committedHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertEquals(backupHead, committedHead)
        assertEquals(versionBeforeApply + 1L, repository.snapshot().version)
        assertTrue(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(backupHead, localHead.first),
        ).use { it.moveToFirst() })
        assertFalse(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun divergentIdenticalHeadsRequireAndCommitAMergeWithBothParents() {
        val base = database.rawQuery(
            "SELECT h.revision_id,r.payload FROM entity_heads h " +
                "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='job' AND h.entity_id=?",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) to rows.getString(1) }
        val localHead = UUID.fromString("d0000000-0000-4000-8000-000000000002").toString()
        val backupHead = UUID.fromString("d0000000-0000-4000-8000-000000000003").toString()
        insertSyntheticRevision(localHead, "job", jobId, base.first, base.second, jobId = jobId)
        database.execSQL(
            "UPDATE entity_heads SET revision_id=? WHERE kind='job' AND entity_id=?",
            arrayOf(localHead, jobId),
        )
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()
        records.removeAll { record ->
            record["table"] == "entity_heads" &&
                values(record)["kind"] == "job" && values(record)["entity_id"] == jobId
        }
        records.add(revisionRecord(backupHead, "job", jobId, jobId, payload = base.second))
        records.add(parentRecord(backupHead, base.first))
        records.add(headRecord("job", jobId, backupHead))

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        assertEquals("DIVERGENT", conflict["revisionRelationship"])
        assertEquals(listOf("KEEP_LOCAL", "MERGE_IDENTICAL_CONTENT"), conflict["allowedChoices"])
        assertFalse(conflict["resolved"] as Boolean)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "MERGE_IDENTICAL_CONTENT",
        )

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        val mergedHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val parents = database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
            arrayOf(mergedHead),
        ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertNotEquals(localHead, mergedHead)
        assertNotEquals(backupHead, mergedHead)
        assertEquals(setOf(localHead, backupHead), parents.toSet())
        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        assertEquals(applied.stateVersion, repository.snapshot().version)
        assertFalse(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun divergentCustomerHeadsCanSelectEitherSideAndReimportWithoutAnotherMerge() {
        data class Case(
            val id: String,
            val baseRevision: String,
            val localRevision: String,
            val backupRevision: String,
            val localName: String,
            val backupName: String,
            val selectedChoice: String,
        )
        val cases = listOf(
            Case(
                "divergent-customer-local", "divergent-customer-local-base",
                "divergent-customer-local-head", "divergent-customer-local-backup",
                "Local customer", "Backup customer", "MERGE_KEEP_LOCAL_CONTENT",
            ),
            Case(
                "divergent-customer-backup", "divergent-customer-backup-base",
                "divergent-customer-backup-head", "divergent-customer-backup-branch",
                "Another local customer", "Selected backup customer", "MERGE_USE_BACKUP_CONTENT",
            ),
        )
        fun customerValues(id: String, name: String) = mapOf<String, Any?>(
            "id" to id,
            "name" to name,
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )
        fun payload(values: Map<String, Any?>) = JSONObject(values.toSortedMap()).toString()

        val records = mutableListOf<Map<String, Any?>>()
        cases.forEach { case ->
            val baseValues = customerValues(case.id, "Common customer")
            val localValues = customerValues(case.id, case.localName)
            val backupValues = customerValues(case.id, case.backupName)
            database.execSQL(
                "INSERT INTO customers(id,name,email,phone,address,notes,archived) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(case.id, case.localName, "", "", "", "", 0),
            )
            insertSyntheticRevision(
                case.baseRevision, "customer", case.id, null, payload(baseValues),
            )
            insertSyntheticRevision(
                case.localRevision, "customer", case.id, case.baseRevision, payload(localValues),
            )
            database.execSQL(
                "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES('customer',?,?)",
                arrayOf(case.id, case.localRevision),
            )
            records.add(revisionRecord(
                case.baseRevision, "customer", case.id, null, payload = payload(baseValues),
            ))
            records.add(revisionRecord(
                case.backupRevision, "customer", case.id, null, payload = payload(backupValues),
            ))
            records.add(parentRecord(case.backupRevision, case.baseRevision))
            records.add(stagedRow("customers", backupValues))
            records.add(headRecord("customer", case.id, case.backupRevision))
        }

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflicts = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[1].toString() }
        assertEquals(cases.size, headConflicts.size)
        cases.forEach { case ->
            val conflict = requireNotNull(headConflicts[case.id])
            assertEquals("DIVERGENT", conflict["revisionRelationship"])
            assertEquals(
                setOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
                (conflict["allowedChoices"] as List<*>).toSet(),
            )
            assertEquals("MERGE_KEEP_LOCAL_CONTENT", conflict["defaultChoice"])
        }

        val beforeInvalidChoice = repository.snapshot()
        val revisionCountBeforeInvalidChoice = number("SELECT COUNT(*) FROM entity_revisions")
        val invalidTarget = requireNotNull(headConflicts[cases.first().id])
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                invalidTarget["conflictId"] as String, "MERGE_TAKE_BACKUP",
            )
        }
        assertEquals(beforeInvalidChoice, repository.snapshot())
        assertEquals(revisionCountBeforeInvalidChoice, number("SELECT COUNT(*) FROM entity_revisions"))
        cases.forEach { case ->
            assertEquals(case.localName, customerName(case.id))
            assertEquals(case.localRevision, database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind='customer' AND entity_id=?",
                arrayOf(case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        }

        cases.forEach { case ->
            val conflict = requireNotNull(headConflicts[case.id])
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflict["conflictId"] as String, case.selectedChoice,
            )
        }
        val beforeInjectedRollback = repository.snapshot()
        val revisionCountBeforeRollback = number("SELECT COUNT(*) FROM entity_revisions")
        val parentCountBeforeRollback = number("SELECT COUNT(*) FROM revision_parents")
        database.execSQL(
            "CREATE TRIGGER reject_backup_merge_parent BEFORE INSERT ON revision_parents " +
                "WHEN (SELECT origin FROM entity_revisions WHERE id=NEW.revision_id)='BACKUP_MERGE' " +
                "BEGIN SELECT RAISE(ABORT,'synthetic divergent merge failure'); END",
        )
        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        database.execSQL("DROP TRIGGER reject_backup_merge_parent")
        assertEquals(beforeInjectedRollback, repository.snapshot())
        assertEquals(revisionCountBeforeRollback, number("SELECT COUNT(*) FROM entity_revisions"))
        assertEquals(parentCountBeforeRollback, number("SELECT COUNT(*) FROM revision_parents"))
        cases.forEach { case ->
            assertEquals(case.localName, customerName(case.id))
            assertEquals(case.localRevision, database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind='customer' AND entity_id=?",
                arrayOf(case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        }

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        val mergedHeads = mutableMapOf<String, String>()
        cases.forEach { case ->
            val expectedName = if (case.selectedChoice == "MERGE_KEEP_LOCAL_CONTENT") {
                case.localName
            } else {
                case.backupName
            }
            assertEquals(expectedName, customerName(case.id))
            val committed = database.rawQuery(
                "SELECT h.revision_id,r.payload,r.origin FROM entity_heads h " +
                    "JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='customer' AND h.entity_id=?",
                arrayOf(case.id),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                Triple(rows.getString(0), rows.getString(1), rows.getString(2))
            }
            assertEquals("BACKUP_MERGE", committed.third)
            assertEquals(expectedName, JSONObject(committed.second).getString("name"))
            assertNotEquals(case.localRevision, committed.first)
            assertNotEquals(case.backupRevision, committed.first)
            val parents = database.rawQuery(
                "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
                arrayOf(committed.first),
            ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertEquals(setOf(case.localRevision, case.backupRevision), parents)
            mergedHeads[case.id] = committed.first
        }

        val repeatStarted = stageImport(records)
        val repeatPreview = importStore.finish(database, repeatStarted.importId)
        var repeatCursor: String? = null
        val repeatConflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, repeatStarted.importId, repeatPreview.previewToken, repeatCursor, null,
            )
            repeatConflicts.addAll(page.conflicts)
            repeatCursor = page.nextCursor
            if (page.done) break
        } while (true)
        repeatConflicts.forEach { conflict ->
            val choice = conflict["defaultChoice"] as? String
                ?: throw AssertionError("A repeat-import conflict needs an explicit safe default")
            importStore.resolveConflict(
                database, repeatStarted.importId, repeatPreview.previewToken,
                repeatPreview.localStateVersion, conflict["conflictId"] as String, choice,
            )
        }
        val versionBeforeRepeat = repository.snapshot().version
        val revisionCountBeforeRepeat = number("SELECT COUNT(*) FROM entity_revisions")
        val repeated = importStore.apply(
            database, repeatStarted.importId, repeatPreview.previewToken, repeatPreview.localStateVersion,
        )
        assertEquals(versionBeforeRepeat, repeated.stateVersion)
        assertEquals(revisionCountBeforeRepeat, number("SELECT COUNT(*) FROM entity_revisions"))
        cases.forEach { case ->
            assertEquals(mergedHeads.getValue(case.id), database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind='customer' AND entity_id=?",
                arrayOf(case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
            assertEquals(
                if (case.selectedChoice == "MERGE_KEEP_LOCAL_CONTENT") case.localName else case.backupName,
                customerName(case.id),
            )
        }
    }

    @Test
    fun simpleMutableRevisionKindsPreviewValidatedTwoSidedChoices() {
        data class Case(
            val kind: String,
            val table: String,
            val id: String,
            val jobId: String?,
            val columns: List<String>,
            val field: String,
            val localValue: String,
            val backupValue: String,
        )

        val boardColumnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val cardId = "merge-matrix-card"
        database.execSQL(
            "INSERT INTO customers(id,name,email,phone,address,notes,archived) VALUES(?,?,?,?,?,?,0)",
            arrayOf<Any?>("merge-matrix-customer", "Base customer", "", "", "", ""),
        )
        database.execSQL(
            "INSERT INTO work_sites(id,job_id,name,latitude,longitude,radius,enabled,registration_key) " +
                "VALUES(?,?,?,0,0,50,0,?)",
            arrayOf("merge-matrix-site", jobId, "Base site", UUID.randomUUID().toString()),
        )
        database.execSQL(
            "INSERT INTO kanban_columns(id,job_id,name,position,completed) VALUES(?,?,?,91,0)",
            arrayOf("merge-matrix-column", jobId, "Base column"),
        )
        database.execSQL(
            "INSERT INTO kanban_cards(id,job_id,column_id,title,description,due_date,position,completed_ms," +
                "billable,price_minor,currency,template_id,charge_type) VALUES(?,?,?,?,'',NULL,92,NULL,1,NULL,'USD',NULL,'UNCLASSIFIED')",
            arrayOf(cardId, jobId, boardColumnId, "Base card"),
        )
        database.execSQL(
            "INSERT INTO task_templates(id,title,description,price_minor,currency,source_job_id,source_card_id,charge_type) " +
                "VALUES(?,?, '',NULL,'USD',NULL,NULL,'UNCLASSIFIED')",
            arrayOf("merge-matrix-template", "Base template"),
        )
        database.execSQL(
            "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("merge-matrix-session", jobId, now + 1_000_000L, now + 1_001_000L, "Etc/UTC", 0, "MANUAL"),
        )
        database.execSQL(
            "INSERT INTO task_comments(id,task_id,body,created_ms,updated_ms) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("merge-matrix-comment", cardId, "Base comment", now, now),
        )
        database.execSQL(
            "INSERT INTO actual_costs(id,job_id,job_name,customer_id,customer_name,task_id,task_name," +
                "amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note," +
                "created_ms,updated_ms,deleted_ms) VALUES(?,NULL,'',NULL,'',NULL,'','123','USD','MATERIALS',?," +
                "'Etc/UTC',0,'Base vendor','','',?,?,NULL)",
            arrayOf<Any?>("merge-matrix-cost", now, now, now),
        )

        val cases = listOf(
            Case("customer", "customers", "merge-matrix-customer", null, BusinessStore.customerColumns,
                "name", "Local customer", "Backup customer"),
            Case("site", "work_sites", "merge-matrix-site", jobId, BusinessSchema.siteColumns,
                "name", "Local site", "Backup site"),
            Case("column", "kanban_columns", "merge-matrix-column", jobId, BusinessSchema.columnColumns,
                "name", "Local column", "Backup column"),
            Case("card", "kanban_cards", cardId, jobId, BoardStore.cardColumns,
                "title", "Local card", "Backup card"),
            Case("template", "task_templates", "merge-matrix-template", null, BoardStore.templateColumns,
                "title", "Local template", "Backup template"),
            Case("session", "sessions", "merge-matrix-session", jobId, BusinessSchema.sessionColumns,
                "origin", "REPAIR", "WIDGET"),
            Case("task_comment", "task_comments", "merge-matrix-comment", jobId,
                listOf("id", "task_id", "body", "created_ms", "updated_ms"),
                "body", "Local comment", "Backup comment"),
            Case("actual_cost", "actual_costs", "merge-matrix-cost", null, listOf(
                "id", "job_id", "job_name", "customer_id", "customer_name", "task_id", "task_name",
                "amount_minor", "currency", "category", "occurred_ms", "timezone", "offset_seconds",
                "vendor", "reference", "note", "created_ms", "updated_ms", "deleted_ms",
            ), "vendor", "Local vendor", "Backup vendor"),
        )

        val records = mutableListOf<Map<String, Any?>>()
        data class Expected(val case: Case, val localRevision: String, val backupRevision: String)
        val expected = cases.mapIndexed { index, case ->
            val baseRow = readRowValues(case.table, "id", case.id)
            val baseValues = baseRow.filterKeys(case.columns.toSet()::contains)
            val baseRevision = RevisionStore.record(database, case.kind, case.id, case.jobId, baseValues, now)
            database.execSQL(
                "UPDATE ${case.table} SET ${case.field}=? WHERE id=?",
                arrayOf(case.localValue, case.id),
            )
            val localValues = readRowValues(case.table, "id", case.id).filterKeys(case.columns.toSet()::contains)
            RevisionStore.record(database, case.kind, case.id, case.jobId, localValues, now + 1L)
                .also { assertNotEquals(baseRevision, it) }
            val localHead = database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(case.kind, case.id),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

            val backupRow = baseRow.filterKeys(case.columns.toSet()::contains).toMutableMap().apply {
                put(case.field, case.backupValue)
            }
            val backupValues = backupRow.filterKeys(case.columns.toSet()::contains)
            val backupRevision = "merge-matrix-backup-$index"
            records.add(stagedRow("entity_revisions", readRowValues("entity_revisions", "id", baseRevision)))
            records.add(revisionRecord(
                backupRevision, case.kind, case.id, case.jobId,
                payload = JSONObject(backupValues.toSortedMap()).toString(),
            ))
            records.add(parentRecord(backupRevision, baseRevision))
            records.add(stagedRow(case.table, backupRow))
            records.add(headRecord(case.kind, case.id, backupRevision))
            Expected(case, localHead, backupRevision)
        }

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflicts = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[1].toString() }
        assertEquals(expected.size, headConflicts.size)
        expected.forEach { value ->
            val conflict = requireNotNull(headConflicts[value.case.id])
            assertEquals("DIVERGENT", conflict["revisionRelationship"])
            assertEquals(
                "Unexpected choices for ${value.case.kind}",
                setOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
                (conflict["allowedChoices"] as List<*>).toSet(),
            )
        }
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun divergentSiteMergeKeepsPrivateRegistrationKeyAndBothRevisionParents() {
        val siteId = "divergent-private-registration-site"
        val registrationKey = UUID.randomUUID().toString()
        database.execSQL(
            "INSERT INTO work_sites(id,job_id,name,latitude,longitude,radius,enabled,registration_key) " +
                "VALUES(?,?,?,0,0,50,0,?)",
            arrayOf(siteId, jobId, "Base site", registrationKey),
        )
        val baseValues = readRowValues("work_sites", "id", siteId)
            .filterKeys(BusinessSchema.siteColumns.toSet()::contains)
        val baseRevision = RevisionStore.record(database, "site", siteId, jobId, baseValues, now)
        database.execSQL("UPDATE work_sites SET name=? WHERE id=?", arrayOf("Local site", siteId))
        val localValues = readRowValues("work_sites", "id", siteId)
            .filterKeys(BusinessSchema.siteColumns.toSet()::contains)
        val localRevision = RevisionStore.record(database, "site", siteId, jobId, localValues, now + 1L)
        val backupValues = baseValues + ("name" to "Backup site")
        val backupRevision = "divergent-private-registration-backup"
        val started = stageImport(listOf(
            stagedRow("entity_revisions", readRowValues("entity_revisions", "id", baseRevision)),
            revisionRecord(
                backupRevision,
                "site",
                siteId,
                jobId,
                payload = JSONObject(backupValues.toSortedMap()).toString(),
            ),
            parentRecord(backupRevision, baseRevision),
            stagedRow("work_sites", backupValues),
            headRecord("site", siteId, backupRevision),
        ))
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflict = requireNotNull(
            conflicts.singleOrNull { conflict ->
                conflict["table"] == "entity_heads" &&
                    (conflict["primaryKey"] as? List<*>)?.getOrNull(1)?.toString() == siteId
            },
        )
        assertEquals("DIVERGENT", headConflict["revisionRelationship"])
        assertTrue((headConflict["allowedChoices"] as List<*>).contains("MERGE_USE_BACKUP_CONTENT"))
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            headConflict["conflictId"] as String,
            "MERGE_USE_BACKUP_CONTENT",
        )

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals("Backup site", readRowValues("work_sites", "id", siteId)["name"])
        assertEquals(registrationKey, readRowValues("work_sites", "id", siteId)["registration_key"])
        val mergedHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='site' AND entity_id=?",
            arrayOf(siteId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val parents = database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=?",
            arrayOf(mergedHead),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertEquals(setOf(localRevision, backupRevision), parents)
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun cardMoneyAndQuantityConflictsUseOneBackupSideAndPreserveBothParents() {
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val cardId = "aggregate-card-money-quantity"
        assertNull(BoardStore.saveCard(
            database,
            BoardStore.Draft(
                cardId, jobId, columnId, "Base card", priceMinor = MoneyMath.parse("100"),
                currency = "USD", quantityUnits = 10_000L,
            ),
            now,
        ))
        val templateId = readRowValues("kanban_cards", "id", cardId).getValue("template_id").toString()
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        fun currentHead(kind: String, id: String): String = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, id),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

        fun archiveValues(table: String, keyColumn: String, key: String): MutableMap<String, Any?> {
            val record = records.first { it["table"] == table && values(it)[keyColumn]?.toString() == key }
            return values(record).entries.associate { it.key.toString() to it.value }.toMutableMap()
        }

        fun replaceArchiveRow(table: String, keyColumn: String, key: String, replacement: Map<String, Any?>) {
            records.removeAll { it["table"] == table && values(it)[keyColumn]?.toString() == key }
            records.add(stagedRow(table, replacement))
        }

        fun addBackupHead(
            kind: String,
            entityId: String,
            entityJobId: String?,
            baseRevision: String,
            backupRevision: String,
            payload: Map<String, Any?>,
        ) {
            records.add(revisionRecord(
                backupRevision, kind, entityId, entityJobId,
                payload = JSONObject(payload.toSortedMap()).toString(),
            ))
            records.add(parentRecord(backupRevision, baseRevision))
            records.removeAll {
                it["table"] == "entity_heads" && values(it)["kind"] == kind &&
                    values(it)["entity_id"] == entityId
            }
            records.add(headRecord(kind, entityId, backupRevision))
        }

        val baseCardHead = currentHead("card", cardId)
        val baseMoneyHead = currentHead("card_money", cardId)
        val baseQuantityHead = currentHead("task_quantity", cardId)
        val baseTemplateHead = currentHead("template", templateId)
        val baseTemplateMoneyHead = currentHead("template_money", templateId)
        val baseTemplateQuantityHead = currentHead("template_quantity", templateId)
        val baseCard = archiveValues("kanban_cards", "id", cardId)
            .filterKeys(BoardStore.cardColumns.toSet()::contains)
        val baseMoney = archiveValues("card_money", "id", cardId)
        val baseQuantity = archiveValues("task_quantities", "card_id", cardId)
        val baseTemplate = archiveValues("task_templates", "id", templateId)
            .filterKeys(BoardStore.templateColumns.toSet()::contains)
        val baseTemplateMoney = archiveValues("template_money", "id", templateId)
        val baseTemplateQuantity = archiveValues("template_quantities", "template_id", templateId)

        database.execSQL("UPDATE kanban_cards SET title=? WHERE id=?", arrayOf("Local card", cardId))
        val localCard = readRowValues("kanban_cards", "id", cardId).filterKeys(BoardStore.cardColumns.toSet()::contains)
        val localCardHead = RevisionStore.record(database, "card", cardId, jobId, localCard, now + 1L)
        MoneyStore.save(
            database, "card_money", cardId, jobId, MoneyMath.parse("200"), now + 1L,
        )
        val localMoneyHead = currentHead("card_money", cardId)
        database.execSQL("UPDATE task_quantities SET quantity_units=? WHERE card_id=?", arrayOf<Any?>(20_000L, cardId))
        val localQuantityHead = RevisionStore.record(
            database,
            "task_quantity",
            cardId,
            jobId,
            mapOf("card_id" to cardId, "job_id" to jobId, "quantity_units" to 20_000L),
            now + 1L,
        )
        database.execSQL("UPDATE task_templates SET title=? WHERE id=?", arrayOf("Local template", templateId))
        val localTemplate = readRowValues("task_templates", "id", templateId)
            .filterKeys(BoardStore.templateColumns.toSet()::contains)
        val localTemplateHead = RevisionStore.record(
            database, "template", templateId, null, localTemplate, now + 1L,
        )
        MoneyStore.save(
            database, "template_money", templateId, null, MoneyMath.parse("200"), now + 1L,
        )
        val localTemplateMoneyHead = currentHead("template_money", templateId)
        database.execSQL(
            "UPDATE template_quantities SET quantity_units=? WHERE template_id=?",
            arrayOf<Any?>(20_000L, templateId),
        )
        val localTemplateQuantityHead = RevisionStore.record(
            database, "template_quantity", templateId, null,
            mapOf("template_id" to templateId, "quantity_units" to 20_000L), now + 1L,
        )
        assertNotEquals(baseCardHead, localCardHead)
        assertNotEquals(baseMoneyHead, localMoneyHead)
        assertNotEquals(baseQuantityHead, localQuantityHead)
        assertNotEquals(baseTemplateHead, localTemplateHead)
        assertNotEquals(baseTemplateMoneyHead, localTemplateMoneyHead)
        assertNotEquals(baseTemplateQuantityHead, localTemplateQuantityHead)

        val backupCard = baseCard.toMutableMap().apply { put("title", "Backup card") }
        val backupMoney = baseMoney.toMutableMap().apply { put("minor_text", "300") }
        val backupQuantity = baseQuantity.toMutableMap().apply { put("quantity_units", 30_000L) }
        val backupTemplate = baseTemplate.toMutableMap().apply { put("title", "Backup template") }
        val backupTemplateMoney = baseTemplateMoney.toMutableMap().apply { put("minor_text", "300") }
        val backupTemplateQuantity = baseTemplateQuantity.toMutableMap().apply { put("quantity_units", 30_000L) }
        replaceArchiveRow("kanban_cards", "id", cardId, backupCard)
        replaceArchiveRow("card_money", "id", cardId, backupMoney)
        replaceArchiveRow("task_quantities", "card_id", cardId, backupQuantity)
        replaceArchiveRow("task_templates", "id", templateId, backupTemplate)
        replaceArchiveRow("template_money", "id", templateId, backupTemplateMoney)
        replaceArchiveRow("template_quantities", "template_id", templateId, backupTemplateQuantity)
        addBackupHead("card", cardId, jobId, baseCardHead, "aggregate-backup-card-head", backupCard)
        addBackupHead(
            "card_money", cardId, jobId, baseMoneyHead, "aggregate-backup-card-money-head",
            mapOf("id" to cardId, "minor_text" to "300", "job_id" to jobId),
        )
        addBackupHead(
            "task_quantity", cardId, jobId, baseQuantityHead, "aggregate-backup-card-quantity-head",
            mapOf("card_id" to cardId, "job_id" to jobId, "quantity_units" to 30_000L),
        )
        addBackupHead(
            "template", templateId, null, baseTemplateHead, "aggregate-backup-template-head", backupTemplate,
        )
        addBackupHead(
            "template_money", templateId, null, baseTemplateMoneyHead, "aggregate-backup-template-money-head",
            mapOf("id" to templateId, "minor_text" to "300", "job_id" to null),
        )
        addBackupHead(
            "template_quantity", templateId, null, baseTemplateQuantityHead,
            "aggregate-backup-template-quantity-head",
            mapOf("template_id" to templateId, "quantity_units" to 30_000L),
        )

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflicts = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[0].toString() }
        assertEquals(
            setOf("card", "card_money", "task_quantity", "template", "template_money", "template_quantity"),
            headConflicts.keys,
        )
        val cardConflict = requireNotNull(headConflicts["card"])
        val cardGroups = cardConflict["aggregateKeys"] as List<*>
        assertTrue(cardGroups.contains("card-fields:$cardId"))

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            cardConflict["conflictId"] as String,
            "MERGE_USE_BACKUP_CONTENT",
        )
        val templateConflict = requireNotNull(headConflicts["template"])
        assertTrue((templateConflict["aggregateKeys"] as List<*>).contains("template-fields:$templateId"))
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            templateConflict["conflictId"] as String,
            "MERGE_KEEP_LOCAL_CONTENT",
        )
        val resolvedConflicts = mutableListOf<Map<String, Any?>>()
        cursor = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            resolvedConflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        resolvedConflicts.filter { it["table"] == "entity_heads" }.forEach { conflict ->
            assertTrue(conflict["resolved"] as Boolean)
            val kind = (conflict["primaryKey"] as List<*>)[0].toString()
            val expected = if (kind in setOf("card", "card_money", "task_quantity")) {
                "MERGE_USE_BACKUP_CONTENT"
            } else {
                "MERGE_KEEP_LOCAL_CONTENT"
            }
            assertEquals(expected, conflict["selectedChoice"])
        }

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals("Backup card", readRowValues("kanban_cards", "id", cardId)["title"])
        assertEquals("300", readRowValues("card_money", "id", cardId)["minor_text"])
        assertEquals(30_000L, readRowValues("task_quantities", "card_id", cardId)["quantity_units"])
        assertEquals("Local template", readRowValues("task_templates", "id", templateId)["title"])
        assertEquals("200", readRowValues("template_money", "id", templateId)["minor_text"])
        assertEquals(20_000L, readRowValues("template_quantities", "template_id", templateId)["quantity_units"])
        listOf(
            Triple("template", localTemplateHead, "aggregate-backup-template-head"),
            Triple("template_money", localTemplateMoneyHead, "aggregate-backup-template-money-head"),
            Triple("template_quantity", localTemplateQuantityHead, "aggregate-backup-template-quantity-head"),
        ).forEach { (kind, localRevision, backupRevision) ->
            val committed = currentHead(kind, templateId)
            assertNotEquals(backupRevision, committed)
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, backupRevision),
            ).use { it.moveToFirst() })
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, localRevision),
            ).use { it.moveToFirst() })
        }
        listOf(
            Triple("card", localCardHead, "aggregate-backup-card-head"),
            Triple("card_money", localMoneyHead, "aggregate-backup-card-money-head"),
            Triple("task_quantity", localQuantityHead, "aggregate-backup-card-quantity-head"),
        ).forEach { (kind, localRevision, backupRevision) ->
            val committed = currentHead(kind, cardId)
            assertNotEquals(backupRevision, committed)
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, backupRevision),
            ).use { it.moveToFirst() })
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(committed, localRevision),
            ).use { it.moveToFirst() })
        }
        assertFalse(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }
}
