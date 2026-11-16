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
class BackupImportStoreAggregateConflictTest : BackupImportStoreTestBase() {
    @Test
    fun globalSettingsConflictsResolveAsOneLocalSnapshot() {
        data class SettingCase(
            val kind: String,
            val table: String,
            val field: String,
            val localValue: Any,
            val backupValue: Any,
            val revisionColumns: List<String>,
        )

        val cases = listOf(
            SettingCase(
                "settings", "app_settings", "company_name", "Local company", "Backup company",
                BusinessSchema.settingsColumns,
            ),
            SettingCase(
                "settings_money", "settings_money", "minor_text", "7200", "8400",
                listOf("id", "minor_text", "job_id"),
            ),
            SettingCase(
                "billing_tax_settings", "billing_tax_settings", "default_tax_units", 1_250, 1_500,
                listOf("id", "default_tax_units"),
            ),
        )
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        fun archiveValues(table: String): MutableMap<String, Any?> {
            val record = records.first { it["table"] == table && values(it)["id"].toString() == "1" }
            return values(record).entries.associate { it.key.toString() to it.value }.toMutableMap()
        }
        fun replaceArchiveRow(table: String, replacement: Map<String, Any?>) {
            records.removeAll { it["table"] == table && values(it)["id"].toString() == "1" }
            records.add(stagedRow(table, replacement))
        }
        fun currentHead(kind: String): String = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id='1'",
            arrayOf(kind),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        fun rowValues(table: String): Map<String, Any?> = readRowValues(table, "id", "1")

        data class Expected(val case: SettingCase, val localHead: String, val backupHead: String)
        val expected = cases.mapIndexed { index, case ->
            val backupRow = archiveValues(case.table)
            val baseHead = currentHead(case.kind)
            database.execSQL(
                "UPDATE ${case.table} SET ${case.field}=? WHERE id=1",
                arrayOf(case.localValue),
            )
            val localRow = rowValues(case.table)
            val localPayload = case.revisionColumns.associateWith { column ->
                when {
                    column == "job_id" -> null
                    case.kind == "settings_money" && column == "id" -> "1"
                    else -> localRow[column]
                }
            }
            val localHead = RevisionStore.record(
                database, case.kind, "1", null, localPayload, now + 1L,
            )

            val backupRevisionId = "global-settings-backup-$index"
            val backupRowUpdated = backupRow.toMutableMap().apply { put(case.field, case.backupValue) }
            val backupPayload = case.revisionColumns.associateWith { column ->
                when {
                    column == "job_id" -> null
                    case.kind == "settings_money" && column == "id" -> "1"
                    else -> backupRowUpdated[column]
                }
            }
            records.add(revisionRecord(
                backupRevisionId, case.kind, "1", null,
                payload = JSONObject(backupPayload.toSortedMap()).toString(),
            ))
            records.add(parentRecord(backupRevisionId, baseHead))
            records.removeAll {
                it["table"] == "entity_heads" && values(it)["kind"] == case.kind &&
                    values(it)["entity_id"].toString() == "1"
            }
            records.add(headRecord(case.kind, "1", backupRevisionId))
            replaceArchiveRow(case.table, backupRowUpdated)
            Expected(case, localHead, backupRevisionId)
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
        val heads = conflicts.filter { it["table"] == "entity_heads" }
            .associateBy { (it["primaryKey"] as List<*>)[0].toString() }
        assertEquals(cases.map { it.kind }.toSet(), heads.keys)
        heads.values.forEach { conflict ->
            assertTrue((conflict["aggregateKeys"] as List<*>).contains("global:settings"))
            assertEquals(
                listOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT", "MERGE_USE_BACKUP_CONTENT"),
                conflict["allowedChoices"],
            )
        }

        val selected = requireNotNull(heads["settings"])
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            selected["conflictId"] as String, "MERGE_KEEP_LOCAL_CONTENT",
        )
        val resolved = mutableListOf<Map<String, Any?>>()
        cursor = null
        do {
            val page = importStore.readConflictPage(database, started.importId, preview.previewToken, cursor, null)
            resolved.addAll(page.conflicts)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        resolved.filter { it["aggregateKeys"] is List<*> &&
            (it["aggregateKeys"] as List<*>).contains("global:settings") }.forEach { conflict ->
            assertTrue(conflict["resolved"] as Boolean)
            assertTrue(conflict["selectedChoice"] == "MERGE_KEEP_LOCAL_CONTENT" ||
                conflict["selectedChoice"] == "KEEP_LOCAL")
        }

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertEquals("Local company", rowValues("app_settings")["company_name"])
        assertEquals("7200", rowValues("settings_money")["minor_text"])
        assertEquals(1_250L, rowValues("billing_tax_settings")["default_tax_units"])
        expected.forEach { item ->
            val mergedHead = currentHead(item.case.kind)
            val parents = database.rawQuery(
                "SELECT parent_id FROM revision_parents WHERE revision_id=? ORDER BY parent_id",
                arrayOf(mergedHead),
            ).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertEquals(setOf(item.localHead, item.backupHead), parents)
        }
        assertFalse(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun quoteInvoiceAggregateIncludesOriginalInvoiceForRowsAndRevisionHeads() {
        val quoteId = "aggregate-quote-origin"
        val invoiceJobId = "aggregate-quote-origin-job"
        val taskId = "aggregate-quote-origin-task"
        val quotedInvoiceId = "aggregate-quote-origin-invoice"
        val replacementInvoiceId = "aggregate-quote-origin-replacement"
        assertNull(repository.saveQuote(
            "aggregate-quote-origin-save",
            repository.snapshot().version,
            QuoteStore.Draft(
                quoteId, null, null, "Quoted tile installation", "USD", 75_000,
                listOf(QuoteStore.Line("tile", "Tile", "Synthetic tile work", "ITEM", "260000", "1250")),
            ),
        ).conflict)
        assertNull(repository.issueQuote(
            "aggregate-quote-origin-issue", repository.snapshot().version, quoteId, true,
        ).conflict)
        assertNull(repository.decideQuote(
            "aggregate-quote-origin-accept", repository.snapshot().version, quoteId, 1,
            "ACCEPTED", "Explicit aggregate fixture acceptance", true,
        ).conflict)
        assertNull(repository.applyQuoteScope(
            "aggregate-quote-origin-scope",
            repository.snapshot().version,
            QuoteScopeStore.Draft(
                quoteId, 1, invoiceJobId,
                listOf(QuoteScopeStore.Item("tile", taskId, "Install tile")),
                "Reviewed scope for aggregate fixture", true, "Aggregate fixture job",
                jobDifferencesReviewed = true,
            ),
        ).conflict)
        val board = repository.boardSnapshot(invoiceJobId).board
        val completedColumn = board.columns.single { it.completed }.id
        assertNull(repository.saveCard(
            "aggregate-quote-origin-complete",
            repository.snapshot().version,
            BoardStore.Draft(
                taskId, invoiceJobId, completedColumn, "Install tile",
                priceMinor = java.math.BigInteger("999"), currency = "USD", quantityUnits = 260_000L,
            ),
        ).conflict)
        val request = QuoteInvoiceReviewStore.Request(
            quoteId, 1,
            ReportStore.Selection("2026-01-01", "2026-12-31", "UTC", listOf(invoiceJobId)),
        )
        val choices = QuoteInvoiceReviewStore.Choices(
            listOf(taskId), emptyList(), note = "Reviewed actual tile work", confirmed = true,
        )
        assertNull(repository.prepareQuoteInvoice(
            "aggregate-quote-origin-prepare",
            repository.snapshot().version,
            QuoteInvoiceStore.Draft(quotedInvoiceId, request, choices),
        ).conflict)
        val originalInvoice = repository.invoice(quotedInvoiceId)
        val originalDocument = InvoiceStore.Snapshot.decode(originalInvoice.payload)
        val originalLine = originalDocument.lines.single()
        assertNull(repository.replaceInvoice(
            "aggregate-quote-origin-replace",
            repository.snapshot().version,
            InvoiceLifecycleStore.ReplacementDraft(
                quotedInvoiceId, replacementInvoiceId,
                listOf(InvoiceLifecycleStore.LineEdit(
                    0, originalLine.title, originalLine.detail, "39000", "260000", "1500",
                )),
                75_000, "Synthetic correction for aggregate fixture", "UTC", true,
            ),
        ).conflict)

        val localLink = database.rawQuery(
            "SELECT quote_id,revision,original_invoice_id,created_ms,payload FROM quote_invoice_links WHERE invoice_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            listOf(rows.getString(0), rows.getInt(1), rows.getString(2), rows.getLong(3), rows.getString(4))
        }
        assertEquals(quotedInvoiceId, localLink[2])
        val localLinkHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='quote_invoice_link' AND entity_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val linkJobId = repository.invoice(replacementInvoiceId).jobId
        val baseRevisionId = "aggregate-quote-origin-common-base"
        val basePayload = JSONObject(mapOf(
            "invoice_id" to replacementInvoiceId,
            "quote_id" to quoteId,
            "revision" to localLink[1],
            "original_invoice_id" to quotedInvoiceId,
            "created_ms" to localLink[3],
            "payload" to localLink[4],
        )).toString()
        database.execSQL(
            "INSERT INTO entity_revisions(id,kind,entity_id,job_id,payload,deleted,changed_ms,origin) " +
                "VALUES(?,'quote_invoice_link',?,?,?,0,?,'MUTATION')",
            arrayOf<Any?>(baseRevisionId, replacementInvoiceId, linkJobId, basePayload, now - 2L),
        )
        database.execSQL(
            "INSERT INTO revision_parents(revision_id,parent_id) VALUES(?,?)",
            arrayOf(localLinkHead, baseRevisionId),
        )
        val source = exportStore.start(database)
        val records = readAllRows(source.snapshotId).toMutableList()

        fun replaceArchiveRow(table: String, keyColumn: String, key: String, replacement: Map<String, Any?>) {
            records.removeAll { it["table"] == table && values(it)[keyColumn]?.toString() == key }
            records.add(stagedRow(table, replacement))
        }
        fun archiveValues(table: String, predicate: (Map<*, *>) -> Boolean): MutableMap<String, Any?> {
            val record = records.first { it["table"] == table && predicate(values(it)) }
            return values(record).entries.associate { it.key.toString() to it.value }.toMutableMap()
        }

        val backupCreatedMs = (localLink[3] as Long) + 1L
        val replacementLink = archiveValues("quote_invoice_links") {
            it["invoice_id"] == replacementInvoiceId
        }.apply { put("created_ms", backupCreatedMs) }
        replaceArchiveRow("quote_invoice_links", "invoice_id", replacementInvoiceId, replacementLink)

        val changedInvoiceNumber = "INV-9997"
        val staleInvoice = archiveValues("invoices") { it["id"] == quotedInvoiceId }.apply {
            put("number", changedInvoiceNumber)
            put("payload", originalDocument.copy(number = changedInvoiceNumber).encode())
        }
        replaceArchiveRow("invoices", "id", quotedInvoiceId, staleInvoice)
        val staleDecision = archiveValues("quote_decisions") { it["quote_id"] == quoteId }
        val localDecisionNote = staleDecision["note"]
        staleDecision["note"] = "Stale backup acceptance note"
        records.removeAll { it["table"] == "quote_decisions" && values(it)["quote_id"] == quoteId }
        records.add(stagedRow("quote_decisions", staleDecision))
        val localDecisionHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='quote_decision' AND entity_id=?",
            arrayOf("$quoteId:${staleDecision.getValue("revision")}"),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        val decisionJobId = database.rawQuery(
            "SELECT job_id FROM entity_revisions WHERE id=?",
            arrayOf(localDecisionHead),
        ).use { rows -> assertTrue(rows.moveToFirst()); if (rows.isNull(0)) null else rows.getString(0) }
        val backupDecisionHead = "aggregate-quote-decision-backup-head"
        records.add(revisionRecord(
            backupDecisionHead, "quote_decision", "$quoteId:${staleDecision.getValue("revision")}",
            decisionJobId, payload = JSONObject(staleDecision).toString(),
        ))
        records.add(parentRecord(backupDecisionHead, localDecisionHead))
        records.removeAll {
            it["table"] == "entity_heads" && values(it)["kind"] == "quote_decision" &&
                values(it)["entity_id"] == "$quoteId:${staleDecision.getValue("revision")}"
        }
        records.add(headRecord(
            "quote_decision", "$quoteId:${staleDecision.getValue("revision")}", backupDecisionHead,
        ))
        val staleScopeRevision = archiveValues("quote_scope_revisions") { it["quote_id"] == quoteId }
        val localScopeNote = staleScopeRevision["note"]
        staleScopeRevision["note"] = "Stale backup scope note"
        records.removeAll { it["table"] == "quote_scope_revisions" && values(it)["quote_id"] == quoteId }
        records.add(stagedRow("quote_scope_revisions", staleScopeRevision))

        val backupHeadRevisionId = "aggregate-quote-origin-backup-head"
        val backupPayload = JSONObject(mapOf(
            "invoice_id" to replacementInvoiceId,
            "quote_id" to quoteId,
            "revision" to localLink[1],
            "original_invoice_id" to quotedInvoiceId,
            "created_ms" to backupCreatedMs,
            "payload" to localLink[4],
        )).toString()
        records.add(revisionRecord(
            backupHeadRevisionId, "quote_invoice_link", replacementInvoiceId,
            linkJobId, payload = backupPayload,
        ))
        records.add(parentRecord(backupHeadRevisionId, baseRevisionId))
        records.removeAll {
            it["table"] == "entity_heads" && values(it)["kind"] == "quote_invoice_link" &&
                values(it)["entity_id"] == replacementInvoiceId
        }
        records.add(headRecord("quote_invoice_link", replacementInvoiceId, backupHeadRevisionId))

        val started = stageImport(records)
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
        fun conflict(table: String, matches: (List<*>) -> Boolean): Map<String, Any?> =
            requireNotNull(conflicts.singleOrNull { row ->
                row["table"] == table && matches(row["primaryKey"] as List<*>)
            }) { "Missing $table aggregate fixture conflict" }

        // The divergent immutable row is resolved atomically through its revision-head conflict.
        val linkHeadConflict = conflict("entity_heads") {
            it.getOrNull(0) == "quote_invoice_link" && it.getOrNull(1) == replacementInvoiceId
        }
        assertFalse(conflicts.any { row ->
            row["table"] == "quote_invoice_links" &&
                (row["primaryKey"] as? List<*>)?.singleOrNull() == replacementInvoiceId
        })
        val originalInvoiceConflict = conflict("invoices") { it.singleOrNull() == quotedInvoiceId }
        val quoteDecisionConflict = conflict("quote_decisions") { it.getOrNull(0) == quoteId }
        val quoteScopeConflict = conflict("quote_scope_revisions") { it.getOrNull(0) == quoteId }
        val linkAggregateKeys = linkHeadConflict["aggregateKeys"] as List<*>
        assertTrue(linkAggregateKeys.contains("invoice:$quotedInvoiceId"))
        assertTrue(linkAggregateKeys.contains("invoice:$replacementInvoiceId"))
        assertTrue(linkAggregateKeys.contains("quote:$quoteId"))
        assertTrue((originalInvoiceConflict["aggregateKeys"] as List<*>).contains("invoice:$quotedInvoiceId"))
        listOf(quoteDecisionConflict, quoteScopeConflict).forEach { row ->
            assertTrue((row["aggregateKeys"] as List<*>).contains("quote:$quoteId"))
        }
        val stateBeforeApply = repository.snapshot()
        assertEquals(
            listOf("KEEP_LOCAL", "MERGE_KEEP_LOCAL_CONTENT"),
            linkHeadConflict["allowedChoices"],
        )
        assertEquals("MERGE_KEEP_LOCAL_CONTENT", linkHeadConflict["defaultChoice"])
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database, started.importId, preview.previewToken, preview.localStateVersion,
                linkHeadConflict["conflictId"] as String, "MERGE_USE_BACKUP_CONTENT",
            )
        }
        importStore.resolveConflict(
            database, started.importId, preview.previewToken, preview.localStateVersion,
            linkHeadConflict["conflictId"] as String, "MERGE_KEEP_LOCAL_CONTENT",
        )
        assertEquals(stateBeforeApply, repository.snapshot())
        assertEquals(localLinkHead, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='quote_invoice_link' AND entity_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(originalInvoice.number, repository.invoice(quotedInvoiceId).number)
        assertEquals(quotedInvoiceId, database.rawQuery(
            "SELECT original_invoice_id,created_ms,payload FROM quote_invoice_links WHERE invoice_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(localLink[2], rows.getString(0))
            assertEquals(localLink[3], rows.getLong(1))
            assertEquals(localLink[4], rows.getString(2))
            rows.getString(0)
        })
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$backupHeadRevisionId'"))

        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)

        assertEquals(originalInvoice.number, repository.invoice(quotedInvoiceId).number)
        assertEquals(quotedInvoiceId, database.rawQuery(
            "SELECT original_invoice_id,created_ms,payload FROM quote_invoice_links WHERE invoice_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(localLink[2], rows.getString(0))
            assertEquals(localLink[3], rows.getLong(1))
            assertEquals(localLink[4], rows.getString(2))
            rows.getString(0)
        })
        assertEquals(localDecisionNote, database.rawQuery(
            "SELECT note FROM quote_decisions WHERE quote_id=? AND revision=?",
            arrayOf(quoteId, staleDecision.getValue("revision").toString()),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(localScopeNote, database.rawQuery(
            "SELECT note FROM quote_scope_revisions WHERE quote_id=? AND revision=?",
            arrayOf(quoteId, staleScopeRevision.getValue("revision").toString()),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        val mergedLinkHead = database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind='quote_invoice_link' AND entity_id=?",
            arrayOf(replacementInvoiceId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) }
        assertNotEquals(localLinkHead, mergedLinkHead)
        assertEquals(2L, number(
            "SELECT COUNT(*) FROM revision_parents WHERE revision_id='$mergedLinkHead' " +
                "AND parent_id IN('$localLinkHead','$backupHeadRevisionId')",
        ))
        assertFalse(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }

    @Test
    fun nonIdenticalDivergentHeadsRemainUnresolvedWithoutAMergeAdapter() {
        val kind = "quote_decision"
        val entityId = "nonidentical-divergent-entity"
        val baseRevision = "nonidentical-divergent-base"
        val localRevision = "nonidentical-divergent-local"
        val backupRevision = "nonidentical-divergent-backup"
        insertSyntheticRevision(baseRevision, kind, entityId, null)
        insertSyntheticRevision(localRevision, kind, entityId, baseRevision, "{\"side\":\"local\"}")
        database.execSQL(
            "INSERT INTO entity_heads(kind,entity_id,revision_id) VALUES(?,?,?)",
            arrayOf(kind, entityId, localRevision),
        )
        val started = stageImport(listOf(
            revisionRecord(baseRevision, kind, entityId, null),
            revisionRecord(backupRevision, kind, entityId, null, payload = "{\"side\":\"backup\"}"),
            parentRecord(backupRevision, baseRevision),
            headRecord(kind, entityId, backupRevision),
        ))
        val preview = importStore.finish(database, started.importId)
        val conflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        assertEquals("DIVERGENT", conflict["revisionRelationship"])
        assertEquals(emptyList<String>(), conflict["allowedChoices"])
        assertNull(conflict["defaultChoice"])
        assertFalse(conflict["resolved"] as Boolean)

        val before = repository.snapshot()
        assertFailure("UNSUPPORTED_CHOICE") {
            importStore.resolveConflict(
                database,
                started.importId,
                preview.previewToken,
                preview.localStateVersion,
                conflict["conflictId"] as String,
                "KEEP_LOCAL",
            )
        }
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }

        assertEquals(before, repository.snapshot())
        assertEquals(localRevision, database.rawQuery(
            "SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?",
            arrayOf(kind, entityId),
        ).use { rows -> assertTrue(rows.moveToFirst()); rows.getString(0) })
        assertEquals(0L, number("SELECT COUNT(*) FROM entity_revisions WHERE id='$backupRevision'"))
        assertTrue(importStore.close(started.importId))
    }

    @Test
    fun oldBackupPointCoveredByLocalRangeRequiresExplicitPreserveChoice() {
        val session = startAndStopSessionWithPoint("old-backup-covered-point")
        val pointCountBeforeExport = number("SELECT COUNT(*) FROM location_points")
        val source = exportStore.start(database)
        assertEquals(1L, pointCountBeforeExport)
        assertNull(repository.deleteLocationRange(
            "delete-after-old-backup-export",
            repository.snapshot().version,
            GpsDeletionStore.Range(jobId, session.second, session.second + 1L, "Etc/UTC"),
        ).conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))

        val started = importStore.begin(
            database,
            source.archiveFormatVersion,
            source.schemaVersion,
            "d".repeat(64),
            source.recordCount,
            source.mediaCount,
            source.mediaBytes,
        )
        transferRows(source.snapshotId, started.importId)
        val preview = importStore.finish(database, started.importId)
        val coverageConflict = findConflict(started.importId, preview, "GPS_DELETION_COVERAGE")
        assertNotNull(coverageConflict)
        val conflict = requireNotNull(coverageConflict)
        assertEquals("PRESERVE_DELETION", conflict["defaultChoice"])
        assertEquals(listOf("PRESERVE_DELETION"), conflict["allowedChoices"])
        assertEquals(false, conflict["resolved"])
        assertFailure("UNRESOLVED_CONFLICTS") {
            importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        }
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            conflict["conflictId"] as String,
            "PRESERVE_DELETION",
        )
        val headConflict = requireNotNull(findConflict(started.importId, preview, "EXISTING_RECORD_DIFFERS"))
        importStore.resolveConflict(
            database,
            started.importId,
            preview.previewToken,
            preview.localStateVersion,
            headConflict["conflictId"] as String,
            "KEEP_LOCAL",
        )
        importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
        assertNoCoordinates(conflict)
        assertEquals(0L, number("SELECT COUNT(*) FROM location_points"))
        assertEquals(1L, number("SELECT COUNT(*) FROM location_deletion_ranges"))
        assertFalse(preview.applyReady)
        assertFalse(importStore.close(started.importId))
        assertTrue(exportStore.close(source.snapshotId))
    }
}
