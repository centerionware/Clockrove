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
class BackupImportStoreGpsDeletionTest : BackupImportStoreTestBase() {
    @Test
    fun stripsExportOnlySha256BeforeNativeImportStaging() {
        val validBlobId = "sha256-strip-blob-" + "0".repeat(25)
        val exportRecord = mapOf<String, Any?>(
            "type" to "row",
            "table" to "logo_assets",
            "values" to mapOf(
                "id" to "sha256-strip-logo",
                "mime" to "image/png",
                "width" to 1,
                "height" to 1,
                "content_hash" to "synthetic-content-hash",
            ),
            "blobs" to listOf(mapOf(
                "id" to validBlobId,
                "column" to "bytes",
                "sizeBytes" to 3L,
                "sha256" to "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
            )),
        )

        val stagedRecord = stripExportOnlySha256(listOf(exportRecord)).single()
        val exportDescriptor = (exportRecord["blobs"] as List<*>).single() as Map<*, *>
        val stagedDescriptor = (stagedRecord["blobs"] as List<*>).single() as Map<*, *>

        assertTrue(exportDescriptor.containsKey("sha256"))
        assertFalse(stagedDescriptor.containsKey("sha256"))
        assertEquals(setOf("id", "column", "sizeBytes"), stagedDescriptor.keys)
        assertEquals(exportRecord["values"], stagedRecord["values"])

        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "f".repeat(64),
            1,
            1,
            3,
        )
        val stagedRecords = listOf(stagedRecord)
        assertEquals(
            1L,
            importStore.appendPage(
                started.importId,
                0,
                stagedRecords,
                encodedAppendRequest(started.importId, 0, stagedRecords),
            ),
        )
    }

    @Test
    fun backupOnlyPointCoveredByLocalRangeOmitsItsCompleteRevisionGraph() {
        val (sessionId, measuredAt) = startAndStopSessionWithPoint("local-point-for-backup-only-preserve")
        assertNull(repository.deleteLocationRange(
            "create-empty-local-deletion-range",
            repository.snapshot().version,
            GpsDeletionStore.Range(jobId, measuredAt, measuredAt + 1L, "Etc/UTC"),
        ).conflict)
        val localStateBeforePreview = repository.snapshot()
        val incomingPointId = "backup-only-covered-point"
        val incomingPointRevision = "backup-only-covered-point-revision"
        val started = stageImport(listOf(
            revisionRecord(
                incomingPointRevision,
                "location",
                incomingPointId,
                jobId,
                payload = JSONObject().put("id", incomingPointId)
                    .put("session_id", sessionId).toString(),
            ),
            headRecord("location", incomingPointId, incomingPointRevision),
            stagedRow("location_points", mapOf(
                "id" to incomingPointId,
                "session_id" to sessionId,
                "measured_ms" to measuredAt,
                "received_ms" to measuredAt,
                "latitude" to 12.0,
                "longitude" to -34.0,
                "accuracy" to 5.0,
            )),
        ))

        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals(listOf("PRESERVE_DELETION"), conflict["allowedChoices"])
        assertFalse(conflict["resolved"] as Boolean)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "PRESERVE_DELETION",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(localStateBeforePreview, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertEquals(1L, number("SELECT COUNT(*) FROM sessions WHERE id='$sessionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$incomingPointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location' AND entity_id='$incomingPointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$incomingPointRevision'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents WHERE revision_id='$incomingPointRevision' OR parent_id='$incomingPointRevision'",
        ))
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun stagedRangeConflictCanKeepAnUnparentedBackupPointByOmittingTheRange() {
        val (sessionId, measuredAt) = sessionWithoutLivePoint("staged-range-keep-point")
        val rangeId = "staged-range-keep-point-range"
        val rangeRevisionId = "staged-range-keep-point-range-revision"
        val pointId = "staged-range-keep-point-point"
        val pointRevisionId = "staged-range-keep-point-point-revision"
        val started = stageImport(backupRangeAndUnparentedPointRecords(
            rangeId, rangeRevisionId, pointId, pointRevisionId, sessionId, measuredAt,
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals("location_deletion_ranges", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "KEEP_LOCAL",
        )

        val applied = importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$rangeRevisionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='location' AND h.entity_id='$pointId' AND r.deleted=0",
        ))
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun stagedRangeConflictCanApplyDeletionAndOmitUnparentedBackupPointGraph() {
        val (sessionId, measuredAt) = sessionWithoutLivePoint("staged-range-apply-delete")
        val rangeId = "staged-range-apply-delete-range"
        val rangeRevisionId = "staged-range-apply-delete-range-revision"
        val pointId = "staged-range-apply-delete-point"
        val pointRevisionId = "staged-range-apply-delete-point-revision"
        val started = stageImport(backupRangeAndUnparentedPointRecords(
            rangeId, rangeRevisionId, pointId, pointRevisionId, sessionId, measuredAt,
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals("location_deletion_ranges", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )

        val applied = importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(preview.localStateVersion + 1L, applied.stateVersion)
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId' AND revision_id='$rangeRevisionId'",
        ))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$pointRevisionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location' AND entity_id='$pointId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_heads h LEFT JOIN entity_revisions r ON r.id=h.revision_id WHERE r.id IS NULL",
        ))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents p LEFT JOIN entity_revisions child ON child.id=p.revision_id " +
                "LEFT JOIN entity_revisions parent ON parent.id=p.parent_id WHERE child.id IS NULL OR parent.id IS NULL",
        ))
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun manualGpsTombstoneRequiresExplicitPreservationAndKeepsThePointDeleted() {
        val (sessionId, measuredAt) = startAndStopSessionWithPoint("manual-tombstone-import-point")
        val source = exportStore.start(database)
        val sessionEnd = database.rawQuery(
            "SELECT end_ms FROM sessions WHERE id=?",
            arrayOf(sessionId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getLong(0) }
        val correctedStart = measuredAt + 1L
        assertNull(repository.correctSession(
            "correct-session-before-backup-restore",
            repository.snapshot().version,
            SessionHistoryStore.Draft(
                sessionId, jobId, correctedStart, sessionEnd, "Etc/UTC", 0,
            ),
        ).conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))

        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "c".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)
        val tombstone = requireNotNull(findConflict(started.importId, preview, "GPS_TOMBSTONE")) {
            "conflicts=${conflictDebug(readAllConflicts(started.importId, preview))}"
        }
        assertEquals("PRESERVE_LOCAL_DELETION", tombstone["defaultChoice"])
        assertEquals(listOf("PRESERVE_LOCAL_DELETION"), tombstone["allowedChoices"])
        assertFalse(tombstone["resolved"] as Boolean)

        val conflicts = importStore.readConflictPage(
            database, started.importId, preview.previewToken, null, null,
        )
        assertTrue(conflicts.done)
        conflicts.conflicts.forEach { conflict ->
            val choice = when (conflict["kind"]) {
                "GPS_TOMBSTONE" -> "PRESERVE_LOCAL_DELETION"
                else -> "KEEP_LOCAL"
            }
            assertTrue(
                "${conflict["kind"]}/${conflict["table"]} missing $choice; " +
                    "allowed=${conflict["allowedChoices"]}",
                (conflict["allowedChoices"] as List<*>).contains(choice),
            )
            try {
                importStore.resolveConflict(
                    database,
                    started.importId,
                    preview.previewToken,
                    preview.localStateVersion,
                    conflict["conflictId"] as String,
                    choice,
                )
            } catch (failure: BackupImportStore.Failure) {
                fail(
                    "Resolving ${conflict["kind"]}/${conflict["table"]} as $choice failed: " +
                        "${failure.message}; allowed=${conflict["allowedChoices"]}",
                )
            }
        }

        val versionBeforeApply = repository.snapshot().version
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(0L, applied.insertedCount)
        assertEquals(versionBeforeApply, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(correctedStart, database.rawQuery(
            "SELECT start_ms FROM sessions WHERE id=?",
            arrayOf(sessionId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getLong(0) })
        assertNull(repository.snapshot().running)
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun importedRangeCanBeKeptLocalWithoutChangingThePointGraph() {
        val pointId = "local-point-covered-by-import"
        val session = startAndStopSessionWithPoint(pointId)
        val rangeId = "synthetic-imported-range"
        val revisionId = "synthetic-imported-range-revision"
        val rangeStart = session.second
        val rangeEnd = session.second + 1L
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to rangeStart,
            "end_ms" to rangeEnd,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to revisionId,
        )
        val records = listOf(
            revisionRecord(
                revisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", rangeStart).put("end_ms", rangeEnd)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, revisionId),
            stagedRow("location_deletion_ranges", range),
        )
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges"))

        val before = repository.snapshot()
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
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
        val preview = importStore.finish(database, started.importId)
        val coverageConflict = findConflict(started.importId, preview, "GPS_DELETION_COVERAGE")
        assertNotNull(coverageConflict)
        val conflict = requireNotNull(coverageConflict)
        assertNull(conflict["defaultChoice"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        assertEquals(false, conflict["resolved"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        val localHeadBefore = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals(before, repository.snapshot())
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertEquals(localHeadBefore, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$revisionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId'"))
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun importedRangeDeletionAddsCausalParentsAndRemovesCoveredLocalPoint() {
        val pointId = "local-point-deleted-by-import"
        val measuredAt = startAndStopSessionWithPoint(pointId).second
        val rangeId = "synthetic-import-delete-range"
        val rangeRevisionId = "synthetic-import-delete-range-revision"
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
        val records = listOf(
            revisionRecord(
                rangeRevisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", rangeStart).put("end_ms", rangeEnd)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, rangeRevisionId),
            stagedRow("location_deletion_ranges", range),
        )
        val localHeadBefore = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val before = repository.snapshot()
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "GPS_DELETION_COVERAGE"))
        assertEquals("location_deletion_ranges", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), conflict["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            conflict["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )

        val applied = importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(before.version + 1L, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        val tombstoneRevisionId = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertNotEquals(localHeadBefore, tombstoneRevisionId)
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE id='$tombstoneRevisionId' AND deleted=1 AND origin='GPS_RANGE_DELETE'",
        ))
        val parents = database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=?",
            arrayOf(tombstoneRevisionId),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertEquals(setOf(localHeadBefore, rangeRevisionId), parents)
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents p LEFT JOIN entity_revisions child ON child.id=p.revision_id " +
                "LEFT JOIN entity_revisions parent ON parent.id=p.parent_id WHERE child.id IS NULL OR parent.id IS NULL",
        ))
        assertEquals(applied.stateVersion, repository.snapshot().version)
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun failedImportedRangeTombstoneRollsBackRangePointAndRevisionGraph() {
        val pointId = "range-rollback-local-point"
        val (_, measuredAt) = startAndStopSessionWithPoint(pointId)
        val rangeId = "range-rollback-imported-range"
        val rangeRevisionId = "range-rollback-imported-range-revision"
        val range = mapOf<String, Any?>(
            "id" to rangeId,
            "job_id" to jobId,
            "start_ms" to measuredAt,
            "end_ms" to measuredAt + 1L,
            "timezone_id" to "Etc/UTC",
            "deleted_ms" to now,
            "revision_id" to rangeRevisionId,
        )
        val started = stageImport(listOf(
            revisionRecord(
                rangeRevisionId, "location_deletion_range", rangeId, jobId,
                origin = "GPS_RANGE_DELETE",
                payload = JSONObject().put("id", rangeId).put("job_id", jobId)
                    .put("start_ms", measuredAt).put("end_ms", measuredAt + 1L)
                    .put("timezone_id", "Etc/UTC").toString(),
            ),
            headRecord("location_deletion_range", rangeId, rangeRevisionId),
            stagedRow("location_deletion_ranges", range),
        ))
        val preview = importStore.finish(database, started.importId)
        val coverageConflict = requireNotNull(findConflict(
            started.importId, preview, "GPS_DELETION_COVERAGE",
        ))
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            coverageConflict["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )
        val localPointHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val versionBefore = repository.snapshot().version
        database.execSQL(
            "CREATE TRIGGER reject_imported_gps_tombstone BEFORE INSERT ON entity_revisions " +
                "WHEN NEW.kind='location' AND NEW.entity_id='$pointId' AND NEW.origin='GPS_RANGE_DELETE' " +
                "BEGIN SELECT RAISE(ABORT,'synthetic imported GPS tombstone failure'); END",
        )

        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$rangeRevisionId'"))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_heads WHERE kind='location_deletion_range' AND entity_id='$rangeId'",
        ))
        assertEquals(localPointHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE kind='location' AND entity_id='$pointId' " +
                "AND origin='GPS_RANGE_DELETE'",
        ))
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM revision_parents WHERE revision_id='$rangeRevisionId' OR parent_id='$rangeRevisionId'",
        ))

        database.execSQL("DROP TRIGGER reject_imported_gps_tombstone")
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(versionBefore + 1L, applied.stateVersion)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points WHERE id='$pointId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges WHERE id='$rangeId'"))
        val tombstoneRevisionId = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='location' AND entity_id=?",
            arrayOf(pointId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertEquals(1L, number(
            "SELECT COUNT(*) FROM entity_revisions WHERE id='$tombstoneRevisionId' " +
                "AND deleted=1 AND origin='GPS_RANGE_DELETE'",
        ))
        assertEquals(setOf(localPointHead, rangeRevisionId), database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=?",
            arrayOf(tombstoneRevisionId),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } })
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun stagingFullRowsAndMediaProducesBoundedReadOnlyPreview() {
        val logo = ByteArray(420_123) { (it * 17 % 251).toByte() }
        database.execSQL(
            "INSERT INTO logo_assets(id,mime,width,height,content_hash,bytes) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>("synthetic-logo", "image/png", 32, 32, "synthetic-hash", logo),
        )
        val source = exportStore.start(database)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "a".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        assertEquals(1L, source.mediaCount)
        assertEquals(logo.size.toLong(), source.mediaBytes)
        val stateBeforePreview = repository.snapshot()

        val blobs = mutableListOf<Pair<String, Long>>()
        var firstOrdinal = 0L
        var cursor: String? = null
        var done = false
        while (!done) {
            val page = exportStore.readPage(source.snapshotId, cursor, started.maxPageBytes)
            val importRecords = stripExportOnlySha256(page.records)
            assertTrue(
                encodedAppendRequest(started.importId, firstOrdinal, importRecords) <=
                    BackupImportStore.MAX_REQUEST_BYTES,
            )
            page.records.forEach { record ->
                val descriptors = record["blobs"] as List<*>
                descriptors.forEach { raw ->
                    val descriptor = raw as Map<*, *>
                    val blobId = descriptor["id"] as String
                    val sizeBytes = descriptor["sizeBytes"] as Number
                    blobs.add(blobId to sizeBytes.toLong())
                }
            }
            firstOrdinal = importStore.appendPage(
                started.importId,
                firstOrdinal,
                importRecords,
                encodedAppendRequest(started.importId, firstOrdinal, importRecords),
            )
            cursor = page.nextCursor
            done = page.done
        }

        blobs.forEach { (blobId, size) ->
            var offset = 0L
            while (offset < size) {
                val sourceChunk = exportStore.readBlob(
                    source.snapshotId,
                    blobId,
                    offset,
                    BackupImportStore.MAX_BLOB_CHUNK_BYTES,
                )
                val requestBytes = StandardMethodCodec.INSTANCE.encodeMethodCall(MethodCall(
                    "writeBackupImportBlob",
                    mapOf("importId" to started.importId, "blobId" to blobId, "offset" to offset, "bytes" to sourceChunk.bytes),
                )).position()
                assertTrue(requestBytes <= BackupImportStore.MAX_REQUEST_BYTES)
                if (offset == 0L) {
                    assertFailure("INVALID_BLOB_OFFSET") {
                        importStore.writeBlob(started.importId, blobId, sourceChunk.nextOffset, sourceChunk.bytes, requestBytes)
                    }
                }
                offset = importStore.writeBlob(
                    started.importId,
                    blobId,
                    offset,
                    sourceChunk.bytes,
                    requestBytes,
                )
                if (sourceChunk.offset == 0L) {
                    assertFailure("INVALID_BLOB_OFFSET") {
                        importStore.writeBlob(started.importId, blobId, 0, sourceChunk.bytes, requestBytes)
                    }
                }
            }
            assertEquals(size, offset)
        }

        val preview = importStore.finish(database, started.importId)
        assertEquals(source.recordCount, preview.recordCount)
        assertEquals(source.mediaCount, preview.mediaCount)
        assertEquals(0L, preview.addedCount)
        assertEquals(source.recordCount, preview.unchangedCount)
        assertEquals(0L, preview.conflictCount)
        assertEquals(0L, preview.openSessionCount)
        assertTrue(preview.applyReady)
        assertEquals(stateBeforePreview, repository.snapshot())
        val conflicts = importStore.readConflictPage(database, started.importId, preview.previewToken, null, null)
        assertTrue(conflicts.done)
        assertTrue(conflicts.conflicts.isEmpty())

        val localVersion = repository.snapshot().version
        assertEquals(localVersion, preview.localStateVersion)
        val settings = repository.jobDetail(jobId).job.settings.copy(name = "Changed after preview")
        assertNull(repository.saveJobSettings("change-after-preview", localVersion, settings).conflict)
        assertFailure("PREVIEW_STALE") {
            importStore.readConflictPage(database, started.importId, preview.previewToken, null, null)
        }
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }
}
