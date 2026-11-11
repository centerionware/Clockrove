package io.github.centerionware.clockrove.location

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import com.google.android.gms.location.*
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.concurrent.Executors

/** User-visible, on-clock fused sampling. Permission/UI entry is wired separately. */
class WorkLocationService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val repository by lazy { TimerRepository.get(this) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var subscription: AutoCloseable? = null
    private var callback: LocationCallback? = null
    private var requestedSession: String? = null
    @Volatile private var closed = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (!hasLocationPermission()) { health = "PERMISSION_MISSING"; stopSelf(); return }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW))
        }
        try {
            val notification = notification(null)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION, notification)
        } catch (_: RuntimeException) {
            health = "START_RESTRICTED"
            stopSelf()
            return
        }
        dispatch {
            subscription = repository.subscribe { _ ->
                if (!closed) dispatch { reconcile() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Never invent continuity following process death or Android force-stop.
        return START_NOT_STICKY
    }

    private fun reconcile() {
        if (closed) return
        val state = repository.snapshot()
        val running = state.running
        if (running == null || !hasLocationPermission()) {
            health = if (running == null) "IDLE" else "PERMISSION_MISSING"
            main.post { stopSelf() }
            return
        }
        main.post {
            if (!closed) getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION, notification(state))
        }
        if (requestedSession == running.id) return
        callback?.let { fused.removeLocationUpdates(it) }
        requestedSession = running.id
        val boundSession = running.id
        val next = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val received = System.currentTimeMillis()
                if (!closed) dispatch {
                    result.locations.filter { it.hasAccuracy() }.forEach { observed ->
                        FusedSampleMapper.map(boundSession, observed, received)?.let {
                            repository.recordLocation(it)
                        }
                    }
                    TimerWidget.refreshAll(this@WorkLocationService)
                }
            }
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!closed) health = if (availability.isLocationAvailable) "REQUESTING" else "LOCATION_UNAVAILABLE"
            }
        }
        callback = next
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 600_000L)
            .setMinUpdateIntervalMillis(600_000L).setMaxUpdateDelayMillis(0).build()
        try {
            fused.requestLocationUpdates(request, next, Looper.getMainLooper())
                .addOnSuccessListener {
                    if (closed) fused.removeLocationUpdates(next) else health = "REQUESTING"
                }
                .addOnFailureListener {
                    if (!closed) { health = "PROVIDER_ERROR"; stopSelf() }
                }
        } catch (_: SecurityException) {
            health = "PERMISSION_MISSING"
            main.post { stopSelf() }
        }
    }

    private fun notification(state: TimerRepository.Snapshot?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_tracking).setContentTitle(state?.running?.jobName ?: getString(R.string.tracking_channel))
            .setContentText(getString(R.string.tracking_target)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
        if (state != null) {
            val intent = Intent(this, NotificationStopReceiver::class.java)
                .setData(android.net.Uri.parse("clockrove://notification/stop/${state.version}"))
                .putExtra("version", state.version)
            val stop = PendingIntent.getBroadcast(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.widget_stop), stop).build())
        }
        return builder.build()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun dispatch(block: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) block() } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    override fun onDestroy() {
        closed = true
        worker.execute {
            subscription?.close()
            callback?.let { fused.removeLocationUpdates(it) }
        }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (health in setOf("REQUESTING", "START_REQUESTED", "STARTING", "LOCATION_UNAVAILABLE")) health = "STOPPED"
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "clockrove-work-location"
        private const val NOTIFICATION = 101
        internal fun markStartRequested() { health = "START_REQUESTED" }
        internal fun markStartRestricted() { health = "START_RESTRICTED" }
        private val healthListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
        internal fun subscribeHealth(listener: (String) -> Unit): AutoCloseable {
            healthListeners.add(listener)
            listener(health)
            return AutoCloseable { healthListeners.remove(listener) }
        }
        @Volatile var health: String = "STOPPED"
            private set(value) {
                field = value
                healthListeners.forEach { try { it(value) } catch (_: Exception) { } }
            }
    }
}
