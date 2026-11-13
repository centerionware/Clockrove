package io.github.centerionware.clockrove.invoice

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.ReportStore
import io.github.centerionware.clockrove.tracking.TimerRepository
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
class InvoiceHandoffTest {
    private lateinit var repository: TimerRepository
    private lateinit var provider: InvoiceProvider
    private lateinit var job: String
    private val context get() = RuntimeEnvironment.getApplication()
    private val version get() = repository.snapshot().version
    private val synthetic = "%PDF-synthetic-content-provider-fixture".toByteArray()
    @Before fun setup() {
        context.deleteDatabase("clockrove.db"); repository = TimerRepository(context)
        job = repository.createJob("Synthetic private job")
        val settings = repository.businessSnapshot().settings
        repository.saveBusinessSettings("settings", version, settings.copy(defaultHourlyMinor = java.math.BigInteger.valueOf(10000)))
        repository.execute(TimerRepository.Command("start", version, TimerRepository.Action.START, job, 1793491200000, "UTC", 0, TimerRepository.Origin.MANUAL))
        repository.execute(TimerRepository.Command("stop", version, TimerRepository.Action.STOP, null, 1793494800000, "UTC", 0, TimerRepository.Origin.MANUAL))
        assertNull(repository.prepareInvoice("prepare", version, "invoice", ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))).conflict)
        val record = repository.invoice("invoice"); assertTrue(repository.cacheInvoicePdf("invoice", record.payload, synthetic))
        provider = InvoiceProvider(repository)
        provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.invoices"; exported = false; grantUriPermissions = true })
    }
    @After fun close() { repository.close(); provider.shutdown() }
    @Test fun emailIntentUsesAttachmentCapableSendActionAndContainsActualRecipientBodyAndPdfReadGrant() {
        val record = repository.invoice("invoice"); val s = InvoiceStore.Snapshot.decode(record.payload).copy(email = "synthetic@example.test", subject = "Synthetic invoice", body = "Hello\nActual invoice attached.")
        val intent = InvoiceDocuments.email(context, record.copy(payload = s.encode()), repository.businessSnapshot().settings)
        val uri = InvoiceDocuments.uri(context, record.id)
        assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("application/pdf", intent.type)
        assertNull(intent.selector)
        assertArrayEquals(arrayOf("synthetic@example.test"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Synthetic invoice", intent.getStringExtra(Intent.EXTRA_SUBJECT)); assertEquals("Hello\nActual invoice attached.", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)); assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    @Test fun accountingCopyUsesCurrentReceivablesOrCompanyFallbackAndDeduplicatesCustomer() {
        val record = repository.invoice("invoice")
        val company = repository.businessSnapshot().settings.copy(companyEmail = "company@example.test")
        val s = InvoiceStore.Snapshot.decode(record.payload).copy(email = "customer@example.test")
        val retained = record.copy(payload = s.encode())
        assertArrayEquals(arrayOf("company@example.test"), InvoiceDocuments.email(context, retained, company).getStringArrayExtra(Intent.EXTRA_CC))
        val receivables = company.copy(receivablesEmail = "accounts@example.test")
        assertArrayEquals(arrayOf("accounts@example.test"), InvoiceDocuments.email(context, retained, receivables).getStringArrayExtra(Intent.EXTRA_CC))
        assertArrayEquals(emptyArray<String>(), InvoiceDocuments.email(context, retained, company.copy(receivablesEmail = "CUSTOMER@example.test")).getStringArrayExtra(Intent.EXTRA_CC))
        assertArrayEquals(emptyArray<String>(), InvoiceDocuments.email(context, retained, company.copy(companyEmail = "")).getStringArrayExtra(Intent.EXTRA_CC))
        try {
            InvoiceDocuments.email(context, retained, company.copy(receivablesEmail = "accounts@example.test\r\nBcc:other@example.test"))
            fail("Accounting address injection accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(record.payload, repository.invoice("invoice").payload)
    }
    @Test fun oversizedExpandedEmailIsRejectedBeforeActivityBinderHandoff() {
        val record = repository.invoice("invoice")
        val snapshot = InvoiceStore.Snapshot.decode(record.payload).copy(body = "x".repeat(100001))
        try { InvoiceDocuments.email(context, record.copy(payload = snapshot.encode()), repository.businessSnapshot().settings); fail("Oversize email accepted") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(synthetic, repository.invoice("invoice").pdf)
    }
    @Test fun receiptUriGrantsOnlyItsFrozenBytesAndAccountingCopyUsesCurrentSettings() {
        val total = io.github.centerionware.clockrove.tracking.PaymentMath.parseMajor(InvoiceStore.Snapshot.decode(repository.invoice("invoice").payload).total, 2)
        val at = System.currentTimeMillis()
        assertNull(repository.recordInvoicePayment("pay", version, io.github.centerionware.clockrove.tracking.InvoicePaymentStore.Draft("payment", "invoice", total.divide(java.math.BigInteger.valueOf(2)).toString(), "USD", at - 1000, "UTC")).conflict)
        val copy = repository.invoiceCopy("receipt-payment")
        val bytes = "%PDF-synthetic-receipt-provider-fixture".toByteArray()
        assertTrue(repository.cacheInvoiceCopyPdf("receipt-payment", copy.payload, bytes))
        val ready = InvoiceDocuments.ensure(repository, "invoice", "receipt-payment")
        val uri = InvoiceDocuments.uri(context, ready)
        assertEquals(listOf("copies", "receipt-payment"), uri.pathSegments)
        ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { assertArrayEquals(bytes, it.readBytes()) }
        val company = repository.businessSnapshot().settings.copy(companyEmail = "company@example.test", receivablesEmail = "accounts@example.test")
        val intent = InvoiceDocuments.email(context, ready, company)
        assertArrayEquals(arrayOf("accounts@example.test"), intent.getStringArrayExtra(Intent.EXTRA_CC))
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)); assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("Payment receipt"))
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Original total:"))
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Balance due:"))
        assertTrue(InvoiceDocuments.filename(ready).startsWith("INV-1000-receipt-"))
        try { InvoiceDocuments.ensure(repository, "other-invoice", "receipt-payment"); fail("Wrong invoice copy accepted") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(synthetic, repository.invoice("invoice").pdf)
    }
    @Test fun recipientAndSubjectInjectionAreRejectedBeforeOpeningAnyApplication() {
        val record = repository.invoice("invoice"); val s = InvoiceStore.Snapshot.decode(record.payload)
        for (bad in listOf(s.copy(email = "a@example.test\r\nBcc:b@example.test"), s.copy(subject = "Invoice\nBcc:other@example.test"))) {
            try { InvoiceDocuments.email(context, record.copy(payload = bad.encode()), repository.businessSnapshot().settings); fail("Injection accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun providerReturnsPreparedBytesThroughASeekableReadOnlyDescriptorWithoutNamedCacheFiles() {
        val uri = InvoiceDocuments.uri(context, "invoice")
        assertEquals("application/pdf", provider.getType(uri))
        provider.query(uri, null, null, null, null).use { rows ->
            assertTrue(rows.moveToFirst()); assertEquals("INV-1000.pdf", rows.getString(rows.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(synthetic.size, rows.getInt(rows.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { assertArrayEquals(synthetic, it.readBytes()) }
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("invoice-read-") })
        try { provider.openFile(uri, "rw"); fail("Writable descriptor returned") } catch (_: java.io.FileNotFoundException) {}
    }
    @Test fun providerRejectsUnexpectedAuthoritiesPathsAndMissingInvoicesAndManifestLimitsExposure() {
        for (uri in listOf(Uri.parse("content://other/invoices/invoice"), Uri.parse("content://${context.packageName}.invoices/other/invoice"),
            Uri.parse("content://${context.packageName}.invoices/invoices/%2F..%2Fsecret"), InvoiceDocuments.uri(context, "missing"))) {
            try { provider.openFile(uri, "r"); fail("Invalid URI opened") } catch (_: java.io.FileNotFoundException) {}
        }
        val info = context.packageManager.getProviderInfo(ComponentName(context, InvoiceProvider::class.java), 0)
        assertFalse(info.exported); assertTrue(info.grantUriPermissions); assertEquals("${context.packageName}.invoices", info.authority)
    }
    @Test fun shareFallbackCarriesTheSameAttachmentWithoutClaimingItWasSent() {
        val intent = InvoiceDocuments.share(context, repository.invoice("invoice"))
        assertEquals(Intent.ACTION_SEND, intent.action); assertNull(intent.selector)
        assertEquals("content", intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.scheme)
        assertEquals(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM), intent.clipData!!.getItemAt(0).uri)
    }
}
