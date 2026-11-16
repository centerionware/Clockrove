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
class BackupImportStoreTombstoneTest : BackupImportStoreTestBase() {
    @Test
    fun staleBackupCannotResurrectDeletedBoardCard() {
        val cardId = "stale-deleted-card"
        val commentId = "stale-deleted-card-comment"
        val attachmentId = "stale-deleted-card-attachment"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "add-card-for-stale-restore", repository.snapshot().version,
            BoardStore.Draft(
                cardId, jobId, columnId, "Card to delete",
                priceMinor = java.math.BigInteger.valueOf(2_500L),
                currency = currency,
                quantityUnits = 25_000L,
            ),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "comment-for-stale-restore", repository.snapshot().version,
            jobId, cardId, commentId, "This comment and image must stay deleted.",
        ).conflict)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "image-for-stale-restore", repository.snapshot().version,
            jobId, cardId, commentId, attachmentId, png,
        ).conflict)
        val templateId = database.rawQuery(
            "SELECT template_id FROM kanban_cards WHERE id=?", arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "task_comments" && values(it)["id"] == commentId })
        assertTrue(staleRecords.any { it["table"] == "task_attachments" && values(it)["id"] == attachmentId })
        assertTrue(staleRecords.any { it["table"] == "task_quantities" && values(it)["card_id"] == cardId })
        assertTrue(staleRecords.any { it["table"] == "card_money" && values(it)["id"] == cardId })

        assertNull(repository.removeCard(
            "delete-card-before-stale-restore", repository.snapshot().version, jobId, cardId,
        ).conflict)
        val deletedVersion = repository.snapshot().version
        val deletedHead = database.rawQuery(
            "SELECT h.revision_id FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind='card' AND h.entity_id=? AND r.deleted=1",
            arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val fencedEntities = listOf(
            "card" to cardId,
            "card_money" to cardId,
            "task_quantity" to cardId,
            "task_comment" to commentId,
            "task_attachment" to attachmentId,
        )
        fun revisionFenceSnapshot(): Triple<List<List<String?>>, List<List<String?>>, List<List<String?>>> {
            val identityFilter = fencedEntities.joinToString(" OR ") { "(kind=? AND entity_id=?)" }
            val identityArgs = fencedEntities.flatMap { listOf(it.first, it.second) }.toTypedArray()
            val revisions = database.rawQuery(
                "SELECT id,kind,entity_id,job_id,payload,deleted,changed_ms,origin " +
                    "FROM entity_revisions WHERE $identityFilter ORDER BY kind,entity_id,id",
                identityArgs,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add((0 until rows.columnCount).map { index ->
                    if (rows.isNull(index)) null else rows.getString(index)
                })
            } }
            val revisionIds = revisions.map { requireNotNull(it.first()) }
            val graphArgs = revisionIds.toTypedArray()
            val graphFilter = revisionIds.joinToString(",") { "?" }
            val parents = if (revisionIds.isEmpty()) emptyList() else database.rawQuery(
                "SELECT revision_id,parent_id FROM revision_parents " +
                    "WHERE revision_id IN ($graphFilter) OR parent_id IN ($graphFilter) " +
                    "ORDER BY revision_id,parent_id",
                graphArgs + graphArgs,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add(listOf(rows.getString(0), rows.getString(1)))
            } }
            val heads = database.rawQuery(
                "SELECT kind,entity_id,revision_id FROM entity_heads WHERE $identityFilter ORDER BY kind,entity_id",
                identityArgs,
            ).use { rows -> buildList {
                while (rows.moveToNext()) add(listOf(rows.getString(0), rows.getString(1), rows.getString(2)))
            } }
            return Triple(revisions, parents, heads)
        }
        val localRevisionFence = revisionFenceSnapshot()

        val started = stageSnapshot(source)
        assertTrue(exportStore.close(source.snapshotId))
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val tombstoneConflict = requireNotNull(conflicts.singleOrNull { conflict ->
            conflict["kind"] == "ENTITY_DELETION_TOMBSTONE" && conflict["table"] == "kanban_cards" &&
                (conflict["primaryKey"] as List<*>).singleOrNull() == cardId
        })
        assertEquals("kanban_cards", tombstoneConflict["table"])
        assertEquals(listOf("PRESERVE_LOCAL_DELETION"), tombstoneConflict["allowedChoices"])
        assertEquals("PRESERVE_LOCAL_DELETION", tombstoneConflict["defaultChoice"])
        assertFalse(preview.applyReady)
        val tombstoneTables = conflicts
            .filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }
            .map { it["table"] }
            .toSet()
        assertTrue(tombstoneTables.containsAll(setOf(
            "kanban_cards", "task_quantities", "card_money", "task_comments", "task_attachments",
        )))
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_cards WHERE id='$cardId'"))
        assertEquals(deletedHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='card' AND entity_id=?",
            arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comment_events WHERE task_id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_quantities WHERE card_id='$cardId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM card_money WHERE id='$cardId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM task_templates WHERE id='$templateId'"))
        assertEquals(localRevisionFence, revisionFenceSnapshot())
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun staleBackupCannotResurrectIndividuallyDeletedCommentOrImage() {
        val cardId = "stale-individual-delete-card"
        val commentId = "stale-individual-delete-comment"
        val commentImageId = "stale-individual-delete-comment-image"
        val standaloneImageId = "stale-individual-delete-standalone-image"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "stale-individual-delete-card-create", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Deletion test card", currency = currency),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "stale-individual-delete-comment-create", repository.snapshot().version,
            jobId, cardId, commentId, "This note will be deleted.",
        ).conflict)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "stale-individual-delete-comment-image-create", repository.snapshot().version,
            jobId, cardId, commentId, commentImageId, png,
        ).conflict)
        assertNull(repository.attachTaskImage(
            "stale-individual-delete-standalone-image-create", repository.snapshot().version,
            jobId, cardId, null, standaloneImageId, png,
        ).conflict)

        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "task_comments" && values(it)["id"] == commentId })
        assertTrue(staleRecords.any { it["table"] == "task_attachments" && values(it)["id"] == commentImageId })
        assertTrue(staleRecords.any { it["table"] == "task_attachments" && values(it)["id"] == standaloneImageId })

        assertNull(repository.removeTaskComment(
            "stale-individual-delete-comment-remove", repository.snapshot().version, jobId, cardId, commentId,
        ).conflict)
        assertNull(repository.removeTaskImage(
            "stale-individual-delete-image-remove", repository.snapshot().version, jobId, cardId, standaloneImageId,
        ).conflict)
        val deletedVersion = repository.snapshot().version
        val started = stageSnapshot(source)
        assertTrue(exportStore.close(source.snapshotId))
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val deletedEntityIds = conflicts
            .filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }
            .map { (it["primaryKey"] as List<*>).single() }
            .toSet()
        assertTrue(deletedEntityIds.containsAll(setOf(commentId, commentImageId, standaloneImageId)))
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_attachments WHERE id IN('$commentImageId','$standaloneImageId')"))
        assertEquals(3L, number(
            "SELECT COUNT(*) FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE (h.kind='task_comment' AND h.entity_id='$commentId' AND r.deleted=1) OR " +
                "(h.kind='task_attachment' AND h.entity_id IN('$commentImageId','$standaloneImageId') AND r.deleted=1)",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun staleBackupDeletionFencesCoverUnusedCustomerSiteAndEmptyColumn() {
        val customerId = "stale-deleted-customer"
        val siteId = "stale-deleted-site"
        val columnId = "stale-deleted-column"
        assertNull(repository.saveCustomer(
            "add-customer-for-stale-restore", repository.snapshot().version,
            BusinessStore.Customer(customerId, "Unused stale customer", email = "stale@example.test"),
        ).conflict)
        assertNull(repository.saveSite(
            "add-site-for-stale-restore", repository.snapshot().version,
            GeofenceStore.Site(siteId, jobId, "Stale site", 12.0, -34.0, enabled = false),
        ).conflict)
        assertNull(repository.saveColumn(
            "add-column-for-stale-restore", repository.snapshot().version,
            columnId, jobId, "Stale column", false,
        ).conflict)

        val source = exportStore.start(database)
        val staleRecords = readAllRows(source.snapshotId)
        assertTrue(staleRecords.any { it["table"] == "customers" && values(it)["id"] == customerId })
        assertTrue(staleRecords.any { it["table"] == "work_sites" && values(it)["id"] == siteId })
        assertTrue(staleRecords.any { it["table"] == "kanban_columns" && values(it)["id"] == columnId })
        assertTrue(exportStore.close(source.snapshotId))

        assertNull(repository.removeCustomer(
            "delete-customer-before-stale-restore", repository.snapshot().version, customerId,
        ).conflict)
        assertNull(repository.removeSite(
            "delete-site-before-stale-restore", repository.snapshot().version, siteId,
        ).conflict)
        assertNull(repository.removeColumn(
            "delete-column-before-stale-restore", repository.snapshot().version, jobId, columnId, null,
        ).conflict)
        val deletedVersion = repository.snapshot().version

        val started = stageImport(staleRecords)
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        listOf(
            "customers" to customerId,
            "work_sites" to siteId,
            "kanban_columns" to columnId,
        ).forEach { (table, entityId) ->
            val conflict = conflicts.single { item ->
                item["kind"] == "ENTITY_DELETION_TOMBSTONE" && item["table"] == table &&
                    (item["primaryKey"] as List<*>).singleOrNull() == entityId
            }
            assertEquals(listOf("PRESERVE_LOCAL_DELETION"), conflict["allowedChoices"])
            assertEquals("PRESERVE_LOCAL_DELETION", conflict["defaultChoice"])
        }
        assertFalse(preview.applyReady)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(deletedVersion, repository.snapshot().version)
        assertEquals(0L, number("SELECT COUNT(*) FROM customers WHERE id='$customerId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM work_sites WHERE id='$siteId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM kanban_columns WHERE id='$columnId'"))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun backupJobDeletionCannotFenceStillLiveLocalJob() {
        val currentJob = database.rawQuery(
            "SELECT id FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val parentRevision = "active-job-deletion-parent"
        val deletionRevision = "active-job-deletion-head"
        val deletionPayload = JSONObject().put("id", currentJob).put("job_id", currentJob).toString()
        val records = listOf(
            revisionRecord(
                parentRevision, "job", currentJob, currentJob, payload = null,
            ),
            revisionRecord(
                deletionRevision, "job", currentJob, currentJob,
                deleted = 1L, payload = deletionPayload,
            ),
            parentRecord(deletionRevision, parentRevision),
            headRecord("job", currentJob, deletionRevision),
            stagedRow("job_deletions", mapOf(
                "id" to currentJob,
                "revision_id" to deletionRevision,
                "deleted_ms" to now,
            )),
        )
        val versionBefore = repository.snapshot().version
        val localHeadBefore = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(currentJob),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "ACTIVE_JOB_DELETION"))
        assertEquals("job_deletions", conflict["table"])
        assertEquals(listOf("KEEP_LOCAL"), conflict["allowedChoices"])
        assertNull(conflict["defaultChoice"])
        assertFalse(preview.applyReady)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM jobs WHERE id='$currentJob'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM job_deletions WHERE id='$currentJob'"))
        assertEquals(localHeadBefore, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='job' AND entity_id=?",
            arrayOf(currentJob),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun backupCommentAndImageDeletionCanBeKeptWithoutDeletingTheLocalGraph() {
        val cardId = "backup-delete-choice-card"
        val commentId = "backup-delete-choice-comment"
        val attachmentId = "backup-delete-choice-image"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "backup-delete-choice-card-create", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Keep local content", currency = currency),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "backup-delete-choice-comment-create", repository.snapshot().version,
            jobId, cardId, commentId, "Local comment stays live.",
        ).conflict)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "backup-delete-choice-image-create", repository.snapshot().version,
            jobId, cardId, commentId, attachmentId, png,
        ).conflict)
        val commentHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_comment' AND entity_id=?",
            arrayOf(commentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val attachmentHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_attachment' AND entity_id=?",
            arrayOf(attachmentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val commentDeletion = "backup-delete-choice-comment-head"
        val attachmentDeletion = "backup-delete-choice-image-head"
        val records = listOf(
            revisionRecord(
                commentDeletion, "task_comment", commentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", commentId).put("task_id", cardId).toString(),
            ),
            parentRecord(commentDeletion, commentHead),
            headRecord("task_comment", commentId, commentDeletion),
            revisionRecord(
                attachmentDeletion, "task_attachment", attachmentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", attachmentId).put("task_id", cardId)
                    .put("comment_id", commentId).toString(),
            ),
            parentRecord(attachmentDeletion, attachmentHead),
            headRecord("task_attachment", attachmentId, attachmentDeletion),
            stagedRow("task_comment_events", mapOf(
                "id" to "backup-delete-choice-event",
                "task_id" to cardId,
                "comment_id" to commentId,
                "action" to "DELETED",
                "recorded_ms" to now + 1L,
            )),
        )
        val versionBefore = repository.snapshot().version
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        var cursor: String? = null
        val conflicts = mutableListOf<Map<String, Any?>>()
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        assertTrue(conflicts.any { it["kind"] == "BACKUP_COMMENT_DELETION" })
        val deletionHeads = conflicts.filter { it["kind"] == "BACKUP_ENTITY_DELETION" }
        assertEquals(2, deletionHeads.size)
        assertTrue(deletionHeads.all { it["allowedChoices"] == listOf("KEEP_LOCAL") })
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        resolveSafeConflictChoices(started.importId, preview, conflicts)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(versionBefore, repository.snapshot().version)
        assertEquals(1L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertEquals(commentHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_comment' AND entity_id=?",
            arrayOf(commentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(attachmentHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='task_attachment' AND entity_id=?",
            arrayOf(attachmentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(0L, number(
            "SELECT COUNT(*) FROM task_comment_events WHERE id='backup-delete-choice-event'",
        ))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun sessionsOverlappingWithinOneBackupRequireExplicitOmission() {
        val firstSessionId = "backup-overlap-first-session"
        val secondSessionId = "backup-overlap-second-session"
        val records = listOf(
            stagedRow("sessions", mapOf(
                "id" to firstSessionId,
                "job_id" to jobId,
                "start_ms" to now,
                "end_ms" to now + 10_000L,
                "timezone" to "Etc/UTC",
                "offset_seconds" to 0,
                "origin" to "MANUAL",
            )),
            stagedRow("sessions", mapOf(
                "id" to secondSessionId,
                "job_id" to jobId,
                "start_ms" to now + 5_000L,
                "end_ms" to now + 15_000L,
                "timezone" to "Etc/UTC",
                "offset_seconds" to 0,
                "origin" to "MANUAL",
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(1L, preview.conflictCount)
        assertFalse(preview.applyReady)
        val conflict = requireNotNull(findConflict(started.importId, preview, "SESSION_OVERLAP"))
        assertEquals("BACKUP:$firstSessionId", conflict["localIdentifier"])
        assertEquals(listOf("KEEP_LOCAL", "OMIT_BACKUP_SESSION"), conflict["allowedChoices"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "OMIT_BACKUP_SESSION",
        )
        val applied = importStore.apply(
            database, started.importId, preview.previewToken, preview.localStateVersion,
        )
        assertEquals(1L, applied.insertedCount)
        assertEquals(1L, number("SELECT COUNT(*) FROM sessions WHERE id='$firstSessionId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM sessions WHERE id='$secondSessionId'"))
        assertTrue(importStore.close(started.importId))
    }
}
