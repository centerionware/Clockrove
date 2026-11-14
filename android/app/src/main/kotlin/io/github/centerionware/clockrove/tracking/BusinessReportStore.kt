package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Currency
import java.util.TimeZone
import org.json.JSONObject

/** Read-only, event-dated accounting summaries. Amounts remain exact minor-unit integers. */
internal object BusinessReportStore {
    private const val ROW_LIMIT = 20_000
    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")

    data class Selection(
        val firstDate: String,
        val lastDate: String,
        val timezone: String,
        val jobIds: List<String> = emptyList(),
        val customerIds: List<String> = emptyList(),
    )

    data class Summary(
        val currency: String,
        val digits: Int,
        val invoicedSales: BigInteger,
        val cashReceived: BigInteger,
        val taxBilled: BigInteger,
        val taxCollected: BigInteger,
        val outstanding: BigInteger,
        val labor: BigInteger,
        val supplies: BigInteger,
        val unclassified: BigInteger,
        val directCosts: BigInteger,
        val overhead: BigInteger,
        val grossProfit: BigInteger,
        val netProfit: BigInteger,
        val grossMarginPercent: String?,
        val netMarginPercent: String?,
        val cashSurplus: BigInteger,
        val costCount: Int,
        val unattributedCostCount: Int,
        val legacyInvoiceCount: Int,
        val unknownTaxInvoiceCount: Int,
    )

    data class InvoiceMovement(
        val id: String,
        val invoiceId: String,
        val number: String,
        val kind: String,
        val occurred: Long,
        val jobId: String,
        val job: String,
        val customerId: String?,
        val customer: String,
        val currency: String,
        val digits: Int,
        val salesMinor: BigInteger?,
        val taxMinor: BigInteger?,
        val legacyTotalMinor: BigInteger?,
        val laborMinor: BigInteger?,
        val suppliesMinor: BigInteger?,
        val unclassifiedMinor: BigInteger?,
        val relatedInvoiceId: String?,
        val reason: String,
    )

    data class CashMovement(
        val id: String,
        val invoiceId: String,
        val paymentId: String,
        val number: String,
        val kind: String,
        val occurred: Long,
        val jobId: String,
        val job: String,
        val customerId: String?,
        val customer: String,
        val currency: String,
        val digits: Int,
        val cashMinor: BigInteger,
        val creditMinor: BigInteger,
        val salesMinor: BigInteger?,
        val taxMinor: BigInteger?,
        val reference: String,
    )

    data class CostMovement(
        val id: String,
        val jobId: String?,
        val job: String,
        val customerId: String?,
        val customer: String,
        val taskId: String?,
        val task: String,
        val occurred: Long,
        val timezone: String,
        val offsetSeconds: Int,
        val currency: String,
        val digits: Int,
        val category: String,
        val amountMinor: BigInteger,
        val vendor: String,
        val reference: String,
        val note: String,
    )

    data class Report(
        val selection: Selection,
        val asOfExclusiveMillis: Long,
        val summaries: List<Summary>,
        val invoices: List<InvoiceMovement>,
        val cash: List<CashMovement>,
        val costs: List<CostMovement>,
        val excludedUnknownCustomerCostCount: Int,
    )

    private data class InvoiceRecord(
        val id: String,
        val number: String,
        val created: Long,
        val jobId: String,
        val customerId: String?,
        val document: InvoiceStore.Snapshot,
    )

    private data class Event(
        val id: String,
        val invoiceId: String,
        val paymentId: String,
        val kind: String,
        val occurred: Long,
        val cash: BigInteger,
        val credit: BigInteger,
        val sales: BigInteger?,
        val tax: BigInteger?,
        val reference: String,
    )

    private data class Components(
        val sales: BigInteger?,
        val tax: BigInteger?,
        val labor: BigInteger?,
        val supplies: BigInteger?,
        val unclassified: BigInteger?,
        val legacyTotal: BigInteger?,
    )

