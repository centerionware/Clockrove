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
class BackupImportStoreRevisionValidationTest : BackupImportStoreTestBase() {
    @Test
    fun rejectsUnsupportedCrossEntityRevisionParents() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("job-revision", "job", "job-one", "job-one"),
            revisionRecord("customer-revision", "customer", "customer-one", null),
            parentRecord("job-revision", "customer-revision"),
            headRecord("job", "job-one", "job-revision"),
            headRecord("customer", "customer-one", "customer-revision"),
        ))
    }

    @Test
    fun rejectsMissingRevisionParentsAndCycles() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("child", "job", "job-one", "job-one"),
            parentRecord("child", "missing-parent"),
            headRecord("job", "job-one", "child"),
        ))

        assertInvalidRevisionGraph(listOf(
            revisionRecord("first", "job", "job-one", "job-one"),
            revisionRecord("second", "job", "job-one", "job-one"),
            parentRecord("first", "second"),
            parentRecord("second", "first"),
            headRecord("job", "job-one", "first"),
        ))
    }

    @Test
    fun rejectsRevisionsWithoutAnOwnedHead() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("unheaded-revision", "job", "job-one", "job-one"),
        ))
    }

    @Test
    fun rejectsGpsRevisionPayloadsContainingCoordinatesOrOtherFields() {
        val pointId = "privacy-sensitive-point"
        val validPrefix = "\"id\":\"$pointId\",\"session_id\":\"synthetic-session\""
        val invalidPayloads = listOf(
            "{malformed",
            "[]",
            "{\"id\":\"different-point\",\"session_id\":\"synthetic-session\"}",
            "{$validPrefix,\"private\":\"gps-payload-canary\"}",
            "{$validPrefix,\"latitude\":12.0}",
            "{$validPrefix,\"longitude\":-34.0}",
            "{$validPrefix,\"accuracy\":5.0}",
            "{$validPrefix,\"measured_ms\":123}",
            "{$validPrefix,\"received_ms\":123}",
        )
        invalidPayloads.forEachIndexed { index, payload ->
            val revisionId = "invalid-location-payload-$index"
            assertInvalidRevisionGraph(
                listOf(
                    revisionRecord(
                        revisionId, "location", pointId, jobId, payload = payload,
                    ),
                    headRecord("location", pointId, revisionId),
                ),
                forbiddenDiagnostic = "gps-payload-canary",
            )
        }
    }

    @Test
    fun acceptsRedactedNullRevisionPayload() {
        val revisionId = "redacted-job-revision"
        val records = listOf(
            revisionRecord(revisionId, "job", "redacted-job", "redacted-job", payload = null),
            headRecord("job", "redacted-job", revisionId),
        )
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

        val preview = importStore.finish(database, started.importId)

        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun rejectsRowsWithUnresolvedForeignKeys() {
        assertInvalidReference(listOf(stagedRow("sessions", mapOf(
            "id" to "orphan-session",
            "job_id" to "missing-job",
            "start_ms" to now,
            "end_ms" to null,
            "timezone" to "Etc/UTC",
            "offset_seconds" to 0,
            "origin" to "MANUAL",
        ))))
    }

    @Test
    fun previewWithoutInvoiceTimeClaimsIsValid() {
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            0,
            0,
            0,
        )

        val preview = importStore.finish(database, started.importId)

        assertEquals(0L, preview.recordCount)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun rejectsInvoiceTaskClaimWhoseJobDiffersFromItsInvoice() {
        assertInvalidReference(listOf(
            invoiceRecord("mismatch-task-invoice", jobId, "INV-SYNTHETIC-TASK"),
            stagedRow("invoice_task_claims", mapOf(
                "id" to "mismatch-task-claim",
                "invoice_id" to "mismatch-task-invoice",
                "job_id" to "synthetic-other-job",
                "task_id" to "synthetic-task",
                "released_ms" to null,
            )),
        ))
    }

    @Test
    fun rejectsInvoiceTimeClaimWhoseJobDiffersFromItsInvoice() {
        assertInvalidReference(listOf(
            invoiceRecord("mismatch-time-invoice", jobId, "INV-SYNTHETIC-TIME"),
            invoiceTimeClaimRecord(
                "mismatch-time-claim", "mismatch-time-invoice", "synthetic-other-job", 100, 200,
            ),
        ))
    }

    @Test
    fun rejectsOverlappingActiveImportedInvoiceTimeClaims() {
        assertInvalidReference(listOf(
            invoiceRecord("overlap-import-invoice", jobId, "INV-SYNTHETIC-OVERLAP"),
            invoiceTimeClaimRecord("overlap-import-claim-a", "overlap-import-invoice", jobId, 100, 200),
            invoiceTimeClaimRecord("overlap-import-claim-b", "overlap-import-invoice", jobId, 199, 300),
        ))
    }

    @Test
    fun omittingConflictedInvoiceRequiresExplicitOmissionOfDependentClaim() {
        database.execSQL(
            "INSERT INTO invoices(id,job_id,number,created_ms,payload) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("local-number-owner", jobId, "INV-DEPENDENCY-OWNER", now, "{}"),
        )
        val backupInvoiceId = "backup-number-collision"
        val started = stageImport(listOf(
            invoiceRecord(backupInvoiceId, jobId, "INV-DEPENDENCY-OWNER"),
            invoiceTimeClaimRecord(
                "backup-claim-referencing-omitted-invoice", backupInvoiceId, jobId, 100, 200,
            ),
        ))
        val preview = importStore.finish(database, started.importId)
        assertEquals(2L, preview.conflictCount)
        val invoiceConflict = requireNotNull(findConflict(started.importId, preview, "UNIQUE_VALUE_IN_USE"))
        val dependentConflict = requireNotNull(findConflict(started.importId, preview, "DEPENDENT_ON_CONFLICT"))
        assertEquals(listOf("OMIT_BACKUP_RECORD"), dependentConflict["allowedChoices"])
        assertNull(dependentConflict["defaultChoice"])
        assertFalse(dependentConflict["resolved"] as Boolean)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            invoiceConflict["conflictId"] as String, "OMIT_BACKUP_RECORD",
        )
        val resolvedDependent = requireNotNull(findConflict(
            started.importId, preview, "DEPENDENT_ON_CONFLICT",
        ))
        assertEquals(dependentConflict["conflictId"], resolvedDependent["conflictId"])
        assertTrue(resolvedDependent["resolved"] as Boolean)
        assertEquals("OMIT_BACKUP_RECORD", resolvedDependent["selectedChoice"])
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(0L, number("SELECT COUNT(*) FROM invoices WHERE id='backup-number-collision'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM invoice_time_claims WHERE id='backup-claim-referencing-omitted-invoice'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM invoices WHERE id='local-number-owner'"))
    }

    @Test
    fun rejectsImportedInvoiceTimeClaimOverlappingAnExistingLocalClaim() {
        database.execSQL(
            "INSERT INTO invoices(id,job_id,number,created_ms,payload) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("local-overlap-invoice", jobId, "INV-LOCAL-OVERLAP", now, "{}"),
        )
        database.execSQL(
            "INSERT INTO invoice_time_claims(id,invoice_id,job_id,session_id,start_ms,end_ms,timezone) " +
                "VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "local-overlap-claim", "local-overlap-invoice", jobId, "local-overlap-session", 100, 200, "Etc/UTC",
            ),
        )
        assertInvalidReference(listOf(
            invoiceRecord("incoming-overlap-invoice", jobId, "INV-INCOMING-OVERLAP"),
            invoiceTimeClaimRecord("incoming-overlap-claim", "incoming-overlap-invoice", jobId, 150, 250),
        ))
    }

    @Test
    fun acceptsDisjointAndReleasedInvoiceTimeClaims() {
        val records = listOf(
            invoiceRecord("valid-time-claims-invoice", jobId, "INV-SYNTHETIC-VALID-TIME"),
            invoiceTimeClaimRecord("valid-time-claim-a", "valid-time-claims-invoice", jobId, 100, 200),
            invoiceTimeClaimRecord("valid-time-claim-b", "valid-time-claims-invoice", jobId, 200, 300),
            invoiceTimeClaimRecord(
                "released-time-claim", "valid-time-claims-invoice", jobId, 150, 250, releasedMs = now,
            ),
        )
        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "b".repeat(64),
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

        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun localOpenTimerBlocksApplyReadinessUntilClockedOutAndPreviewedAgain() {
        assertNull(repository.execute(TimerRepository.Command(
            "start-local-timer-before-backup-preview",
            repository.snapshot().version,
            TimerRepository.Action.START,
            jobId,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val runningState = repository.snapshot()
        assertNotNull(runningState.running)

        val started = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "d".repeat(64),
            0,
            0,
            0,
        )
        val preview = importStore.finish(database, started.importId)
        assertEquals(0L, preview.openSessionCount)
        assertEquals(1L, preview.localOpenSessionCount)
        assertFalse(preview.applyReady)
        assertEquals(runningState, repository.snapshot())
        assertTrue(importStore.close(started.importId))

        now += 1_000L
        assertNull(repository.execute(TimerRepository.Command(
            "explicit-clock-out-before-backup-preview",
            repository.snapshot().version,
            TimerRepository.Action.STOP,
            null,
            now,
            "Etc/UTC",
            0,
            TimerRepository.Origin.MANUAL,
        )).conflict)
        val stoppedState = repository.snapshot()
        assertNull(stoppedState.running)

        val fresh = importStore.begin(
            database,
            BackupExportStore.ARCHIVE_FORMAT_VERSION,
            BackupExportStore.CURRENT_SCHEMA_VERSION,
            "e".repeat(64),
            0,
            0,
            0,
        )
        val freshPreview = importStore.finish(database, fresh.importId)
        assertEquals(0L, freshPreview.localOpenSessionCount)
        assertTrue(freshPreview.applyReady)
        assertEquals(stoppedState, repository.snapshot())
        assertTrue(importStore.close(fresh.importId))
    }

    @Test
    fun rejectsOutOfRangeAndNoncanonicalPortableValues() {
        val (sessionId, measuredAt) = startAndStopSessionWithPoint("invalid-coordinate-reference")
        val invalidPointId = "invalid-coordinate-point"
        val invalidPointRevisionId = "invalid-coordinate-revision"
        assertInvalidArchive(listOf(
            revisionRecord(invalidPointRevisionId, "location", invalidPointId, jobId),
            headRecord("location", invalidPointId, invalidPointRevisionId),
            stagedRow("location_points", mapOf(
                "id" to invalidPointId,
                "session_id" to sessionId,
                "measured_ms" to measuredAt,
                "received_ms" to measuredAt,
                "latitude" to 91.0,
                "longitude" to -34.0,
                "accuracy" to 5.0,
            )),
        ))
        assertInvalidArchive(listOf(stagedRow("quotes", mapOf(
            "id" to "noncanonical-quote",
            "number" to "Q-INVALID",
            "created_ms" to "not-an-integer",
        ))))
        assertInvalidArchive(listOf(stagedRow("customers", mapOf(
            "id" to "missing-customer-name",
            "name" to null,
            "email" to "",
            "phone" to "",
            "address" to "",
            "notes" to "",
            "archived" to 0L,
        ))))
    }

    @Test
    fun integralRealLocationValuesRoundTripThroughPreview() {
        startAndStopSessionWithPoint("integral-real-preview-point")
        val before = repository.snapshot()
        val source = exportStore.start(database)
        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "e".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )

        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)

        assertEquals(source.recordCount, preview.recordCount)
        assertEquals(0L, preview.conflictCount)
        assertEquals(before, repository.snapshot())
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun rejectsRevisionHeadsOwnedByAnotherEntity() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("job-revision", "job", "job-one", "job-one"),
            headRecord("job", "job-one", "job-revision"),
            headRecord("customer", "customer-one", "job-revision"),
        ))
    }

    @Test
    fun rejectsUnknownRevisionKindsAndMalformedObjectPayloadsBeforePreview() {
        assertInvalidRevisionGraph(listOf(
            revisionRecord("unknown-kind-revision", "future_kind", "future-entity", null),
            headRecord("future_kind", "future-entity", "unknown-kind-revision"),
        ))

        listOf("not-json", "[]").forEachIndexed { index, payload ->
            val revisionId = "malformed-object-payload-$index"
            assertInvalidRevisionGraph(listOf(
                revisionRecord(
                    revisionId, "quote_decision", "malformed-quote:$index", null, payload = payload,
                ),
                headRecord("quote_decision", "malformed-quote:$index", revisionId),
            ))
        }
    }

    @Test
    fun conflictingHeadsClassifyAncestorDescendantAndDivergentHistoryExplicitly() {
        data class Node(val id: String, val parent: String? = null)
        data class Case(
            val entityId: String,
            val localNodes: List<Node>,
            val localHead: String,
            val backupNodes: List<Node>,
            val backupHead: String,
            val relationship: String,
        )
        val kind = "quote_decision"
        val cases = listOf(
            Case(
                "backup-descendant-case",
                listOf(Node("descendant-base")),
                "descendant-base",
                listOf(Node("descendant-base"), Node("descendant-backup", "descendant-base")),
                "descendant-backup",
                "BACKUP_DESCENDANT",
            ),
            Case(
                "backup-ancestor-case",
                listOf(Node("ancestor-base"), Node("ancestor-local", "ancestor-base")),
                "ancestor-local",
                listOf(Node("ancestor-base")),
                "ancestor-base",
                "BACKUP_ANCESTOR",
            ),
            Case(
                "divergent-history-case",
                listOf(Node("divergent-base"), Node("divergent-local", "divergent-base")),
                "divergent-local",
                listOf(Node("divergent-base"), Node("divergent-backup", "divergent-base")),
                "divergent-backup",
                "DIVERGENT",
            ),
        )

        cases.forEach { case ->
            case.localNodes.forEach { node ->
                insertSyntheticRevision(node.id, kind, case.entityId, node.parent)
            }
            database.execSQL(
                "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
                arrayOf(kind, case.entityId, case.localHead),
            )
            val backupRecords = case.backupNodes.flatMap { node ->
                buildList {
                    add(revisionRecord(node.id, kind, case.entityId, null))
                    node.parent?.let { add(parentRecord(node.id, it)) }
                }
            } + headRecord(kind, case.entityId, case.backupHead)
            val started = stageImport(backupRecords)
            val preview = importStore.finish(database, started.importId)
            val conflict = requireNotNull(
                findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"),
            )
            assertEquals(case.relationship, conflict["revisionRelationship"])
            assertFalse(conflict["resolved"] as Boolean)
            if (case.relationship == "DIVERGENT") {
                assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
            }
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflict["conflictId"] as String, "KEEP_LOCAL",
            )
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
            val committedHead = database.rawQuery(
                "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
                arrayOf(kind, case.entityId),
            ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
            assertEquals(case.localHead, committedHead)
            assertTrue(importStore.close(started.importId))
        }
    }
}
