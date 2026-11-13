package io.github.centerionware.clockrove.invoice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.io.File
import java.io.FileInputStream

internal class DocumentHostApi(private val activity: Activity, messenger: BinaryMessenger) {
    private val repository = TimerRepository.get(activity.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val channel = MethodChannel(messenger, "clockrove/documents/v1", io.flutter.plugin.common.StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())
    private data class Save(val bytes: ByteArray, val result: MethodChannel.Result)
    private var save: Save? = null
    private var logoResult: MethodChannel.Result? = null
    private var photoResult: MethodChannel.Result? = null
    private var photoCaptureFile: File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var attached = true
    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "settingsInvoicePreview" -> {
                        val style = requireNotNull(call.argument<String>("style"))
                        val currency = requireNotNull(call.argument<String>("currency"))
                        val digits = requireNotNull(call.argument<Int>("digits"))
                        val page = requireNotNull(call.argument<Int>("page"))
                        val pdf = InvoiceDocuments.settingsPreview(style, currency, digits, call.argument<ByteArray>("logo"))
                        result.success(InvoiceDocuments.preview(activity, pdf, page))
                    }
                    "pickLogo" -> main.post {
                        if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to choose a logo", null)
                        else if (externalActionPending()) result.error("DOCUMENT_PICKER_OPEN", "Finish the open document or photo action first", null)
                        else {
                            logoResult = result
                            try { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                            }, 0xcc43) }
                            catch (_: ActivityNotFoundException) { logoResult = null; result.error("NO_DOCUMENT_PICKER", "No image document picker is installed", null) }
                        }
                    }
                    "pickTaskPhoto" -> launchTaskPhoto(result, camera = false)
                    "captureTaskPhoto" -> launchTaskPhoto(result, camera = true)
                    "invoicePreview", "quotePreview" -> {
                        val id = requireNotNull(call.argument<String>(if (call.method == "quotePreview") "quoteId" else "invoiceId"))
                        val index = requireNotNull(call.argument<Int>("page"))
                        val quote = call.method == "quotePreview" || call.method.endsWith("Quote")
                        val record = if (quote) InvoiceDocuments.ensureQuote(repository, id, requireNotNull(call.argument<Int>("revision"))) else InvoiceDocuments.ensure(repository, id, call.argument<String>("copyId"))
                        result.success(InvoiceDocuments.preview(activity, record, index) + mapOf("notice" to InvoiceDocuments.historicalNotice(repository, record)))
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
                            else if (externalActionPending()) result.error("DOCUMENT_PICKER_OPEN", "Finish the open document or photo action first", null)
                            else {
                                save = Save(bytes, result)
                                try { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "Clockrove-${if (gps) "gps" else "work"}-${selection.firstDate}-${selection.lastDate}.csv")
                                }, 0xcc42) }
                                catch (_: ActivityNotFoundException) { save = null; result.error("NO_DOCUMENT_PICKER", "No document picker is installed", null) }
                            }
                        }
                    }
                    "saveInvoice", "saveQuote" -> {
                        val id = requireNotNull(call.argument<String>(if (call.method == "saveQuote") "quoteId" else "invoiceId"))
                        val quote = call.method == "quotePreview" || call.method.endsWith("Quote")
                        val record = if (quote) InvoiceDocuments.ensureQuote(repository, id, requireNotNull(call.argument<Int>("revision"))) else InvoiceDocuments.ensure(repository, id, call.argument<String>("copyId"))
                        main.post {
                            if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your invoice", null)
                            else if (externalActionPending()) result.error("DOCUMENT_PICKER_OPEN", "Finish the open document or photo action first", null)
                            else {
                                save = Save(requireNotNull(record.pdf), result)
                                try { activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, InvoiceDocuments.filename(record))
                                }, 0xcc42) }
                                catch (_: ActivityNotFoundException) { save = null; result.error("NO_DOCUMENT_PICKER", "No document picker is installed", null) }
                            }
                        }
                    }
                    "emailInvoice", "shareInvoice", "emailQuote", "shareQuote" -> {
                        val id = requireNotNull(call.argument<String>(if (call.method.endsWith("Quote")) "quoteId" else "invoiceId"))
                        val quote = call.method == "quotePreview" || call.method.endsWith("Quote")
                        val record = if (quote) InvoiceDocuments.ensureQuote(repository, id, requireNotNull(call.argument<Int>("revision"))) else InvoiceDocuments.ensure(repository, id, call.argument<String>("copyId"))
                        val notice = InvoiceDocuments.historicalNotice(repository, record)
                        val intent = if (call.method == "emailInvoice" || call.method == "emailQuote") InvoiceDocuments.email(activity, record, repository.businessSnapshot().settings, notice) else Intent.createChooser(InvoiceDocuments.share(activity, record, notice), if (quote) "Share quote" else "Share invoice")
                        main.post {
                            if (!attached || activity.isFinishing) result.error("ACTIVITY_UNAVAILABLE", "Reopen the app to share your invoice", null)
                            else try { activity.startActivity(intent); result.success(null) }
                            catch (_: ActivityNotFoundException) { result.error("NO_COMPATIBLE_APP", "No compatible app is installed. Save or share the PDF instead.", null) }
                            catch (_: SecurityException) { result.error("SHARE_UNAVAILABLE", "The invoice could not be shared", null) }
                            catch (_: RuntimeException) { result.error("SHARE_UNAVAILABLE", "The email or share app could not accept this document. Save the PDF instead.", null) }
                        }
                    }
                    else -> result.notImplemented()
                }
            } catch (failure: IllegalArgumentException) {
                if (failure.message == "PAYMENT_HISTORY_TOO_LARGE") result.error("PAYMENT_HISTORY_TOO_LARGE", "This history is too long for one PDF. Open individual saved receipts instead.", null)
                else result.error("DOCUMENT_UNAVAILABLE", "The selected document is unavailable", null)
            }
            catch (_: Exception) { result.error("DOCUMENT_UNAVAILABLE", "The document could not be prepared. Your saved invoice is retained.", null) }
        }
    }
    private fun externalActionPending() = save != null || logoResult != null || photoResult != null

    private fun launchTaskPhoto(result: MethodChannel.Result, camera: Boolean) = main.post {
        if (!attached || activity.isFinishing) {
            result.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to attach a task photo", null)
            return@post
        }
        if (externalActionPending()) {
            result.error("DOCUMENT_PICKER_OPEN", "Finish the open document or photo action first", null)
            return@post
        }
        val requestCode = if (camera) 0xcc45 else 0xcc44
        try {
            val intent = if (camera) {
                val directory = File(activity.cacheDir, "task-captures").apply { mkdirs() }
                val file = File.createTempFile("capture-", ".jpg", directory)
                val uri = Uri.parse("content://${activity.packageName}.task-capture/capture/${file.name}")
                photoCaptureFile = file
                Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                    clipData = ClipData.newUri(activity.contentResolver, "Clockrove task photo", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                }
            }
            if (camera && isCiCameraFixtureEnabled()) {
                intent.setClassName(
                    activity,
                    "io.github.centerionware.clockrove.testsupport.TaskPhotoCameraActivity",
                )
            }
            photoResult = result
            activity.startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            photoResult = null
            photoCaptureFile?.delete()
            photoCaptureFile = null
            result.error(if (camera) "NO_CAMERA_APP" else "NO_DOCUMENT_PICKER", if (camera) "No camera app is available" else "No image picker is installed", null)
        } catch (_: SecurityException) {
            photoResult = null
            photoCaptureFile?.delete()
            photoCaptureFile = null
            result.error("PHOTO_SOURCE_UNAVAILABLE", "The selected photo source could not be opened", null)
        } catch (_: RuntimeException) {
            photoResult = null
            photoCaptureFile?.delete()
            photoCaptureFile = null
            result.error("PHOTO_SOURCE_UNAVAILABLE", "The selected photo source could not be opened", null)
        }
    }

    private fun isCiCameraFixtureEnabled(): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            activity.applicationInfo.metaData?.getBoolean(
                "io.github.centerionware.clockrove.CI_CAMERA_FIXTURE",
            ) == true

    fun activityResult(code: Int, status: Int, data: Intent?): Boolean {
        if (code == 0xcc44 || code == 0xcc45) {
            val pending = photoResult ?: return true
            photoResult = null
            val camera = code == 0xcc45
            val file = photoCaptureFile
            photoCaptureFile = null
            if (status != Activity.RESULT_OK) {
                file?.delete()
                pending.success(null)
                return true
            }
            val uri = data?.data
            writer.execute {
                try {
                    val input = if (camera) FileInputStream(requireNotNull(file).also { require(it.isFile && it.length() in 1..5_000_000) })
                        else activity.contentResolver.openInputStream(requireNotNull(uri))
                    val asset = requireNotNull(input).use { LogoImages.normalize(it) }
                    main.post { if (attached) pending.success(asset.bytes) else pending.error("ACTIVITY_UNAVAILABLE", "Reopen Clockrove to attach a task photo", null) }
                } catch (_: Exception) {
                    main.post { pending.error("INVALID_TASK_PHOTO", "Choose a PNG, JPEG or WebP image up to 5 MB and 16 megapixels", null) }
                } finally {
                    file?.delete()
                }
            }
            return true
        }
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
                val bytes = pending.bytes
                requireNotNull(activity.contentResolver.openOutputStream(uri, "w")).use { it.write(bytes) }
                main.post { pending.result.success(true) }
            } catch (_: Exception) { main.post { pending.result.error("SAVE_UNAVAILABLE", "The PDF could not be saved. Your prepared invoice is retained.", null) } }
        }
        return true
    }
    fun detach() {
        attached = false
        logoResult?.error("ACTIVITY_UNAVAILABLE", "Reopen the app to choose a logo", null)
        logoResult = null
        save?.result?.error("ACTIVITY_UNAVAILABLE", "Reopen the app to save your invoice", null)
        save = null
        photoResult?.error("ACTIVITY_UNAVAILABLE", "Reopen the app to attach a task photo", null)
        photoResult = null
        photoCaptureFile?.delete()
        photoCaptureFile = null
        writer.shutdown()
        channel.setMethodCallHandler(null)
    }
}
