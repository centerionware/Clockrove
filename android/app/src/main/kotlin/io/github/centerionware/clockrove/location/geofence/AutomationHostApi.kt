package io.github.centerionware.clockrove.location.geofence

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.tracking.GeofenceStore
import io.github.centerionware.clockrove.tracking.TimerRepository

/** Settings commands carry expected versions and never accept a caller-selected event origin. */
class AutomationHostApi internal constructor(private val activity: Activity, messenger: BinaryMessenger,
    private val repository: TimerRepository = TimerRepository.get(activity),
    private val reconcileMonitoring: () -> Unit = { GeofenceRuntime.resume(activity.applicationContext) },
    private val positionProvider: SitePositionProvider = FusedSitePositionProvider(activity.applicationContext),
    private val visible: () -> Boolean) {
    private val channel = MethodChannel(messenger, "clockrove/automation/v1")
    private val events = EventChannel(messenger, "clockrove/automation/health/v1")
    private val main = Handler(Looper.getMainLooper())
    private val position = SitePositionController(positionProvider, visible, ::precise)
    private var pending: MethodChannel.Result? = null
    private var subscription: AutoCloseable? = null
    private var generation = 0
    @Volatile private var detached = false

    init {
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                subscription?.close()
                val token = ++generation
                subscription = GeofenceRuntime.subscribeHealth { code ->
                    main.post { if (!detached && token == generation) sink.success(code) }
                }
            }
            override fun onCancel(arguments: Any?) { ++generation; subscription?.close(); subscription = null }
        })
        channel.setMethodCallHandler { call, result ->
            if (call.method == "retryMonitoring") {
                if (!visible()) result.error("FOREGROUND_REQUIRED", "Open Clockrove to retry site monitoring", null)
                else if (pending != null || position.busy) result.error("REQUEST_PENDING", "A location request is pending", null)
                else try { reconcileMonitoring(); result.success(null) }
                catch (_: RuntimeException) { result.error("REGISTRATION_UNAVAILABLE", "Site monitoring could not be retried", null) }
                return@setMethodCallHandler
            }
            if (call.method == "cancelPosition") {
                position.cancel("CANCELLED")
                result.success(null)
                return@setMethodCallHandler
            }
            if (call.method == "currentPosition") {
                if (pending != null) result.error("REQUEST_PENDING", "A permission request is pending", null)
                else position.request((call.arguments as? Map<*, *>)?.get("purposeAccepted") == true, result)
                return@setMethodCallHandler
            }
            if (call.method in setOf("requestPrecise", "requestBackground", "openSettings")) {
                try {
                    if (!visible()) result.error("FOREGROUND_REQUIRED", "Open Clockrove to change permissions", null)
                    else if (pending != null || position.busy) result.error("REQUEST_PENDING", "A permission request is pending", null)
                    else if (call.method == "openSettings") {
                        openSettings()
                        result.success(permissions())
                    } else if (call.argument<Boolean>("purposeAccepted") != true) {
                        result.error("PURPOSE_REQUIRED", "Confirm the work site location purpose first", null)
                    } else if (call.method == "requestPrecise") {
                        pending = result
                        activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION), PRECISE)
                    } else if (!precise()) result.error("PRECISE_REQUIRED", "Grant precise location first", null)
                    else if (Build.VERSION.SDK_INT >= 30) { openSettings(); result.success(permissions()) }
                    else if (Build.VERSION.SDK_INT == 29) {
                        pending = result
                        activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND)
                    } else result.success(permissions())
                } catch (_: RuntimeException) {
                    if (pending === result) pending = null
                    result.error("PERMISSION_UNAVAILABLE", "Open app settings to change location access", null)
                }
                return@setMethodCallHandler
            }
            DepartureRuntime.executor.execute {
                try {
                    val value: Any? = when (call.method) {
                        "snapshot" -> encode(repository.automationSnapshot(requireNotNull(call.argument<String>("jobId")))) + permissions()
                        "saveSite", "removeSite", "saveSettings", "acceptReview" -> {
                            val id = requireNotNull(call.argument<String>("id"))
                            val version = requireNotNull(call.argument<Number>("version")).toLong()
                            require(version >= 0)
                            val outcome = when (call.method) {
                                "saveSite" -> repository.saveSite(id, version, GeofenceStore.Site(
                                    requireNotNull(call.argument<String>("siteId")), requireNotNull(call.argument<String>("jobId")),
                                    requireNotNull(call.argument<String>("name")), requireNotNull(call.argument<Number>("latitude")).toDouble(),
                                    requireNotNull(call.argument<Number>("longitude")).toDouble(), requireNotNull(call.argument<Number>("radius")).toDouble(),
                                    requireNotNull(call.argument<Boolean>("enabled"))))
                                "removeSite" -> repository.removeSite(id, version, requireNotNull(call.argument<String>("siteId")))
                                "acceptReview" -> repository.acceptGeofenceReview(id, version, requireNotNull(call.argument<String>("jobId")))
                                else -> repository.saveAutomation(id, version, GeofenceStore.Settings(
                                    requireNotNull(call.argument<String>("jobId")), requireNotNull(call.argument<Boolean>("enabled")),
                                    requireNotNull(call.argument<Boolean>("autoStart")), requireNotNull(call.argument<Boolean>("automaticDeparture")),
                                    requireNotNull(call.argument<Number>("graceMinutes")).toInt()))
                            }
                            mapOf("version" to outcome.version, "conflict" to outcome.conflict)
                        }
                        else -> { main.post { if (!detached) result.notImplemented() }; return@execute }
                    }
                    main.post { if (!detached) result.success(value) }
                } catch (_: IllegalArgumentException) {
                    main.post { if (!detached) result.error("INVALID_ARGUMENT", "Invalid work site request", null) }
                } catch (_: ClassCastException) {
                    main.post { if (!detached) result.error("INVALID_ARGUMENT", "Invalid work site request", null) }
                } catch (_: Exception) {
                    main.post { if (!detached) result.error("STORAGE_UNAVAILABLE", "Work site storage unavailable", null) }
                }
            }
        }
    }

    fun permissionResult(code: Int): Boolean {
        if (code != PRECISE && code != BACKGROUND) return false
        val result = pending
        pending = null
        if (detached) return true
        result?.success(permissions())
        reconcileMonitoring()
        return true
    }
    private fun precise() = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun permissions(): Map<String, Any?> = mapOf(
        "precise" to precise(), "background" to AndroidGeofencePlatform.hasPermission(activity),
        "backgroundLabel" to if (Build.VERSION.SDK_INT >= 30) activity.packageManager.backgroundPermissionOptionLabel.toString() else "Allow all the time",
        "health" to GeofenceRuntime.health,
    )
    private fun openSettings() { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }
    fun foregroundStopped() { position.cancel() }
    fun detach() {
        detached = true
        position.detach()
        pending?.error("ACTIVITY_DETACHED", "Reopen Clockrove to continue", null)
        pending = null
        ++generation
        subscription?.close()
        events.setStreamHandler(null)
        channel.setMethodCallHandler(null)
    }
    private fun encode(value: TimerRepository.AutomationSnapshot): Map<String, Any?> = mapOf(
        "version" to value.version, "enabled" to value.settings.enabled, "autoStart" to value.settings.autoStart,
        "automaticDeparture" to value.settings.automaticDeparture, "graceMinutes" to value.settings.graceMinutes,
        "armed" to value.armed, "suppressed" to value.suppressed, "working" to value.working,
        "sites" to value.sites.map { mapOf("id" to it.id, "name" to it.name, "latitude" to it.latitude,
            "longitude" to it.longitude, "radius" to it.radiusMeters, "enabled" to it.enabled) },
        "pending" to value.pending?.let { mapOf("detected" to it.detectedUtcMillis, "deadline" to it.deadlineUtcMillis) },
        "reviews" to value.reviews.map { mapOf("sessionId" to it.sessionId, "start" to it.start, "end" to it.end, "historical" to it.historical) },
    )
    companion object { private const val PRECISE = 2411; private const val BACKGROUND = 2412 }
}
