package io.github.centerionware.clockrove.tracking

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.Currency

/** Strict transport codec. All prices cross the channel as decimal integer strings. */
internal class BusinessHostApi(context: Context, messenger: BinaryMessenger) {
    private val app = context.applicationContext
    private val repository = TimerRepository.get(app)
    private val channel = MethodChannel(messenger, "clockrove/business/v1", io.flutter.plugin.common.StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())
    init {
        channel.setMethodCallHandler { call, result ->
            try {
                val response = BusinessDispatch(repository) { token -> io.github.centerionware.clockrove.invoice.LogoImages.recall(app, token) }.call(call)
                if (response == null) result.notImplemented() else {
                    if (response is TimerRepository.Outcome) {
                        TimerWidget.refreshAll(app)
                        result.success(mapOf("version" to response.version, "conflict" to response.conflict))
                    } else result.success(response)
                }
            } catch (_: IllegalArgumentException) { result.error("INVALID_ARGUMENT", "Invalid business request", null) }
            catch (_: ClassCastException) { result.error("INVALID_ARGUMENT", "Invalid business request", null) }
            catch (_: Exception) { result.error("STORAGE_UNAVAILABLE", "Business storage is unavailable", null) }
        }
    }
    fun detach() { channel.setMethodCallHandler(null) }
}

