package io.github.centerionware.clockrove.invoice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.tracking.TimerRepository

internal class DocumentHostApi(private val activity: Activity, messenger: BinaryMessenger) {
    private val repository = TimerRepository.get(activity.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val channel = MethodChannel(messenger, "clockrove/documents/v1", io.flutter.plugin.common.StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())
    private data class Save(val invoiceId: String?, val bytes: ByteArray?, val result: MethodChannel.Result)
    private var save: Save? = null
    private var logoResult: MethodChannel.Result? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var attached = true
    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "pickLogo" -> main.post {
                        if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to choose a logo", null)
                        else if (save != null || logoResult != null) result.error("DOCUMENT_PICKER_OPEN", "Finish choosing a document first", null)
                        else {
                            logoResult = result
                            try { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                            }, 0xcc43) }
                            catch (_: ActivityNotFoundException) { logoResult = null; result.error("NO_DOCUMENT_PICKER", "No image document picker is installed", null) }
                        }
                    }
                    "invoicePreview" -> {
                        val id = requireNotNull(call.argument<String>("invoiceId"))
                        val index = requireNotNull(call.argument<Int>("page"))
                        result.success(InvoiceDocuments.preview(activity, InvoiceDocuments.ensure(repository, id), index))
                    }
                    "saveReport" -> {
                        val selection = io.github.centerionware.clockrove.tracking.ReportStore.Selection(requireNotNull(call.argument<String>("firstDate")), requireNotNull(call.argument<String>("lastDate")), requireNotNull(call.argument<String>("timezone")), requireNotNull(call.argument<List<*>>("jobIds")).map { requireNotNull(it as? String) })
                        val snapshot = repository.report(selection)
                        val rawVersion = call.argument<Any>("version"); require(rawVersion is Int || rawVersion is Long)
                        val expected = (rawVersion as Number).toLong()
                        if (snapshot.version != expected) { result.error("STALE_STATE", "Work changed. Refresh the report before exporting.", null); return@setMethodCallHandler }
                        val gps = requireNotNull(call.argument<Boolean>("gps"))
                        val bytes = (if (gps) io.github.centerionware.clockrove.tracking.ReportCsv.gps(snapshot.report) else io.github.centerionware.clockrove.tracking.ReportCsv.work(snapshot.report)).toByteArray(Charsets.UTF_8)
                        main.post {
                            if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your report", null)
                            else if (save != null || logoResult != null) result.error("DOCUMENT_PICKER_OPEN", "Finish choosing a destination first", null)
                            else {
                                save = Save(null, bytes, result)
                                try { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "Clockrove-${if (gps) "gps" else "work"}-${selection.firstDate}-${selection.lastDate}.csv")
                                }, 0xcc42) }
                                catch (_: ActivityNotFoundException) { save = null; result.error("NO_DOCUMENT_PICKER", "No document picker is installed", null) }
                            }
                        }
                    }
                    "saveInvoice" -> {
                        val id = requireNotNull(call.argument<String>("invoiceId"))
                        val record = InvoiceDocuments.ensure(repository, id)
                        main.post {
                            if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your invoice", null)
                            else if (save != null || logoResult != null) result.error("DOCUMENT_PICKER_OPEN", "Finish choosing a destination first", null)
                            else {
                                save = Save(id, null, result)
                                try { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, "${record.number}.pdf")
                                }, 0xcc42) }
                                catch (_: ActivityNotFoundException) { save = null; result.error("NO_DOCUMENT_PICKER", "No document picker is installed", null) }
                            }
                        }
                    }
                    "emailInvoice", "shareInvoice" -> {
                        val id = requireNotNull(call.argument<String>("invoiceId"))
                        val record = InvoiceDocuments.ensure(repository, id)
                        val intent = if (call.method == "emailInvoice") InvoiceDocuments.email(activity, record, repository.businessSnapshot().settings) else Intent.createChooser(InvoiceDocuments.share(activity, record), "Share invoice")
                        main.post {
                            if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to share your invoice", null)
                            else try { activity.startActivity(intent); result.success(null) }
                            catch (_: ActivityNotFoundException) { result.error("NO_COMPATIBLE_APP", "No compatible app is installed. Save or share the PDF instead.", null) }
                            catch (_: SecurityException) { result.error("SHARE_UNAVAILABLE", "The invoice could not be shared", null) }
                        }
                    }
                    else -> result.notImplemented()
                }
            } catch (_: IllegalArgumentException) { result.error("DOCUMENT_UNAVAILABLE", "The selected document is unavailable", null) }
            catch (_: Exception) { result.error("DOCUMENT_UNAVAILABLE", "The document could not be prepared. Your saved invoice is retained.", null) }
        }
    }
    fun activityResult(code: Int, status: Int, data: Intent?): Boolean {
        if (code == 0xcc43) {
            val pending = logoResult ?: return true
            logoResult = null
            val uri = data?.data
            if (status != Activity.RESULT_OK || uri == null) { pending.success(null); return true }
            writer.execute {
                try {
                    val asset = requireNotNull(activity.contentResolver.openInputStream(uri)).use { LogoImages.normalize(it) }
                    LogoImages.remember(activity, asset)
                    main.post { pending.success(mapOf("token" to asset.id, "width" to asset.width, "height" to asset.height, "png" to asset.bytes)) }
                } catch (_: Exception) { main.post { pending.error("INVALID_LOGO", "Choose a PNG, JPEG or WebP image up to 5 MB and 16 megapixels", null) } }
            }
            return true
        }
        if (code != 0xcc42) return false
        val pending = save ?: return true
        save = null
        val uri = data?.data
        if (status != Activity.RESULT_OK || uri == null) { pending.result.success(false); return true }
        writer.execute {
            try {
                val bytes = pending.bytes ?: requireNotNull(repository.invoice(requireNotNull(pending.invoiceId)).pdf)
                requireNotNull(activity.contentResolver.openOutputStream(uri, "w")).use { it.write(bytes) }
                main.post { pending.result.success(true) }
            } catch (_: Exception) { main.post { pending.result.error("SAVE_UNAVAILABLE", "The PDF could not be saved. Your prepared invoice is retained.", null) } }
        }
        return true
    }
    fun detach() { attached = false; logoResult?.error("ACTIVITY_UNAVAILABLE", "Reopen the app to choose a logo", null); logoResult = null; save?.result?.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your invoice", null); save = null; writer.shutdown(); channel.setMethodCallHandler(null) }
}
