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
        repository.saveBusinessSettings("settings", version, settings.copy(defaultHourlyMinor = 10000))
        repository.execute(TimerRepository.Command("start", version, TimerRepository.Action.START, job, 1793491200000, "UTC", 0, TimerRepository.Origin.MANUAL))
        repository.execute(TimerRepository.Command("stop", version, TimerRepository.Action.STOP, null, 1793494800000, "UTC", 0, TimerRepository.Origin.MANUAL))
        assertNull(repository.prepareInvoice("prepare", version, "invoice", ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job))).conflict)
        val record = repository.invoice("invoice"); assertTrue(repository.cacheInvoicePdf("invoice", record.payload, synthetic))
        provider = InvoiceProvider(repository)
        provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.invoices"; exported = false; grantUriPermissions = true })
    }
    @After fun close() { repository.close(); provider.shutdown() }
    @Test fun emailIntentRoutesToEmailAppsAndContainsActualRecipientBodyAndPdfReadGrant() {
        val record = repository.invoice("invoice"); val s = InvoiceStore.Snapshot.decode(record.payload).copy(email = "synthetic@example.test", subject = "Synthetic invoice", body = "Hello\nActual invoice attached.")
        val intent = InvoiceDocuments.email(context, record.copy(payload = s.encode()))
        val uri = InvoiceDocuments.uri(context, record.id)
        assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("application/pdf", intent.type)
        assertEquals(Intent.ACTION_SENDTO, intent.selector?.action); assertEquals("mailto", intent.selector?.data?.scheme)
        assertArrayEquals(arrayOf("synthetic@example.test"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Synthetic invoice", intent.getStringExtra(Intent.EXTRA_SUBJECT)); assertEquals("Hello\nActual invoice attached.", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)); assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    @Test fun recipientAndSubjectInjectionAreRejectedBeforeOpeningAnyApplication() {
        val record = repository.invoice("invoice"); val s = InvoiceStore.Snapshot.decode(record.payload)
        for (bad in listOf(s.copy(email = "a@example.test\r\nBcc:b@example.test"), s.copy(subject = "Invoice\nBcc:other@example.test"))) {
            try { InvoiceDocuments.email(context, record.copy(payload = bad.encode())); fail("Injection accepted") } catch (_: IllegalArgumentException) {}
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
