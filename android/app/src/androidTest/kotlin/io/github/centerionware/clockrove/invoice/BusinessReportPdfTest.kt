package io.github.centerionware.clockrove.invoice

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.tracking.BusinessReportStore
import java.io.File
import java.math.BigInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BusinessReportPdfTest {
    @Test fun financialReportPdfRendersSummaryAndSupportingRows() {
        val zero = BigInteger.ZERO
        val report = BusinessReportStore.Report(
            BusinessReportStore.Selection("2026-06-15", "2026-06-15", "UTC"),
            1_781_539_200_000L,
            listOf(BusinessReportStore.Summary(
                currency = "USD", digits = 2, invoicedSales = BigInteger("15000"),
                cashReceived = BigInteger("5000"), taxBilled = BigInteger("1125"),
                taxCollected = BigInteger("349"), outstanding = BigInteger("11125"),
                labor = BigInteger("5000"), supplies = BigInteger("10000"), unclassified = zero,
                directCosts = BigInteger("2000"), overhead = BigInteger("1000"),
                grossProfit = BigInteger("13000"), netProfit = BigInteger("12000"),
                grossMarginPercent = "86.6667", netMarginPercent = "80", cashSurplus = BigInteger("2000"),
                costCount = 1, unattributedCostCount = 0, legacyInvoiceCount = 0, unknownTaxInvoiceCount = 0,
            )),
            invoices = listOf(BusinessReportStore.InvoiceMovement(
                id = "issue-invoice", invoiceId = "invoice", number = "INV-1000", kind = "ISSUED",
                occurred = 1_781_510_400_000L, jobId = "job", job = "Synthetic job",
                customerId = "customer", customer = "Synthetic customer", currency = "USD", digits = 2,
                salesMinor = BigInteger("15000"), taxMinor = BigInteger("1125"), legacyTotalMinor = null,
                laborMinor = BigInteger("5000"), suppliesMinor = BigInteger("10000"), unclassifiedMinor = zero,
                relatedInvoiceId = null, reason = "Invoice issued",
            )),
            cash = listOf(BusinessReportStore.CashMovement(
                id = "payment-event", invoiceId = "invoice", paymentId = "payment", number = "INV-1000",
                kind = "PAYMENT", occurred = 1_781_510_400_000L, jobId = "job", job = "Synthetic job",
                customerId = "customer", customer = "Synthetic customer", currency = "USD", digits = 2,
                cashMinor = BigInteger("5000"), creditMinor = zero, salesMinor = BigInteger("4651"),
                taxMinor = BigInteger("349"), reference = "Synthetic payment",
            )),
            costs = listOf(BusinessReportStore.CostMovement(
                id = "cost", jobId = "job", job = "Synthetic job", customerId = "customer",
                customer = "Synthetic customer", taskId = "task", task = "Materials", occurred = 1_781_510_400_000L,
                timezone = "UTC", offsetSeconds = 0, currency = "USD", digits = 2,
                category = "MATERIALS", amountMinor = BigInteger("2000"), vendor = "Synthetic supplier",
                reference = "Receipt-1", note = "Synthetic materials",
            )),
            excludedUnknownCustomerCostCount = 0,
        )
        val bytes = BusinessReportPdf.generate(report)
        assertTrue(bytes.size > 1000)
        val file = File.createTempFile("clockrove-business-report-", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertTrue(pdf.pageCount >= 1)
                    pdf.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue(bitmap.getPixel(45, 45) != android.graphics.Color.TRANSPARENT)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
