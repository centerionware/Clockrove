package io.github.centerionware.clockrove.tracking

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupImportStoreSessionRevisionMergeTest : BackupImportStoreTestBase() {
    @Test
    fun divergentFinalizedSessionOffersBothSidesAndMergesBothRevisionParents() {
        val sessionId = "divergent-finalized-session"
        val baseValues = mapOf<String, Any?>(
            "id" to sessionId,
            "job_id" to jobId,
            "start_ms" to now + 2_000_000L,
            "end_ms" to now + 2_001_000L,
            "timezone" to "Etc/UTC",
            "offset_seconds" to 0,
            "origin" to "MANUAL",
        )
        database.execSQL(
            "INSERT INTO sessions(id,job_id,start_ms,end_ms,timezone,offset_seconds,origin) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                sessionId, jobId, now + 2_000_000L, now + 2_001_000L, "Etc/UTC", 0, "MANUAL",
            ),
        )
        val baseRevision = RevisionStore.record(database, "session", sessionId, jobId, baseValues, now)
        database.execSQL("UPDATE sessions SET origin='REPAIR' WHERE id=?", arrayOf(sessionId))
        val localRevision = RevisionStore.record(
            database,
            "session",
            sessionId,
            jobId,
            baseValues + ("origin" to "REPAIR"),
            now + 1L,
        )
        val backupValues = baseValues + ("origin" to "WIDGET")
        val backupRevision = "divergent-finalized-session-backup"
        val started = stageImport(listOf(
            stagedRow("entity_revisions", readRowValues("entity_revisions", "id", baseRevision)),
            revisionRecord(
                backupRevision,
                "session",
                sessionId,
                jobId,
                payload = JSONObject(backupValues.toSortedMap()).toString(),
            ),
            parentRecord(backupRevision, baseRevision),
            stagedRow("sessions", backupValues),
            headRecord("session", sessionId, backupRevision),
        ))
        val preview = importStore.finish(database, started.importId)
        val conflicts = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(
                database, started.importId, preview.previewToken, cursor, null,
            )
            conflicts.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        val headConflict = requireNotNull(conflicts.singleOrNull { conflict ->
            conflict["table"] == "entity_heads" &&
                (conflict["primaryKey"] as? List<*>)?.getOrNull(1)?.toString() == sessionId
        })
        assertEquals("DIVERGENT", headConflict["revisionRelationship"])
        assertEquals(
            setOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
            (headConflict["allowedChoices"] as List<*>).toSet(),
        )
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            headConflict["conflictId"] as String,
            "MERGE_USE_BACKUP_CONTENT",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals("WIDGET", readRowValues("sessions", "id", sessionId)["origin"])
        val mergedHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='session' AND entity_id=?",
            arrayOf(sessionId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val parents = database.rawQuery(
            "SELECT parent_id FROM revision_parents WHERE revision_id=?",
            arrayOf(mergedHead),
        ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
        assertEquals(setOf(localRevision, backupRevision), parents)
        assertFalse(importStore.close(started.importId))
    }
}
