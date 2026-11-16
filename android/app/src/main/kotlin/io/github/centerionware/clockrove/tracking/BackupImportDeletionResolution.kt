package io.github.centerionware.clockrove.tracking

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

internal object BackupImportDeletionResolution {
    val restorableTables = setOf("task_comments", "task_attachments")
    val deletableKinds = setOf("task_comment", "task_attachment")

    data class Planned(
        val kind: String,
        val entityId: String,
        val localRevision: String,
        val backupRevision: String,
        val choice: String,
    )

    fun canPreserveLocalContent(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        expectedLocalRevision: String,
        backupRevision: String,
    ): Boolean {
        val table = BackupImportStore.revisionDataTables[kind] ?: return false
        if (table in BackupImportStore.immutableTables) return false
        val head = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else return false }
        if (head != expectedLocalRevision) return false
        val local = store.revisionContent(db, "entity_revisions", head) ?: return false
        val backup = store.revisionContent(handle.database, "validation_revisions", backupRevision) ?: return false
        if (!store.validLiveRevision(local, kind, entityId) || backup.kind != kind ||
            backup.entityId != entityId || backup.deleted != 1L || local.jobId != backup.jobId) {
            return false
        }
        val spec = handle.catalog[table] ?: return false
        val keyColumns = BackupImportStore.revisionDataKeyColumns[kind] ?: listOf("id")
        val keyValues = store.revisionDataKeyValues(kind, entityId)
        if (keyColumns.size != keyValues.size) return false
        val row = store.findLocalRow(db, table, spec, keyColumns.zip(keyValues).toMap()) ?: return false
        val values = store.revisionPayloadValuesForDataRow(db, null, kind, row.values) ?: return false
        val columns = BackupImportStore.revisionDataColumns[kind] ?: return false
        return store.revisionPayloadMatchesRow(
            local.payload, columns, values, store.optionalRevisionPayloadKeys(kind),
        ) && store.revisionJobMatchesData(
            db, handle, kind, local.jobId, row.values, values, preferStaged = false,
        )
    }

    fun canPreserveLocalDeletion(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        expectedLocalRevision: String,
        backupRevision: String,
    ): Boolean {
        val head = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else return false }
        if (head != expectedLocalRevision) return false
        val local = store.revisionContent(db, "entity_revisions", head) ?: return false
        val backup = store.revisionContent(handle.database, "validation_revisions", backupRevision) ?: return false
        if (local.kind != kind || local.entityId != entityId || local.deleted != 1L ||
            !store.validLiveRevision(backup, kind, entityId) || local.jobId != backup.jobId) return false
        val table = BackupImportStore.revisionDataTables[kind] ?: return false
        val spec = handle.catalog[table] ?: return false
        val keyColumns = BackupImportStore.revisionDataKeyColumns[kind] ?: listOf("id")
        val keyValues = store.revisionDataKeyValues(kind, entityId)
        if (keyColumns.size != keyValues.size) return false
        val stagedRow = handle.database.rawQuery(
            "SELECT row_json FROM import_rows WHERE table_name=? AND primary_key=? LIMIT 1",
            arrayOf(table, org.json.JSONArray(keyValues).toString()),
        ).use { rows ->
            if (!rows.moveToFirst()) return false
            @Suppress("UNCHECKED_CAST")
            store.jsonValue(org.json.JSONObject(rows.getString(0)).getJSONObject("values")) as? Map<String, Any?>
        } ?: return false
        val payloadValues = store.revisionPayloadValuesForDataRow(db, handle, kind, stagedRow) ?: return false
        val columns = BackupImportStore.revisionDataColumns[kind] ?: return false
        return store.revisionPayloadMatchesRow(
            backup.payload, columns, payloadValues, store.optionalRevisionPayloadKeys(kind),
        ) && store.revisionJobMatchesData(
            db, handle, kind, backup.jobId, stagedRow, payloadValues, preferStaged = true,
        )
    }

    fun localJobDeleted(db: SQLiteDatabase, kind: String, entityId: String): Boolean {
        val jobId = db.rawQuery(
            "SELECT r.job_id FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                "WHERE h.kind=? AND h.entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst() && !rows.isNull(0)) rows.getString(0) else null }
        return jobId != null && db.rawQuery(
            "SELECT 1 FROM job_deletions WHERE id=? LIMIT 1", arrayOf(jobId),
        ).use { it.moveToFirst() }
    }

    fun canPreserveCurrentLocalDeletion(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        backupRevision: String,
    ): Boolean {
        val localRevision = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else return false }
        return canPreserveLocalDeletion(
            store, db, handle, kind, entityId, localRevision, backupRevision,
        )
    }

    fun alreadyMergedDeletionResolution(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        backupRevision: String,
    ): Boolean {
        val localRevision = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else return false }
        val local = store.revisionContent(db, "entity_revisions", localRevision) ?: return false
        val backup = store.revisionContent(handle.database, "validation_revisions", backupRevision) ?: return false
        // A plain local deletion is also descended from the backup head. Only a head
        // created by a previous backup merge should suppress a fresh conflict.
        val localOrigin = db.rawQuery(
            "SELECT origin FROM entity_revisions WHERE id=?", arrayOf(localRevision),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else return false }
        return local.kind == kind && local.entityId == entityId && local.deleted != backup.deleted &&
            backup.kind == kind && backup.entityId == entityId && localOrigin == "BACKUP_MERGE" &&
            store.revisionHeadRelationship(db, handle, localRevision, backupRevision) == "BACKUP_ANCESTOR"
    }

    fun alreadyMergedCommentDeletion(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        commentId: String,
    ): Boolean {
        val backupHead = stagedHeadRevision(handle, "task_comment", commentId) ?: return false
        return alreadyMergedDeletionResolution(
            store, db, handle, "task_comment", commentId, backupHead,
        )
    }

    fun stagedHeadRevision(
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
    ): String? = handle.database.rawQuery(
        "SELECT row_json FROM import_rows WHERE table_name='entity_heads' AND primary_key=? LIMIT 1",
        arrayOf(org.json.JSONArray(listOf(kind, entityId)).toString()),
    ).use { rows ->
        if (!rows.moveToFirst()) null else
            org.json.JSONObject(rows.getString(0)).getJSONObject("values").optString("revision_id")
                .takeIf(String::isNotEmpty)
    }

    fun stagedChoice(
        handle: BackupImportStore.Handle,
        table: String,
        primaryKey: List<Any?>,
    ): String? = handle.database.rawQuery(
        "SELECT c.selected_choice FROM import_conflicts c JOIN import_rows r ON r.ordinal=c.row_ordinal " +
            "WHERE r.table_name=? AND r.primary_key=? LIMIT 1",
        arrayOf(table, org.json.JSONArray(primaryKey).toString()),
    ).use { rows -> if (rows.moveToFirst() && !rows.isNull(0)) rows.getString(0) else null }

    fun validateCommentDeletionChoices(
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        commentId: String,
    ) {
        val eventChoice = handle.database.rawQuery(
            "SELECT c.selected_choice,r.row_json FROM import_conflicts c " +
                "JOIN import_rows r ON r.ordinal=c.row_ordinal WHERE r.table_name='task_comment_events'",
            null,
        ).use { rows ->
            var choice: String? = null
            while (rows.moveToNext()) {
                val values = org.json.JSONObject(rows.getString(1)).getJSONObject("values")
                if (values.optString("comment_id") == commentId && values.optString("action") == "DELETED") {
                    choice = if (rows.isNull(0)) null else rows.getString(0)
                    break
                }
            }
            choice
        }
        if (eventChoice != "APPLY_BACKUP_DELETION") unsupportedDeletion()
        val attachmentIds = db.rawQuery(
            "SELECT id FROM task_attachments WHERE comment_id=? ORDER BY id", arrayOf(commentId),
        ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        attachmentIds.forEach { attachmentId ->
            val headChoice = stagedChoice(handle, "entity_heads", listOf("task_attachment", attachmentId))
            if (headChoice != "APPLY_BACKUP_DELETION") unsupportedDeletion()
        }
    }

    fun canApplyDeletion(
        db: SQLiteDatabase,
        kind: String,
        entityId: String,
    ): Boolean = when (kind) {
        "task_comment" -> db.rawQuery(
            "SELECT 1 FROM task_comments c JOIN kanban_cards k ON k.id=c.task_id " +
                "JOIN jobs j ON j.id=k.job_id WHERE c.id=? AND j.archived=0",
            arrayOf(entityId),
        ).use { it.moveToFirst() }
        "task_attachment" -> db.rawQuery(
            "SELECT 1 FROM task_attachments a JOIN kanban_cards k ON k.id=a.task_id " +
                "JOIN jobs j ON j.id=k.job_id WHERE a.id=? AND j.archived=0",
            arrayOf(entityId),
        ).use { it.moveToFirst() }
        else -> false
    }

    fun applyBackupDeletion(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        expectedLocalRevision: String,
        backupRevision: String,
    ) {
        if (kind !in deletableKinds) unsupportedDeletion()
        val localHead = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows ->
            if (!rows.moveToFirst()) staleDeletion()
            rows.getString(0)
        }
        val originalLocalRevision = store.revisionContent(db, "entity_revisions", expectedLocalRevision)
            ?: staleDeletion()
        val currentLocalRevision = store.revisionContent(db, "entity_revisions", localHead) ?: staleDeletion()
        if (originalLocalRevision.kind != kind || originalLocalRevision.entityId != entityId ||
            originalLocalRevision.deleted != 0L ||
            (localHead != expectedLocalRevision &&
                (currentLocalRevision.kind != kind || currentLocalRevision.entityId != entityId ||
                    currentLocalRevision.deleted != 1L))) {
            staleDeletion()
        }
        val backupRevisionContent = store.revisionContent(handle.database, "validation_revisions", backupRevision)
            ?: staleDeletion()
        if (backupRevisionContent.kind != kind || backupRevisionContent.entityId != entityId ||
            backupRevisionContent.deleted != 1L ||
            originalLocalRevision.jobId != backupRevisionContent.jobId) {
            staleDeletion()
        }

        val localDeletionHead = currentDeletionHead(store, db, kind, entityId, localHead)
        if (localDeletionHead == expectedLocalRevision) {
            when (kind) {
                "task_comment" -> {
                    val comment = db.rawQuery(
                        "SELECT c.task_id,k.job_id FROM task_comments c " +
                            "JOIN kanban_cards k ON k.id=c.task_id WHERE c.id=?",
                        arrayOf(entityId),
                    ).use { rows ->
                        if (!rows.moveToFirst()) null else rows.getString(0) to rows.getString(1)
                    } ?: staleDeletion()
                    val failure = TaskConversationStore.removeComment(
                        db, comment.second, comment.first, entityId, store.wallClock(), recordEvent = false,
                    )
                    if (failure != null) staleDeletion()
                }
                "task_attachment" -> {
                    val attachment = db.rawQuery(
                        "SELECT a.task_id,k.job_id FROM task_attachments a " +
                            "JOIN kanban_cards k ON k.id=a.task_id WHERE a.id=?",
                        arrayOf(entityId),
                    ).use { rows ->
                        if (!rows.moveToFirst()) null else rows.getString(0) to rows.getString(1)
                    } ?: staleDeletion()
                    val failure = TaskConversationStore.removeAttachment(
                        db, attachment.second, attachment.first, entityId, store.wallClock(),
                    )
                    if (failure != null) staleDeletion()
                }
            }
        }

        val tombstoneHead = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else staleDeletion() }
        val tombstone = store.revisionContent(db, "entity_revisions", tombstoneHead) ?: staleDeletion()
        if (tombstone.deleted != 1L) staleDeletion()
        mergeDeletionParents(store, db, kind, entityId, tombstoneHead, backupRevision, tombstone)
    }

    fun applyBackupRestore(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        backupRevision: String,
    ) {
        if (kind !in deletableKinds) unsupportedDeletion()
        val localHead = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else staleDeletion() }
        val localRevision = store.revisionContent(db, "entity_revisions", localHead) ?: staleDeletion()
        val backup = store.revisionContent(handle.database, "validation_revisions", backupRevision) ?: staleDeletion()
        if (localRevision.kind != kind || localRevision.entityId != entityId || localRevision.deleted != 1L ||
            backup.kind != kind || backup.entityId != entityId || backup.deleted != 0L ||
            localRevision.jobId != backup.jobId) {
            staleDeletion()
        }
        val table = when (kind) {
            "task_comment" -> "task_comments"
            "task_attachment" -> "task_attachments"
            else -> unsupportedDeletion()
        }
        val spec = handle.catalog[table] ?: staleDeletion()
        val key = when (kind) {
            "task_comment", "task_attachment" -> mapOf("id" to entityId)
            else -> unsupportedDeletion()
        }
        val row = store.findLocalRow(db, table, spec, key) ?: staleDeletion()
        val values = store.revisionPayloadValuesForDataRow(db, handle, kind, row.values) ?: staleDeletion()
        val columns = BackupImportStore.revisionDataColumns[kind] ?: staleDeletion()
        if (!store.validLiveRevision(backup, kind, entityId) ||
            !store.revisionPayloadMatchesRow(
                backup.payload, columns, values, store.optionalRevisionPayloadKeys(kind),
            ) || !store.revisionJobMatchesData(db, handle, kind, backup.jobId, row.values, values, preferStaged = true)) {
            staleDeletion()
        }
        store.recordRestoredHead(db, kind, entityId, backup.jobId, values, localHead, backupRevision)
    }

    fun applyPreserveLocalContent(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        expectedLocalRevision: String,
        backupRevision: String,
    ) {
        if (!canPreserveLocalContent(
                store, db, handle, kind, entityId, expectedLocalRevision, backupRevision,
            )) staleDeletion()
        val local = store.revisionContent(db, "entity_revisions", expectedLocalRevision) ?: staleDeletion()
        recordMergedHead(
            store, db, kind, entityId, local.jobId, local.payload, deleted = false,
            expectedLocalRevision, backupRevision,
        )
    }

    fun applyPreserveLocalDeletion(
        store: BackupImportStore,
        db: SQLiteDatabase,
        handle: BackupImportStore.Handle,
        kind: String,
        entityId: String,
        expectedLocalRevision: String,
        backupRevision: String,
    ): Boolean {
        if (!canPreserveLocalDeletion(
                store, db, handle, kind, entityId, expectedLocalRevision, backupRevision,
            ) && !localJobDeleted(db, kind, entityId)) staleDeletion()
        val local = store.revisionContent(db, "entity_revisions", expectedLocalRevision) ?: staleDeletion()
        if (store.isRevisionAncestor(db, "revision_parents", backupRevision, expectedLocalRevision)) return false
        recordMergedHead(
            store, db, kind, entityId, local.jobId, local.payload, deleted = true,
            expectedLocalRevision, backupRevision,
        )
        return true
    }

    private fun recordMergedHead(
        store: BackupImportStore,
        db: SQLiteDatabase,
        kind: String,
        entityId: String,
        jobId: String?,
        payload: String?,
        deleted: Boolean,
        expectedLocalRevision: String,
        backupRevision: String,
    ) {
        val merged = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,?,?,?,?,'BACKUP_MERGE')",
            arrayOf<Any?>(merged, kind, entityId, jobId, payload, if (deleted) 1 else 0, store.wallClock()),
        )
        listOf(expectedLocalRevision, backupRevision).distinct().sorted().forEach { parent ->
            db.execSQL("INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)", arrayOf(merged, parent))
        }
        val updated = db.update(
            "entity_heads",
            ContentValues().apply { put("revision_id", merged) },
            "kind=? AND entity_id=? AND revision_id=?",
            arrayOf(kind, entityId, expectedLocalRevision),
        )
        if (updated != 1) staleDeletion()
    }

    private fun currentDeletionHead(
        store: BackupImportStore,
        db: SQLiteDatabase,
        kind: String,
        entityId: String,
        expectedLocalHead: String,
    ): String {
        val current = db.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else staleDeletion() }
        if (current != expectedLocalHead) {
            val revision = store.revisionContent(db, "entity_revisions", current) ?: staleDeletion()
            if (revision.kind != kind || revision.entityId != entityId || revision.deleted != 1L) staleDeletion()
        }
        return current
    }

    private fun mergeDeletionParents(
        store: BackupImportStore,
        db: SQLiteDatabase,
        kind: String,
        entityId: String,
        localTombstone: String,
        backupTombstone: String,
        localContent: BackupImportStore.RevisionContent,
    ) {
        val parents = linkedSetOf(localTombstone, backupTombstone)
        val merged = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,?,?,1,?,'BACKUP_MERGE')",
            arrayOf<Any?>(merged, kind, entityId, localContent.jobId, localContent.payload, store.wallClock()),
        )
        parents.sorted().forEach { parent ->
            db.execSQL("INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)", arrayOf(merged, parent))
        }
        val updated = db.update(
            "entity_heads",
            ContentValues().apply { put("revision_id", merged) },
            "kind=? AND entity_id=? AND revision_id=?",
            arrayOf(kind, entityId, localTombstone),
        )
        if (updated != 1) staleDeletion()
    }

    private fun BackupImportStore.recordRestoredHead(
        db: SQLiteDatabase,
        kind: String,
        entityId: String,
        jobId: String?,
        values: Map<String, Any?>,
        localTombstone: String,
        backupRevision: String,
    ) {
        val columns = BackupImportStore.revisionDataColumns[kind] ?: staleDeletion()
        val payload = org.json.JSONObject().apply {
            columns.sorted().forEach { key -> put(key, values[key] ?: org.json.JSONObject.NULL) }
        }.toString()
        val merged = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,?,?,?,?,0,?,'BACKUP_MERGE')",
            arrayOf<Any?>(merged, kind, entityId, jobId, payload, wallClock()),
        )
        listOf(localTombstone, backupRevision).distinct().sorted().forEach { parent ->
            db.execSQL("INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)", arrayOf(merged, parent))
        }
        val updated = db.update(
            "entity_heads",
            ContentValues().apply { put("revision_id", merged) },
            "kind=? AND entity_id=? AND revision_id=?",
            arrayOf(kind, entityId, localTombstone),
        )
        if (updated != 1) staleDeletion()
    }

    private fun unsupportedDeletion(): Nothing = throw BackupImportStore.Failure(
        "UNSUPPORTED_CHOICE", "This backup deletion has no safe native adapter.",
    )

    private fun staleDeletion(): Nothing = throw BackupImportStore.Failure(
        "PREVIEW_STALE", "A backup deletion changed after preview; create a new preview.",
    )
}
