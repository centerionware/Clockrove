package io.github.centerionware.clockrove.tracking

import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.centerionware.clockrove.invoice.InvoiceDocuments
import io.github.centerionware.clockrove.invoice.InvoiceProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic bytes verify cache identity and grants; actual Android tests render real PDFs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class QuoteDocumentTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var provider: InvoiceProvider
    private val version get() = repository.snapshot().version
    private val bytes = "%PDF-synthetic-quote-cache".toByteArray()
    private fun draft() = QuoteStore.Draft("quote", "customer", null, "Synthetic estimate", "USD", 75000,
        listOf(QuoteStore.Line("tile", "Install tile", "Synthetic detail", "ITEM", "260000", "1250")), "2026-12-01", "Synthetic terms", "Synthetic note")
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); repository = TimerRepository(app, wallClock = { 1793494800000L })
        assertNull(repository.saveCustomer("customer", version, BusinessStore.Customer("customer", "Synthetic customer", "customer@example.test")).conflict)
        assertNull(repository.saveBusinessSettings("company", version, repository.businessSnapshot().settings.copy(companyName = "Original company", companyEmail = "company@example.test")).conflict)
        assertNull(repository.saveQuote("save", version, draft()).conflict)
        assertNull(repository.issueQuote("issue", version, "quote", true).conflict)
        provider = InvoiceProvider(repository)
        provider.attachInfo(app, ProviderInfo().apply { authority = "${app.packageName}.invoices"; exported = false; grantUriPermissions = true })
    }
    @After fun close() { repository.close(); provider.shutdown() }
    @Test fun frozenQuoteAdaptationContainsExactPricesTaxRevisionAndNoPaymentStatement() {
        val record = repository.quoteDocument("quote", 1); val snapshot = InvoiceStore.Snapshot.decode(record.payload)
        assertEquals("QUOTE", record.documentKind); assertEquals(1, record.quoteRevision)
        assertEquals("Q-1000 · Revision 1", snapshot.number); assertEquals("349.38", snapshot.total)
        assertEquals("325.00", snapshot.subtotal); assertEquals("24.38", snapshot.taxAmount); assertNull(snapshot.statement)
        assertEquals("325.00", snapshot.lines.single().amount); assertTrue(snapshot.lines.single().detail.contains("26 units × 12.50 USD each"))
        assertTrue(snapshot.period.contains("2026-12-01")); assertTrue(snapshot.footer.contains("Synthetic terms"))
        assertTrue(snapshot.subject.startsWith("Quote Q-1000")); assertTrue(snapshot.body.contains("Estimated total: 349.38 USD"))
        assertNull(repository.saveBusinessSettings("change", version, repository.businessSnapshot().settings.copy(companyName = "Changed company")).conflict)
        assertEquals(record.payload, repository.quoteDocument("quote", 1).payload)
    }
    @Test fun firstCacheWinsAndProviderServesExactRevisionWithReadOnlyPrivateGrant() {
        val record = repository.quoteDocument("quote", 1)
        val before = version
        assertFalse(repository.cacheQuotePdf("quote", 1, "wrong payload", bytes))
        assertTrue(repository.cacheQuotePdf("quote", 1, record.payload, bytes))
        assertFalse(repository.cacheQuotePdf("quote", 1, record.payload, "%PDF-other".toByteArray()))
        assertEquals(before, version)
        val cached = repository.quoteDocument("quote", 1); assertArrayEquals(bytes, cached.pdf)
        val uri = InvoiceDocuments.uri(app, cached); assertEquals(listOf("quotes", "quote", "1"), uri.pathSegments)
        assertEquals("Q-1000-revision-1.pdf", InvoiceDocuments.filename(cached))
        val email = InvoiceDocuments.email(app, cached, repository.businessSnapshot().settings.copy(receivablesEmail = "accounts@example.test"))
        assertArrayEquals(arrayOf("customer@example.test"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertArrayEquals(arrayOf("accounts@example.test"), email.getStringArrayExtra(Intent.EXTRA_CC))
        assertEquals(uri, email.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)); assertEquals(uri, email.clipData!!.getItemAt(0).uri)
        assertTrue(email.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertEquals(0, email.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { assertArrayEquals(bytes, it.readBytes()) }
        try { provider.openFile(uri, "w"); fail("Read only") } catch (_: java.io.FileNotFoundException) { }
        assertNull(repository.saveQuote("revise", version, draft().copy(title = "New revision")).conflict)
        assertNull(repository.issueQuote("reissue", version, "quote", true).conflict)
        assertNull(repository.quoteDocument("quote", 2).pdf)
        assertArrayEquals(bytes, repository.quoteDocument("quote", 1).pdf)
        assertTrue(InvoiceDocuments.historicalNotice(repository, cached)!!.contains("current issued revision is 2"))
    }
    @Test fun revisedQuoteRetainsCustomTemplatesWithAnExplicitEstimatePrefix() {
        assertNull(repository.saveBusinessSettings("templates", version, repository.businessSnapshot().settings.copy(
            emailSubject = "For {customer_name}: {invoice_number}", emailBody = "Scope for {job_name}: {amount} {currency} — {company_name}")).conflict)
        assertNull(repository.saveQuote("revise", version, draft()).conflict)
        assertNull(repository.issueQuote("reissue", version, "quote", true).conflict)
        val snapshot = InvoiceStore.Snapshot.decode(repository.quoteDocument("quote", 2).payload)
        assertEquals("Quote · For Synthetic customer: Q-1000 revision 2", snapshot.subject)
        assertEquals("QUOTE — estimate, not an invoice.\n\nScope for Synthetic estimate: 349.38 USD — Original company", snapshot.body)
        val original = InvoiceStore.Snapshot.decode(repository.quoteDocument("quote", 1).payload)
        assertTrue(original.subject.startsWith("Quote Q-1000 · Revision 1"))
    }
}
