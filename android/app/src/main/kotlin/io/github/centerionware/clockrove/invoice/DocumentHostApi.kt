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
    private data class Save(val invoiceId: String, val result: MethodChannel.Result)
    private var save: Save? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var attached = true
    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "invoicePreview" -> {
                        val id = requireNotNull(call.argument<String>("invoiceId"))
                        val index = requireNotNull(call.argument<Int>("page"))
                        result.success(InvoiceDocuments.preview(activity, InvoiceDocuments.ensure(repository, id), index))
                    }
                    "saveInvoice" -> {
                        val id = requireNotNull(call.argument<String>("invoiceId"))
                        val record = InvoiceDocuments.ensure(repository, id)
                        main.post {
                            if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your invoice", null)
                            else if (save != null) result.error("DOCUMENT_PICKER_OPEN", "Finish choosing a destination first", null)
                            else {
                                save = Save(id, result)
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
                        val intent = if (call.method == "emailInvoice") InvoiceDocuments.email(activity, record) else Intent.createChooser(InvoiceDocuments.share(activity, record), "Share invoice")
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
        if (code != 0xcc42) return false
        val pending = save ?: return true
        save = null
        val uri = data?.data
        if (status != Activity.RESULT_OK || uri == null) { pending.result.success(false); return true }
        writer.execute {
            try {
                val bytes = requireNotNull(repository.invoice(pending.invoiceId).pdf)
                requireNotNull(activity.contentResolver.openOutputStream(uri, "w")).use { it.write(bytes) }
                main.post { pending.result.success(true) }
            } catch (_: Exception) { main.post { pending.result.error("SAVE_UNAVAILABLE", "The PDF could not be saved. Your prepared invoice is retained.", null) } }
        }
        return true
    }
    fun detach() { attached = false; save?.result?.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your invoice", null); save = null; writer.shutdown(); channel.setMethodCallHandler(null) }
}