    private class Accumulator(val currency: String) {
        val digits = Currency.getInstance(currency).defaultFractionDigits
        var sales = BigInteger.ZERO
        var cash = BigInteger.ZERO
        var taxBilled = BigInteger.ZERO
        var taxCollected = BigInteger.ZERO
        var outstanding = BigInteger.ZERO
        var labor = BigInteger.ZERO
        var supplies = BigInteger.ZERO
        var unclassified = BigInteger.ZERO
        var directCosts = BigInteger.ZERO
        var overhead = BigInteger.ZERO
        var costCount = 0
        var unattributedCostCount = 0
        val legacyInvoiceIds = mutableSetOf<String>()
        val unknownTaxInvoiceIds = mutableSetOf<String>()
    }

    private data class Outstanding(
        val amounts: Map<String, BigInteger>,
        val openInvoiceIds: Set<String>,
    )

    private fun validIds(values: List<String>, max: Int): Boolean =
        values.size <= max && values.distinct().size == values.size && values.all(idPattern::matches)

    private fun inDate(value: Long, start: Long, end: Long) = value >= start && value < end
    private fun optionalMinor(value: String?, digits: Int): BigInteger? = value?.takeIf { it.isNotBlank() }?.let {
        try { PaymentMath.parseMajor(it, digits) } catch (_: IllegalArgumentException) { null }
    }

    private fun components(document: InvoiceStore.Snapshot, negate: Boolean = false): Components {
        val digits = document.digits
        val sales = optionalMinor(document.subtotal, digits)
        if (sales == null) return Components(null, null, null, null, null, optionalMinor(document.total, digits))
        val tax = optionalMinor(document.taxAmount, digits)
        var labor = BigInteger.ZERO
        var supplies = BigInteger.ZERO
        var unclassified = BigInteger.ZERO
        var lineTotal = BigInteger.ZERO
        for (line in document.lines) {
            if (line.amount.isBlank()) continue
            val amount = optionalMinor(line.amount, digits) ?: continue
            lineTotal = lineTotal.add(amount)
            when (line.chargeType) {
                TaskClassification.LABOR -> labor = labor.add(amount)
                TaskClassification.SUPPLIES -> supplies = supplies.add(amount)
                else -> unclassified = unclassified.add(amount)
            }
        }
        // Preserve the frozen invoice subtotal even if a historic line snapshot
        // is malformed or incomplete; expose its unreconciled difference as
        // unclassified instead of silently losing revenue.
        unclassified = unclassified.add(sales.subtract(lineTotal))
        val sign = if (negate) BigInteger.valueOf(-1) else BigInteger.ONE
        return Components(sales.multiply(sign), tax?.multiply(sign), labor.multiply(sign),
            supplies.multiply(sign), unclassified.multiply(sign), null)
    }

