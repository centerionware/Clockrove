package io.github.centerionware.clockrove.tracking

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupImportStoreDeletionResolutionTest : BackupImportStoreTestBase() {
    @Test
    fun applyingBackupCommentDeletionDeletesAttachedImageAndKeepsBothTombstoneParents() {
        val (cardId, commentId, attachmentId, _) = createCommentWithImage("apply-backup-delete")
        val localCommentHead = currentHead("task_comment", commentId)
        val localAttachmentHead = currentHead("task_attachment", attachmentId)
        val backupCommentDeletion = "backup-comment-delete-revision"
        val backupAttachmentDeletion = "backup-attachment-delete-revision"
        val eventId = "backup-comment-delete-event"
        val records = listOf(
            revisionRecord(
                backupCommentDeletion, "task_comment", commentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", commentId).put("task_id", cardId).toString(),
            ),
            parentRecord(backupCommentDeletion, localCommentHead),
            headRecord("task_comment", commentId, backupCommentDeletion),
            revisionRecord(
                backupAttachmentDeletion, "task_attachment", attachmentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", attachmentId).put("task_id", cardId)
                    .put("comment_id", commentId).toString(),
            ),
            parentRecord(backupAttachmentDeletion, localAttachmentHead),
            headRecord("task_attachment", attachmentId, backupAttachmentDeletion),
            stagedRow("task_comment_events", mapOf(
                "id" to eventId,
                "task_id" to cardId,
                "comment_id" to commentId,
                "action" to "DELETED",
                "recorded_ms" to now + 1L,
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = conflictsFor(started.importId, preview)
        val commentEvent = conflict(conflicts, "BACKUP_COMMENT_DELETION", "task_comment_events", eventId)
        assertEquals(listOf("KEEP_LOCAL", "APPLY_BACKUP_DELETION"), commentEvent["allowedChoices"])
        listOf(
            commentId to "task_comment",
            attachmentId to "task_attachment",
        ).forEach { (entityId, kind) ->
            val head = conflicts.single { value ->
                value["kind"] == "BACKUP_ENTITY_DELETION" && value["table"] == "entity_heads" &&
                    (value["primaryKey"] as? List<*>) == listOf(kind, entityId)
            }
            assertEquals(listOf("PRESERVE_LOCAL_CONTENT", "APPLY_BACKUP_DELETION"), head["allowedChoices"])
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            commentEvent["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )
        val selectedGraphConflicts = conflictsFor(started.importId, preview)
            .filter { it["kind"] in setOf("BACKUP_COMMENT_DELETION", "BACKUP_ENTITY_DELETION") }
        assertTrue(selectedGraphConflicts.all {
            it["resolved"] == true && it["selectedChoice"] == "APPLY_BACKUP_DELETION"
        })

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM task_comment_events WHERE id='$eventId' AND action='DELETED'"))
        assertMergedTombstoneParents("task_comment", commentId, backupCommentDeletion)
        assertMergedTombstoneParents("task_attachment", attachmentId, backupAttachmentDeletion)
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun commentDeletionRejectsMismatchedAttachmentChoiceBeforeChangingLocalRows() {
        val (cardId, commentId, attachmentId, _) = createCommentWithImage("mismatched-backup-delete")
        val localCommentHead = currentHead("task_comment", commentId)
        val localAttachmentHead = currentHead("task_attachment", attachmentId)
        val commentDeletion = "mismatched-comment-delete-revision"
        val attachmentDeletion = "mismatched-attachment-delete-revision"
        val records = listOf(
            revisionRecord(
                commentDeletion, "task_comment", commentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", commentId).put("task_id", cardId).toString(),
            ),
            parentRecord(commentDeletion, localCommentHead),
            headRecord("task_comment", commentId, commentDeletion),
            revisionRecord(
                attachmentDeletion, "task_attachment", attachmentId, jobId, deleted = 1L,
                payload = JSONObject().put("id", attachmentId).put("task_id", cardId)
                    .put("comment_id", commentId).toString(),
            ),
            parentRecord(attachmentDeletion, localAttachmentHead),
            headRecord("task_attachment", attachmentId, attachmentDeletion),
            stagedRow("task_comment_events", mapOf(
                "id" to "mismatched-comment-delete-event",
                "task_id" to cardId,
                "comment_id" to commentId,
                "action" to "DELETED",
                "recorded_ms" to now + 1L,
            )),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = conflictsFor(started.importId, preview)
        val commentEvent = conflict(
            conflicts, "BACKUP_COMMENT_DELETION", "task_comment_events", "mismatched-comment-delete-event",
        )
        val attachmentHead = conflicts.single { value ->
            value["kind"] == "BACKUP_ENTITY_DELETION" && value["table"] == "entity_heads" &&
                (value["primaryKey"] as? List<*>) == listOf("task_attachment", attachmentId)
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            commentEvent["conflictId"] as String, "APPLY_BACKUP_DELETION",
        )
        val staged = activeImportDatabase()
        val attachmentConflictId = attachmentHead["conflictId"] as String
        val conflictingChoice = staged.rawQuery(
            "SELECT conflict_json FROM import_conflicts WHERE conflict_id=?",
            arrayOf(attachmentConflictId),
        ).use { assertTrue(it.moveToFirst()); JSONObject(it.getString(0)) }
            .put("selectedChoice", "PRESERVE_LOCAL_CONTENT")
            .put("aggregateSelectionIntent", "LOCAL_CONTENT_PRESERVE")
        staged.update(
            "import_conflicts",
            ContentValues().apply {
                put("selected_choice", "PRESERVE_LOCAL_CONTENT")
                put("conflict_json", conflictingChoice.toString())
            },
            "conflict_id=?",
            arrayOf(attachmentConflictId),
        )
        assertFailure("AGGREGATE_CONFLICT_UNSUPPORTED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(1L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(1L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertEquals(localCommentHead, currentHead("task_comment", commentId))
        assertEquals(localAttachmentHead, currentHead("task_attachment", attachmentId))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun restoringCommentGraphDoesNotResolveAnUnrelatedLocalCardEdit() {
        val (cardId, commentId, attachmentId, png) = createCommentWithImage("restore-backup-delete")
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val columnId = database.rawQuery(
            "SELECT column_id FROM kanban_cards WHERE id=?", arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val source = exportStore.start(database)
        val backupCommentHead = currentHead("task_comment", commentId)
        val backupAttachmentHead = currentHead("task_attachment", attachmentId)

        assertNull(repository.saveCard(
            "restore-backup-delete-card-edit", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Locally edited card", currency = currency),
        ).conflict)
        assertNull(repository.removeTaskComment(
            "restore-backup-delete-comment", repository.snapshot().version, jobId, cardId, commentId,
        ).conflict)
        val localCommentTombstone = currentHead("task_comment", commentId)
        val localAttachmentTombstone = currentHead("task_attachment", attachmentId)
        val started = stageSnapshot(source)
        assertTrue(exportStore.close(source.snapshotId))
        val preview = importStore.finish(database, started.importId)
        val conflicts = conflictsFor(started.importId, preview)
        val commentDataConflict = conflict(
            conflicts, "ENTITY_DELETION_TOMBSTONE", "task_comments", commentId,
        )
        assertEquals(
            listOf("PRESERVE_LOCAL_DELETION", "RESTORE_BACKUP_CONTENT"),
            commentDataConflict["allowedChoices"],
        )
        val cardConflict = conflicts.single { value ->
            value["table"] == "entity_heads" && value["kind"] == "EXISTING_RECORD_DIFFERS" &&
                (value["primaryKey"] as? List<*>) == listOf("card", cardId)
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            commentDataConflict["conflictId"] as String, "RESTORE_BACKUP_CONTENT",
        )
        val afterRestoreSelection = conflictsFor(started.importId, preview)
        assertTrue(afterRestoreSelection.filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }.all {
            it["selectedChoice"] == "RESTORE_BACKUP_CONTENT"
        })
        val restoredAttachmentDependencies = afterRestoreSelection.filter {
            it["kind"] == "DEPENDENT_ON_CONFLICT" && it["table"] == "task_attachments"
        }
        assertTrue(restoredAttachmentDependencies.isNotEmpty())
        assertTrue(restoredAttachmentDependencies.all {
            it["resolved"] == true && it["selectedChoice"] == "RESTORE_BACKUP_CONTENT"
        })
        assertEquals(false, conflictById(afterRestoreSelection, cardConflict["conflictId"] as String)["resolved"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            cardConflict["conflictId"] as String, "KEEP_LOCAL",
        )
        val afterCardSelection = conflictsFor(started.importId, preview)
        assertTrue(afterCardSelection.filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }.all {
            it["selectedChoice"] == "RESTORE_BACKUP_CONTENT"
        })

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals("Locally edited card", database.rawQuery(
            "SELECT title FROM kanban_cards WHERE id=?", arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals("Comment restored from backup", database.rawQuery(
            "SELECT body FROM task_comments WHERE id=?", arrayOf(commentId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        val restoredImage = database.rawQuery(
            "SELECT data FROM task_attachments WHERE id=?", arrayOf(attachmentId),
        ).use { assertTrue(it.moveToFirst()); it.getBlob(0) }
        assertArrayEquals(png, restoredImage)
        assertMergedLiveParents("task_comment", commentId, localCommentTombstone, backupCommentHead)
        assertMergedLiveParents("task_attachment", attachmentId, localAttachmentTombstone, backupAttachmentHead)
        assertFalse(importStore.close(started.importId))
    }

    @Test
    fun keepingLocalCommentDeletionDoesNotResolveUnrelatedCardEdit() {
        val (cardId, commentId, attachmentId, _) = createCommentWithImage("keep-local-delete")
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val columnId = database.rawQuery(
            "SELECT column_id FROM kanban_cards WHERE id=?", arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val source = exportStore.start(database)
        val backupCommentHead = currentHead("task_comment", commentId)
        val backupAttachmentHead = currentHead("task_attachment", attachmentId)
        assertNull(repository.saveCard(
            "keep-local-delete-card-edit", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Keep this local card", currency = currency),
        ).conflict)
        assertNull(repository.removeTaskComment(
            "keep-local-delete-comment", repository.snapshot().version, jobId, cardId, commentId,
        ).conflict)
        val localCommentTombstone = currentHead("task_comment", commentId)
        val localAttachmentTombstone = currentHead("task_attachment", attachmentId)
        val started = stageSnapshot(source)
        val preview = importStore.finish(database, started.importId)
        val conflicts = conflictsFor(started.importId, preview)
        val commentDataConflict = conflict(
            conflicts, "ENTITY_DELETION_TOMBSTONE", "task_comments", commentId,
        )
        val cardConflict = conflicts.single { value ->
            value["table"] == "entity_heads" && value["kind"] == "EXISTING_RECORD_DIFFERS" &&
                (value["primaryKey"] as? List<*>) == listOf("card", cardId)
        }

        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            commentDataConflict["conflictId"] as String, "PRESERVE_LOCAL_DELETION",
        )
        val afterDeleteChoice = conflictsFor(started.importId, preview)
        assertTrue(afterDeleteChoice.filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }.all {
            it["selectedChoice"] == "PRESERVE_LOCAL_DELETION"
        })
        val preservedAttachmentDependencies = afterDeleteChoice.filter {
            it["kind"] == "DEPENDENT_ON_CONFLICT" && it["table"] == "task_attachments"
        }
        assertTrue(preservedAttachmentDependencies.isNotEmpty())
        assertTrue(preservedAttachmentDependencies.all {
            it["resolved"] == true && it["selectedChoice"] == "PRESERVE_LOCAL_DELETION"
        })
        assertEquals(false, conflictById(afterDeleteChoice, cardConflict["conflictId"] as String)["resolved"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            cardConflict["conflictId"] as String, "KEEP_LOCAL",
        )
        val afterCardChoice = conflictsFor(started.importId, preview)
        assertTrue(afterCardChoice.filter { it["kind"] == "ENTITY_DELETION_TOMBSTONE" }.all {
            it["selectedChoice"] == "PRESERVE_LOCAL_DELETION"
        })

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals("Keep this local card", database.rawQuery(
            "SELECT title FROM kanban_cards WHERE id=?", arrayOf(cardId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM task_comments WHERE id='$commentId'"))
        assertEquals(0L, number("SELECT COUNT(*) FROM task_attachments WHERE id='$attachmentId'"))
        assertMergedPreservedTombstoneParents(
            "task_comment", commentId, localCommentTombstone, backupCommentHead,
        )
        assertMergedPreservedTombstoneParents(
            "task_attachment", attachmentId, localAttachmentTombstone, backupAttachmentHead,
        )
        assertFalse(importStore.close(started.importId))

        val replay = stageSnapshot(source)
        val replayPreview = importStore.finish(database, replay.importId)
        val replayConflicts = conflictsFor(replay.importId, replayPreview)
        assertTrue(replayConflicts.none { value ->
            value["kind"] in setOf("ENTITY_DELETION_TOMBSTONE", "BACKUP_ENTITY_DELETION") &&
                (value["primaryKey"] as? List<*>)?.lastOrNull()?.toString() in setOf(commentId, attachmentId)
        })
        assertTrue(importStore.close(replay.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun preservingLocalContentAgainstBackupDeletionKeepsBothCausalParents() {
        val customerId = "preserved-delete-customer"
        assertNull(repository.saveCustomer(
            "unsupported-delete-customer-create", repository.snapshot().version,
            BusinessStore.Customer(customerId, "Still live"),
        ).conflict)
        val localHead = currentHead("customer", customerId)
        val backupDeletion = "preserved-delete-customer-revision"
        val records = listOf(
            revisionRecord(
                backupDeletion, "customer", customerId, null, deleted = 1L,
                payload = JSONObject().put("id", customerId).toString(),
            ),
            parentRecord(backupDeletion, localHead),
            headRecord("customer", customerId, backupDeletion),
        )
        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val conflicts = conflictsFor(started.importId, preview)
        val deletion = conflict(conflicts, "BACKUP_ENTITY_DELETION", "entity_heads", customerId, "customer")
        assertEquals(listOf("PRESERVE_LOCAL_CONTENT"), deletion["allowedChoices"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            deletion["conflictId"] as String, "PRESERVE_LOCAL_CONTENT",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals(1L, number("SELECT COUNT(*) FROM customers WHERE id='$customerId'"))
        val merged = currentHead("customer", customerId)
        assertMergedLiveParents("customer", customerId, localHead, backupDeletion)
        assertNotEquals(localHead, merged)
        assertFalse(importStore.close(started.importId))

        val replay = stageImport(records)
        val replayPreview = importStore.finish(database, replay.importId)
        assertEquals(0L, replayPreview.conflictCount)
        assertTrue(importStore.close(replay.importId))
    }

    @Test
    fun immutableQuoteDecisionDeletionHasNoChoicesAndCannotApply() {
        val quoteId = "unsupported-delete-quote"
        assertNull(repository.saveQuote(
            "unsupported-delete-quote-create", repository.snapshot().version,
            QuoteStore.Draft(
                quoteId, null, jobId, "Immutable deletion fixture", "USD", 0,
                listOf(QuoteStore.Line(
                    "unsupported-delete-quote-line", "Synthetic work", "Immutable fixture", "ITEM", "10000", "1000",
                )),
            ),
        ).conflict)
        assertNull(repository.issueQuote(
            "unsupported-delete-quote-issue", repository.snapshot().version, quoteId, true,
        ).conflict)
        assertNull(repository.decideQuote(
            "unsupported-delete-quote-decision", repository.snapshot().version,
            quoteId, 1, "ACCEPTED", "Keep the immutable record", true,
        ).conflict)

        val entityId = "$quoteId:1"
        val localHead = currentHead("quote_decision", entityId)
        val localPayload = database.rawQuery(
            "SELECT payload FROM entity_revisions WHERE id=?", arrayOf(localHead),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val backupDeletion = "unsupported-delete-quote-revision"
        val started = stageImport(listOf(
            revisionRecord(
                backupDeletion, "quote_decision", entityId, jobId,
                deleted = 1L, payload = localPayload,
            ),
            parentRecord(backupDeletion, localHead),
            headRecord("quote_decision", entityId, backupDeletion),
        ))
        val preview = importStore.finish(database, started.importId)
        val conflicts = conflictsFor(started.importId, preview)
        val deletion = conflict(
            conflicts, "BACKUP_ENTITY_DELETION", "entity_heads", entityId, "quote_decision",
        )
        assertEquals(emptyList<String>(), deletion["allowedChoices"])
        assertNull(deletion["defaultChoice"])
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                deletion["conflictId"] as String, "KEEP_LOCAL",
            )
        }
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        assertEquals(localHead, currentHead("quote_decision", entityId))
        assertEquals(1L, database.rawQuery(
            "SELECT COUNT(*) FROM quote_decisions WHERE quote_id=? AND revision=1", arrayOf(quoteId),
        ).use { assertTrue(it.moveToFirst()); it.getLong(0) })
        assertTrue(importStore.close(started.importId))
    }

    private fun createCommentWithImage(prefix: String): CommentImage {
        val cardId = "$prefix-card"
        val commentId = "$prefix-comment"
        val attachmentId = "$prefix-attachment"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertNull(repository.saveCard(
            "$prefix-card-create", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Backup restore target", currency = currency),
        ).conflict)
        assertNull(repository.saveTaskComment(
            "$prefix-comment-create", repository.snapshot().version, jobId, cardId, commentId,
            "Comment restored from backup",
        ).conflict)
        val png = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
            Base64.DEFAULT,
        )
        assertNull(repository.attachTaskImage(
            "$prefix-attachment-create", repository.snapshot().version, jobId, cardId, commentId,
            attachmentId, png,
        ).conflict)
        return CommentImage(cardId, commentId, attachmentId, png)
    }

    private fun conflictsFor(
        importId: String,
        preview: BackupImportStore.Preview,
    ): List<Map<String, Any?>> {
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        while (true) {
            val page = importStore.readConflictPage(
                database, importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            if (page.done) return conflicts
            cursor = page.nextCursor
        }
    }

    private fun conflict(
        conflicts: List<Map<String, Any?>>,
        kind: String,
        table: String,
        entityId: String,
        entityKind: String? = null,
    ): Map<String, Any?> = conflicts.single { value ->
        val primaryKey = value["primaryKey"] as? List<*>
        value["kind"] == kind && value["table"] == table &&
            (primaryKey?.lastOrNull()?.toString() == entityId) &&
            (entityKind == null || primaryKey?.firstOrNull()?.toString() == entityKind)
    }

    private fun conflictById(conflicts: List<Map<String, Any?>>, id: String): Map<String, Any?> =
        conflicts.single { it["conflictId"] == id }

    private fun currentHead(kind: String, entityId: String): String = database.rawQuery(
        "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
        arrayOf(kind, entityId),
    ).use { assertTrue(it.moveToFirst()); it.getString(0) }

    private fun activeImportDatabase(): SQLiteDatabase {
        val activeField = BackupImportStore::class.java.getDeclaredField("active").apply { isAccessible = true }
        val handle = requireNotNull(activeField.get(importStore))
        val databaseField = handle.javaClass.getDeclaredField("database").apply { isAccessible = true }
        return databaseField.get(handle) as SQLiteDatabase
    }

    private fun assertMergedTombstoneParents(kind: String, entityId: String, backupParent: String) {
        val revisionId = currentHead(kind, entityId)
        val revision = database.rawQuery(
            "SELECT deleted,origin FROM entity_revisions WHERE id=?", arrayOf(revisionId),
        ).use { assertTrue(it.moveToFirst()); it.getLong(0) to it.getString(1) }
        assertEquals(1L, revision.first)
        assertEquals("BACKUP_MERGE", revision.second)
        val parents = parentIds(revisionId)
        assertTrue(backupParent in parents)
        assertEquals(2, parents.size)
        assertTrue(parents.all { parent ->
            database.rawQuery("SELECT deleted FROM entity_revisions WHERE id=?", arrayOf(parent)).use {
                assertTrue(it.moveToFirst()); it.getLong(0) == 1L
            }
        })
    }

    private fun assertMergedPreservedTombstoneParents(
        kind: String,
        entityId: String,
        localTombstone: String,
        backupLive: String,
    ) {
        val revisionId = currentHead(kind, entityId)
        val revision = database.rawQuery(
            "SELECT deleted,origin FROM entity_revisions WHERE id=?", arrayOf(revisionId),
        ).use { assertTrue(it.moveToFirst()); it.getLong(0) to it.getString(1) }
        assertEquals(1L, revision.first)
        assertEquals("BACKUP_MERGE", revision.second)
        assertEquals(setOf(localTombstone, backupLive), parentIds(revisionId))
    }

    private fun parentIds(revisionId: String): Set<String> = database.rawQuery(
        "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
        arrayOf(revisionId),
    ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }

    private data class CommentImage(
        val cardId: String,
        val commentId: String,
        val attachmentId: String,
        val png: ByteArray,
    )
}
