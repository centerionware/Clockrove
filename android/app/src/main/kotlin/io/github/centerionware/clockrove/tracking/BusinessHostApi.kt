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
                val response = BusinessDispatch(repository).call(call)
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

internal class BusinessDispatch(private val repository: TimerRepository) {
    private class Input(val value: Map<*, *>) {
        fun string(key: String) = requireNotNull(value[key] as? String) { "Missing $key" }
        fun nullable(key: String): String? { val v = value[key]; require(v == null || v is String); return v as String? }
        fun integer(key: String): Long { val v = value[key]; require(v is Int || v is Long); return (v as Number).toLong() }
        fun int(key: String): Int { val v = integer(key); require(v in Int.MIN_VALUE..Int.MAX_VALUE); return v.toInt() }
        fun bool(key: String) = requireNotNull(value[key] as? Boolean)
        fun money(key: String): Long? = nullable(key)?.let { require(it.matches(Regex("0|[1-9][0-9]{0,18}"))); requireNotNull(it.toLongOrNull()) }
        fun strings(key: String): List<String> = requireNotNull(value[key] as? List<*>).map { requireNotNull(it as? String) }
    }
    fun call(call: MethodCall): Any? {
        val input = Input((call.arguments ?: emptyMap<String, Any?>()) as Map<*, *>)
        when (call.method) {
            "resolveSessionTime" -> return SessionTime.resolve(input.string("localTime"), input.string("timezone")).map {
                mapOf("instant" to it.instant, "offsetSeconds" to it.offsetSeconds, "label" to it.label)
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
            "sessionRevisions" -> return repository.sessionRevisions(input.string("jobId"), input.string("sessionId")).map {
                mapOf("id" to it.id, "changed" to it.changed, "origin" to it.origin, "deleted" to it.deleted, "payload" to it.payload, "parents" to it.parents)
            }
            "board" -> { val s = repository.boardSnapshot(input.string("jobId")); return mapOf("version" to s.version, "jobId" to s.board.jobId, "name" to s.board.name,
                "currency" to s.board.currency, "fractionDigits" to Currency.getInstance(s.board.currency).defaultFractionDigits, "archived" to s.board.archived,
                "columns" to s.board.columns.map { mapOf("id" to it.id, "name" to it.name, "position" to it.position, "completed" to it.completed) },
                "cards" to s.board.cards.map { mapOf("id" to it.id, "columnId" to it.columnId, "title" to it.title, "description" to it.description, "dueDate" to it.dueDate,
                    "position" to it.position, "completedMillis" to it.completedMillis, "billable" to it.billable, "priceMinor" to it.priceMinor?.toString(), "currency" to it.currency) }) }
            "templates" -> return repository.taskTemplates(input.string("search")).map { mapOf("id" to it.id, "title" to it.title, "description" to it.description,
                "priceMinor" to it.priceMinor?.toString(), "currency" to it.currency, "sourceJobName" to it.sourceJobName) }
            "invoices" -> { val s = repository.invoices(input.string("jobId")); return mapOf("version" to s.version, "invoices" to s.invoices.map { mapOf("id" to it.id, "number" to it.number, "created" to it.created, "total" to it.total, "currency" to it.currency, "style" to it.style) }) }
            "archivedJobs" -> { val s = repository.archiveSnapshot(); return mapOf("version" to s.version, "jobs" to s.jobs.map { mapOf("id" to it.id, "name" to it.name, "archivedMillis" to it.archivedMillis, "canDelete" to it.canDelete) }) }
            "reportContext" -> return mapOf("timezone" to java.util.TimeZone.getDefault().id, "jobs" to repository.reportChoices().map { mapOf("id" to it.first, "name" to it.second, "archived" to it.third) })
            "business" -> { val s = repository.businessSnapshot(); return mapOf("version" to s.version, "settings" to settings(s.settings), "customers" to s.customers.map(::customer)) }
            "job" -> { val s = repository.jobDetail(input.string("jobId")); return mapOf("version" to s.version, "settings" to job(s.job.settings), "archived" to s.job.archived,
                "archivedMillis" to s.job.archivedMillis, "canDelete" to s.canDelete, "customer" to s.job.customer?.let(::customer), "effectiveEmail" to s.job.effectiveEmail, "effectiveHourlyMinor" to s.job.effectiveHourlyMinor?.toString()) }
            "report" -> { val s = repository.report(ReportStore.Selection(input.string("firstDate"), input.string("lastDate"), input.string("timezone"), input.strings("jobIds"))); return report(s) }
        }
        val supported = setOf("saveColumn", "reorderColumns", "removeColumn", "saveCard", "moveCard", "removeCard", "copyTemplate", "removeTemplate", "saveSettings", "saveCustomer", "removeCustomer", "saveJob", "archiveJob", "restoreJob", "deleteJob", "prepareInvoice", "createManualSession", "correctSession", "deleteSession")
        if (call.method !in supported) return null
        val id = input.string("id"); val version = input.integer("version"); require(version >= 0)
        val v = Input(requireNotNull(input.value["value"] as? Map<*, *>))
        return when (call.method) {
            "createManualSession", "correctSession" -> {
                val draft = SessionHistoryStore.Draft(v.string("sessionId"), v.string("jobId"), v.integer("start"), v.integer("end"), v.string("timezone"), v.int("offsetSeconds"))
                if (call.method == "createManualSession") repository.createManualSession(id, version, draft) else repository.correctSession(id, version, draft)
            }
            "deleteSession" -> repository.deleteSession(id, version, v.string("sessionId"))
            "saveColumn" -> repository.saveColumn(id, version, v.string("columnId"), v.string("jobId"), v.string("name"), v.bool("completed"))
            "reorderColumns" -> repository.reorderColumns(id, version, v.string("jobId"), v.strings("columnIds"))
            "removeColumn" -> repository.removeColumn(id, version, v.string("jobId"), v.string("columnId"), v.nullable("destinationId"))
            "saveCard" -> repository.saveCard(id, version, BoardStore.Draft(v.string("cardId"), v.string("jobId"), v.string("columnId"), v.string("title"), v.string("description"), v.nullable("dueDate"), v.bool("billable"), v.money("priceMinor"), v.string("currency")))
            "moveCard" -> repository.moveCard(id, version, v.string("jobId"), v.string("cardId"), v.string("destinationId"), v.int("position"))
            "removeCard" -> repository.removeCard(id, version, v.string("jobId"), v.string("cardId"))
            "copyTemplate" -> repository.copyTaskTemplate(id, version, v.string("templateId"), v.string("cardId"), v.string("jobId"), v.string("columnId"), v.bool("copyPrice"))
            "removeTemplate" -> repository.removeTaskTemplate(id, version, v.string("templateId"))
            "saveSettings" -> repository.saveBusinessSettings(id, version, BusinessStore.Settings(v.string("currency"), v.money("defaultHourlyMinor"), v.string("companyName"), v.string("companyEmail"), v.string("companyPhone"), v.string("companyAddress"), v.string("companyWebsite"), v.string("invoiceHeader"), v.string("invoiceFooter"), v.string("invoiceStyle"), v.string("emailSubject"), v.string("emailBody")))
            "saveCustomer" -> repository.saveCustomer(id, version, BusinessStore.Customer(v.string("customerId"), v.string("name"), v.string("email"), v.string("phone"), v.string("address"), v.string("notes"), v.bool("archived")))
            "removeCustomer" -> repository.removeCustomer(id, version, v.string("customerId"))
            "archiveJob" -> repository.archiveJob(id, version, v.string("jobId"))
            "restoreJob" -> repository.restoreJob(id, version, v.string("jobId"))
            "deleteJob" -> repository.deleteJob(id, version, v.string("jobId"))
            "prepareInvoice" -> repository.prepareInvoice(id, version, v.string("invoiceId"), ReportStore.Selection(v.string("firstDate"), v.string("lastDate"), v.string("timezone"), v.strings("jobIds")))
            "saveJob" -> repository.saveJobSettings(id, version, BusinessStore.JobSettings(v.string("jobId"), v.string("name"), v.integer("colorArgb"), v.string("billingMode"), v.string("currency"), v.money("hourlyRateMinor"), v.nullable("customerId"), v.nullable("customerEmailOverride")))
            else -> error("Unreachable")
        }
    }
    private fun settings(v: BusinessStore.Settings): Map<String, Any?> = mapOf("currency" to v.currency, "fractionDigits" to Currency.getInstance(v.currency).defaultFractionDigits,
        "defaultHourlyMinor" to v.defaultHourlyMinor?.toString(), "companyName" to v.companyName, "companyEmail" to v.companyEmail, "companyPhone" to v.companyPhone,
        "companyAddress" to v.companyAddress, "companyWebsite" to v.companyWebsite, "invoiceHeader" to v.invoiceHeader, "invoiceFooter" to v.invoiceFooter,
        "invoiceStyle" to v.invoiceStyle, "emailSubject" to v.emailSubject, "emailBody" to v.emailBody)
    private fun customer(v: BusinessStore.Customer): Map<String, Any?> = mapOf("customerId" to v.id, "name" to v.name, "email" to v.email, "phone" to v.phone, "address" to v.address, "notes" to v.notes, "archived" to v.archived)
    private fun job(v: BusinessStore.JobSettings): Map<String, Any?> = mapOf("jobId" to v.id, "name" to v.name, "colorArgb" to v.colorArgb, "billingMode" to v.billingMode,
        "currency" to v.currency, "fractionDigits" to Currency.getInstance(v.currency).defaultFractionDigits, "hourlyRateMinor" to v.hourlyRateMinor?.toString(), "customerId" to v.customerId, "customerEmailOverride" to v.customerEmailOverride)
    private fun report(s: TimerRepository.ReportSnapshot): Map<String, Any?> {
        val r = s.report
        return mapOf("version" to s.version, "timezone" to r.selection.timezone, "workCsv" to ReportCsv.work(r), "gpsCsv" to ReportCsv.gps(r),
            "days" to r.days.map { mapOf("date" to it.date, "start" to it.start, "end" to it.end) },
            "intervals" to r.intervals.map { mapOf("sessionId" to it.sessionId, "jobId" to it.jobId, "jobName" to it.jobName, "date" to it.date, "start" to it.start, "end" to it.end, "sourceStart" to it.sourceStart, "sourceEnd" to it.sourceEnd, "origin" to it.origin, "startLabel" to ReportCsv.instant(it.start, r.selection.timezone), "endLabel" to ReportCsv.instant(it.end, r.selection.timezone)) },
            "excluded" to r.excluded.map { mapOf("sessionId" to it.sessionId, "jobId" to it.jobId, "start" to it.start, "end" to it.end, "reason" to it.reason, "startLabel" to ReportCsv.instant(it.start, r.selection.timezone)) },
            "points" to r.points.map { mapOf("id" to it.id, "sessionId" to it.sessionId, "jobId" to it.jobId, "jobName" to it.jobName, "measured" to it.measured, "latitude" to it.latitude, "longitude" to it.longitude, "accuracy" to it.accuracy, "measuredLabel" to ReportCsv.instant(it.measured, r.selection.timezone)) },
            "tasks" to r.tasks.map { mapOf("id" to it.id, "jobId" to it.jobId, "title" to it.title, "completed" to it.completed, "priceMinor" to it.priceMinor?.toString(), "currency" to it.currency, "completedLabel" to ReportCsv.instant(it.completed, r.selection.timezone)) },
            "totals" to r.totals.map { mapOf("jobId" to it.jobId, "jobName" to it.jobName, "mode" to it.mode, "currency" to it.currency, "fractionDigits" to Currency.getInstance(it.currency).defaultFractionDigits,
                "durationMillis" to it.durationMillis.toString(), "hourlyMinor" to it.hourlyMinor?.toString(), "amountMinor" to it.amountMinor?.toString(), "missingPrices" to it.missingPrices) })
    }
}
