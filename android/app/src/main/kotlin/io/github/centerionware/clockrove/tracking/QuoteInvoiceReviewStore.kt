package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
import java.util.Currency
import org.json.JSONObject

/** Read-only agreement/actual-work boundary. Never turns estimated work into a charge. */
internal object QuoteInvoiceReviewStore {
    data class Time(val sessionId: String, val start: Long, val end: Long)
    data class Request(val quoteId: String, val revision: Int, val selection: ReportStore.Selection)
    data class Choices(val taskIds: List<String>, val times: List<Time>, val currentPrices: Boolean = false,
        val currentTax: Boolean = false, val currentParty: Boolean = false,
        val note: String, val confirmed: Boolean, val historicalReviewed: Boolean = false)
    data class Task(val actual: ReportStore.Task, val accepted: QuoteStore.Line)
    data class Context(val issued: QuoteStore.Revision, val agreement: QuoteStore.Draft, val digits: Int,
        val job: BusinessStore.JobDetail, val coverage: InvoiceCoverageStore.Preview,
        val tasks: List<Task>, val lines: List<QuoteStore.Line>, val acceptedRate: BigInteger?, val historical: Boolean)
    data class Reviewed(val context: Context, val report: ReportStore.Report, val taxUnits: Int,
        val choices: Choices, val acceptedParty: Boolean)
    data class Read(val problem: String?, val context: Context? = null)
    data class Review(val problem: String?, val value: Reviewed? = null)

    fun read(db: SQLiteDatabase, request: Request): Read {
        if (request.revision < 1 || request.selection.jobIds.size != 1) return Read("INVALID_QUOTE_INVOICE")
        val issued = QuoteStore.revision(db, request.quoteId, request.revision) ?: return Read("QUOTE_UNAVAILABLE")
        if (issued.decision != "ACCEPTED") return Read("QUOTE_ACCEPTANCE_REQUIRED")
        val frozen = JSONObject(issued.payload)
        val agreement = QuoteStore.Draft.decode(frozen.getJSONObject("draft").toString())
        val jobId = request.selection.jobIds.single()
        if (!db.rawQuery("SELECT 1 FROM jobs WHERE id=?", arrayOf(jobId)).use { it.moveToFirst() }) return Read("JOB_UNAVAILABLE")
        val job = BusinessStore.job(db, jobId)
        val digits = frozen.getInt("digits")
        if (job.settings.currency != agreement.currency) return Read("CURRENCY_MISMATCH")
        if (Currency.getInstance(agreement.currency).defaultFractionDigits != digits) return Read("QUOTE_CURRENCY_PRECISION_CHANGED")
        val linked = db.rawQuery("SELECT line_id FROM quote_scope_revisions WHERE quote_id=? AND revision=?", arrayOf(request.quoteId, request.revision.toString())).use { rows -> buildSet {
            while (rows.moveToNext()) add(rows.getString(0))
        } }
        val bindings = QuoteScopeStore.bindings(db, request.quoteId).filter { it.jobId == jobId && it.lineId in linked }
        val byId = agreement.lines.associateBy { it.id }
        if (bindings.isEmpty()) return Read("QUOTE_INVOICE_MAPPING_REQUIRED")
        if (bindings.any { byId[it.lineId]?.kind != it.kind }) return Read("QUOTE_SCOPE_KIND_CHANGED")
        val lines = bindings.map { requireNotNull(byId[it.lineId]) }
        val kind = lines.first().kind
        if (lines.any { it.kind != kind } || kind == "HOURS" && lines.map { it.unitPrice }.distinct().size != 1) return Read("QUOTE_SCOPE_SPLIT_REQUIRED")
        if (job.settings.billingMode != if (kind == "ITEM") "TASK" else "HOURLY") return Read("QUOTE_JOB_MODE_MISMATCH")
        val coverage = try { InvoiceCoverageStore.preview(db, ReportStore.read(db, request.selection)) }
            catch (_: IllegalArgumentException) { return Read("INVALID_REPORT_RANGE") }
        val byTask = bindings.associateBy { it.taskId }
        val tasks = if (kind == "ITEM") coverage.report.tasks.mapNotNull { task ->
            byTask[task.id]?.let { Task(task, requireNotNull(byId[it.lineId])) }
        } else emptyList()
        val actualTasks = tasks.map { it.actual }
        val actualIntervals = if (kind == "HOURS") coverage.report.intervals else emptyList()
        val duration = actualIntervals.fold(BigInteger.ZERO) { sum, interval -> sum.add(BigInteger.valueOf(interval.end - interval.start)) }
        val missing = actualTasks.count { it.priceMinor == null || it.currency != agreement.currency }
        val currentAmount = if (kind == "HOURS") job.effectiveHourlyMinor?.let { ReportStore.hourlyAmount(duration, it) }
            else if (missing > 0) null else actualTasks.fold(BigInteger.ZERO) { sum, task -> sum.add(TaskQuantityStore.amount(requireNotNull(task.priceMinor), task.quantityUnits)) }
        val currentTotal = coverage.report.totals.single().copy(durationMillis = duration, amountMinor = currentAmount, missingPrices = missing)
        val eligible = coverage.report.copy(tasks = actualTasks, intervals = actualIntervals, points = emptyList(), totals = listOf(currentTotal))
        if (actualTasks.size > 2000 || actualIntervals.size > 5000) return Read("INVOICE_TOO_LARGE")
        val context = Context(issued, agreement, digits, job, coverage.copy(report = eligible), tasks, lines,
            if (kind == "HOURS") MoneyMath.parse(lines.first().unitPrice) else null,
            QuoteStore.latest(db, request.quoteId)?.revision != request.revision)
        return Read(null, context)
    }

