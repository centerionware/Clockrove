package io.github.centerionware.clockrove

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.tracking.TimerHostApi
import io.github.centerionware.clockrove.tracking.BackupHostApi
import io.github.centerionware.clockrove.location.TrackingHostApi

open class MainActivity : FlutterActivity() {
    private var timerApi: TimerHostApi? = null
    private var trackingApi: TrackingHostApi? = null
    private var automationApi: io.github.centerionware.clockrove.location.geofence.AutomationHostApi? = null
    private var businessApi: io.github.centerionware.clockrove.tracking.BusinessHostApi? = null
    private var backupApi: BackupHostApi? = null
    private var backupFileApi: io.github.centerionware.clockrove.backup.BackupDocumentHostApi? = null
    private var documentApi: io.github.centerionware.clockrove.invoice.DocumentHostApi? = null
    private var mapProviderApi: io.github.centerionware.clockrove.maps.MapProviderSettingsHostApi? = null
    private var languageApi: LanguageHostApi? = null
    private var helpApi: MethodChannel? = null
    private var debugApi: io.flutter.plugin.common.MethodChannel? = null
    private var visible = false

    override fun onStart() {
        super.onStart()
        visible = true
        io.github.centerionware.clockrove.location.geofence.GeofenceRuntime.resume(applicationContext)
    }
    override fun onStop() { visible = false; automationApi?.foregroundStopped(); super.onStop() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (backupFileApi?.activityResult(requestCode, resultCode, data) == true) return
        if (documentApi?.activityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        if (automationApi?.permissionResult(code) == true) return
        if (trackingApi?.permissionResult(code) != true) super.onRequestPermissionsResult(code, permissions, results)
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        languageApi = LanguageHostApi(this, flutterEngine.dartExecutor.binaryMessenger)
        helpApi = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "clockrove/help/v1").also { api ->
            api.setMethodCallHandler { call, result ->
                if (call.method != "appVersion") {
                    result.notImplemented()
                } else {
                    @Suppress("DEPRECATION")
                    val installed = packageManager.getPackageInfo(packageName, 0)
                    result.success(installed.versionName)
                }
            }
        }
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            debugApi = io.flutter.plugin.common.MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "clockrove/debug/v1").also { api ->
                api.setMethodCallHandler { call, result ->
                    if (call.method != "flutterError") result.notImplemented()
                    else {
                        val args = call.arguments as? Map<*, *>
                        val kind = (args?.get("type") as? String).orEmpty().take(128)
                        val stack = (args?.get("stack") as? String).orEmpty().take(16000)
                        android.util.Log.e("ClockroveDebug", "Flutter debug error type: $kind")
                        stack.chunked(3000).forEach { android.util.Log.e("ClockroveDebug", it) }
                        result.success(null)
                    }
                }
            }
        }
        documentApi = io.github.centerionware.clockrove.invoice.DocumentHostApi(
            this,
            flutterEngine.dartExecutor.binaryMessenger,
        ) { backupFileApi?.hasPendingAction() == true }
        backupFileApi = io.github.centerionware.clockrove.backup.BackupDocumentHostApi(
            this,
            flutterEngine.dartExecutor.binaryMessenger,
            documentActionPending = { documentApi?.hasPendingExternalAction() == true },
        )
        mapProviderApi = io.github.centerionware.clockrove.maps.MapProviderSettingsHostApi(this, flutterEngine.dartExecutor.binaryMessenger)
        businessApi = io.github.centerionware.clockrove.tracking.BusinessHostApi(this, flutterEngine.dartExecutor.binaryMessenger)
        backupApi = BackupHostApi(this, flutterEngine.dartExecutor.binaryMessenger)
        timerApi = TimerHostApi(this, flutterEngine.dartExecutor.binaryMessenger)
        trackingApi = TrackingHostApi(this, flutterEngine.dartExecutor.binaryMessenger) { visible }
        automationApi = io.github.centerionware.clockrove.location.geofence.AutomationHostApi(this, flutterEngine.dartExecutor.binaryMessenger) { visible }
    }
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        languageApi?.detach()
        languageApi = null
        helpApi?.setMethodCallHandler(null)
        helpApi = null
        debugApi?.setMethodCallHandler(null)
        debugApi = null
        backupFileApi?.detach()
        backupFileApi = null
        documentApi?.detach()
        documentApi = null
        mapProviderApi?.detach()
        mapProviderApi = null
        businessApi?.detach()
        businessApi = null
        backupApi?.detach()
        backupApi = null
        automationApi?.detach()
        automationApi = null
        trackingApi?.detach()
        trackingApi = null
        timerApi?.detach()
        timerApi = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
