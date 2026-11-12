package io.github.centerionware.clockrove.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.util.concurrent.Executors

/** Separate activities preserve pending forms in an already-open main Flutter engine. */
class WidgetBoardActivity : MainActivity() {
    override fun getInitialRoute(): String {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val job = intent.getStringExtra("jobId")
        val active = try { if (TimerWidget.owned(this, id)) TimerRepository.get(this).snapshot().jobs.firstOrNull { it.id == job } else null }
            catch (_: Exception) { null }
        return if (active == null) "/widget-unavailable" else android.net.Uri.Builder().path("/widget-board")
            .appendQueryParameter("job", active.id).appendQueryParameter("name", active.name).build().toString()
    }
}
class WidgetPickerActivity : MainActivity() { override fun getInitialRoute() = "/" }
class WidgetConfigurationActivity : MainActivity() {
    private var channel: MethodChannel? = null
    override fun onCreate(savedInstanceState: Bundle?) { setResult(Activity.RESULT_CANCELED); super.onCreate(savedInstanceState) }
    override fun getInitialRoute() = "/widget-configuration"
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "clockrove/widget/v1",
            io.flutter.plugin.common.StandardMethodCodec.INSTANCE, flutterEngine.dartExecutor.binaryMessenger.makeBackgroundTaskQueue()).also { api ->
            api.setMethodCallHandler { call, result ->
                try {
                    val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                    require(widgetId > 0)
                    val owned = TimerWidget.owned(this, widgetId)
                    val repository = TimerRepository.get(this)
                    when (call.method) {
                        "configuration" -> {
                            require(owned)
                            val snapshot = repository.widgetSnapshot(widgetId)
                            val taskOnly = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className == TaskListWidget::class.java.name
                            result.success(mapOf("widgetId" to widgetId, "jobId" to snapshot.configuration.jobId,
                                "missing" to snapshot.missingDefault, "taskOnly" to taskOnly))
                        }
                        "save" -> {
                            val args = call.arguments as Map<*, *>
                            val id = args["id"] as String
                            val rawVersion = args["version"]; require(rawVersion is Int || rawVersion is Long)
                            val version = (rawVersion as Number).toLong(); require(version >= 0)
                            val job = args["jobId"]; require(job == null || job is String)
                            val outcome = repository.saveWidgetConfiguration(id, version, widgetId, job as String?, available = owned)
                            TimerWidget.refreshAll(this)
                            result.success(mapOf("version" to outcome.version, "conflict" to outcome.conflict))
                        }
                        "finish" -> runOnUiThread {
                            // A removed widget has no launcher configuration to
                            // finish; close cleanly without reporting it added.
                            setResult(if (owned) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            result.success(null)
                            finish()
                        }
                        else -> result.notImplemented()
                    }
                } catch (_: IllegalArgumentException) { result.error("INVALID_WIDGET", "This widget is unavailable. Add it again from your launcher.", null) }
                catch (_: Exception) { result.error("STORAGE_UNAVAILABLE", "Widget settings are unavailable. Retry the same operation.", null) }
            }
        }
    }
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) { channel?.setMethodCallHandler(null); channel = null; super.cleanUpFlutterEngine(flutterEngine) }
}

/** Clock-in executes natively, even with no Flutter engine and no GPS permission. */
class WidgetStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worker.execute {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            var choose = true
            var startLocation = false
            try {
                val jobId = intent.getStringExtra("jobId")
                val token = intent.getStringExtra("token")
                val version = intent.getLongExtra("version", -1)
                if (TimerWidget.owned(this, id) && jobId != null && token != null && version >= 0) {
                    val repository = TimerRepository.get(this)
                    val before = repository.snapshot()
                    val outcome = repository.startConfiguredWidget(id, version, token, jobId)
                    choose = outcome.conflict != null
                    startLocation = !choose && before.running == null && outcome.version > before.version && repository.snapshot().running?.jobId == jobId
                }
                TimerWidget.refreshAll(this)
            } catch (_: Exception) { choose = true }
            runOnUiThread {
                if (choose) startActivity(Intent(this, WidgetPickerActivity::class.java))
                else if (startLocation && (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                    try {
                        io.github.centerionware.clockrove.location.WorkLocationService.markStartRequested()
                        val service = Intent(this, io.github.centerionware.clockrove.location.WorkLocationService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    } catch (_: RuntimeException) { io.github.centerionware.clockrove.location.WorkLocationService.markStartRestricted() }
                }
                finish()
            }
        }
    }
    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
