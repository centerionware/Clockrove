package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.centerionware.clockrove.R
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** One selection powers totals, list, map and export. No report read changes work. */
internal object ReportStore {
    data class Selection(val firstDate: String, val lastDate: String, val timezone: String, val jobIds: List<String>)
    data class Day(val date: String, val start: Long, val end: Long)
    data class Interval(val sessionId: String, val jobId: String, val jobName: String, val date: String,
        val sourceStart: Long, val sourceEnd: Long, val start: Long, val end: Long, val origin: String,
        val sourceTimezone: String, val sourceOffsetSeconds: Int)
    data class Excluded(val sessionId: String, val jobId: String, val start: Long, val end: Long?, val reason: String)
    data class Point(val id: String, val sessionId: String, val jobId: String, val jobName: String,
        val measured: Long, val latitude: Double, val longitude: Double, val accuracy: Double)
    data class Task(val id: String, val jobId: String, val title: String, val completed: Long, val priceMinor: BigInteger?, val currency: String,
        val quantityUnits: Long = TaskQuantityStore.one, val chargeType: String = TaskClassification.UNCLASSIFIED)
    data class Total(val jobId: String, val jobName: String, val mode: String, val currency: String,
        val durationMillis: BigInteger, val hourlyMinor: BigInteger?, val amountMinor: BigInteger?, val missingPrices: Int,
        val billedHours: String? = null, val minimumTaskAdjustmentMinor: BigInteger = BigInteger.ZERO,
        val billingRule: String? = null, val policyHourlyMode: String? = null,
        val policyMinimumHours: String? = null, val policyTaskMinimumMinor: BigInteger? = null)
    data class Report(val selection: Selection, val days: List<Day>, val intervals: List<Interval>, val excluded: List<Excluded>,
        val points: List<Point>, val tasks: List<Task>, val totals: List<Total>)
    private val utc = TimeZone.getTimeZone("UTC")
    private fun calendar(zone: TimeZone): GregorianCalendar = GregorianCalendar(zone, Locale.ROOT).apply { gregorianChange = Date(Long.MIN_VALUE) }
    private fun dateParts(value: String) = value.split('-').map { it.toInt() }
    private fun midnight(value: String, zone: TimeZone): Long {
        val parts = dateParts(value)
        // A report day begins at the FIRST repeated midnight, or the next valid
        // instant if midnight is skipped. java.util.Calendar defaults to the
        // later overlap and can omit an hour in zones such as America/Havana.
        return android.icu.util.GregorianCalendar(android.icu.util.TimeZone.getTimeZone(zone.id), Locale.ROOT).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            repeatedWallTimeOption = android.icu.util.Calendar.WALLTIME_FIRST
            skippedWallTimeOption = android.icu.util.Calendar.WALLTIME_NEXT_VALID
            clear(); set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        }.timeInMillis
    }
    private fun nextDate(value: String): String {
        val next = calendar(utc).apply { timeInMillis = midnight(value, utc); add(Calendar.DAY_OF_MONTH, 1) }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", next.get(Calendar.YEAR), next.get(Calendar.MONTH) + 1, next.get(Calendar.DAY_OF_MONTH))
    }
    fun days(selection: Selection): List<Day> {
        require(BoardStore.validDate(selection.firstDate) && BoardStore.validDate(selection.lastDate) && selection.firstDate <= selection.lastDate) { "INVALID_REPORT_RANGE" }
        require(selection.timezone in TimeZone.getAvailableIDs().toSet()) { "INVALID_REPORT_TIMEZONE" }
        require(selection.jobIds.isNotEmpty() && selection.jobIds.size <= 1000 && selection.jobIds.distinct().size == selection.jobIds.size) { "INVALID_REPORT_JOBS" }
        require(midnight(selection.lastDate, utc) - midnight(selection.firstDate, utc) <= 3660L * 86400000) { "REPORT_RANGE_TOO_LARGE" }
        val zone = TimeZone.getTimeZone(selection.timezone)
        return buildList {
            var current = selection.firstDate
            while (true) {
                val next = nextDate(current)
                add(Day(current, midnight(current, zone), midnight(next, zone)))
                if (current == selection.lastDate) break
                current = next
            }
        }
    }
    /** Round the selected job total once, half up, to the currency's minor unit. */
    fun hourlyAmount(durationMillis: BigInteger, rateMinor: BigInteger): BigInteger {
        require(durationMillis.signum() >= 0 && rateMinor.signum() >= 0)
        return durationMillis.multiply(rateMinor).add(BigInteger.valueOf(1800000)).divide(BigInteger.valueOf(3600000))
    }
    fun read(db: SQLiteDatabase, selection: Selection): Report {
        val days = days(selection); val first = days.first().start; val end = days.last().end
        val jobs = selection.jobIds.map { BusinessStore.job(db, it) }
        val intervals = mutableListOf<Interval>(); val excluded = mutableListOf<Excluded>(); val points = mutableListOf<Point>(); val tasks = mutableListOf<Task>()
        jobs.forEach { job ->
            val id = job.settings.id
            db.rawQuery("SELECT s.id,s.start_ms,s.end_ms,s.origin,s.timezone,s.offset_seconds,EXISTS(SELECT 1 FROM geofence_conflicts c WHERE c.session_id=s.id) FROM sessions s WHERE s.job_id=? AND s.start_ms<? AND (s.end_ms IS NULL OR s.end_ms>?) ORDER BY s.start_ms,s.id",
                arrayOf(id, end.toString(), first.toString())).use { rows -> while (rows.moveToNext()) {
                val sessionId = rows.getString(0); val sourceStart = rows.getLong(1); val sourceEnd = if (rows.isNull(2)) null else rows.getLong(2)
                if (sourceEnd == null || rows.getInt(6) != 0) {
                    excluded.add(Excluded(sessionId, id, sourceStart, sourceEnd, if (rows.getInt(6) != 0) "NEEDS_REVIEW" else "ACTIVE_OR_PROVISIONAL"))
                } else {
                    days.forEach { day ->
                        val start = maxOf(sourceStart, day.start); val finish = minOf(sourceEnd, day.end)
                        if (start < finish) intervals.add(Interval(sessionId, id, job.settings.name, day.date, sourceStart, sourceEnd,
                            start, finish, rows.getString(3), rows.getString(4), rows.getInt(5)))
                    }
                    db.rawQuery("SELECT id,measured_ms,latitude,longitude,accuracy FROM location_points WHERE session_id=? AND measured_ms>=? AND measured_ms<? AND measured_ms>=? AND measured_ms<=? ORDER BY measured_ms,id",
                        arrayOf(sessionId, first.toString(), end.toString(), sourceStart.toString(), sourceEnd.toString())).use { p -> while (p.moveToNext()) {
                        points.add(Point(p.getString(0), sessionId, id, job.settings.name, p.getLong(1), p.getDouble(2), p.getDouble(3), p.getDouble(4)))
                    } }
                }
            } }
            db.rawQuery("SELECT c.id,c.title,c.completed_ms,CASE WHEN m.id IS NOT NULL THEN m.minor_text ELSE CAST(c.price_minor AS TEXT) END,c.currency,COALESCE(q.quantity_units,10000),c.charge_type FROM kanban_cards c LEFT JOIN task_quantities q ON q.card_id=c.id LEFT JOIN card_money m ON m.id=c.id WHERE c.job_id=? AND c.billable=1 AND c.completed_ms>=? AND c.completed_ms<? ORDER BY c.completed_ms,c.id",
                arrayOf(id, first.toString(), end.toString())).use { rows -> while (rows.moveToNext()) tasks.add(Task(rows.getString(0), id, rows.getString(1), rows.getLong(2), if (rows.isNull(3)) null else MoneyMath.parse(rows.getString(3)), rows.getString(4), rows.getLong(5), rows.getString(6))) }
        }
        val totals = jobs.map { job ->
            val duration = intervals.filter { it.jobId == job.settings.id }.fold(BigInteger.ZERO) { value, interval -> value.add(BigInteger.valueOf(interval.end - interval.start)) }
            val selectedTasks = tasks.filter { it.jobId == job.settings.id }
            val missing = selectedTasks.count { it.priceMinor == null || it.currency != job.settings.currency }
            val amount = if (job.settings.billingMode == "HOURLY") job.effectiveHourlyMinor?.let { hourlyAmount(duration, it) }
                else if (missing > 0) null else selectedTasks.fold(BigInteger.ZERO) { value, task -> value.add(TaskQuantityStore.amount(requireNotNull(task.priceMinor), task.quantityUnits)) }
            Total(job.settings.id, job.settings.name, job.settings.billingMode, job.settings.currency, duration, job.effectiveHourlyMinor, amount, missing)
        }
        return Report(selection, days, intervals.sortedWith(compareBy({ it.start }, { it.sessionId })), excluded.sortedBy { it.start },
            points.sortedWith(compareBy({ it.measured }, { it.id })), tasks.sortedWith(compareBy({ it.completed }, { it.id })), totals)
    }
}

