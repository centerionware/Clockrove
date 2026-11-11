package io.github.centerionware.clockrove.location.geofence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.location.WorkLocationService
import io.github.centerionware.clockrove.tracking.TimerRepository
import io.github.centerionware.clockrove.widget.TimerWidget

internal object GeofenceRuntime {
    private var registration: GeofenceRegistrationController? = null
    private var subscription: AutoCloseable? = null
    private const val CHOICES = 202
    private const val CHANNEL = "clockrove-automation"

    fun attach(context: Context, repository: TimerRepository) {
        val preferences = context.getSharedPreferences("clockrove-geofence-runtime", Context.MODE_PRIVATE)
        val controller = GeofenceRegistrationController(repository, AndroidGeofencePlatform(context),
            { AndroidGeofencePlatform.hasPermission(context) },
            { preferences.getBoolean("cleanupRequired", false) },
            { preferences.edit().putBoolean("cleanupRequired", it).commit(); Unit })
        // Hold recovery uncertainty before any already-delivered alarm can finalize.
        controller.prepareRecovery()
        registration = controller
        subscription = repository.subscribe {
            DepartureRuntime.executor.execute {
                controller.reconcile()
                if (repository.snapshot().running != null)
                    context.getSystemService(NotificationManager::class.java).cancel(CHOICES)
            }
        }
    }

    fun resume(context: Context, recovery: Boolean = false) {
        DepartureRuntime.executor.execute {
            TimerRepository.get(context)
            val controller = registration ?: return@execute
            controller.reconcile(force = recovery || controller.health != GeofenceRegistrationController.Health.READY)
        }
    }

    fun receive(context: Context, intent: Intent, received: Long, finish: () -> Unit) {
        DepartureRuntime.executor.execute {
            try {
                val repository = TimerRepository.get(context)
                val event = try { GeofencingEvent.fromIntent(intent) } catch (_: RuntimeException) { null }
                if (event == null || event.hasError()) {
                    registration?.providerUnavailable() ?: repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
                    return@execute
                }
                val presence = when (event.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceMembership.Presence.INSIDE
                    Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceMembership.Presence.OUTSIDE
                    else -> null
                }
                val handler = GeofenceCallbackHandler(repository, { AndroidGeofencePlatform.hasPermission(context) },
                    { startLocation(context, repository, it) }, { showChoices(context) })
                handler.receive(event.triggeringGeofences?.map { it.requestId }?.toSet() ?: emptySet(),
                    presence, event.triggeringLocation?.time, received)
                TimerWidget.refreshAll(context)
            } finally { finish() }
        }
    }

    private fun startLocation(context: Context, repository: TimerRepository, sessionId: String) {
        if (repository.snapshot().running?.id != sessionId) return
        try {
            WorkLocationService.markStartRequested()
            val intent = Intent(context, WorkLocationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (_: RuntimeException) {
            WorkLocationService.markStartRestricted()
            repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
        }
    }

    private fun showChoices(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        try {
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.automation_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, CHOICES, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
            manager.notify(CHOICES, builder.setSmallIcon(R.drawable.ic_tracking)
                .setContentTitle(context.getString(R.string.automation_choose_title))
                .setContentText(context.getString(R.string.automation_choose_description))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: RuntimeException) { /* Notification denial never changes the timer. */ }
    }
}

class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidGeofencePlatform.ACTION) return
        val received = System.currentTimeMillis()
        val pending = goAsync()
        GeofenceRuntime.receive(context.applicationContext, intent, received) { pending.finish() }
    }
}