    fun read(db: SQLiteDatabase, selection: Selection): Report {
        require(selection.timezone in TimeZone.getAvailableIDs().toSet()) { "INVALID_BUSINESS_REPORT_TIMEZONE" }
        require(validIds(selection.jobIds, 1000) && validIds(selection.customerIds, 1000) &&
            selection.jobIds.size + selection.customerIds.size <= 900) { "INVALID_BUSINESS_REPORT_FILTER" }
        val days = ReportStore.days(ReportStore.Selection(
            selection.firstDate, selection.lastDate, selection.timezone,
            selection.jobIds.ifEmpty { listOf("__all_business_jobs__") },
        ))
        val start = days.first().start
        val end = days.last().end

        val records = readInvoices(db, selection, end)
        val recordsById = records.associateBy { it.id }
        val voids = readVoids(db, records)
        val movements = mutableListOf<InvoiceMovement>()
        for (record in records) {
            if (inDate(record.created, start, end)) {
                val amounts = components(record.document)
                movements += InvoiceMovement(
                    "issue-${record.id}", record.id, record.number, "ISSUED", record.created,
                    record.jobId, record.document.job, record.customerId, record.document.customer,
                    record.document.currency, record.document.digits,
                    amounts.sales, amounts.tax, amounts.legacyTotal, amounts.labor, amounts.supplies, amounts.unclassified,
                    null, "Invoice issued",
                )
            }
            val void = voids[record.id]
            if (void != null && inDate(void.at, start, end)) {
                val amounts = components(record.document, negate = true)
                val related = void.replacementId
                movements += InvoiceMovement(
                    "void-${record.id}", record.id, record.number, "VOID_ADJUSTMENT", void.at,
                    record.jobId, record.document.job, record.customerId, record.document.customer,
                    record.document.currency, record.document.digits,
                    amounts.sales, amounts.tax, amounts.legacyTotal, amounts.labor, amounts.supplies, amounts.unclassified,
                    related, void.reason,
                )
            }
        }

        val allEvents = readEvents(db, selection, end)
        val cash = allEvents.filter { inDate(it.occurred, start, end) }.map { event ->
            val record = recordsById[event.invoiceId]
                ?: error("Business report event has no invoice snapshot")
            CashMovement(
                event.id, event.invoiceId, event.paymentId, record.number, event.kind, event.occurred,
                record.jobId, record.document.job, record.customerId, record.document.customer,
                record.document.currency, record.document.digits,
                event.cash, event.credit, event.sales, event.tax, event.reference,
            )
        }

        val costRead = readCosts(db, selection, start, end)
        val costs = costRead.first
        val accumulators = sortedMapOf<String, Accumulator>()
        fun accumulator(currency: String) = accumulators.getOrPut(currency) { Accumulator(currency) }

        // Keep zero-activity currencies visible when a selected job establishes
        // the currency but has not yet issued a document or recorded an expense.
        val jobsWhere = mutableListOf<String>()
        val jobsArgs = mutableListOf<String>()
        if (selection.jobIds.isNotEmpty()) {
            jobsWhere += "id IN (${placeholders(selection.jobIds.size)})"
            jobsArgs.addAll(selection.jobIds)
        }
        if (selection.customerIds.isNotEmpty()) {
            jobsWhere += "customer_id IN (${placeholders(selection.customerIds.size)})"
            jobsArgs.addAll(selection.customerIds)
        }
        val jobsSql = "SELECT currency FROM jobs" + if (jobsWhere.isEmpty()) "" else " WHERE ${jobsWhere.joinToString(" AND ")}"
        db.rawQuery(jobsSql, jobsArgs.toTypedArray().takeIf { it.isNotEmpty() }).use { rows ->
            while (rows.moveToNext()) accumulator(rows.getString(0))
        }

        movements.forEach { movement ->
            val total = accumulator(movement.currency)
            if (movement.salesMinor != null) {
                total.sales = total.sales.add(movement.salesMinor)
                total.labor = total.labor.add(movement.laborMinor ?: BigInteger.ZERO)
                total.supplies = total.supplies.add(movement.suppliesMinor ?: BigInteger.ZERO)
                total.unclassified = total.unclassified.add(movement.unclassifiedMinor ?: BigInteger.ZERO)
            }
            movement.taxMinor?.let { total.taxBilled = total.taxBilled.add(it) }
        }
        cash.forEach { movement ->
            val total = accumulator(movement.currency)
            total.cash = total.cash.add(movement.cashMinor)
            if ((movement.kind == "PAYMENT" || movement.kind == "REVERSAL") && movement.taxMinor != null) {
                total.taxCollected = total.taxCollected.add(movement.taxMinor)
            }
        }
        costs.forEach { cost ->
            val total = accumulator(cost.currency)
            total.costCount++
            when (cost.category) {
                ActualCostStore.OVERHEAD -> total.overhead = total.overhead.add(cost.amountMinor)
                else -> total.directCosts = total.directCosts.add(cost.amountMinor)
            }
            if (cost.jobId == null) total.unattributedCostCount++
        }

        val outstanding = outstanding(records, allEvents, voids, end)
        outstanding.amounts.forEach { (currency, value) -> accumulator(currency).outstanding = value }
        (movements.map { it.invoiceId } + cash.map { it.invoiceId } + outstanding.openInvoiceIds).toSet().forEach { invoiceId ->
            val record = recordsById[invoiceId] ?: return@forEach
            val total = accumulator(record.document.currency)
            if (optionalMinor(record.document.subtotal, record.document.digits) == null) total.legacyInvoiceIds += invoiceId
            if (optionalMinor(record.document.taxAmount, record.document.digits) == null) total.unknownTaxInvoiceIds += invoiceId
        }
        val summaries = accumulators.values.map { total ->
            val gross = total.sales.subtract(total.directCosts)
            val net = gross.subtract(total.overhead)
            Summary(
                total.currency, total.digits, total.sales, total.cash, total.taxBilled,
                total.taxCollected, total.outstanding, total.labor, total.supplies,
                total.unclassified, total.directCosts, total.overhead, gross, net,
                margin(gross, total.sales), margin(net, total.sales),
                total.cash.subtract(total.directCosts).subtract(total.overhead),
                total.costCount, total.unattributedCostCount, total.legacyInvoiceIds.size,
                total.unknownTaxInvoiceIds.size,
            )
        }
        return Report(selection, end, summaries, movements.sortedWith(compareBy({ it.occurred }, { it.id })),
            cash.sortedWith(compareBy({ it.occurred }, { it.id })), costs.sortedWith(compareBy({ it.occurred }, { it.id })),
            costRead.second)
    }

