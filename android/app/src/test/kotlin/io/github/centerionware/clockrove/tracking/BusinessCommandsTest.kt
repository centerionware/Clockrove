package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
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
class BusinessCommandsTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var sequence = 0
    private val version get() = repository.snapshot().version
    private val settings get() = repository.businessSnapshot().settings
    private val detail get() = repository.jobDetail(job).job
    private fun save(value: BusinessStore.Settings) = repository.saveBusinessSettings("settings-${sequence++}", version, value)
    private fun customer(value: BusinessStore.Customer) = repository.saveCustomer("customer-${sequence++}", version, value)
    private fun job(value: BusinessStore.JobSettings) = repository.saveJobSettings("job-${sequence++}", version, value)
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 10000 }); job = repository.createJob("Synthetic job")
    }
    @After fun close() { repository.close() }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)

    @Test fun globalRateAndJobOverrideRemainExactAndModesDoNotChangeTimeOrTaskPrices() {
        assertNull(detail.effectiveHourlyMinor)
        assertNull(save(settings.copy(defaultHourlyMinor = 9007199254740993L)).conflict)
        assertEquals(9007199254740993L, detail.effectiveHourlyMinor)
        assertNull(job(detail.settings.copy(hourlyRateMinor = 0, billingMode = "TASK")).conflict)
        assertEquals(0L, detail.effectiveHourlyMinor); assertEquals("TASK", detail.settings.billingMode)
        assertNull(job(detail.settings.copy(hourlyRateMinor = null, billingMode = "HOURLY")).conflict)
        assertEquals(9007199254740993L, detail.effectiveHourlyMinor)
        assertNull(repository.snapshot().running)
    }
    @Test fun changingCurrencyNeverRelabelsOldJobsOrUsesAnIncompatibleDefaultRate() {
        val old = detail.settings.currency
        val other = if (old == "JPY") "USD" else "JPY"
        assertNull(save(settings.copy(currency = other, defaultHourlyMinor = 123)).conflict)
        assertEquals(old, detail.settings.currency); assertNull(detail.effectiveHourlyMinor)
        assertEquals("CURRENCY_IMMUTABLE", job(detail.settings.copy(currency = other)).conflict)
        val fresh = repository.createJob("Synthetic new currency")
        assertEquals(other, repository.jobDetail(fresh).job.settings.currency)
        assertEquals(123L, repository.jobDetail(fresh).job.effectiveHourlyMinor)
    }
    @Test fun settingsPersistCompanyTemplatesAndThreeStylesWithoutChangingInvoiceCounter() {
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val value = settings.copy(companyName = "Synthetic Co", companyEmail = "billing@example.test", companyPhone = "123",
                companyAddress = "Synthetic street\nSynthetic city", companyWebsite = "https://example.test", invoiceHeader = "Synthetic header",
                invoiceFooter = "Synthetic footer", invoiceStyle = style, emailSubject = "{company_name}: {invoice_number}", emailBody = "Hello {customer_name}\n{amount} {currency}")
            assertNull(save(value).conflict); assertEquals(value, settings)
        }
        repository.close(); repository = TimerRepository(RuntimeEnvironment.getApplication())
        assertEquals("MODERN", settings.invoiceStyle); assertEquals("Synthetic Co", settings.companyName)
        inspect { db -> db.rawQuery("SELECT next_invoice_number,logo_id FROM app_settings", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)); assertTrue(it.isNull(1)) } }
    }
    @Test fun malformedSettingsDoNotPartiallyWriteOrChangeVersion() {
        val before = repository.businessSnapshot()
        listOf(settings.copy(defaultHourlyMinor = -1), settings.copy(currency = "XXX"), settings.copy(invoiceStyle = "OTHER"),
            settings.copy(emailSubject = "Hello\r\nBcc:other@example.test"), settings.copy(emailBody = "{execute}"),
            settings.copy(companyEmail = "a@example.test\nBcc:other@example.test")).forEach { assertEquals("INVALID_SETTINGS", save(it).conflict) }
        assertEquals(before, repository.businessSnapshot())
    }
    @Test fun jobEmailDefaultsToCustomerAndExplicitOverrideCanBeClearedOrBlank() {
        customer(BusinessStore.Customer("c", "Synthetic customer", "one@example.test"))
        assertNull(job(detail.settings.copy(customerId = "c")).conflict)
        assertEquals("one@example.test", detail.effectiveEmail)
        assertNull(job(detail.settings.copy(customerEmailOverride = "job@example.test")).conflict)
        customer(BusinessStore.Customer("c", "Synthetic revised", "two@example.test"))
        assertEquals("job@example.test", detail.effectiveEmail)
        assertNull(job(detail.settings.copy(customerEmailOverride = "")).conflict); assertEquals("", detail.effectiveEmail)
        assertNull(job(detail.settings.copy(customerEmailOverride = null)).conflict); assertEquals("two@example.test", detail.effectiveEmail)
    }
    @Test fun assignedCustomersCanBeArchivedButNotDeletedOrNewlyAssigned() {
        customer(BusinessStore.Customer("c", "Synthetic customer")); job(detail.settings.copy(customerId = "c"))
        assertEquals("CUSTOMER_IN_USE", repository.removeCustomer("remove", version, "c").conflict)
        assertNull(customer(BusinessStore.Customer("c", "Synthetic customer", archived = true)).conflict)
        assertNull(job(detail.settings.copy(name = "Still assigned")).conflict)
        val other = repository.createJob("Synthetic other")
        assertEquals("CUSTOMER_ARCHIVED", repository.saveJobSettings("assign", version, repository.jobDetail(other).job.settings.copy(customerId = "c")).conflict)
        assertEquals("c", detail.customer?.id)
    }
    @Test fun deletingUnusedCustomerLeavesOnlyAnOpaqueRevisionTombstone() {
        customer(BusinessStore.Customer("c", "Synthetic private", notes = "Synthetic note"))
        assertNull(repository.removeCustomer("remove", version, "c").conflict)
        assertTrue(repository.businessSnapshot().customers.isEmpty())
        inspect { db -> db.rawQuery("SELECT payload,deleted FROM entity_revisions WHERE kind='customer' AND entity_id='c' ORDER BY deleted", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.moveToNext()); assertEquals(1, it.getInt(1)); assertFalse(it.getString(0).contains("Synthetic"))
        } }
    }
    @Test fun staleAndDuplicateSettingsCommandsPreserveOriginalOutcomeAndRejectReuse() {
        val firstVersion = version; val value = settings.copy(defaultHourlyMinor = 2200)
        val result = repository.saveBusinessSettings("same", firstVersion, value)
        assertNull(result.conflict)
        assertEquals(result, repository.saveBusinessSettings("same", firstVersion, value))
        assertEquals("COMMAND_ID_REUSED", repository.saveBusinessSettings("same", firstVersion, value.copy(defaultHourlyMinor = 2300)).conflict)
        assertEquals("STALE_STATE", repository.saveBusinessSettings("stale", firstVersion, value.copy(defaultHourlyMinor = 2400)).conflict)
        assertEquals(2200L, settings.defaultHourlyMinor)
    }
    @Test fun invalidJobOrCustomerReferencesNeverPartiallyRenameAJob() {
        val before = detail
        assertEquals("CUSTOMER_UNAVAILABLE", job(detail.settings.copy(name = "Must not persist", customerId = "missing")).conflict)
        assertEquals("INVALID_JOB", job(detail.settings.copy(billingMode = "BOTH")).conflict)
        assertEquals("INVALID_JOB", job(detail.settings.copy(hourlyRateMinor = -1)).conflict)
        assertEquals("INVALID_JOB", job(detail.settings.copy(customerEmailOverride = "bad\naddress@example.test")).conflict)
        assertEquals(before, detail)
    }
    @Test fun failedSettingsRevisionRollsBackDataVersionAndCommandReceipt() {
        val before = repository.businessSnapshot()
        inspect { it.execSQL("CREATE TRIGGER fail_revision BEFORE INSERT ON entity_revisions WHEN NEW.kind='settings' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        try { repository.saveBusinessSettings("failed", version, settings.copy(companyName = "Must roll back")); fail("Unexpected success") }
        catch (_: android.database.SQLException) {}
        assertEquals(before, repository.businessSnapshot())
        inspect { db -> db.rawQuery("SELECT id FROM commands WHERE id='failed'", null).use { assertFalse(it.moveToFirst()) } }
    }
    @Test fun emailTemplatesUseLiteralReplacementAndKeepBodyNewlinesWithoutHeaderInjection() {
        val values = mapOf("company_name" to "Synthetic\r\nBcc: other@example.test", "job_name" to "\$1 {amount}", "amount" to "12.00")
        assertEquals("Synthetic  Bcc: other@example.test — \$1 {amount}", InvoiceEmailTemplate.expand("{company_name} — {job_name}", values, true))
        assertEquals("Hello\n\$1 {amount}", InvoiceEmailTemplate.expand("Hello\n{job_name}", values, false))
        assertFalse(InvoiceEmailTemplate.valid("{unknown}", false)); assertFalse(InvoiceEmailTemplate.valid("Header\n", true))
    }
}