/** RFC-4180 quoting plus spreadsheet formula protection for all untrusted text. */
internal object ReportCsv {
    private fun text(value: String): String {
        val first = value.dropWhile { it.isWhitespace() || it.isISOControl() }.firstOrNull()
        val protected = if (first in setOf('=', '+', '-', '@') || value.firstOrNull() in setOf('\t', '\r', '\n')) "'$value" else value
        return "\"${protected.replace("\"", "\"\"")}\""
    }
    private data class Numeric(val value: String)
    private fun numeric(value: Any) = Numeric(value.toString())
    private fun exactMinor(value: BigInteger?) = value?.let { "'${it}" }.orEmpty()
    private fun row(vararg values: Any) = values.joinToString(",") { if (it is Numeric) "\"${it.value}\"" else text(it.toString()) } + "\r\n"
    internal fun instant(value: Long, zone: String) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(value))
    fun work(context: Context, report: ReportStore.Report): String = buildString {
        append(row(
            context.getString(R.string.csv_session_id),
            context.getString(R.string.csv_job_id),
            context.getString(R.string.csv_job_name),
            context.getString(R.string.csv_report_date),
            context.getString(R.string.csv_source_start),
            context.getString(R.string.csv_source_end),
            context.getString(R.string.csv_export_start),
            context.getString(R.string.csv_export_end),
            context.getString(R.string.csv_duration_seconds),
            context.getString(R.string.report_csv_col_timezone),
            context.getString(R.string.csv_origin),
            context.getString(R.string.csv_source_timezone),
            context.getString(R.string.csv_source_offset_seconds),
        ))
        report.intervals.forEach { i -> append(row(i.sessionId, i.jobId, i.jobName, i.date, instant(i.sourceStart, report.selection.timezone), instant(i.sourceEnd, report.selection.timezone),
            instant(i.start, report.selection.timezone), instant(i.end, report.selection.timezone), numeric(java.math.BigDecimal.valueOf(i.end - i.start, 3).stripTrailingZeros().toPlainString()), report.selection.timezone, localizedOrigin(context, i.origin), i.sourceTimezone, numeric(i.sourceOffsetSeconds))) }
    }
    fun gps(context: Context, report: ReportStore.Report): String = buildString {
        append(row(
            context.getString(R.string.csv_point_id),
            context.getString(R.string.csv_session_id),
            context.getString(R.string.csv_job_id),
            context.getString(R.string.csv_job_name),
            context.getString(R.string.csv_measured_timestamp),
            context.getString(R.string.csv_latitude),
            context.getString(R.string.csv_longitude),
            context.getString(R.string.csv_accuracy_meters),
            context.getString(R.string.report_csv_col_timezone),
        ))
        report.points.forEach { p -> append(row(p.id, p.sessionId, p.jobId, p.jobName, instant(p.measured, report.selection.timezone), numeric(p.latitude), numeric(p.longitude), numeric(p.accuracy), report.selection.timezone)) }
    }

    /** Exact minor-unit values are exported as spreadsheet text, never as rounded floating-point cells. */
    fun business(context: Context, report: BusinessReportStore.Report): String = buildString {
        append(row(
            context.getString(R.string.report_csv_col_record_type), context.getString(R.string.report_csv_col_date),
            context.getString(R.string.report_csv_col_timezone), context.getString(R.string.report_csv_col_currency),
            context.getString(R.string.report_csv_col_fraction_digits), context.getString(R.string.report_csv_col_job),
            context.getString(R.string.report_csv_col_customer), context.getString(R.string.report_csv_col_document),
            context.getString(R.string.report_csv_col_activity), context.getString(R.string.report_csv_col_sales),
            context.getString(R.string.report_csv_col_tax), context.getString(R.string.report_csv_col_cash),
            context.getString(R.string.report_csv_col_credit), context.getString(R.string.report_csv_col_cost),
            context.getString(R.string.report_csv_col_cost_category), context.getString(R.string.report_csv_col_labor),
            context.getString(R.string.report_csv_col_supplies), context.getString(R.string.report_csv_col_unclassified),
            context.getString(R.string.report_csv_col_legacy_total), context.getString(R.string.report_csv_col_reference),
            context.getString(R.string.report_csv_col_detail), context.getString(R.string.report_csv_col_overhead),
            context.getString(R.string.report_csv_col_outstanding), context.getString(R.string.report_csv_col_tax_collected),
            context.getString(R.string.report_csv_col_gross_profit), context.getString(R.string.report_csv_col_net_profit),
            context.getString(R.string.report_csv_col_cash_surplus), context.getString(R.string.report_csv_col_gross_margin),
            context.getString(R.string.report_csv_col_net_margin),
            context.getString(R.string.report_csv_col_excluded_unknown_customer_cost_count),
        ))
        report.summaries.forEach { value -> append(row(
            context.getString(R.string.report_csv_summary), "${report.selection.firstDate}..${report.selection.lastDate}", report.selection.timezone,
            value.currency, value.digits, "", "", "", "", exactMinor(value.invoicedSales), exactMinor(value.taxBilled),
            exactMinor(value.cashReceived), "", exactMinor(value.directCosts), "", exactMinor(value.labor),
            exactMinor(value.supplies), exactMinor(value.unclassified), "", "",
            "", exactMinor(value.overhead), exactMinor(value.outstanding), exactMinor(value.taxCollected),
            exactMinor(value.grossProfit), exactMinor(value.netProfit), exactMinor(value.cashSurplus),
            value.grossMarginPercent.orEmpty(), value.netMarginPercent.orEmpty(), report.excludedUnknownCustomerCostCount,
        )) }
        report.invoices.forEach { value -> append(row(
            context.getString(R.string.report_csv_invoice), instant(value.occurred, report.selection.timezone), report.selection.timezone,
            value.currency, value.digits, value.job, value.customer, value.number, localizedInvoiceActivity(context, value.kind),
            exactMinor(value.salesMinor), exactMinor(value.taxMinor), "", "", "", "",
            exactMinor(value.laborMinor), exactMinor(value.suppliesMinor), exactMinor(value.unclassifiedMinor),
            exactMinor(value.legacyTotalMinor), "", listOf(value.reason, value.relatedInvoiceId?.let { context.getString(R.string.report_csv_replacement, it) }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "), "", "", "", "", "", "", "", "", "",
        )) }
        report.cash.forEach { value -> append(row(
            context.getString(R.string.report_csv_cash_event), instant(value.occurred, report.selection.timezone), report.selection.timezone,
            value.currency, value.digits, value.job, value.customer, value.number, localizedCashActivity(context, value.kind),
            exactMinor(value.salesMinor), exactMinor(value.taxMinor), exactMinor(value.cashMinor), exactMinor(value.creditMinor),
            "", "", "", "", "", "", value.reference, "", "", "", "", "", "", "", "", "", "",
        )) }
        report.costs.forEach { value -> append(row(
            context.getString(R.string.report_csv_actual_cost), instant(value.occurred, value.timezone), value.timezone,
            value.currency, value.digits, value.job, value.customer, "", "", "", "", "", "",
            exactMinor(value.amountMinor), localizedCostCategory(context, value.category), "", "", "", "", value.reference,
            listOf(value.task, value.vendor, value.note).filter { it.isNotBlank() }.joinToString(" · "),
            "", "", "", "", "", "", "", "", "",
        )) }
    }

    private fun localizedOrigin(context: Context, origin: String): String = context.getString(when (origin) {
        "WIDGET" -> R.string.report_csv_origin_widget
        "NOTIFICATION" -> R.string.report_csv_origin_notification
        "GEOFENCE" -> R.string.report_csv_origin_geofence
        else -> R.string.report_csv_origin_manual
    })

    private fun localizedInvoiceActivity(context: Context, kind: String): String = context.getString(when (kind) {
        "VOID_ADJUSTMENT" -> R.string.report_csv_void_adjustment
        else -> R.string.report_csv_issued
    })

    private fun localizedCashActivity(context: Context, kind: String): String = context.getString(when (kind) {
        "REVERSAL" -> R.string.report_csv_payment_correction
        "TRANSFER" -> R.string.report_csv_credit_transfer
        else -> R.string.report_csv_payment
    })

    private fun localizedCostCategory(context: Context, category: String): String = context.getString(when (category) {
        ActualCostStore.MATERIALS -> R.string.report_pdf_cost_materials
        ActualCostStore.SUBCONTRACT_LABOR -> R.string.report_pdf_cost_labor
        ActualCostStore.OTHER_DIRECT -> R.string.report_pdf_cost_other_direct
        ActualCostStore.OVERHEAD -> R.string.report_pdf_cost_overhead
        else -> R.string.actual_cost_unknown_category
    })
}
