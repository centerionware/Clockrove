package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exports the app's own database and restores it immediately, which is what the
 * owner does after taking a backup. The archive must always validate, including
 * after an earlier restore has written its normalization revisions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class BackupImportStoreRoundTripTest : BackupImportStoreTestBase() {
    private val version get() = repository.snapshot().version
    private val sessionStart = 1_793_491_200_000L
    private val sessionEnd = 1_793_498_400_000L

    private fun seedQuotes() {
        val draft = QuoteStore.Draft(
            "quote", "customer", null, "Synthetic round-trip estimate", "USD", 75000,
            listOf(QuoteStore.Line("tile", "Install tile", "Synthetic detail", "ITEM", "260000", "1250")),
            "2026-12-01", "Synthetic terms", "Synthetic note",
        )
        assertNull(repository.saveQuote("round-trip-save", version, draft).conflict)
        assertNull(repository.issueQuote("round-trip-issue", version, "quote", true).conflict)
        assertNull(
            repository.saveQuote("round-trip-revise", version, draft.copy(title = "Synthetic round-trip revision")).conflict,
        )
        assertNull(repository.issueQuote("round-trip-reissue", version, "quote", true).conflict)
        assertEquals(2, requireNotNull(repository.quote("quote").latest).revision)
    }

    private fun seedInvoice() {
        val job = repository.createJob("Synthetic round-trip job")
        assertNull(
            repository.saveBusinessSettings(
                "round-trip-settings",
                version,
                repository.businessSnapshot().settings.copy(
                    currency = "USD",
                    companyName = "Synthetic round-trip company",
                    companyEmail = "company@example.test",
                    defaultHourlyMinor = BigInteger.valueOf(10000),
                    defaultTaxUnits = 75000,
                ),
            ).conflict,
        )
        assertNull(
            repository.saveJobSettings(
                "round-trip-job-settings",
                version,
                repository.jobDetail(job).job.settings.copy(billingMode = "HOURLY", customerEmailOverride = "customer@example.test"),
            ).conflict,
        )
        assertNull(
            repository.execute(
                TimerRepository.Command(
                    "round-trip-start", version, TimerRepository.Action.START, job,
                    sessionStart, "UTC", 0, TimerRepository.Origin.MANUAL,
                ),
            ).conflict,
        )
        assertNull(
            repository.execute(
                TimerRepository.Command(
                    "round-trip-stop", version, TimerRepository.Action.STOP, null,
                    sessionEnd, "UTC", 0, TimerRepository.Origin.MANUAL,
                ),
            ).conflict,
        )
        val selection = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
        assertNull(repository.prepareInvoice("round-trip-prepare", version, "invoice", selection).conflict)
        val total = InvoiceStore.Snapshot.decode(repository.invoice("invoice").payload).total
        val half = PaymentMath.parseMajor(total, 2).divide(BigInteger.valueOf(2))
        assertNull(
            repository.recordInvoicePayment(
                "round-trip-payment",
                version,
                InvoicePaymentStore.Draft(
                    "round-trip-payment", "invoice", half.toString(), "USD",
                    sessionEnd, "UTC", "Bank transfer", "Synthetic reference", "Synthetic note",
                ),
            ).conflict,
        )
        // A removed cost keeps its row with deleted_ms set and a tombstone head.
        assertNull(
            repository.saveActualCost(
                "round-trip-removed-cost",
                version,
                ActualCostStore.Draft(
                    "round-trip-removed-cost", job, null, "500", "USD", ActualCostStore.MATERIALS,
                    sessionStart, "UTC", "Synthetic supplier", "Synthetic receipt", "Synthetic removed cost",
                ),
            ).conflict,
        )
        assertNull(repository.removeActualCost("round-trip-remove-cost", version, "round-trip-removed-cost").conflict)

        val archived = repository.createJob("Synthetic archived job")
        assertNull(repository.archiveJob("round-trip-archive", version, archived).conflict)

        // A retained cost must survive its job's permanent deletion and still restore:
        // purging the job used to tombstone the cost's revision head while the row
        // remained live, so the freshly exported archive failed INVALID_REVISION_GRAPH.
        val costJob = repository.createJob("Synthetic purged cost job")
        assertNull(
            repository.saveActualCost(
                "round-trip-cost",
                version,
                ActualCostStore.Draft(
                    "round-trip-cost", costJob, null, "1234", "USD", ActualCostStore.MATERIALS,
                    sessionStart, "UTC", "Synthetic supplier", "Synthetic receipt", "Synthetic cost",
                ),
            ).conflict,
        )
        assertNull(repository.archiveJob("round-trip-archive-cost-job", version, costJob).conflict)
        database.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf<Any?>(costJob))
        assertNull(repository.deleteJob("round-trip-delete-cost-job", version, costJob).conflict)

        // Simulate an install upgraded from an older release: retained rows keep a
        // live head whose payload the old purge redacted. Opening the database must
        // repair them before the export copies the graph.
        listOf("actual_cost" to "round-trip-cost", "invoice_coverage" to "invoice").forEach { (kind, entityId) ->
            database.execSQL(
                "UPDATE entity_revisions SET payload=NULL WHERE id=" +
                    "(SELECT revision_id FROM entity_heads WHERE kind=? AND entity_id=?)",
                arrayOf<Any?>(kind, entityId),
            )
        }
        database.close()
        repository.close()
        repository = TimerRepository(app, wallClock = { now })
        repository.snapshot()
        database = SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        database.setForeignKeyConstraintsEnabled(true)
    }

    /** One export -> stage -> preview -> apply cycle against the live database. */
    private fun restore() {
        val snapshot = exportStore.start(database)
        try {
            val started = stageSnapshot(snapshot)
            try {
                val preview = importStore.finish(database, started.importId)
                assertEquals("Self restore must not report conflicts", 0L, preview.conflictCount)
                assertEquals("Self restore must not report unresolved references", 0L, preview.unresolvedReferenceCount)
                assertTrue("Self restore must be apply ready", preview.applyReady)
                importStore.apply(database, started.importId, preview.previewToken, preview.localStateVersion)
            } finally {
                importStore.close(started.importId)
            }
        } finally {
            exportStore.close(snapshot.snapshotId)
        }
    }

    @Test
    fun exportedArchiveRestoresImmediatelyAndAgainAfterAnEarlierRestore() {
        // The synthetic session ends at the test clock, so the payment is received on time.
        now = sessionEnd
        assertNull(
            repository.saveCustomer(
                "round-trip-customer",
                version,
                BusinessStore.Customer("customer", "Synthetic customer", "customer@example.test"),
            ).conflict,
        )
        seedQuotes()
        seedInvoice()

        restore()
        assertTrue(repository.invoice("invoice").payload.isNotEmpty())
        assertEquals("349.38", InvoiceStore.Snapshot.decode(repository.quoteDocument("quote", 2).payload).total)
        assertTrue(repository.actualCosts().page.entries.any { it.id == "round-trip-cost" })
        assertTrue(repository.actualCosts().page.entries.none { it.id == "round-trip-removed-cost" })

        // Repeat the cycle so restore-time normalization revisions are exercised too;
        // this is extra coverage, not a claim that the owner had restored before.
        restore()
        assertEquals("349.38", InvoiceStore.Snapshot.decode(repository.quoteDocument("quote", 2).payload).total)
        assertTrue(repository.quotes().page.entries.any { it.id == "quote" })
        assertTrue(repository.actualCosts().page.entries.any { it.id == "round-trip-cost" })
        assertTrue(repository.actualCosts().page.entries.none { it.id == "round-trip-removed-cost" })
    }
}
