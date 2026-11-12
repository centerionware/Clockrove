package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class MoneyPrecisionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private val at = 1793494800000L
    private val wide = BigInteger("100000000000000000000")
    private val version get() = repository.snapshot().version
    private val selection get() = ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { at })
        assertNull(repository.saveBusinessSettings("currency", version, repository.businessSnapshot().settings.copy(currency = "USD")).conflict)
        job = repository.createJob("Synthetic precise-money job")
    }
    @After fun close() { repository.close() }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun draft(price: BigInteger? = wide) = BoardStore.Draft("tile", job, repository.boardSnapshot(job).board.columns.last().id,
        "Synthetic precise tile", priceMinor = price, currency = "USD", quantityUnits = 260000)
    private fun fingerprint(id: String) = inspect { db -> db.rawQuery("SELECT fingerprint FROM commands WHERE id=?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) } }
    @Test fun wideDefaultJobOverrideAndHourlyTotalPersistAsExactText() {
        assertNull(repository.saveBusinessSettings("wide-rate", version, repository.businessSnapshot().settings.copy(defaultHourlyMinor = wide)).conflict)
        assertEquals(wide, repository.jobDetail(job).job.effectiveHourlyMinor)
        assertNull(repository.saveJobSettings("zero", version, repository.jobDetail(job).job.settings.copy(hourlyRateMinor = BigInteger.ZERO)).conflict)
        assertEquals(BigInteger.ZERO, repository.jobDetail(job).job.effectiveHourlyMinor)
        assertNull(repository.saveJobSettings("wide-job", version, repository.jobDetail(job).job.settings.copy(hourlyRateMinor = wide.add(BigInteger.ONE))).conflict)
        assertEquals(wide.add(BigInteger.ONE), repository.jobDetail(job).job.effectiveHourlyMinor)
        assertNull(repository.createManualSession("work", version, SessionHistoryStore.Draft("work", job, at - 3600000, at, "UTC", 0)).conflict)
        assertEquals(wide.add(BigInteger.ONE), repository.report(selection).report.totals.single().amountMinor)
        repository.close(); repository = TimerRepository(app, wallClock = { at })
        assertEquals(wide, repository.businessSnapshot().settings.defaultHourlyMinor)
        assertEquals(wide.add(BigInteger.ONE), repository.jobDetail(job).job.effectiveHourlyMinor)
        inspect { db ->
            db.rawQuery("SELECT typeof(minor_text),minor_text FROM settings_money", null).use { it.moveToFirst(); assertEquals("text", it.getString(0)); assertEquals(wide.toString(), it.getString(1)) }
            db.rawQuery("SELECT default_hourly_minor FROM app_settings", null).use { it.moveToFirst(); assertTrue(it.isNull(0)) }
            db.rawQuery("SELECT hourly_rate_minor FROM jobs WHERE id=?", arrayOf(job)).use { it.moveToFirst(); assertTrue(it.isNull(0)) }
        }
        assertNull(repository.saveJobSettings("inherit", version, repository.jobDetail(job).job.settings.copy(hourlyRateMinor = null)).conflict)
        assertEquals(wide, repository.jobDetail(job).job.effectiveHourlyMinor)
    }
    @Test fun wideTaskQuantityTaxAndFrozenInvoiceNeverNarrowOrRebill() {
        assertNull(repository.saveBusinessSettings("tax", version, repository.businessSnapshot().settings.copy(defaultTaxUnits = 75000)).conflict)
        assertNull(repository.saveJobSettings("task-mode", version, repository.jobDetail(job).job.settings.copy(billingMode = "TASK")).conflict)
        assertNull(repository.saveCard("save", version, draft()).conflict)
        assertEquals(BigInteger("2600000000000000000000"), repository.report(selection).report.totals.single().amountMinor)
        assertNull(repository.prepareInvoice("prepare", version, "invoice", selection).conflict)
        val original = repository.invoice("invoice").payload
        val invoice = InvoiceStore.Snapshot.decode(original)
        assertEquals("26000000000000000000.00", invoice.subtotal)
        assertEquals("1950000000000000000.00", invoice.taxAmount)
        assertEquals("27950000000000000000.00", invoice.total)
        assertEquals(wide.toString(), invoice.lines.single().unitPriceMinor)
        assertTrue(invoice.body.contains("27950000000000000000.00 USD"))
        assertNull(repository.saveCard("changed", version, draft(wide.multiply(BigInteger.TEN))).conflict)
        assertEquals("NO_BILLABLE_ITEMS", repository.prepareInvoice("again", version, "another", selection).conflict)
        assertEquals(original, repository.invoice("invoice").payload)
        inspect { db -> db.rawQuery("SELECT typeof(minor_text),minor_text FROM card_money WHERE id='tile'", null).use {
            it.moveToFirst(); assertEquals("text", it.getString(0)); assertEquals(wide.multiply(BigInteger.TEN).toString(), it.getString(1))
        } }
    }
    @Test fun wideTemplateSurvivesSourcePurgeAndCopiesExactPriceWithoutWorkHistory() {
        assertNull(repository.saveCard("save", version, draft()).conflict)
        val template = repository.taskTemplates().single()
        assertEquals(wide, template.priceMinor)
        assertNull(repository.archiveJob("archive", version, job).conflict)
        inspect { it.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf(job)) }
        assertNull(repository.deleteJob("purge", version, job).conflict)
        assertEquals(wide, repository.taskTemplates().single().priceMinor)
        val next = repository.createJob("Synthetic next job")
        assertNull(repository.copyTaskTemplate("copy", version, template.id, "next", next, repository.boardSnapshot(next).board.columns.first().id, true).conflict)
        val copied = repository.boardSnapshot(next).board.cards.single()
        assertEquals(wide, copied.priceMinor); assertEquals(260000L, copied.quantityUnits); assertNull(copied.completedMillis)
        inspect { db -> db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) } }
    }
    @Test fun releasedSmallSettingsJobAndCardReceiptFingerprintsStayIdentical() {
        val settings = repository.businessSnapshot().settings.copy(defaultHourlyMinor = BigInteger.valueOf(500))
        val initial = version
        val saved = repository.saveBusinessSettings("small-settings", initial, settings)
        val oldSettings = listOf<Any?>(initial, "BUSINESS_SETTINGS", "USD", 500L, settings.companyName, settings.companyEmail, settings.companyPhone,
            settings.companyAddress, settings.companyWebsite, settings.invoiceHeader, settings.invoiceFooter, settings.invoiceStyle, settings.emailSubject, settings.emailBody)
        assertEquals(JSONArray(oldSettings).toString(), fingerprint("small-settings"))
        assertEquals(saved, repository.saveBusinessSettings("small-settings", initial, settings))
        val jobSettings = repository.jobDetail(job).job.settings.copy(hourlyRateMinor = BigInteger.ZERO)
        val jobVersion = version
        val jobSaved = repository.saveJobSettings("small-job", jobVersion, jobSettings)
        val oldJob = listOf<Any?>(jobVersion, "JOB_SETTINGS", job, jobSettings.name, jobSettings.colorArgb, jobSettings.billingMode, "USD", 0L, jobSettings.customerId, jobSettings.customerEmailOverride)
        assertEquals(JSONArray(oldJob).toString(), fingerprint("small-job"))
        assertEquals(jobSaved, repository.saveJobSettings("small-job", jobVersion, jobSettings))
        val task = draft(BigInteger.valueOf(12500)).copy(quantityUnits = 10000)
        val taskVersion = version
        val taskSaved = repository.saveCard("small-card", taskVersion, task)
        val oldCard = listOf<Any?>(taskVersion, "CARD_SAVE", task.id, job, task.columnId, task.title, task.description, task.dueDate, task.billable, 12500L, task.currency)
        assertEquals(JSONArray(oldCard).toString(), fingerprint("small-card"))
        assertEquals(taskSaved, repository.saveCard("small-card", taskVersion, task))
    }
    @Test fun wideReceiptsDistinguishValuesAndReplayAfterOpaquePurge() {
        val initial = version; val task = draft()
        val saved = repository.saveCard("wide-card", initial, task)
        assertNull(saved.conflict); assertEquals(saved, repository.saveCard("wide-card", initial, task))
        assertEquals("COMMAND_ID_REUSED", repository.saveCard("wide-card", initial, task.copy(priceMinor = wide.add(BigInteger.ONE))).conflict)
        assertEquals("COMMAND_ID_REUSED", repository.saveCard("wide-card", initial, task.copy(priceMinor = null)).conflict)
        inspect { OpaqueReceipt.purgePayloads(it) }
        assertEquals(saved, repository.saveCard("wide-card", initial, task))
    }
    @Test fun failedExactWriteRollsBackCardTemplateQuantityAndLegacyColumn() {
        val before = repository.boardSnapshot(job)
        inspect { it.execSQL("CREATE TRIGGER synthetic_money_failure BEFORE INSERT ON card_money BEGIN SELECT RAISE(ABORT,'Synthetic exact-money failure'); END") }
        try { repository.saveCard("rollback", version, draft()); fail("Synthetic failure did not abort") } catch (_: android.database.SQLException) {}
        assertEquals(before, repository.boardSnapshot(job)); assertTrue(repository.taskTemplates().isEmpty())
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM task_quantities", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.execSQL("DROP TRIGGER synthetic_money_failure")
        }
        assertNull(repository.saveCard("rollback", version, draft()).conflict)
        assertEquals(wide, repository.boardSnapshot(job).board.cards.single().priceMinor)
    }
    @Test fun malformedMoneyIsRejectedByTransportDomainAndDatabaseWithoutChangingValues() {
        assertNull(repository.saveBusinessSettings("wide-rate", version, repository.businessSnapshot().settings.copy(defaultHourlyMinor = wide)).conflict)
        val before = repository.businessSnapshot()
        assertEquals("INVALID_SETTINGS", repository.saveBusinessSettings("negative", version, before.settings.copy(defaultHourlyMinor = BigInteger.valueOf(-1))).conflict)
        assertEquals("INVALID_TASK", repository.saveCard("too-long", version, draft(BigInteger("1".repeat(1025)))).conflict)
        inspect { db ->
            for (bad in listOf("01", "-1", "1.5", "1e9", "1".repeat(1025))) {
                try { db.execSQL("UPDATE settings_money SET minor_text=?", arrayOf(bad)); fail("Noncanonical money accepted") } catch (_: android.database.SQLException) {}
            }
        }
        assertEquals(before, repository.businessSnapshot())
        for (bad in listOf("01", "-1", "1.5", "1e9", "1".repeat(1025))) {
            try { MoneyMath.parse(bad); fail("Malformed amount accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}
