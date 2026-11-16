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
class BackupImportStoreSessionApplyTest : BackupImportStoreTestBase() {
    @Test
    fun repeatingAnIdenticalBackupImportDoesNotChangeCommittedState() {
        val records = listOf(stagedRow("customers", mapOf(
            "id" to "repeat-import-customer",
            "name" to "Repeat Import Customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )))
        val first = stageImport(records)
        val firstPreview = importStore.finish(database, first.importId)
        assertEquals(0L, firstPreview.conflictCount)
        val firstApplied = importStore.apply(
            database, first.importId, firstPreview.previewToken, firstPreview.localStateVersion,
        )
        assertEquals(3L, firstApplied.insertedCount)
        val committedAfterFirstImport = repository.snapshot()
        assertEquals(1L, number("SELECT COUNT(*) FROM customers WHERE id='repeat-import-customer'"))
        assertFalse(importStore.close(first.importId))

        val second = stageImport(records)
        val secondPreview = importStore.finish(database, second.importId)
        assertEquals(0L, secondPreview.conflictCount)
        assertEquals(3L, secondPreview.unchangedCount)
        val secondApplied = importStore.apply(
            database, second.importId, secondPreview.previewToken, secondPreview.localStateVersion,
        )

        assertEquals(0L, secondApplied.insertedCount)
        assertEquals(3L, secondApplied.skippedCount)
        assertEquals(committedAfterFirstImport, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM customers WHERE id='repeat-import-customer'"))
        assertFalse(importStore.close(second.importId))
    }

    @Test
    fun openBackupSessionIsProvisionalAndRequiresAnExplicitPreviewChoice() {
        val version = repository.snapshot().version
        assertNull(repository.execute(TimerRepository.Command(
            "start-session-for-import-preview",
            version,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val runningBefore = requireNotNull(repository.snapshot().running)
        val source = exportStore.start(database)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "9".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.openSessionCount)
        assertFalse(preview.applyReady)
        val page = importStore.readConflictPage(database, started.importId, preview.previewToken, null, null)
        val openSession = page.conflicts.single { it["kind"] == "OPEN_SESSION" }
        assertEquals(false, openSession["resolved"])
        assertEquals("OMIT_OPEN_SESSION", openSession["defaultChoice"])
        assertEquals(listOf("OMIT_OPEN_SESSION"), openSession["allowedChoices"])
        assertEquals(runningBefore, repository.snapshot().running)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun applyCanOmitAnOpenBackupSessionAndRestoreOtherRowsWithoutStartingATimer() {
        val importedCustomerId = "open-session-omitted-customer"
        val importedSessionId = "open-session-omitted-session"
        val records = listOf(
            stagedRow("customers", mapOf(
                "id" to importedCustomerId,
                "name" to "Restored beside omitted timer",
                "email" to "",
                "phone" to "",
                "address" to "",
                "notes" to "",
                "archived" to 0,
            )),
            stagedRow("sessions", mapOf(
                "id" to importedSessionId,
                "job_id" to jobId,
                "start_ms" to now,
                "end_ms" to null,
                "timezone" to "Etc/UTC",
                "offset_seconds" to 0,
                "origin" to "MANUAL",
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.openSessionCount)
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "OPEN_SESSION"))
        assertEquals(listOf("OMIT_OPEN_SESSION"), conflict["allowedChoices"])

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "OMIT_OPEN_SESSION",
        )
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )

        assertEquals(3L, applied.insertedCount)
        assertEquals(1L, applied.skippedCount)
        assertEquals("Restored beside omitted timer", customerName(importedCustomerId))
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$importedSessionId'"))
        assertNull(repository.snapshot().running)
    }

    @Test
    fun applyRestoresRowsAtomicallyAndKeepsImportedAutomationDisabled() {
        val before = repository.snapshot()
        val importedJobId = "backup-job"
        val importedCustomerId = "backup-customer"
        val revisionId = "backup-customer-revision"
        val jobRevisionId = "backup-job-revision"
        val automationRevisionId = "backup-automation-revision"
        val siteRevisionId = "backup-site-revision"
        val customerPayload = JSONObject().apply {
            put("id", importedCustomerId)
            put("name", "Backup customer")
            put("email", "backup@example.invalid")
            put("phone", "")
            put("address", "")
            put("notes", "synthetic fixture")
            put("archived", 0)
        }.toString()
        val jobPayload = JSONObject().apply {
            put("id", importedJobId)
            put("name", "Backup job")
            put("archived", 0)
            put("archived_ms", JSONObject.NULL)
            put("color_argb", 4279724885L)
            put("billing_mode", "HOURLY")
            put("currency", "USD")
            put("hourly_rate_minor", JSONObject.NULL)
            put("customer_id", JSONObject.NULL)
            put("customer_email_override", JSONObject.NULL)
        }.toString()
        val automationPayload = JSONObject().apply {
            put("job_id", importedJobId)
            put("enabled", 1)
            put("auto_start", 1)
            put("automatic_departure", 1)
            put("grace_minutes", 30)
        }.toString()
        val sitePayload = JSONObject().apply {
            put("id", "backup-disabled-site")
            put("job_id", importedJobId)
            put("name", "Imported site")
            put("latitude", 12.5)
            put("longitude", -34.5)
            put("radius", 100.0)
            put("enabled", 1)
        }.toString()
        val records = listOf(
            headRecord("customer", importedCustomerId, revisionId),
            revisionRecord(revisionId, "customer", importedCustomerId, null, payload = customerPayload),
            headRecord("job", importedJobId, jobRevisionId),
            revisionRecord(jobRevisionId, "job", importedJobId, importedJobId, payload = jobPayload),
            headRecord("automation", importedJobId, automationRevisionId),
            revisionRecord(
                automationRevisionId, "automation", importedJobId, importedJobId, payload = automationPayload,
            ),
            headRecord("site", "backup-disabled-site", siteRevisionId),
            revisionRecord(
                siteRevisionId, "site", "backup-disabled-site", importedJobId, payload = sitePayload,
            ),
            stagedRow("customers", mapOf(
                "id" to importedCustomerId,
                "name" to "Backup customer",
                "email" to "backup@example.invalid",
                "phone" to "",
                "address" to "",
                "notes" to "synthetic fixture",
                "archived" to 0,
            )),
            stagedRow("jobs", mapOf(
                "id" to importedJobId,
                "name" to "Backup job",
                "archived" to 0,
                "archived_ms" to null,
                "color_argb" to 4279724885L,
                "billing_mode" to "HOURLY",
                "currency" to "USD",
                "hourly_rate_minor" to null,
                "customer_id" to null,
                "customer_email_override" to null,
            )),
            stagedRow("automation_policies", mapOf(
                "job_id" to importedJobId,
                "enabled" to 1,
                "auto_start" to 1,
                "automatic_departure" to 1,
                "grace_minutes" to 30,
                "configured_ms" to now,
            )),
            stagedRow("work_sites", mapOf(
                "id" to "backup-disabled-site",
                "job_id" to importedJobId,
                "name" to "Imported site",
                "latitude" to 12.5,
                "longitude" to -34.5,
                "radius" to 100.0,
                "enabled" to 1,
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertTrue(preview.applyReady)

        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )

        assertEquals(records.size.toLong(), applied.insertedCount)
        assertEquals(before.version + 1L, applied.stateVersion)
        assertEquals(applied.stateVersion, repository.snapshot().version)
        assertNull(repository.snapshot().running)
        assertEquals("Backup customer", database.rawQuery(
            "SELECT name FROM customers WHERE id=?", arrayOf(importedCustomerId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(listOf(0, 0, 0), database.rawQuery(
            "SELECT enabled,auto_start,armed FROM automation_policies WHERE job_id=?",
            arrayOf(importedJobId),
        ).use { assertTrue(it.moveToFirst()); listOf(it.getInt(0), it.getInt(1), it.getInt(2)) })
        assertEquals(0L, database.rawQuery(
            "SELECT facts_since_ms FROM automation_policies WHERE job_id=?", arrayOf(importedJobId),
        ).use { assertTrue(it.moveToFirst()); it.getLong(0) })
        val siteRuntime = database.rawQuery(
            "SELECT enabled,registration_key,presence,observed_ms FROM work_sites WHERE id=?",
            arrayOf("backup-disabled-site"),
        ).use {
            assertTrue(it.moveToFirst())
            listOf(it.getInt(0), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getLong(3))
        }
        assertEquals(0, siteRuntime[0])
        assertTrue((siteRuntime[1] as String).matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        ))
        assertEquals("UNKNOWN", siteRuntime[2])
        assertNull(siteRuntime[3])
        assertTrue(database.rawQuery(
            "SELECT 1 FROM entity_heads WHERE kind='customer' AND entity_id=? AND revision_id=?",
            arrayOf(importedCustomerId, revisionId),
        ).use { it.moveToFirst() })
        listOf(
            "automation" to (importedJobId to automationRevisionId),
            "site" to ("backup-disabled-site" to siteRevisionId),
        ).forEach { (kind, entity) ->
            val normalizedRevision = database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(kind, entity.first),
            ).use { assertTrue(it.moveToFirst()); it.getString(0) }
            assertNotEquals(entity.second, normalizedRevision)
            val revision = database.rawQuery(
                "SELECT payload,origin FROM entity_revisions WHERE id=?",
                arrayOf(normalizedRevision),
            ).use { assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1) }
            assertEquals("BACKUP_RESTORE_NORMALIZE", revision.second)
            val payload = JSONObject(revision.first)
            assertEquals(0, payload.getInt("enabled"))
            if (kind == "automation") assertEquals(0, payload.getInt("auto_start"))
            assertTrue(database.rawQuery(
                "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
                arrayOf(normalizedRevision, entity.second),
            ).use { it.moveToFirst() })
        }
        assertFailure("IMPORT_UNAVAILABLE") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
    }

    @Test
    fun previewRejectsRevisionedRowsWithoutMatchingHeads() {
        val started = stageImport(listOf(stagedRow("customers", mapOf(
            "id" to "missing-revision-head-customer",
            "name" to "Untracked customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))), synthesizeMissingRevisionHeads = false)

        assertFailure("INVALID_REVISION_GRAPH") {
            importStore.finish(database, started.importId)
        }
        assertTrue(importStore.close(started.importId))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM customers WHERE id='missing-revision-head-customer'",
        ))
    }

    @Test
    fun repositoryPublishesTheCommittedImportVersion() {
        val record = stagedRow("customers", mapOf(
            "id" to "published-customer",
            "name" to "Published customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))
        val records = prepareSyntheticArchiveRecords(listOf(record))
        val started = repository.beginBackupImport(
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            records.size.toLong(),
            0,
            0,
        )
        repository.appendBackupImportPage(
            started.importId, 0, records, encodedAppendRequest(started.importId, 0, records),
        )
        val preview = repository.finishBackupImport(started.importId)
        val published = mutableListOf<Long>()
        val subscription = repository.subscribe(published::add)
        try {
            val applied = repository.applyBackupImport(
                started.importId, preview.previewToken, preview.localStateVersion,
            )
            assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
            assertEquals(applied.stateVersion, published.last())
            assertEquals("Published customer", customerName("published-customer"))
        } finally {
            subscription.close()
        }
    }

    @Test
    fun applyRestoresTaskCommentMediaAndRollsBackIfALaterAttachmentFails() {
        val firstBytes = byteArrayOf(10, 20, 30)
        val secondBytes = byteArrayOf(40, 50, 60, 70)
        val firstBlobId = "a".repeat(43)
        val secondBlobId = "b".repeat(43)
        val columnId = "backup-media-column"
        val cardId = "backup-media-card"
        val commentId = "backup-media-comment"
        val firstAttachmentId = "backup-media-first-attachment"
        val secondAttachmentId = "backup-media-second-attachment"
        fun attachmentRecord(id: String, blobId: String, bytes: ByteArray) = mapOf(
            "type" to "row",
            "table" to "task_attachments",
            "values" to mapOf(
                "id" to id,
                "task_id" to cardId,
                "comment_id" to commentId,
                "created_ms" to now,
                "mime" to "image/png",
            ),
            "blobs" to listOf(mapOf(
                "id" to blobId,
                "column" to "data",
                "sizeBytes" to bytes.size,
            )),
        )
        val records = listOf(
            stagedRow("kanban_columns", mapOf(
                "id" to columnId,
                "job_id" to jobId,
                "name" to "To do",
                "position" to 0,
                "completed" to 0,
            )),
            stagedRow("kanban_cards", mapOf(
                "id" to cardId,
                "job_id" to jobId,
                "column_id" to columnId,
                "title" to "Backup photo task",
                "description" to "",
                "due_date" to null,
                "position" to 0,
                "completed_ms" to null,
                "billable" to 1,
                "price_minor" to null,
                "currency" to "USD",
                "template_id" to null,
                "charge_type" to "UNCLASSIFIED",
            )),
            stagedRow("task_comments", mapOf(
                "id" to commentId,
                "task_id" to cardId,
                "body" to "Photo attached to this comment",
                "created_ms" to now,
                "updated_ms" to now,
            )),
            attachmentRecord(firstAttachmentId, firstBlobId, firstBytes),
            attachmentRecord(secondAttachmentId, secondBlobId, secondBytes),
        )
        val started = stageImport(
            records,
            mediaCount = 2,
            mediaBytes = (firstBytes.size + secondBytes.size).toLong(),
        )
        listOf(firstBlobId to firstBytes, secondBlobId to secondBytes).forEach { (blobId, bytes) ->
            val writeCall = MethodCall("writeBackupImportBlob", mapOf(
                "importId" to started.importId,
                "blobId" to blobId,
                "offset" to 0,
                "bytes" to bytes,
            ))
            assertEquals(
                bytes.size.toLong(),
                importStore.writeBlob(
                    started.importId,
                    blobId,
                    0,
                    bytes,
                    StandardMethodCodec.INSTANCE.encodeMethodCall(writeCall).position(),
                ),
            )
        }
        val preview = importStore.finish(database, started.importId)
        assertTrue(preview.applyReady)
        val versionBefore = repository.snapshot().version
        database.execSQL(
            "CREATE TRIGGER reject_second_imported_attachment BEFORE INSERT ON task_attachments " +
                "WHEN NEW.id='$secondAttachmentId' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END",
        )

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_columns WHERE id='$columnId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_cards WHERE id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM task_attachments WHERE id IN ('$firstAttachmentId','$secondAttachmentId')",
        ))

        database.execSQL("DROP TRIGGER reject_second_imported_attachment")
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(records.size.toLong(), applied.insertedCount)
        assertEquals(2L, number(
            "SELECT COUNT(*) FROM task_attachments WHERE id IN ('$firstAttachmentId','$secondAttachmentId')",
        ))
        mapOf(firstAttachmentId to firstBytes, secondAttachmentId to secondBytes).forEach { (id, bytes) ->
            val restored = database.rawQuery(
                "SELECT task_id,comment_id,data FROM task_attachments WHERE id=?", arrayOf(id),
            ).use {
                assertTrue(it.moveToFirst())
                Triple(it.getString(0), it.getString(1), it.getBlob(2))
            }
            assertEquals(cardId, restored.first)
            assertEquals(commentId, restored.second)
            assertArrayEquals(bytes, restored.third)
        }
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun applyRequiresFreshTokenVersionAndNoLocalOpenTimer() {
        val records = listOf(stagedRow("customers", mapOf(
            "id" to "freshness-customer",
            "name" to "Freshness customer",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        )))
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertFailure("INVALID_PREVIEW_TOKEN") {
            importStore.apply(database, started.importId, "bad-token", preview.localStateVersion)
        }
        assertFailure("PREVIEW_STALE") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion + 1L)
        }

        // Simulate a writer that failed to advance the state counter. The dedicated live-timer
        // guard must still prevent restore from racing or replacing the open session.
        database.execSQL(
            "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("local-open-for-apply", jobId, now, null, "Etc/UTC", 0, "MANUAL"),
        )
        val versionBefore = repository.snapshot().version
        assertEquals(preview.localStateVersion, versionBefore)
        assertFailure("LOCAL_TIMER_RUNNING") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertEquals(0L, number("SELECT COUNT(*) FROM customers WHERE id='freshness-customer'"))
        assertEquals("local-open-for-apply", repository.snapshot().running?.id)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun applyRejectsUnresolvedOrUnsupportedConflictChoices() {
        database.execSQL(
            "INSERT INTO customers(id,name,email,phone,address,notes,archived) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("conflicted-customer", "Local value", "", "", "", "", 0),
        )
        val started = stageImport(listOf(stagedRow("customers", mapOf(
            "id" to "conflicted-customer",
            "name" to "Backup value",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        val conflictId = conflict["conflictId"] as String
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflictId, "TAKE_BACKUP",
            )
        }
        assertEquals("Local value", customerName("conflicted-customer"))
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflictId, "KEEP_LOCAL",
        )
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(0L, applied.insertedCount)
        assertEquals(1L, applied.skippedCount)
        assertEquals(preview.localStateVersion, applied.stateVersion)
        assertEquals("Local value", customerName("conflicted-customer"))
    }

    @Test
    fun applyFailureRollsBackEarlierRowsAndLeavesPreviewRetryable() {
        val records = listOf(
            stagedRow("customers", mapOf(
                "id" to "rollback-first", "name" to "First", "email" to "", "phone" to "",
                "address" to "", "notes" to "", "archived" to 0,
            )),
            stagedRow("customers", mapOf(
                "id" to "rollback-second", "name" to "Second", "email" to "", "phone" to "",
                "address" to "", "notes" to "", "archived" to 0,
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val versionBefore = repository.snapshot().version
        database.execSQL(
            "CREATE TRIGGER reject_second_imported_customer BEFORE INSERT ON customers " +
                "WHEN NEW.id='rollback-second' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END",
        )

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(0L, number("SELECT COUNT(*) FROM customers WHERE id IN ('rollback-first','rollback-second')"))
        assertEquals(versionBefore, repository.snapshot().version)
        database.execSQL("DROP TRIGGER reject_second_imported_customer")
        val retried = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(6L, retried.insertedCount)
        assertEquals(2L, number("SELECT COUNT(*) FROM customers WHERE id IN ('rollback-first','rollback-second')"))
    }

    @Test
    fun applyRejectsAndRollsBackWhenLocalRevisionHeadOwnershipIsInvalid() {
        val unrelatedRevision = RevisionStore.record(
            database,
            "customer",
            "synthetic-unrelated-head-owner",
            null,
            mapOf("id" to "synthetic-unrelated-head-owner"),
            now,
        )
        database.execSQL(
            "UPDATE entity_heads SET revision_id=? WHERE kind='job' AND entity_id=?",
            arrayOf(unrelatedRevision, jobId),
        )
        val started = stageImport(listOf(stagedRow("customers", mapOf(
            "id" to "must-rollback-on-invalid-resulting-graph",
            "name" to "Must not commit",
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0,
        ))))
        val preview = importStore.finish(database, started.importId)
        val versionBeforeApply = repository.snapshot().version

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(0L, number(
            "SELECT COUNT(*) FROM customers WHERE id='must-rollback-on-invalid-resulting-graph'",
        ))
        assertEquals(versionBeforeApply, repository.snapshot().version)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun scheduledExpiryClosesAndDeletesPrivateImportFiles() {
        var elapsed = 100L
        val scheduler = ManualScheduler { elapsed }
        val expiring = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            elapsedClock = { elapsed },
            idleTtlMillis = 50L,
            scheduler = scheduler,
        )
        val started = expiring.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            0,
            0,
            0,
        )
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        val stageDb = File(directory, "clockrove-import-${started.importId}.db")
        val media = File(directory, "clockrove-import-${started.importId}-media.bin")
        assertTrue(stageDb.exists())
        assertTrue(media.exists())
        elapsed += 51L
        scheduler.runDueTasks()
        assertFalse(stageDb.exists())
        assertFalse(media.exists())
        assertFailure("IMPORT_UNAVAILABLE") { expiring.finish(database, started.importId) }
    }

    @Test
    fun failedImportCleanupRetriesDatabaseSidecarsAndMedia() {
        var elapsed = 2_000L
        var failingPath: String? = null
        var failuresRemaining = 1
        val scheduler = ManualScheduler { elapsed }
        val cleanupStore = BackupImportStore(
            app,
            catalogProvider = exportStore::importCatalog,
            elapsedClock = { elapsed },
            scheduler = scheduler,
            deleteFile = { file ->
                if (file.absolutePath == failingPath && failuresRemaining > 0) {
                    failuresRemaining--
                    false
                } else {
                    file.delete()
                }
            },
        )
        val started = cleanupStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
            0,
            0,
            0,
        )
        val directory = File(app.noBackupFilesDir, "clockrove-backup-imports")
        val stageDb = File(directory, "clockrove-import-${started.importId}.db")
        val sidecar = File(stageDb.path + "-wal")
        val media = File(directory, "clockrove-import-${started.importId}-media.bin")
        assertTrue(sidecar.createNewFile())
        failingPath = media.absolutePath

        assertFailure("CLEANUP_FAILED") { cleanupStore.close(started.importId) }
        assertFalse(stageDb.exists())
        assertFalse(sidecar.exists())
        assertTrue(media.exists())
        assertFailure("IMPORT_UNAVAILABLE") { cleanupStore.finish(database, started.importId) }
        assertTrue(cleanupStore.close(started.importId))
        assertFalse(media.exists())
    }
}
