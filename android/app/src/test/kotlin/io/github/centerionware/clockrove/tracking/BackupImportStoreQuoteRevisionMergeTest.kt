package io.github.centerionware.clockrove.tracking

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupImportStoreQuoteRevisionMergeTest : BackupImportStoreTestBase() {
    private data class QuoteFixture(
        val quoteId: String,
        val decisionEntityId: String,
        val decisionBase: String,
        val decisionLocal: String,
        val decisionValues: Map<String, Any?>,
        val invoiceId: String,
        val linkBase: String,
        val linkLocal: String,
        val linkValues: Map<String, Any?>,
    )

    private fun createQuoteFixture(suffix: String): QuoteFixture {
        val quoteId = "backup-quote-$suffix"
        val draft = QuoteStore.Draft(
            quoteId, null, jobId, "Backup quote $suffix", "USD", 0,
            listOf(QuoteStore.Line("line-$suffix", "Synthetic work", "Backup fixture", "ITEM", "10000", "1000")),
        )
        assertNull(repository.saveQuote("save-$suffix", repository.snapshot().version, draft).conflict)
        assertNull(repository.issueQuote("issue-$suffix", repository.snapshot().version, quoteId, true).conflict)

        val decisionEntityId = "$quoteId:1"
        val decisionBase = "quote-decision-base-$suffix"
        val baseDecisionValues = mapOf<String, Any?>(
            "quote_id" to quoteId, "revision" to 1L, "decision" to "DECLINED",
            "note" to "Earlier branch", "recorded_ms" to now,
        )
        insertSyntheticRevision(
            decisionBase, "quote_decision", decisionEntityId, null,
            JSONObject(baseDecisionValues.toSortedMap()).toString(), jobId,
        )
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES('quote_decision',?,?)",
            arrayOf(decisionEntityId, decisionBase),
        )
        assertNull(repository.decideQuote(
            "decide-$suffix", repository.snapshot().version, quoteId, 1,
            "ACCEPTED", "Local decision", true,
        ).conflict)
        val decisionLocal = head("quote_decision", decisionEntityId)
        val decisionValues = decisionValues(quoteId)

        val invoiceId = "backup-invoice-$suffix"
        val invoiceNumber = "M7-$suffix"
        database.execSQL(
            "INSERT INTO invoices(id,job_id,customer_id,number,created_ms,payload,pdf,logo_id) " +
                "VALUES(?,?,NULL,?,?,?,NULL,NULL)",
            arrayOf<Any?>(invoiceId, jobId, invoiceNumber, now + 2L, "{}"),
        )
        RevisionStore.snapshotRows(
            database, "invoices", "invoice",
            listOf("id", "job_id", "customer_id", "number", "created_ms", "payload", "logo_id"),
            now + 2L, "job_id", "id=?", arrayOf(invoiceId),
        )
        val linkBase = "quote-invoice-link-base-$suffix"
        val baseLinkValues = mapOf<String, Any?>(
            "invoice_id" to invoiceId, "quote_id" to quoteId, "revision" to 1L,
            "original_invoice_id" to invoiceId, "created_ms" to now + 3L, "payload" to "base provenance",
        )
        insertSyntheticRevision(
            linkBase, "quote_invoice_link", invoiceId, null,
            JSONObject(baseLinkValues.toSortedMap()).toString(), jobId,
        )
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES('quote_invoice_link',?,?)",
            arrayOf(invoiceId, linkBase),
        )
        val linkValues = baseLinkValues + mapOf(
            "created_ms" to now + 4L,
            "payload" to "local provenance",
        )
        database.execSQL(
            "INSERT INTO quote_invoice_links(invoice_id,quote_id,revision,original_invoice_id,created_ms,payload) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(
                invoiceId, quoteId, 1L, invoiceId, now + 4L, "local provenance",
            ),
        )
        val linkLocal = RevisionStore.record(database, "quote_invoice_link", invoiceId, jobId, linkValues, now + 4L)
        assertEquals(linkLocal, head("quote_invoice_link", invoiceId))

        return QuoteFixture(
            quoteId, decisionEntityId, decisionBase, decisionLocal, decisionValues,
            invoiceId, linkBase, linkLocal, linkValues,
        )
    }

    private fun decisionValues(quoteId: String): Map<String, Any?> = database.rawQuery(
        "SELECT quote_id,revision,decision,note,recorded_ms FROM quote_decisions WHERE quote_id=? AND revision=1",
        arrayOf(quoteId),
    ).use { rows ->
        assertTrue(rows.moveToFirst())
        mapOf(
            "quote_id" to rows.getString(0), "revision" to rows.getLong(1),
            "decision" to rows.getString(2), "note" to rows.getString(3), "recorded_ms" to rows.getLong(4),
        )
    }

    private fun head(kind: String, entityId: String): String = database.rawQuery(
        "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
        arrayOf(kind, entityId),
    ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

    private fun revisionPayload(revisionId: String): String = database.rawQuery(
        "SELECT payload FROM entity_revisions WHERE id=?",
        arrayOf(revisionId),
    ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }

    private fun addBackupHead(
        records: MutableList<Map<String, Any?>>,
        kind: String,
        entityId: String,
        jobId: String,
        revisionId: String,
        parentId: String,
        payload: String,
    ) {
        records.add(revisionRecord(revisionId, kind, entityId, jobId, payload = payload))
        records.add(parentRecord(revisionId, parentId))
        records.removeAll {
            it["table"] == "entity_heads" && values(it)["kind"] == kind &&
                values(it)["entity_id"] == entityId
        }
        records.add(headRecord(kind, entityId, revisionId))
    }

    private fun conflicts(importId: String, preview: BackupImportStore.Preview): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = importStore.readConflictPage(database, importId, preview.previewToken, cursor, null)
            result.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) return result
        } while (true)
    }

    private fun replaceRow(
        records: MutableList<Map<String, Any?>>,
        table: String,
        predicate: (Map<*, *>) -> Boolean,
        replacement: Map<String, Any?>,
    ) {
        records.removeAll { it["table"] == table && predicate(values(it)) }
        records.add(stagedRow(table, replacement))
    }

    @Test
    fun quoteDecisionAndInvoiceLinkDescendantsFastForwardAndRoundTrip() {
        val fixture = createQuoteFixture("descendant")
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()
        val decisionChild = UUID.randomUUID().toString()
        val linkChild = UUID.randomUUID().toString()
        addBackupHead(
            records, "quote_decision", fixture.decisionEntityId, jobId,
            decisionChild, fixture.decisionLocal, revisionPayload(fixture.decisionLocal),
        )
        addBackupHead(
            records, "quote_invoice_link", fixture.invoiceId, jobId,
            linkChild, fixture.linkLocal, revisionPayload(fixture.linkLocal),
        )

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.applyReady)
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals(decisionChild, head("quote_decision", fixture.decisionEntityId))
        assertEquals(linkChild, head("quote_invoice_link", fixture.invoiceId))
        assertTrue(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(decisionChild, fixture.decisionLocal),
        ).use { it.moveToFirst() })
        assertTrue(database.rawQuery(
            "SELECT 1 FROM revision_parents WHERE revision_id=? AND parent_id=?",
            arrayOf(linkChild, fixture.linkLocal),
        ).use { it.moveToFirst() })
        assertTrue(exportStore.close(source.snapshotId))

        val roundTripSnapshot = exportStore.start(database)
        val roundTripImport = stageSnapshot(roundTripSnapshot)
        val roundTripPreview = importStore.finish(database, roundTripImport.importId)
        assertEquals(0L, roundTripPreview.conflictCount)
        assertTrue(roundTripPreview.applyReady)
        importStore.apply(database, roundTripImport.importId, roundTripPreview.previewToken, roundTripPreview.localStateVersion)
        assertEquals(decisionChild, head("quote_decision", fixture.decisionEntityId))
        assertEquals(linkChild, head("quote_invoice_link", fixture.invoiceId))
        assertTrue(exportStore.close(roundTripSnapshot.snapshotId))
    }

    @Test
    fun divergentQuoteDecisionAndInvoiceLinkKeepImmutableRowsAndMergeBothParents() {
        val fixture = createQuoteFixture("divergent")
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        val backupDecisionValues = fixture.decisionValues.toMutableMap().apply {
            put("note", "Backup branch decision")
        }
        replaceRow(records, "quote_decisions", {
            it["quote_id"] == fixture.quoteId && it["revision"] == 1L
        }, backupDecisionValues)
        val backupDecision = UUID.randomUUID().toString()
        addBackupHead(
            records, "quote_decision", fixture.decisionEntityId, jobId,
            backupDecision, fixture.decisionBase,
            JSONObject(backupDecisionValues.toSortedMap()).toString(),
        )

        val backupLinkValues = fixture.linkValues.toMutableMap().apply {
            put("created_ms", now + 5L)
            put("payload", "backup branch provenance")
        }
        replaceRow(records, "quote_invoice_links", {
            it["invoice_id"] == fixture.invoiceId
        }, backupLinkValues)
        val backupLink = UUID.randomUUID().toString()
        addBackupHead(
            records, "quote_invoice_link", fixture.invoiceId, jobId,
            backupLink, fixture.linkBase,
            JSONObject(backupLinkValues.toSortedMap()).toString(),
        )

        val started = stageImport(records)
        val preview = importStore.finish(database, started.importId)
        val headConflicts = conflicts(started.importId, preview).filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[0].toString() }
        assertEquals(setOf("quote_decision", "quote_invoice_link"), headConflicts.keys)
        headConflicts.values.forEach { conflict ->
            assertEquals("DIVERGENT", conflict["revisionRelationship"])
            assertEquals(listOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT"), conflict["allowedChoices"])
            assertEquals("MERGE_KEEP_LOCAL_CONTENT", conflict["defaultChoice"])
            assertFalse((conflict["allowedChoices"] as List<*>).contains("MERGE_USE_BACKUP_CONTENT"))
        }
        assertFalse(preview.applyReady)
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        headConflicts.values.forEach { conflict ->
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                conflict["conflictId"] as String, "MERGE_KEEP_LOCAL_CONTENT",
            )
        }
        val beforeRejectedChoice = repository.snapshot()
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                headConflicts.getValue("quote_invoice_link")["conflictId"] as String,
                "MERGE_USE_BACKUP_CONTENT",
            )
        }
        assertEquals(beforeRejectedChoice, repository.snapshot())
        assertEquals(fixture.linkValues, readRowValues("quote_invoice_links", "invoice_id", fixture.invoiceId))
        val revisionCountBeforeRollback = number("SELECT COUNT(*) FROM entity_revisions")
        val parentCountBeforeRollback = number("SELECT COUNT(*) FROM revision_parents")
        database.execSQL(
            "CREATE TRIGGER reject_quote_backup_merge_parent BEFORE INSERT ON revision_parents " +
                "WHEN (SELECT origin FROM entity_revisions WHERE id=NEW.revision_id)='BACKUP_MERGE' " +
                "BEGIN SELECT RAISE(ABORT,'synthetic quote merge failure'); END",
        )
        assertFailure("APPLY_FAILED") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        database.execSQL("DROP TRIGGER reject_quote_backup_merge_parent")
        assertEquals(beforeRejectedChoice, repository.snapshot())
        assertEquals(revisionCountBeforeRollback, number("SELECT COUNT(*) FROM entity_revisions"))
        assertEquals(parentCountBeforeRollback, number("SELECT COUNT(*) FROM revision_parents"))
        assertEquals(fixture.decisionLocal, head("quote_decision", fixture.decisionEntityId))
        assertEquals(fixture.linkLocal, head("quote_invoice_link", fixture.invoiceId))
        assertEquals(fixture.decisionValues, decisionValues(fixture.quoteId))
        assertEquals(fixture.linkValues, readRowValues("quote_invoice_links", "invoice_id", fixture.invoiceId))
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(fixture.decisionValues, decisionValues(fixture.quoteId))
        assertEquals(fixture.linkValues, readRowValues("quote_invoice_links", "invoice_id", fixture.invoiceId))
        val mergedDecision = head("quote_decision", fixture.decisionEntityId)
        val mergedLink = head("quote_invoice_link", fixture.invoiceId)
        assertEquals("BACKUP_MERGE", database.rawQuery(
            "SELECT origin FROM entity_revisions WHERE id=?", arrayOf(mergedDecision),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals("BACKUP_MERGE", database.rawQuery(
            "SELECT origin FROM entity_revisions WHERE id=?", arrayOf(mergedLink),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(
            setOf(fixture.decisionLocal, backupDecision),
            parents(mergedDecision),
        )
        assertEquals(setOf(fixture.linkLocal, backupLink), parents(mergedLink))
        assertEquals(
            fixture.decisionValues,
            JSONObject(revisionPayload(mergedDecision)).let { json ->
                mapOf(
                    "quote_id" to json.getString("quote_id"),
                    "revision" to json.getLong("revision"),
                    "decision" to json.getString("decision"),
                    "note" to json.getString("note"),
                    "recorded_ms" to json.getLong("recorded_ms"),
                )
            },
        )
        assertEquals(
            fixture.linkValues,
            JSONObject(revisionPayload(mergedLink)).let { json ->
                mapOf(
                    "invoice_id" to json.getString("invoice_id"),
                    "quote_id" to json.getString("quote_id"),
                    "revision" to json.getLong("revision"),
                    "original_invoice_id" to json.getString("original_invoice_id"),
                    "created_ms" to json.getLong("created_ms"),
                    "payload" to json.getString("payload"),
                )
            },
        )

        assertTrue(exportStore.close(source.snapshotId))
        val roundTripSnapshot = exportStore.start(database)
        val roundTripImport = stageSnapshot(roundTripSnapshot)
        val roundTripPreview = importStore.finish(database, roundTripImport.importId)
        assertEquals(0L, roundTripPreview.conflictCount)
        assertTrue(roundTripPreview.applyReady)
        importStore.apply(database, roundTripImport.importId, roundTripPreview.previewToken, roundTripPreview.localStateVersion)
        assertEquals(mergedDecision, head("quote_decision", fixture.decisionEntityId))
        assertEquals(mergedLink, head("quote_invoice_link", fixture.invoiceId))
        assertTrue(exportStore.close(roundTripSnapshot.snapshotId))
    }

    private fun parents(revisionId: String): Set<String> = database.rawQuery(
        "SELECT parent_id FROM revision_parents WHERE revision_id=?",
        arrayOf(revisionId),
    ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
}