internal class BusinessDispatch(private val repository: TimerRepository, private val logoLoader: (String) -> LogoStore.Asset? = { null }) {
    private class Input(val value: Map<*, *>) {
        fun string(key: String) = requireNotNull(value[key] as? String) { "Missing $key" }
        fun nullable(key: String): String? { val v = value[key]; require(v == null || v is String); return v as String? }
        fun integer(key: String): Long { val v = value[key]; require(v is Int || v is Long); return (v as Number).toLong() }
        fun int(key: String): Int { val v = integer(key); require(v in Int.MIN_VALUE..Int.MAX_VALUE); return v.toInt() }
        fun bool(key: String) = requireNotNull(value[key] as? Boolean)
        fun money(key: String): java.math.BigInteger? = nullable(key)?.let(MoneyMath::parse)
        fun quantity(): Long = if (!value.containsKey("quantityUnits")) TaskQuantityStore.one else requireNotNull(money("quantityUnits")).let { require(it.signum() > 0 && it <= java.math.BigInteger.valueOf(Long.MAX_VALUE)); it.toLong() }
        fun strings(key: String): List<String> = requireNotNull(value[key] as? List<*>).map { requireNotNull(it as? String) }
    }
    private fun historyEntry(entry: SessionHistoryStore.Entry) = mapOf(
        "id" to entry.id, "jobId" to entry.jobId, "start" to entry.start, "end" to entry.end, "timezone" to entry.timezone,
        "offsetSeconds" to entry.offsetSeconds, "origin" to entry.origin, "needsReview" to entry.needsReview)
    private fun historyRevision(value: SessionHistoryStore.Revision) = mapOf("id" to value.id, "changed" to value.changed,
        "origin" to value.origin, "deleted" to value.deleted, "payload" to value.payload, "parents" to value.parents)
    fun call(call: MethodCall): Any? {
        val input = Input((call.arguments ?: emptyMap<String, Any?>()) as Map<*, *>)
        when (call.method) {
            "currencyPrecision" -> {
                val currency = input.string("currency"); require(BusinessStore.validCurrency(currency))
                return mapOf("currency" to currency, "digits" to repository.quoteCurrencyDigits(input.nullable("quoteId"), currency))
            }
            "quotes" -> {
                val raw = input.value["before"]; require(raw == null || raw is Map<*, *>)
                val before = (raw as? Map<*, *>)?.let { Input(it).let { cursor -> QuoteStore.Cursor(cursor.integer("created"), cursor.string("id")) } }
                val state = repository.quotes(before, if (input.value.containsKey("limit")) input.int("limit") else 20, input.nullable("status"))
                return mapOf("version" to state.version, "today" to state.today, "timezone" to state.timezone,
                    "next" to state.page.next?.let { mapOf("created" to it.created, "id" to it.id) },
                    "entries" to state.page.entries.map { entry -> mapOf("quoteId" to entry.id, "number" to entry.number,
                        "created" to entry.created, "title" to entry.title, "currency" to entry.currency, "total" to entry.total,
                        "validUntil" to entry.validUntil, "revision" to entry.revision, "status" to entry.status,
                        "hasDraft" to entry.hasDraft, "jobId" to entry.jobId, "customer" to entry.customer) })
            }
            "quoteRevision" -> return repository.quoteRevision(input.string("quoteId"), input.int("revision"))?.let(::quoteRevision)
                ?: mapOf("unavailable" to true)
            "quoteHistory" -> return repository.quoteHistory(input.string("quoteId"), if (input.value["before"] == null) null else input.int("before"),
                if (input.value.containsKey("limit")) input.int("limit") else 20).map { mapOf("revision" to it.revision, "created" to it.created, "decision" to it.decision) }
            "quoteScope" -> return repository.quoteScope(input.string("quoteId")).let { (version, bindings) ->
                mapOf("version" to version, "bindings" to bindings.map { mapOf("lineId" to it.lineId, "jobId" to it.jobId,
                    "taskId" to it.taskId, "kind" to it.kind, "originalRevision" to it.originalRevision) })
            }
            "quoteContext" -> return repository.quote(input.string("quoteId")).let { state ->
                mapOf("version" to state.version, "draftJson" to state.draft?.encode(), "draftDigits" to state.draftDigits, "today" to state.today, "timezone" to state.timezone,
                    "draftCustomer" to state.draftCustomer, "draftEmail" to state.draftEmail, "draftCompany" to state.draftCompany, "draftStyle" to state.draftStyle,
                    "latest" to state.latest?.let(::quoteRevision))
            }
            "resolveSessionTime" -> return SessionTime.resolve(input.string("localTime"), input.string("timezone")).map {
                mapOf("instant" to it.instant, "offsetSeconds" to it.offsetSeconds, "label" to it.label)
            }
            "formatSessionTime" -> return SessionTime.format(input.integer("instant"), input.string("timezone")).let {
                mapOf("localTime" to it.localTime, "offsetSeconds" to it.offsetSeconds, "label" to it.label)
            }
            "historyContext" -> return mapOf("timezone" to java.util.TimeZone.getDefault().id, "now" to System.currentTimeMillis())
            "sessionDetail" -> return repository.sessionDetail(input.string("jobId"), input.string("sessionId")).let {
                mapOf("version" to it.version, "entry" to it.entry?.let(::historyEntry))
            }
            "sessionAudit" -> {
                val before = if (input.value["before"] == null) null else input.integer("before")
                val result = repository.sessionAudit(input.string("jobId"), before, if (input.value.containsKey("limit")) input.int("limit") else 100)
                return mapOf("version" to result.version, "next" to result.page.next, "events" to result.page.events.map {
                    mapOf("sequence" to it.sequence, "sessionId" to it.sessionId, "revision" to historyRevision(it.revision))
                })
            }
            "sessionHistory" -> {
                val raw = input.value["before"]; require(raw == null || raw is Map<*, *>)
                val before = (raw as Map<*, *>?)?.let { Input(it).let { cursor -> SessionHistoryStore.Cursor(cursor.integer("start"), cursor.string("id")) } }
                val snapshot = repository.sessionHistory(input.string("jobId"), before, if (input.value.containsKey("limit")) input.int("limit") else 200)
                return mapOf("version" to snapshot.version, "entries" to snapshot.page.entries.map { entry -> mapOf(
                    "id" to entry.id, "jobId" to entry.jobId, "start" to entry.start, "end" to entry.end, "timezone" to entry.timezone,
                    "offsetSeconds" to entry.offsetSeconds, "origin" to entry.origin, "needsReview" to entry.needsReview) },
                    "next" to snapshot.page.next?.let { mapOf("start" to it.start, "id" to it.id) })
            }
            "sessionRevisions" -> return repository.sessionRevisions(input.string("jobId"), input.string("sessionId")).map(::historyRevision)
            "board" -> { val s = repository.boardSnapshot(input.string("jobId")); return mapOf("version" to s.version, "jobId" to s.board.jobId, "name" to s.board.name,
                "currency" to s.board.currency, "fractionDigits" to Currency.getInstance(s.board.currency).defaultFractionDigits, "archived" to s.board.archived,
                "columns" to s.board.columns.map { mapOf("id" to it.id, "name" to it.name, "position" to it.position, "completed" to it.completed) },
                "cards" to s.board.cards.map { mapOf("id" to it.id, "columnId" to it.columnId, "title" to it.title, "description" to it.description, "dueDate" to it.dueDate,
                    "position" to it.position, "completedMillis" to it.completedMillis, "billable" to it.billable, "priceMinor" to it.priceMinor?.toString(), "quantityUnits" to it.quantityUnits.toString(), "currency" to it.currency) }) }
            "templates" -> return repository.taskTemplates(input.string("search")).map { mapOf("id" to it.id, "title" to it.title, "description" to it.description,
                "priceMinor" to it.priceMinor?.toString(), "quantityUnits" to it.quantityUnits.toString(), "currency" to it.currency, "sourceJobName" to it.sourceJobName) }
            "invoices" -> { val s = repository.invoices(input.nullable("jobId")); return mapOf("version" to s.version, "invoices" to s.invoices.map { mapOf("id" to it.id, "number" to it.number, "created" to it.created, "total" to it.total, "currency" to it.currency, "style" to it.style,
                "jobId" to it.jobId, "jobName" to it.jobName, "customer" to it.customer, "jobDeleted" to it.jobDeleted, "legacy" to it.legacy, "paidMinor" to it.paidMinor, "dueMinor" to it.dueMinor, "status" to it.status, "digits" to it.digits,
                "createdLabel" to ReportCsv.instant(it.created, java.util.TimeZone.getDefault().id)) }) }
            "invoicePayments" -> return invoicePayments(repository.invoicePaymentContext(input.string("invoiceId")))
            "invoiceLifecycleContext" -> return repository.invoiceLifecycleContext(input.string("invoiceId")).let { state ->
                mapOf("invoice" to invoicePayments(state.payment), "taxUnits" to state.payment.ledger.document.taxRateUnits,
                    "lines" to state.payment.ledger.document.lines.mapIndexed { index, line -> mapOf("sourceIndex" to index,
                        "title" to line.title, "detail" to line.detail, "amountMinor" to line.amount.takeIf { it.isNotBlank() }?.let { PaymentMath.parseMajor(it, state.payment.ledger.document.digits).toString() },
                        "quantityUnits" to line.quantityUnits, "unitPriceMinor" to line.unitPriceMinor) },
                    "claims" to state.claims.map { claim -> mapOf("id" to claim.id, "taskId" to claim.taskId, "sessionId" to claim.sessionId,
                        "start" to claim.start, "end" to claim.end, "timezone" to claim.timezone,
                        "label" to if (claim.taskId != null) claim.label else "${ReportCsv.instant(requireNotNull(claim.start), requireNotNull(claim.timezone))} — ${ReportCsv.instant(requireNotNull(claim.end), requireNotNull(claim.timezone))} · ${claim.timezone}") })
            }
            "invoiceBillingPreview" -> return invoiceBillingPreview(repository.invoiceBillingPreview(ReportStore.Selection(input.string("firstDate"), input.string("lastDate"), input.string("timezone"), input.strings("jobIds"))))
            "legacyInvoiceContext" -> return legacyInvoiceContext(repository.legacyInvoiceContext(input.string("invoiceId")))
            "archivedJobs" -> { val s = repository.archiveSnapshot(); return mapOf("version" to s.version, "jobs" to s.jobs.map { mapOf("id" to it.id, "name" to it.name, "archivedMillis" to it.archivedMillis, "canDelete" to it.canDelete) }) }
            "reportContext" -> return mapOf("timezone" to java.util.TimeZone.getDefault().id, "jobs" to repository.reportChoices().map { mapOf("id" to it.first, "name" to it.second, "archived" to it.third) })
            "companyLogo" -> { val s = repository.logo(); return mapOf("version" to s.version, "id" to s.id, "width" to s.width, "height" to s.height, "png" to s.bytes) }
            "business" -> { val s = repository.businessSnapshot(); return mapOf("version" to s.version, "settings" to settings(s.settings), "customers" to s.customers.map(::customer)) }
            "job" -> { val s = repository.jobDetail(input.string("jobId")); return mapOf("version" to s.version, "settings" to job(s.job.settings), "archived" to s.job.archived,
                "archivedMillis" to s.job.archivedMillis, "canDelete" to s.canDelete, "customer" to s.job.customer?.let(::customer), "effectiveEmail" to s.job.effectiveEmail, "effectiveHourlyMinor" to s.job.effectiveHourlyMinor?.toString(), "effectiveTaxUnits" to s.job.effectiveTaxUnits) }
            "report" -> { val s = repository.report(ReportStore.Selection(input.string("firstDate"), input.string("lastDate"), input.string("timezone"), input.strings("jobIds"))); return report(s) }
        }
        val supported = setOf("applyQuoteScope", "saveQuote", "issueQuote", "decideQuote", "saveColumn", "reorderColumns", "removeColumn", "saveCard", "moveCard", "removeCard", "copyTemplate", "removeTemplate", "saveSettings", "saveCustomer", "removeCustomer", "saveJob", "archiveJob", "restoreJob", "deleteJob", "prepareInvoice", "reconcileInvoiceCoverage", "applyLogo", "removeLogo", "createManualSession", "correctSession", "deleteSession", "recordInvoicePayment", "reverseInvoicePayment", "prepareInvoiceBalanceCopy", "voidInvoice", "replaceInvoice")
        if (call.method !in supported) return null
        val id = input.string("id"); val version = input.integer("version"); require(version >= 0)
        val v = Input(requireNotNull(input.value["value"] as? Map<*, *>))
        return when (call.method) {
            "applyQuoteScope" -> {
                val rows = requireNotNull(v.value["items"] as? List<*>); require(rows.size in 1..2000)
                val items = rows.map { row -> Input(requireNotNull(row as? Map<*, *>)).let { item ->
                    QuoteScopeStore.Item(item.string("lineId"), item.string("taskId"), item.string("title"), item.bool("existing"))
                } }
                repository.applyQuoteScope(id, version, QuoteScopeStore.Draft(v.string("quoteId"), v.int("revision"), v.string("jobId"), items,
                    v.string("note"), v.bool("confirmed"), v.nullable("newJobName"), v.bool("historicalReviewed"), v.bool("jobDifferencesReviewed")))
            }
            "saveQuote" -> {
                val rows = requireNotNull(v.value["lines"] as? List<*>); require(rows.size in 1..2000)
                val lines = rows.map { row -> Input(requireNotNull(row as? Map<*, *>)).let { line ->
                    QuoteStore.Line(line.string("lineId"), line.string("title"), line.string("detail"), line.string("kind"), line.string("quantityUnits"), line.string("unitPriceMinor"))
                } }
                repository.saveQuote(id, version, QuoteStore.Draft(v.string("quoteId"), v.nullable("customerId"), v.nullable("jobId"),
                    v.string("title"), v.string("currency"), v.int("taxUnits"), lines, v.nullable("validUntil"), v.string("terms"), v.string("notes")))
            }
            "issueQuote" -> repository.issueQuote(id, version, v.string("quoteId"), v.bool("confirmed"))
            "decideQuote" -> repository.decideQuote(id, version, v.string("quoteId"), v.int("revision"), v.string("decision"), v.string("note"), v.bool("confirmed"))
            "voidInvoice" -> repository.voidInvoice(id, version, InvoiceLifecycleStore.VoidDraft(v.string("invoiceId"), v.string("reason"), v.string("timezone"), v.bool("confirmed")))
            "replaceInvoice" -> {
                val lines = requireNotNull(v.value["lines"] as? List<*>).map { entry ->
                    Input(requireNotNull(entry as? Map<*, *>)).let { line -> InvoiceLifecycleStore.LineEdit(line.int("sourceIndex"), line.string("title"), line.string("detail"), line.nullable("amountMinor"), line.nullable("quantityUnits"), line.nullable("unitPriceMinor")) }
                }
                val added = v.value["addedWork"].let { raw ->
                    require(raw == null || raw is Map<*, *>)
                    (raw as? Map<*, *>)?.let { Input(it).let { selection -> ReportStore.Selection(selection.string("firstDate"), selection.string("lastDate"), selection.string("timezone"), selection.strings("jobIds")) } }
                }
                repository.replaceInvoice(id, version, InvoiceLifecycleStore.ReplacementDraft(v.string("invoiceId"), v.string("replacementId"), lines,
                    v.int("taxUnits"), v.string("reason"), v.string("timezone"), v.bool("confirmed"), v.strings("releaseClaimIds"), added))
            }
            "createManualSession", "correctSession" -> {
                val draft = SessionHistoryStore.Draft(v.string("sessionId"), v.string("jobId"), v.integer("start"), v.integer("end"), v.string("timezone"), v.int("offsetSeconds"))
                if (call.method == "createManualSession") repository.createManualSession(id, version, draft) else repository.correctSession(id, version, draft)
            }
            "deleteSession" -> repository.deleteSession(id, version, v.string("sessionId"))
            "saveColumn" -> repository.saveColumn(id, version, v.string("columnId"), v.string("jobId"), v.string("name"), v.bool("completed"))
            "reorderColumns" -> repository.reorderColumns(id, version, v.string("jobId"), v.strings("columnIds"))
            "removeColumn" -> repository.removeColumn(id, version, v.string("jobId"), v.string("columnId"), v.nullable("destinationId"))
            "saveCard" -> repository.saveCard(id, version, BoardStore.Draft(v.string("cardId"), v.string("jobId"), v.string("columnId"), v.string("title"), v.string("description"), v.nullable("dueDate"), v.bool("billable"), v.money("priceMinor"), v.string("currency"), v.quantity()))
            "moveCard" -> repository.moveCard(id, version, v.string("jobId"), v.string("cardId"), v.string("destinationId"), v.int("position"))
            "removeCard" -> repository.removeCard(id, version, v.string("jobId"), v.string("cardId"))
            "copyTemplate" -> repository.copyTaskTemplate(id, version, v.string("templateId"), v.string("cardId"), v.string("jobId"), v.string("columnId"), v.bool("copyPrice"))
            "removeTemplate" -> repository.removeTaskTemplate(id, version, v.string("templateId"))
            "saveSettings" -> repository.saveBusinessSettings(id, version, BusinessStore.Settings(v.string("currency"), v.money("defaultHourlyMinor"), v.string("companyName"), v.string("companyEmail"), v.string("companyPhone"), v.string("companyAddress"), v.string("companyWebsite"), v.string("invoiceHeader"), v.string("invoiceFooter"), v.string("invoiceStyle"), v.string("emailSubject"), v.string("emailBody"), if (v.value.containsKey("defaultTaxUnits")) v.int("defaultTaxUnits") else 0, if (v.value.containsKey("receivablesEmail")) v.string("receivablesEmail") else ""))
            "saveCustomer" -> repository.saveCustomer(id, version, BusinessStore.Customer(v.string("customerId"), v.string("name"), v.string("email"), v.string("phone"), v.string("address"), v.string("notes"), v.bool("archived")))
            "removeCustomer" -> repository.removeCustomer(id, version, v.string("customerId"))
            "archiveJob" -> repository.archiveJob(id, version, v.string("jobId"))
            "restoreJob" -> repository.restoreJob(id, version, v.string("jobId"))
            "deleteJob" -> repository.deleteJob(id, version, v.string("jobId"))
            "applyLogo" -> repository.applyLogo(id, version, v.string("token")) { logoLoader(v.string("token")) }
            "removeLogo" -> repository.removeLogo(id, version)
            "recordInvoicePayment" -> repository.recordInvoicePayment(id, version, InvoicePaymentStore.Draft(v.string("paymentId"), v.string("invoiceId"), v.string("amountMinor"), v.string("currency"), v.integer("received"), v.string("timezone"), v.string("method"), v.string("reference"), v.string("note")))
            "reverseInvoicePayment" -> repository.reverseInvoicePayment(id, version, InvoicePaymentStore.Reversal(v.string("reversalId"), v.string("invoiceId"), v.string("paymentId"), v.integer("effective"), v.string("timezone"), v.string("note")))
            "prepareInvoiceBalanceCopy" -> repository.prepareInvoiceBalanceCopy(id, version, v.string("copyToken"), v.string("invoiceId"))
            "prepareInvoice" -> repository.prepareInvoice(id, version, v.string("invoiceId"), ReportStore.Selection(v.string("firstDate"), v.string("lastDate"), v.string("timezone"), v.strings("jobIds")))
            "reconcileInvoiceCoverage" -> {
                val raw = requireNotNull(v.value["spans"] as? List<*>); require(raw.size <= 1000)
                val spans = raw.map { entry -> Input(requireNotNull(entry as? Map<*, *>)).let { span -> LegacyInvoiceStore.Span(span.integer("start"), span.integer("end"), span.string("timezone")) } }
                repository.reconcileInvoiceCoverage(id, version, LegacyInvoiceStore.Draft(v.string("invoiceId"), v.strings("taskIds"), spans, v.string("note"), v.bool("confirmed"), v.bool("noSources")))
            }
            "saveJob" -> repository.saveJobSettings(id, version, BusinessStore.JobSettings(v.string("jobId"), v.string("name"), v.integer("colorArgb"), v.string("billingMode"), v.string("currency"), v.money("hourlyRateMinor"), v.nullable("customerId"), v.nullable("customerEmailOverride"), if (v.value["taxUnits"] == null) null else v.int("taxUnits")))
            else -> error("Unreachable")
        }
    }
    private fun quoteRevision(revision: QuoteStore.Revision): Map<String, Any?> = mapOf("quoteId" to revision.quoteId,
        "revision" to revision.revision, "number" to revision.number, "payload" to revision.payload,
        "created" to revision.created, "decision" to revision.decision, "decisionNote" to revision.decisionNote)
    private fun invoicePayments(context: TimerRepository.InvoicePaymentContext): Map<String, Any?> {
        val ledger = context.ledger; val document = ledger.document
        return mapOf("version" to context.version, "invoiceId" to ledger.invoice.id, "number" to document.number,
            "jobId" to ledger.invoice.jobId, "jobName" to document.job, "customer" to document.customer,
            "currency" to document.currency, "digits" to document.digits, "totalMinor" to ledger.balance.total.toString(),
            "paidMinor" to ledger.balance.paid.toString(), "dueMinor" to ledger.balance.due.toString(), "status" to ledger.balance.status,
            "collectedTaxMinor" to ledger.collectedTax?.toString(),
            "replacesInvoiceId" to context.replacesInvoiceId,
            "void" to context.voidInfo?.let { mapOf("replacementId" to it.replacementId, "creditedMinor" to it.credited, "cancelledMinor" to it.cancelled,
                "created" to it.at, "createdLabel" to ReportCsv.instant(it.at, it.timezone), "timezone" to it.timezone, "reason" to it.reason) }, "legacy" to context.legacy, "jobDeleted" to context.jobDeleted,
            "accountingEmail" to context.accountingEmail, "customerEmail" to document.email,
            "now" to context.now, "timezone" to context.timezone, "localNow" to context.localNow.localTime,
            "nowOffsetSeconds" to context.localNow.offsetSeconds, "nowLabel" to context.localNow.label,
            "payments" to ledger.payments.map { mapOf("id" to it.id, "originalInvoiceId" to it.originalInvoiceId, "currentInvoiceId" to it.currentInvoiceId,
                "amountMinor" to it.amount.toString(), "currency" to it.currency, "received" to it.received, "timezone" to it.timezone,
                "offsetSeconds" to it.offsetSeconds, "receivedLabel" to it.receivedLabel, "method" to it.method, "reference" to it.reference,
                "note" to it.note, "recorded" to it.recorded, "reversalId" to it.reversalId, "reversalNote" to it.reversalNote) },
            "copies" to context.copies.map { mapOf("id" to it.id, "kind" to it.kind, "created" to it.created,
                "createdLabel" to ReportCsv.instant(it.created, context.timezone), "paidMinor" to it.paid, "dueMinor" to it.due) })
    }
    private fun settings(v: BusinessStore.Settings): Map<String, Any?> = mapOf("currency" to v.currency, "fractionDigits" to Currency.getInstance(v.currency).defaultFractionDigits,
        "defaultHourlyMinor" to v.defaultHourlyMinor?.toString(), "companyName" to v.companyName, "companyEmail" to v.companyEmail, "companyPhone" to v.companyPhone,
        "companyAddress" to v.companyAddress, "companyWebsite" to v.companyWebsite, "invoiceHeader" to v.invoiceHeader, "invoiceFooter" to v.invoiceFooter,
        "invoiceStyle" to v.invoiceStyle, "emailSubject" to v.emailSubject, "emailBody" to v.emailBody, "defaultTaxUnits" to v.defaultTaxUnits, "receivablesEmail" to v.receivablesEmail)
    private fun customer(v: BusinessStore.Customer): Map<String, Any?> = mapOf("customerId" to v.id, "name" to v.name, "email" to v.email, "phone" to v.phone, "address" to v.address, "notes" to v.notes, "archived" to v.archived)
    private fun job(v: BusinessStore.JobSettings): Map<String, Any?> = mapOf("jobId" to v.id, "name" to v.name, "colorArgb" to v.colorArgb, "billingMode" to v.billingMode,
        "currency" to v.currency, "fractionDigits" to Currency.getInstance(v.currency).defaultFractionDigits, "hourlyRateMinor" to v.hourlyRateMinor?.toString(), "customerId" to v.customerId, "customerEmailOverride" to v.customerEmailOverride, "taxUnits" to v.taxUnits)
    private fun invoiceBillingPreview(s: TimerRepository.InvoiceBillingPreview): Map<String, Any?> {
        val preview = s.preview; val report = preview.report; val total = report.totals.single()
        val digits = Currency.getInstance(total.currency).defaultFractionDigits
        val subtotal = total.amountMinor?.takeIf { preview.legacy.isEmpty() }
        val tax = subtotal?.takeIf { TaxRate.valid(s.taxUnits) }?.let { TaxRate.amount(it, s.taxUnits) }
        val problem = if (!TaxRate.valid(s.taxUnits)) "INVALID_TAX_RATE" else InvoiceStore.coverageProblem(preview)
        return mapOf("version" to s.version, "jobId" to total.jobId, "jobName" to total.jobName, "currency" to total.currency,
            "taxUnits" to s.taxUnits, "subtotal" to subtotal?.let { InvoiceStore.amount(it, digits) },
            "tax" to tax?.let { InvoiceStore.amount(it, digits) }, "total" to tax?.let { InvoiceStore.amount(requireNotNull(subtotal).add(it), digits) },
            "problem" to problem, "mode" to total.mode,
            "lines" to (if (problem == "INVOICE_TOO_LARGE") emptyList() else InvoiceStore.billableLines(report)).map { mapOf("title" to it.title, "detail" to it.detail, "amount" to it.amount) },
            "excluded" to preview.excluded.map { mapOf("invoiceId" to it.invoiceId, "number" to it.number, "title" to it.title,
                "detail" to if (it.taskId != null) "Previously invoiced task" else "${ReportCsv.instant(requireNotNull(it.start), report.selection.timezone)} — ${ReportCsv.instant(requireNotNull(it.end), report.selection.timezone)}") },
            "legacy" to preview.legacy.map { mapOf("invoiceId" to it.first, "number" to it.second) })
    }
    private fun legacyInvoiceContext(s: TimerRepository.LegacyInvoiceContext): Map<String, Any?> {
        val c = s.context; val zone = java.util.TimeZone.getDefault().id
        return mapOf("version" to s.version, "invoiceId" to c.invoiceId, "number" to c.number, "jobId" to c.jobId,
            "jobName" to c.jobName, "total" to c.total, "currency" to c.currency, "period" to c.period, "legacy" to c.legacy,
            "jobDeleted" to c.jobDeleted, "note" to c.note, "reviewed" to c.reviewed, "timezone" to zone,
            "tasks" to c.tasks.map { mapOf("id" to it.id, "title" to it.title,
                "completedLabel" to it.completed?.let { stamp -> ReportCsv.instant(stamp, zone) }, "invoiceId" to it.invoiceId, "number" to it.number) },
            "reservedTimes" to c.reservedTimes.map { mapOf("start" to it.start, "end" to it.end, "timezone" to it.timezone,
                "startLabel" to ReportCsv.instant(it.start, it.timezone), "endLabel" to ReportCsv.instant(it.end, it.timezone), "invoiceId" to it.invoiceId, "number" to it.number) })
    }
    private fun report(s: TimerRepository.ReportSnapshot): Map<String, Any?> {
        val r = s.report
        return mapOf("version" to s.version, "timezone" to r.selection.timezone, "workCsv" to ReportCsv.work(r), "gpsCsv" to ReportCsv.gps(r),
            "days" to r.days.map { mapOf("date" to it.date, "start" to it.start, "end" to it.end) },
            "intervals" to r.intervals.map { mapOf("sessionId" to it.sessionId, "jobId" to it.jobId, "jobName" to it.jobName, "date" to it.date, "start" to it.start, "end" to it.end, "sourceStart" to it.sourceStart, "sourceEnd" to it.sourceEnd, "origin" to it.origin, "startLabel" to ReportCsv.instant(it.start, r.selection.timezone), "endLabel" to ReportCsv.instant(it.end, r.selection.timezone)) },
            "excluded" to r.excluded.map { mapOf("sessionId" to it.sessionId, "jobId" to it.jobId, "start" to it.start, "end" to it.end, "reason" to it.reason, "startLabel" to ReportCsv.instant(it.start, r.selection.timezone)) },
            "points" to r.points.map { mapOf("id" to it.id, "sessionId" to it.sessionId, "jobId" to it.jobId, "jobName" to it.jobName, "measured" to it.measured, "latitude" to it.latitude, "longitude" to it.longitude, "accuracy" to it.accuracy, "measuredLabel" to ReportCsv.instant(it.measured, r.selection.timezone)) },
            "tasks" to r.tasks.map { mapOf("id" to it.id, "jobId" to it.jobId, "title" to it.title, "completed" to it.completed, "priceMinor" to it.priceMinor?.toString(), "quantityUnits" to it.quantityUnits.toString(), "currency" to it.currency, "completedLabel" to ReportCsv.instant(it.completed, r.selection.timezone)) },
            "totals" to r.totals.map { mapOf("jobId" to it.jobId, "jobName" to it.jobName, "mode" to it.mode, "currency" to it.currency, "fractionDigits" to Currency.getInstance(it.currency).defaultFractionDigits,
                "durationMillis" to it.durationMillis.toString(), "hourlyMinor" to it.hourlyMinor?.toString(), "amountMinor" to it.amountMinor?.toString(), "missingPrices" to it.missingPrices) })
    }
}
