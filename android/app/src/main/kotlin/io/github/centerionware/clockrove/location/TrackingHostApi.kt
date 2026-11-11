package io.github.centerionware.clockrove.location

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.util.concurrent.Executors

/** Explicit visible-activity entry; never starts location as a background side effect. */
class TrackingHostApi(private val activity: Activity, messenger: BinaryMessenger,
    private val visible: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = TimerRepository.get(activity)
    private var pending: MethodChannel.Result? = null
    private var enablingLocation = false
    @Volatile private var detached = false
    private val channel = MethodChannel(messenger, "clockrove/tracking/v1")

    private val events = EventChannel(messenger, "clockrove/tracking/health/v1")
    private var healthSubscription: AutoCloseable? = null
    private var generation = 0

    init {
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                healthSubscription?.close()
                val current = ++generation
                healthSubscription = WorkLocationService.subscribeHealth { code ->
                    main.post { if (!detached && current == generation) sink.success(code) }
                }
            }
            override fun onCancel(arguments: Any?) {
                ++generation
                healthSubscription?.close()
                healthSubscription = null
            }
        })
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> replyStatus(result)
                "enable" -> {
                    if (!visible()) result.error("FOREGROUND_REQUIRED", "Open Clockrove to enable location", null)
                    else if (pending != null) result.error("REQUEST_PENDING", "A permission request is pending", null)
                    else worker.execute {
                        val working = repository.snapshot().running != null
                        main.post {
                            if (detached) return@post
                            if (!visible()) result.error("FOREGROUND_REQUIRED", "Open Clockrove to enable location", null)
                            else if (!working) replyStatus(result)
                            else if (!hasLocation()) {
                                pending = result
                                enablingLocation = true
                                activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
                            } else startVisible(result)
                        }
                    }
                }
                "disable" -> {
                    val waiting = pending
                    pending = null
                    enablingLocation = false
                    waiting?.error("CANCELLED", "Location enable cancelled", null)
                    activity.stopService(Intent(activity, WorkLocationService::class.java))
                    replyStatus(result)
                }
                "requestNotifications" -> {
                    if (!visible()) result.error("FOREGROUND_REQUIRED", "Open Clockrove to request notifications", null)
                    else if (pending != null) result.error("REQUEST_PENDING", "A permission request is pending", null)
                    else if (Build.VERSION.SDK_INT >= 33) {
                        pending = result
                        enablingLocation = false
                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                    } else replyStatus(result)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun permissionResult(code: Int): Boolean {
        if (code != LOCATION_REQUEST && code != NOTIFICATION_REQUEST) return false
        val result = pending ?: return true
        pending = null
        if (enablingLocation && hasLocation()) startVisible(result) else replyStatus(result)
        return true
    }

    private fun startVisible(result: MethodChannel.Result) {
        if (!visible()) {
            result.error("FOREGROUND_REQUIRED", "Open Clockrove to enable location", null)
            return
        }
        try {
            WorkLocationService.markStartRequested()
            val intent = Intent(activity, WorkLocationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
            replyStatus(result)
        } catch (_: RuntimeException) {
            WorkLocationService.markStartRestricted()
            result.error("START_RESTRICTED", "Android restricted location startup; manual timekeeping still works", null)
        }
    }

    private fun hasLocation() = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun replyStatus(result: MethodChannel.Result) {
        if (detached) return
        worker.execute {
            if (detached) return@execute
            val running = repository.snapshot().running
            result.success(mapOf(
                "permission" to when {
                    activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> "precise"
                    hasLocation() -> "approximate"
                    else -> "denied"
                },
                "service" to WorkLocationService.health,
                "working" to (running != null),
                "notificationsAllowed" to activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            ))
        }
    }

    fun detach() {
        detached = true
        pending?.error("ACTIVITY_DETACHED", "Reopen Clockrove to continue", null)
        pending = null
        ++generation
        healthSubscription?.close()
        events.setStreamHandler(null)
        channel.setMethodCallHandler(null)
        worker.shutdown()
    }

    companion object {
        private const val LOCATION_REQUEST = 2401
        private const val NOTIFICATION_REQUEST = 2402
    }
}