    private fun readInvoices(db: SQLiteDatabase, selection: Selection, end: Long): List<InvoiceRecord> {
        val where = mutableListOf("i.created_ms<?")
        val args = mutableListOf(end.toString())
        if (selection.jobIds.isNotEmpty()) {
            where += "i.job_id IN (${placeholders(selection.jobIds.size)})"
            args.addAll(selection.jobIds)
        }
        if (selection.customerIds.isNotEmpty()) {
            where += "i.customer_id IN (${placeholders(selection.customerIds.size)})"
            args.addAll(selection.customerIds)
        }
        val sql = "SELECT i.id,i.number,i.created_ms,i.job_id,i.customer_id,i.payload FROM invoices i WHERE ${where.joinToString(" AND ")} ORDER BY i.created_ms,i.id LIMIT ${ROW_LIMIT + 1}"
        val values = db.rawQuery(sql, args.toTypedArray()).use { rows -> buildList {
            while (rows.moveToNext()) add(InvoiceRecord(
                rows.getString(0), rows.getString(1), rows.getLong(2), rows.getString(3),
                if (rows.isNull(4)) null else rows.getString(4), InvoiceStore.Snapshot.decode(rows.getString(5)),
            ))
        } }
        require(values.size <= ROW_LIMIT) { "BUSINESS_REPORT_TOO_LARGE" }
        return values
    }

