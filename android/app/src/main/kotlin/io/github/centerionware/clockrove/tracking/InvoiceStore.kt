package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Immutable prepared invoice data; rendering is derived outside the write lock. */
internal object InvoiceStore {
    data class Record(val id: String, val jobId: String, val number: String, val payload: String, val pdf: ByteArray?, val logo: ByteArray?, val copyId: String? = null, val documentKind: String = "INVOICE", val quoteRevision: Int? = null)
    data class Line(val title: String, val detail: String, val amount: String, val quantityUnits: String? = null,
        val unitPriceMinor: String? = null, val chargeType: String = TaskClassification.UNCLASSIFIED)
    data class Statement(val kind: String, val status: String, val created: Long, val paid: String, val due: String,
        val events: List<Line>, val reference: String = "") {
        fun encode(): JSONObject = JSONObject(mapOf("kind" to kind, "status" to status, "created" to created,
            "paid" to paid, "due" to due, "reference" to reference, "events" to JSONArray(events.map { JSONObject(mapOf("title" to it.title, "detail" to it.detail, "amount" to it.amount)) })))
        companion object {
            fun decode(value: JSONObject): Statement {
                val rows = value.getJSONArray("events")
                return Statement(value.getString("kind"), value.getString("status"), value.getLong("created"), value.getString("paid"), value.getString("due"),
                    (0 until rows.length()).map { val row = rows.getJSONObject(it); Line(row.getString("title"), row.getString("detail"), row.getString("amount")) }, value.optString("reference", ""))
            }
        }
    }
    data class Snapshot(val number: String, val created: Long, val style: String, val currency: String, val digits: Int,
        val company: String, val companyContact: String, val customer: String, val customerContact: String,
        val job: String, val period: String, val basis: String, val header: String, val footer: String,
        val total: String, val email: String, val subject: String, val body: String, val lines: List<Line>,
        val subtotal: String? = null, val taxRateUnits: Int? = null, val taxAmount: String? = null, val statement: Statement? = null,
        val billingPolicy: String? = null) {
        fun encode(): String = JSONObject(mapOf("number" to number, "created" to created, "style" to style, "currency" to currency, "digits" to digits,
            "company" to company, "companyContact" to companyContact, "customer" to customer, "customerContact" to customerContact,
            "job" to job, "period" to period, "basis" to basis, "header" to header, "footer" to footer, "total" to total, "email" to email,
            "subject" to subject, "body" to body, "lines" to JSONArray(lines.map { JSONObject(mapOf("title" to it.title, "detail" to it.detail, "amount" to it.amount, "quantityUnits" to it.quantityUnits, "unitPriceMinor" to it.unitPriceMinor, "chargeType" to it.chargeType)) }),
            "subtotal" to subtotal, "taxRateUnits" to taxRateUnits, "taxAmount" to taxAmount, "statement" to statement?.encode(),
            "billingPolicy" to billingPolicy)).toString()
        companion object {
            fun decode(value: String): Snapshot { val v = JSONObject(value); val rows = v.getJSONArray("lines")
                return Snapshot(v.getString("number"), v.getLong("created"), v.getString("style"), v.getString("currency"), v.getInt("digits"),
                    v.getString("company"), v.getString("companyContact"), v.getString("customer"), v.getString("customerContact"), v.getString("job"),
                    v.getString("period"), v.getString("basis"), v.getString("header"), v.getString("footer"), v.getString("total"), v.getString("email"),
                    v.getString("subject"), v.getString("body"), (0 until rows.length()).map { val row = rows.getJSONObject(it); Line(row.getString("title"), row.getString("detail"), row.getString("amount"), if (row.isNull("quantityUnits")) null else row.getString("quantityUnits"), if (row.isNull("unitPriceMinor")) null else row.getString("unitPriceMinor"), row.optString("chargeType", TaskClassification.UNCLASSIFIED)) },
                    if (v.isNull("subtotal")) null else v.getString("subtotal"),
                    if (v.isNull("taxRateUnits")) null else v.getInt("taxRateUnits"),
                    if (v.isNull("taxAmount")) null else v.getString("taxAmount"),
                    if (v.isNull("statement")) null else Statement.decode(v.getJSONObject("statement")),
                    if (v.isNull("billingPolicy")) null else v.getString("billingPolicy"))
            }
        }
    }
    fun amount(value: BigInteger, digits: Int): String = BigDecimal(value, digits).toPlainString()
    private fun instant(value: Long, zone: String): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(value))
    private fun duration(value: BigInteger): String = "${BigDecimal(value, 3).stripTrailingZeros().toPlainString()} seconds"
    fun billableLines(report: ReportStore.Report): List<Line> {
        val total = report.totals.single()
        val digits = Currency.getInstance(total.currency).defaultFractionDigits
        return if (total.mode == "TASK") report.tasks.map { task ->
            val price = task.priceMinor?.takeIf { task.currency == total.currency }
            Line(task.title, "${TaskQuantityStore.format(task.quantityUnits)} × ${price?.let { amount(it, digits) + " ${total.currency} each" } ?: "Unit price not set"}\nCompleted ${instant(task.completed, report.selection.timezone)}",
                price?.let { amount(TaskQuantityStore.amount(it, task.quantityUnits), digits) }.orEmpty(), task.quantityUnits.toString(), price?.toString(), task.chargeType)
        } + listOfNotNull(total.minimumTaskAdjustmentMinor.takeIf { it.signum() > 0 }?.let { fee ->
            Line("Task-rate minimum fee", "Adjustment to the configured minimum invoice fee", amount(fee, digits))
        })
        else listOf(Line("Finalized work", "Actual work: ${duration(total.durationMillis)}\nBilled hours: ${total.billedHours ?: "0"}\n${total.billingRule ?: "Exact recorded time"} · ${total.hourlyMinor?.let { amount(it, digits) + " ${total.currency}/hour" } ?: "Hourly rate not set"}",
            total.amountMinor?.let { amount(it, digits) }.orEmpty(), chargeType = TaskClassification.LABOR)) + report.intervals.map {
                Line(it.date, "${instant(it.start, report.selection.timezone)} — ${instant(it.end, report.selection.timezone)}\n${duration(BigInteger.valueOf(it.end - it.start))}", "")
            }
    }
    fun coverageProblem(preview: InvoiceCoverageStore.Preview): String? {
        val report = preview.report; val total = report.totals.single()
        if (preview.legacy.isNotEmpty()) return "LEGACY_INVOICE_REVIEW_REQUIRED"
        if (total.mode == "HOURLY" && report.intervals.isEmpty() || total.mode == "TASK" && report.tasks.isEmpty()) return "NO_BILLABLE_ITEMS"
        if (total.amountMinor == null) return if (total.mode == "HOURLY") "HOURLY_RATE_REQUIRED" else "TASK_PRICES_REQUIRED"
        if (report.intervals.size > 5000 || report.tasks.size > 2000) return "INVOICE_TOO_LARGE"
        return null
    }
    fun prepare(db: SQLiteDatabase, id: String, selection: ReportStore.Selection, at: Long): String? {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}")) || selection.jobIds.size != 1 || at < 0) return "INVALID_INVOICE"
        if (db.rawQuery("SELECT id FROM invoices WHERE id=?", arrayOf(id)).use { it.moveToFirst() }) return "INVOICE_ID_EXISTS"
        val preview = InvoiceCoverageStore.preview(db, ReportStore.read(db, selection))
        return prepareReviewed(db, id, preview, at)
    }
    /** Quote caller supplies a native revalidated report, never client amounts. */
    fun prepareReviewed(db: SQLiteDatabase, id: String, preview: InvoiceCoverageStore.Preview, at: Long,
        agreement: QuoteInvoiceReviewStore.Reviewed? = null): String? {
        val selection = preview.report.selection
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}")) || selection.jobIds.size != 1 || at < 0) return "INVALID_INVOICE"
        if (db.rawQuery("SELECT id FROM invoices WHERE id=?", arrayOf(id)).use { it.moveToFirst() }) return "INVOICE_ID_EXISTS"
        coverageProblem(preview)?.let { return it }
        val report = preview.report
        val total = report.totals.single()
        val amountMinor = requireNotNull(total.amountMinor)
        val job = BusinessStore.job(db, selection.jobIds.single()); val company = BusinessStore.settings(db)
        if (!InvoiceEmailTemplate.valid(company.emailSubject, true)) return "INVALID_EMAIL_SUBJECT"
        if (!InvoiceEmailTemplate.valid(company.emailBody, false)) return "INVALID_EMAIL_BODY"
        val taxUnits = agreement?.taxUnits ?: job.effectiveTaxUnits
        if (!TaxRate.valid(taxUnits)) return "INVALID_TAX_RATE"
        val accepted = agreement?.takeIf { it.acceptedParty }?.context?.issued?.payload?.let(::JSONObject)
        val customer = accepted?.getString("customer") ?: job.customer?.name.orEmpty()
        val email = accepted?.getString("email") ?: job.effectiveEmail
        val contact = if (accepted == null) listOf(job.customer?.address.orEmpty(), email, job.customer?.phone.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
            else listOf(accepted.getString("customerAddress"), email).filter { it.isNotBlank() }.joinToString("\n")
        val sequence = db.rawQuery("SELECT next_invoice_number FROM app_settings WHERE id=1", null).use { it.moveToFirst(); it.getLong(0).coerceAtLeast(1000) }
        if (sequence == Long.MAX_VALUE) return "INVOICE_SEQUENCE_EXHAUSTED"
        val number = "INV-$sequence"
        val digits = Currency.getInstance(total.currency).defaultFractionDigits
        val tax = TaxRate.amount(amountMinor, taxUnits)
        val subtotal = amount(amountMinor, digits)
        val formatted = amount(amountMinor.add(tax), digits)
        val quoteCaption = agreement?.let { "Quote ${it.context.issued.number} · Revision ${it.context.issued.revision}" }
        val lines = billableLines(report).map { line -> if (quoteCaption == null) line else line.copy(detail = line.detail + "\n" + quoteCaption) }
        val values = mapOf("company_name" to company.companyName, "customer_name" to customer, "job_name" to job.settings.name,
            "invoice_number" to number, "amount" to formatted, "currency" to total.currency)
        val snapshot = Snapshot(number, at, company.invoiceStyle, total.currency, digits, company.companyName,
            listOf(company.companyEmail, company.companyPhone, company.companyAddress, company.companyWebsite).filter { it.isNotBlank() }.joinToString("\n"),
            customer, contact,
            job.settings.name, "${selection.firstDate} — ${selection.lastDate} · ${selection.timezone}",
            listOfNotNull(quoteCaption, if (total.mode == "TASK") "Completed billable tasks · quantity × unit price · each line rounded half up" else "Finalized time · ${total.billingRule ?: "exact recorded time"} · actual ${duration(total.durationMillis)} · billed ${total.billedHours ?: "0"} hours",
                if (total.minimumTaskAdjustmentMinor.signum() > 0) "Minimum task-rate fee adjustment ${amount(total.minimumTaskAdjustmentMinor, digits)} ${total.currency}" else null,
                "currency amount rounded half up to ${digits} decimal places").joinToString(" · "),
            company.invoiceHeader, company.invoiceFooter, formatted, email,
            InvoiceEmailTemplate.expand(company.emailSubject, values, true), InvoiceEmailTemplate.expand(company.emailBody, values, false), lines,
            subtotal, taxUnits, amount(tax, digits), billingPolicy = JSONObject(mapOf(
                "mode" to total.mode, "hourlyMode" to total.policyHourlyMode, "minimumHours" to total.policyMinimumHours,
                "minimumTaskFeeMinor" to total.policyTaskMinimumMinor?.toString(), "exactDurationMillis" to total.durationMillis.toString(),
                "billedHours" to total.billedHours, "taskMinimumAdjustmentMinor" to total.minimumTaskAdjustmentMinor.toString())).toString())
        // A single SQLite cursor row must remain readable even when exact prices
        // or repeated template substitutions are very large. Reject before any
        // number, source claim or immutable document is committed; never truncate.
        val payload = snapshot.encode()
        if (payload.toByteArray(Charsets.UTF_8).size > 1_000_000) return "INVOICE_TOO_LARGE"
        val logoId = db.rawQuery("SELECT logo_id FROM app_settings WHERE id=1", null).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }
        db.execSQL("INSERT INTO invoices(id,job_id,customer_id,number,created_ms,payload,logo_id) VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(id, total.jobId, if (accepted == null) job.customer?.id else agreement!!.context.agreement.customerId, number, at, payload, logoId))
        InvoiceCoverageStore.reserve(db, id, report, at)
        db.execSQL("UPDATE app_settings SET next_invoice_number=? WHERE id=1", arrayOf<Any?>(sequence + 1))
        RevisionStore.snapshotRows(db, "invoices", "invoice", listOf("id", "job_id", "customer_id", "number", "created_ms", "payload", "logo_id"), at, "job_id", "id=?", arrayOf(id))
        RevisionStore.snapshotRows(db, "app_settings", "settings", BusinessSchema.settingsColumns, at)
        return null
    }
    fun read(db: SQLiteDatabase, id: String, includePdf: Boolean = true): Record {
        val header = db.rawQuery("SELECT id,job_id,number,payload,logo_id FROM invoices WHERE id=?", arrayOf(id)).use {
            require(it.moveToFirst()) { "INVOICE_UNAVAILABLE" }
            Record(it.getString(0), it.getString(1), it.getString(2), it.getString(3), null, null) to if (it.isNull(4)) null else it.getString(4)
        }
        return header.first.copy(pdf = if (includePdf) InvoiceBlobs.original(db, id) else null,
            logo = header.second?.let { InvoiceBlobs.logo(db, it) })
    }
}
