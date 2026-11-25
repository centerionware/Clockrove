package io.github.centerionware.clockrove.tracking

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
                    1793491200000, "UTC", 0, TimerRepository.Origin.MANUAL,
                ),
            ).conflict,
        )
        assertNull(
            repository.execute(
                TimerRepository.Command(
                    "round-trip-stop", version, TimerRepository.Action.STOP, null,
                    1793498400000, "UTC", 0, TimerRepository.Origin.MANUAL,
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
                    1793498400000, "UTC", "Bank transfer", "Synthetic reference", "Synthetic note",
                ),
            ).conflict,
        )
        val archived = repository.createJob("Synthetic archived job")
        assertNull(repository.archiveJob("round-trip-archive", version, archived).conflict)
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

        // The owner's failing case re-exported a state that an earlier restore had already
        // read, so the second cycle also has to validate the normalization revisions.
        restore()
        assertEquals("349.38", InvoiceStore.Snapshot.decode(repository.quoteDocument("quote", 2).payload).total)
        assertTrue(repository.quotes().page.entries.any { it.id == "quote" })
    }
}
