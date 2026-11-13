package io.github.centerionware.billingtest

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.invoice.InvoiceDocuments
import io.github.centerionware.clockrove.invoice.InvoicePdf
import io.github.centerionware.clockrove.tracking.BoardStore
import io.github.centerionware.clockrove.tracking.InvoiceLifecycleStore
import io.github.centerionware.clockrove.tracking.InvoicePaymentStore
import io.github.centerionware.clockrove.tracking.InvoiceStore
import io.github.centerionware.clockrove.tracking.ReportStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native ledger -> retained copy -> Android PdfDocument/PdfRenderer, no mock PDFs. */
@RunWith(AndroidJUnit4::class)
class InvoiceCopiesPdfTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = TimerRepository.get(app)
    private val createdJobs = mutableListOf<String>()
    private val version get() = repository.snapshot().version
    private fun render(invoiceId: String, copyId: String?, name: String, expected: List<String>): InvoiceStore.Record {
        val record = InvoiceDocuments.ensure(repository, invoiceId, copyId)
        val snapshot = InvoiceStore.Snapshot.decode(record.payload)
        val directory = File(app.getExternalFilesDir(null), "invoice-previews").apply { mkdirs() }
        val bytes = requireNotNull(record.pdf)
        File(directory, "$name.pdf").writeBytes(bytes)
        val file = File.createTempFile("copy-test-", ".pdf", app.cacheDir)
        val text = StringBuilder()
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { pdf ->
                assertTrue(pdf.pageCount > 0)
                if (!name.contains("wide")) assertEquals("Short financial copy must fit one page: $name", 1, pdf.pageCount)
                for (index in 0 until pdf.pageCount) pdf.openPage(index).use { page ->
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        val compact = CopyPdfTextApi35.read(page).filterNot { it.isWhitespace() }
                        val state = requireNotNull(snapshot.statement)
                        val heading = when (state.kind) { "RECEIPT" -> "RECEIPT"; "CORRECTION" -> "PAYMENTUPDATE"; else -> "INVOICE" }
                        assertTrue("Missing document heading on $name page ${index + 1}", compact.contains(heading))
                        assertTrue("Missing payment status on $name page ${index + 1}", compact.contains(state.status.replace("_", "")))
                        text.append(compact.replace("Page${index + 1}·${snapshot.number}", "")
                            .replace("RECEIPT", "").replace("PAYMENTUPDATE", "").replace("INVOICE", "").replace("Invoice", "")
                            .replace(snapshot.number, "").replace(requireNotNull(snapshot.statement).status.replace("_", ""), ""))
                    }
                    if (index == 0 || index == pdf.pageCount - 1) {
                        val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val suffix = if (index == 0) "" else "-page-${index + 1}"
                            File(directory, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { bitmap.recycle() }
                    }
                }
            } }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                File(directory, "$name-extracted.txt").writeText(text.toString())
                for ((value, needed) in expected.groupingBy { it }.eachCount()) {
                    var offset = 0; var count = 0
                    while (offset < text.length) {
                        val found = text.indexOf(value, offset)
                        if (found < 0) break
                        count++; offset = found + value.length
                    }
                    assertTrue("Actual $name is missing exact $value (required=$needed found=$count)", count >= needed)
                }
            }
        } finally { file.delete() }
        return record
    }
    private fun invoice(style: String, token: String, price: BigInteger, quantity: Long = 10000): String {
        val settings = repository.businessSnapshot().settings
        assertNull(repository.saveBusinessSettings("copy-pdf-settings-$token", version, settings.copy(currency = "USD", invoiceStyle = style,
            companyName = "Riverside Studio", companyEmail = "company@example.test", receivablesEmail = "accounts@example.test",
            defaultTaxUnits = if (quantity == 10000L) 75000 else 0)).conflict)
        val job = repository.createJob("Synthetic receipt $style")
        createdJobs.add(job)
        val preference = repository.jobDetail(job).job.settings.copy(billingMode = "TASK", customerEmailOverride = "customer@example.test")
        assertNull(repository.saveJobSettings("copy-pdf-job-$token", version, preference).conflict)
        val column = repository.boardSnapshot(job).board.columns.first { it.completed }.id
        assertNull(repository.saveCard("copy-pdf-card-$token", version, BoardStore.Draft("copy-pdf-card-$token", job, column,
            "Install synthetic tile", priceMinor = price, currency = "USD", quantityUnits = quantity)).conflict)
        val now = System.currentTimeMillis()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val selection = ReportStore.Selection(format.format(Date(now - 86400000)), format.format(Date(now + 86400000)), "UTC", listOf(job))
        val id = "copy-pdf-invoice-$token"
        assertNull(repository.prepareInvoice("copy-pdf-prepare-$token", version, id, selection).conflict)
        return id
    }
    private fun pay(invoiceId: String, token: String, amount: BigInteger) {
        assertNull(repository.recordInvoicePayment("copy-pdf-pay-$token", version, InvoicePaymentStore.Draft(token, invoiceId, amount.toString(), "USD",
            System.currentTimeMillis() - 1000, "UTC", "Bank transfer", "Synthetic payment reference", "PRIVATE INTERNAL NOTE")).conflict)
    }

    @Test fun settingsStylePreviewRendersSamplePdfWithoutChangingBusinessState() {
        val directory = File(app.getExternalFilesDir(null), "invoice-previews").apply { mkdirs() }
        val before = repository.snapshot().version
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val pdf = InvoiceDocuments.settingsPreview(style, "USD", 2)
            val firstPage = InvoiceDocuments.preview(app, pdf, 0)
            assertEquals(0, firstPage["page"])
            assertEquals(1, firstPage["pages"])
            val png = requireNotNull(firstPage["png"] as? ByteArray)
            assertTrue(png.isNotEmpty())
            File(directory, "clockrove-settings-preview-${style.lowercase(Locale.ROOT)}.png").writeBytes(png)
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                val file = File.createTempFile("settings-sample-", ".pdf", app.cacheDir)
                try {
                    file.writeBytes(pdf)
                    val extracted = StringBuilder()
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            renderer.openPage(0).use { page -> extracted.append(CopyPdfTextApi35.read(page)) }
                        }
                    }
                    val extractedText = extracted.toString()
                    val sample = extractedText.replace(Regex("[\\s\\p{Z}\\p{Cf}]+"), "")
                    val expectedSampleValues = listOf(
                        "INV-SAMPLE-1000", "SampleCompanyLLC", "TaylorMorgan",
                        "billing@example.test", "taylor.morgan@example.test",
                        "Kitchen tile installation", "Install porcelain tile", "312.00",
                        "7.5%", "23.40", "335.40", "Sample preview only",
                    )
                    for (value in expectedSampleValues) {
                        assertTrue(
                            "$style sample invoice is missing $value; extracted text: ${extractedText.take(3000)}",
                            sample.contains(value),
                        )
                    }
                } finally { file.delete() }
            }
        }
        assertEquals("Preview generation must not mutate jobs, invoices or numbering", before, repository.snapshot().version)
    }

    @Test fun invoicePartiesUseWrappedColumnsAndSubtotalTaxNumbersEndAtTheRightEdge() {
        val directory = File(app.getExternalFilesDir(null), "invoice-previews").apply { mkdirs() }
        val columnWidth = ((595f - 44f * 2) * .47f)
        val rightColumnX = 595f - 44f - columnWidth
        for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
            val customerContact = "CUSTOMER_ADDRESS_SENTINEL " + "Customer address line with synthetic detail. ".repeat(80)
            val companyContact = "COMPANY_ADDRESS_SENTINEL " + "Company address line with synthetic detail. ".repeat(80)
            val snapshot = InvoiceStore.Snapshot(
                number = "INV-LAYOUT-$style", created = System.currentTimeMillis(), style = style,
                currency = "USD", digits = 2, company = "RIGHT COMPANY SENTINEL", companyContact = companyContact,
                customer = "LEFT CUSTOMER SENTINEL", customerContact = customerContact,
                job = "Layout fixture", period = "2026-11-13 — 2026-11-13 · UTC", basis = "Synthetic layout fixture",
                header = "", footer = "", total = "119.99", email = "", subject = "", body = "", lines = emptyList(),
                subtotal = "111.11", taxRateUnits = 80000, taxAmount = "8.88",
            )
            val bytes = InvoicePdf.generate(snapshot)
            val file = File.createTempFile("party-layout-", ".pdf", app.cacheDir)
            try {
                file.writeBytes(bytes)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { pdf ->
                        assertTrue("Long party details should wrap and paginate: $style", pdf.pageCount > 1)
                        val textAndBounds = mutableListOf<Pair<String, List<android.graphics.RectF>>>()
                        for (index in 0 until pdf.pageCount) pdf.openPage(index).use { page ->
                            if (index == 0) {
                                val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                                try {
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    File(directory, "clockrove-party-layout-${style.lowercase(Locale.ROOT)}.png").outputStream().use {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                } finally { bitmap.recycle() }
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 35) {
                                textAndBounds += CopyPdfTextApi35.entries(page)
                            }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 35) {
                            val extracted = textAndBounds.joinToString(" ") { it.first }
                            assertTrue(extracted.contains("LEFT CUSTOMER SENTINEL"))
                            assertTrue(extracted.contains("RIGHT COMPANY SENTINEL"))
                            assertTrue(extracted.contains("CUSTOMER_ADDRESS_SENTINEL"))
                            assertTrue(extracted.contains("COMPANY_ADDRESS_SENTINEL"))
                            fun markerBounds(marker: String) = textAndBounds.filter { it.first.contains(marker) }.flatMap { it.second }
                            val leftBounds = markerBounds("LEFT")
                            val rightBounds = markerBounds("RIGHT")
                            val customerAddressBounds = markerBounds("CUSTOMER_ADDRESS_SENTINEL")
                            val companyAddressBounds = markerBounds("COMPANY_ADDRESS_SENTINEL")
                            assertTrue(
                                "Customer name must be in the left 47% column; bounds=$leftBounds, " +
                                    "expected x=43..${44f + columnWidth + 1f}",
                                leftBounds.isNotEmpty() && leftBounds.all {
                                    it.left >= 43f && it.right <= 44f + columnWidth + 1f
                                },
                            )
                            assertTrue(
                                "Company name must be in the right 47% column; bounds=$rightBounds, " +
                                    "expected x=${rightColumnX - 1f}..552",
                                rightBounds.isNotEmpty() && rightBounds.all {
                                    it.left >= rightColumnX - 1f && it.right <= 552f
                                },
                            )
                            assertTrue("Customer contact must wrap within the left 47% column", customerAddressBounds.isNotEmpty() && customerAddressBounds.all { it.left >= 43f && it.right <= 44f + columnWidth + 1f && it.width() <= columnWidth + 1f })
                            assertTrue("Company contact must wrap within the right 47% column", companyAddressBounds.isNotEmpty() && companyAddressBounds.all { it.left >= rightColumnX - 1f && it.right <= 552f && it.width() <= columnWidth + 1f })
                            val taxNumberBounds = markerBounds("8.88")
                            assertTrue("Tax amount should be present", taxNumberBounds.isNotEmpty())
                            assertTrue("Tax amount should align to the invoice right edge", kotlin.math.abs(taxNumberBounds.maxOf { it.right } - 551f) < 2f)
                        }
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun nativePaymentsProduceAllStylePartialPaidBalanceCorrectionAndWideCopies() {
        val before = repository.businessSnapshot().settings
        try {
            for (style in listOf("MINIMAL", "CLASSIC", "MODERN")) {
                val token = style.lowercase(Locale.ROOT)
                val id = invoice(style, token, BigInteger.valueOf(10000))
                val original = InvoiceDocuments.ensure(repository, id)
                if (style == "MINIMAL") assertEquals("INV-1000", original.number)
                pay(id, "$token-first", BigInteger.valueOf(3000))
                val partial = render(id, "receipt-$token-first", "clockrove-receipt-partial-$token",
                    listOf("PaidtodateUSD30.00", "BALANCEDUEUSD77.50", "USD107.50", "Syntheticpaymentreference"))
                assertEquals("PARTIALLY_PAID", InvoiceStore.Snapshot.decode(partial.payload).statement!!.status)
                assertFalse(partial.payload.contains("PRIVATE INTERNAL NOTE"))
                val email = InvoiceDocuments.email(app, partial, repository.businessSnapshot().settings)
                assertArrayEquals(arrayOf("customer@example.test"), email.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL))
                assertArrayEquals(arrayOf("accounts@example.test"), email.getStringArrayExtra(android.content.Intent.EXTRA_CC))
                val uri = InvoiceDocuments.uri(app, partial)
                assertEquals(uri, email.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
                app.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(partial.pdf, it.readBytes()) }
                pay(id, "$token-final", BigInteger.valueOf(7750))
                val paid = render(id, "receipt-$token-final", "clockrove-receipt-paid-$token",
                    listOf("PaidtodateUSD107.50", "BALANCEDUEUSD0.00", "USD107.50"))
                assertEquals("PAID", InvoiceStore.Snapshot.decode(paid.payload).statement!!.status)
                assertNull(repository.prepareInvoiceBalanceCopy("copy-pdf-balance-$token", version, "settled-$token", id).conflict)
                render(id, "balance-settled-$token", "clockrove-balance-paid-$token",
                    listOf("PaidtodateUSD107.50", "BALANCEDUEUSD0.00", "Paymentreceived", "Paymentreceived"))
                assertNull(repository.reverseInvoicePayment("copy-pdf-reverse-$token", version,
                    InvoicePaymentStore.Reversal("$token-reversal", id, "$token-first", System.currentTimeMillis(), "UTC", "PRIVATE REVERSAL REASON")).conflict)
                render(id, "correction-$token-reversal", "clockrove-payment-correction-$token",
                    listOf("PaidtodateUSD77.50", "BALANCEDUEUSD30.00", "Paymentreversed", "-30.00"))
                assertEquals(partial.payload, repository.invoiceCopy("receipt-$token-first").payload)
                assertArrayEquals(paid.pdf, repository.invoiceCopy("receipt-$token-final").pdf)
                assertEquals(original.payload, repository.invoice(id).payload); assertArrayEquals(original.pdf, repository.invoice(id).pdf)
                val replacementId = "replacement-$token"
                val source = InvoiceStore.Snapshot.decode(original.payload)
                val edits = source.lines.mapIndexed { index, line -> InvoiceLifecycleStore.LineEdit(index, line.title, line.detail,
                    "12000", line.quantityUnits, "12000") }
                assertNull(repository.replaceInvoice("copy-pdf-replace-$token", version, InvoiceLifecycleStore.ReplacementDraft(id, replacementId, edits,
                    100000, "PRIVATE REPLACEMENT REASON", "UTC", true)).conflict)
                val historicalNotice = requireNotNull(InvoiceDocuments.historicalNotice(repository, original))
                val historicalEmail = InvoiceDocuments.email(app, original, repository.businessSnapshot().settings, historicalNotice)
                assertTrue(historicalEmail.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.startsWith(historicalNotice))
                assertTrue(historicalNotice.contains("Do not pay this invoice"))
                assertFalse(historicalNotice.contains("PRIVATE REPLACEMENT REASON"))
                app.contentResolver.openInputStream(InvoiceDocuments.uri(app, original))!!.use { assertArrayEquals(original.pdf, it.readBytes()) }
                val voided = render(id, "void-$id", "clockrove-void-$token",
                    listOf("CredittransferredUSD77.50", "BALANCEDUEUSD0.00", "Cancelledunpaidbalance", "30.00"))
                assertEquals("VOID", InvoiceStore.Snapshot.decode(voided.payload).statement!!.status)
                assertFalse(voided.payload.contains("PRIVATE REPLACEMENT REASON"))
                val replacement = render(replacementId, null, "clockrove-replacement-$token",
                    listOf("CreditatissueUSD77.50", "BALANCEDUEUSD54.50", "USD132.00", "120.00USDeach"))
                assertEquals("PARTIALLY_PAID", InvoiceStore.Snapshot.decode(replacement.payload).statement!!.status)
                val replacementEmail = InvoiceDocuments.email(app, replacement, repository.businessSnapshot().settings)
                assertArrayEquals(arrayOf("customer@example.test"), replacementEmail.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL))
                assertArrayEquals(arrayOf("accounts@example.test"), replacementEmail.getStringArrayExtra(android.content.Intent.EXTRA_CC))
                assertTrue(replacementEmail.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains("Balance due at issue: 54.50 USD"))
                for (copy in listOf(voided, replacement)) app.contentResolver.openInputStream(InvoiceDocuments.uri(app, copy))!!.use { assertArrayEquals(copy.pdf, it.readBytes()) }
                pay(replacementId, "$token-replacement-final", BigInteger.valueOf(5450))
                render(replacementId, "receipt-$token-replacement-final", "clockrove-replacement-paid-$token",
                    listOf("PaidtodateUSD132.00", "BALANCEDUEUSD0.00", "54.50"))
                assertEquals(BigInteger.valueOf(13200), repository.invoicePayments(replacementId).second.balance.paid)
                assertEquals(BigInteger.valueOf(1200), repository.invoicePayments(replacementId).second.collectedTax)
                assertEquals(original.payload, repository.invoice(id).payload); assertArrayEquals(original.pdf, repository.invoice(id).pdf)
                assertArrayEquals(paid.pdf, repository.invoiceCopy("receipt-$token-final").pdf)
                assertEquals(replacement.payload, repository.invoice(replacementId).payload)
                val wide = BigInteger("1234567890".repeat(100))
                val wideId = invoice(style, "wide-$token", wide, 260000)
                val paidMinor = wide.multiply(BigInteger.valueOf(13))
                pay(wideId, "wide-$token", paidMinor)
                val paidText = InvoiceStore.amount(paidMinor, 2)
                val totalText = InvoiceStore.amount(wide.multiply(BigInteger.valueOf(26)), 2)
                render(wideId, "receipt-wide-$token", "clockrove-receipt-wide-$token",
                    listOf(paidText, paidText, paidText, totalText, totalText))
            }
        } finally {
            for (job in createdJobs) assertNull(repository.archiveJob("copy-pdf-archive-$job", version, job).conflict)
            assertNull(repository.saveBusinessSettings("copy-pdf-restore-settings", version, before).conflict)
        }
    }
}

/** Isolate API-35-only reflection from Android 7. */
@android.annotation.TargetApi(35)
internal object CopyPdfTextApi35 {
    fun read(page: PdfRenderer.Page): String = page.textContents.joinToString(" ") { it.text }
    fun entries(page: PdfRenderer.Page): List<Pair<String, List<android.graphics.RectF>>> = page.textContents.map { content ->
        content.text to content.bounds.map { android.graphics.RectF(it) }
    }
}