    private fun readEvents(db: SQLiteDatabase, selection: Selection, end: Long): List<Event> {
        val where = mutableListOf("e.effective_ms<?")
        val args = mutableListOf(end.toString())
        if (selection.jobIds.isNotEmpty()) {
            where += "i.job_id IN (${placeholders(selection.jobIds.size)})"
            args.addAll(selection.jobIds)
        }
        if (selection.customerIds.isNotEmpty()) {
            where += "i.customer_id IN (${placeholders(selection.customerIds.size)})"
            args.addAll(selection.customerIds)
        }
        val sql = "SELECT e.id,e.invoice_id,e.payment_id,e.kind,e.effective_ms,e.payload,p.reference FROM invoice_payment_events e JOIN invoices i ON i.id=e.invoice_id JOIN invoice_payments p ON p.id=e.payment_id WHERE ${where.joinToString(" AND ")} ORDER BY e.effective_ms,e.command_version,e.ordinal LIMIT ${ROW_LIMIT + 1}"
        val events = db.rawQuery(sql, args.toTypedArray()).use { rows -> buildList {
            while (rows.moveToNext()) {
                val payload = JSONObject(rows.getString(5))
                add(Event(
                    rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4),
                    big(payload, "cash_change_minor"), big(payload, "credit_change_minor"),
                    optionalBig(payload, "sales_change_minor"), optionalBig(payload, "tax_change_minor"), rows.getString(6),
                ))
            }
        } }
        require(events.size <= ROW_LIMIT) { "BUSINESS_REPORT_TOO_LARGE" }
        return events
    }

    private fun readCosts(db: SQLiteDatabase, selection: Selection, start: Long, end: Long): Pair<List<CostMovement>, Int> {
        val where = mutableListOf("deleted_ms IS NULL", "occurred_ms>=?", "occurred_ms<?")
        val args = mutableListOf(start.toString(), end.toString())
        if (selection.jobIds.isNotEmpty()) {
            where += "job_id IN (${placeholders(selection.jobIds.size)})"
            args.addAll(selection.jobIds)
        }
        if (selection.customerIds.isNotEmpty()) {
            where += "customer_id IN (${placeholders(selection.customerIds.size)})"
            args.addAll(selection.customerIds)
        }
        val sql = "SELECT id,job_id,job_name,customer_id,customer_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note FROM actual_costs WHERE ${where.joinToString(" AND ")} ORDER BY occurred_ms,id LIMIT ${ROW_LIMIT + 1}"
        val rows = db.rawQuery(sql, args.toTypedArray()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(CostMovement(
                cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1), cursor.getString(2),
                if (cursor.isNull(3)) null else cursor.getString(3), cursor.getString(4),
                if (cursor.isNull(5)) null else cursor.getString(5), cursor.getString(6),
                cursor.getLong(10), cursor.getString(11), cursor.getInt(12), cursor.getString(8),
                Currency.getInstance(cursor.getString(8)).defaultFractionDigits, cursor.getString(9),
                MoneyMath.parse(cursor.getString(7)), cursor.getString(13), cursor.getString(14), cursor.getString(15),
            ))
        } }
        require(rows.size <= ROW_LIMIT) { "BUSINESS_REPORT_TOO_LARGE" }

        var excludedUnknownCustomer = 0
        if (selection.customerIds.isNotEmpty()) {
            val unknownWhere = mutableListOf("deleted_ms IS NULL", "occurred_ms>=?", "occurred_ms<?", "customer_id IS NULL")
            val unknownArgs = mutableListOf(start.toString(), end.toString())
            if (selection.jobIds.isNotEmpty()) {
                unknownWhere += "job_id IN (${placeholders(selection.jobIds.size)})"
                unknownArgs.addAll(selection.jobIds)
            }
            db.rawQuery("SELECT COUNT(*) FROM actual_costs WHERE ${unknownWhere.joinToString(" AND ")}", unknownArgs.toTypedArray()).use {
                if (it.moveToFirst()) excludedUnknownCustomer = it.getInt(0)
            }
        }
        return rows to excludedUnknownCustomer
    }

    private fun readVoids(db: SQLiteDatabase, records: List<InvoiceRecord>): Map<String, InvoiceLifecycleStore.VoidInfo> {
        val result = mutableMapOf<String, InvoiceLifecycleStore.VoidInfo>()
        records.map { it.id }.chunked(900).forEach { ids ->
            if (ids.isEmpty()) return@forEach
            val sql = "SELECT invoice_id,replacement_id,credited_minor,cancelled_minor,created_ms,timezone,reason FROM invoice_voids WHERE invoice_id IN (${placeholders(ids.size)})"
            db.rawQuery(sql, ids.toTypedArray()).use { rows ->
                while (rows.moveToNext()) result[rows.getString(0)] = InvoiceLifecycleStore.VoidInfo(
                    if (rows.isNull(1)) null else rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getLong(4), rows.getString(5), rows.getString(6),
                )
            }
        }
        return result
    }

    private fun outstanding(
        records: List<InvoiceRecord>,
        events: List<Event>,
        voids: Map<String, InvoiceLifecycleStore.VoidInfo>,
        asOf: Long,
    ): Outstanding {
        val totals = mutableMapOf<String, BigInteger>()
        val openInvoiceIds = mutableSetOf<String>()
        val eventsByInvoice = events.asSequence().filter { it.occurred < asOf }.groupBy { it.invoiceId }
        records.forEach { record ->
            val void = voids[record.id]
            if (void != null && void.at < asOf) return@forEach
            val documentTotal = optionalMinor(record.document.total, record.document.digits) ?: return@forEach
            val allocated = eventsByInvoice[record.id].orEmpty().asSequence()
                .fold(BigInteger.ZERO) { value, event -> value.add(event.cash).add(event.credit) }
            val due = documentTotal.subtract(allocated)
            totals[record.document.currency] = (totals[record.document.currency] ?: BigInteger.ZERO).add(due)
            if (due.signum() != 0) openInvoiceIds += record.id
        }
        return Outstanding(totals, openInvoiceIds)
    }

    private fun margin(profit: BigInteger, sales: BigInteger): String? {
        if (sales.signum() <= 0) return null
        return BigDecimal(profit).multiply(BigDecimal("100"))
            .divide(BigDecimal(sales), 4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun big(value: JSONObject, key: String): BigInteger =
        value.optString(key, "0").takeIf { it.isNotEmpty() }?.let { BigInteger(it) } ?: BigInteger.ZERO

    private fun optionalBig(value: JSONObject, key: String): BigInteger? =
        if (!value.has(key) || value.isNull(key)) null else BigInteger(value.getString(key))

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")
}