    /** Must be re-evaluated inside the invoice command transaction before any write. */
    fun review(db: SQLiteDatabase, request: Request, choices: Choices): Review {
        if (!choices.confirmed || choices.note.length > 2000 || '\u0000' in choices.note ||
            choices.taskIds.size > 2000 || choices.times.size > 5000 ||
            choices.taskIds.distinct().size != choices.taskIds.size || choices.times.distinct().size != choices.times.size) return Review("INVALID_QUOTE_INVOICE")
        val read = read(db, request)
        read.problem?.let { return Review(it) }
        val context = requireNotNull(read.context)
        if (context.historical && !choices.historicalReviewed) return Review("QUOTE_HISTORICAL_REVIEW_REQUIRED")
        if (context.coverage.legacy.isNotEmpty()) return Review("LEGACY_INVOICE_REVIEW_REQUIRED")
        val taskMode = context.job.settings.billingMode == "TASK"
        if (taskMode && choices.times.isNotEmpty() || !taskMode && choices.taskIds.isNotEmpty()) return Review("INVALID_QUOTE_INVOICE_SOURCES")
        val byTask = context.tasks.associateBy { it.actual.id }
        if (choices.taskIds.any { it !in byTask }) return Review("QUOTE_INVOICE_SOURCES_CHANGED")
        val times = context.coverage.report.intervals.associateBy { Time(it.sessionId, it.start, it.end) }
        if (choices.times.any { it !in times }) return Review("QUOTE_INVOICE_SOURCES_CHANGED")
        if (choices.taskIds.isEmpty() && choices.times.isEmpty()) return Review("NO_BILLABLE_ITEMS")
        val tasks = choices.taskIds.map { id -> val selected = requireNotNull(byTask[id])
            selected.actual.copy(priceMinor = if (choices.currentPrices) selected.actual.priceMinor else MoneyMath.parse(selected.accepted.unitPrice))
        }
        val intervals = choices.times.map { requireNotNull(times[it]) }
        val rate = if (choices.currentPrices) context.job.effectiveHourlyMinor else context.acceptedRate
        if (!taskMode && rate == null) return Review("HOURLY_RATE_REQUIRED")
        if (taskMode && tasks.any { it.priceMinor == null || it.currency != context.agreement.currency }) return Review("TASK_PRICES_REQUIRED")
        val duration = intervals.fold(BigInteger.ZERO) { sum, interval -> sum.add(BigInteger.valueOf(interval.end - interval.start)) }
        val amount = if (taskMode) tasks.fold(BigInteger.ZERO) { sum, task -> sum.add(TaskQuantityStore.amount(requireNotNull(task.priceMinor), task.quantityUnits)) }
            else ReportStore.hourlyAmount(duration, requireNotNull(rate))
        val total = context.coverage.report.totals.single().copy(durationMillis = duration, hourlyMinor = rate, amountMinor = amount, missingPrices = 0)
        val report = context.coverage.report.copy(tasks = tasks, intervals = intervals, totals = listOf(total))
        InvoiceStore.coverageProblem(context.coverage.copy(report = report))?.let { return Review(it) }
        return Review(null, Reviewed(context, report, if (choices.currentTax) context.job.effectiveTaxUnits else context.agreement.taxUnits,
            choices, acceptedParty = !choices.currentParty))
    }
}
