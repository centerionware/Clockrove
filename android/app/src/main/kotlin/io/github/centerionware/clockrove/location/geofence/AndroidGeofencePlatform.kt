package io.github.centerionware.clockrove.location.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.github.centerionware.clockrove.tracking.GeofenceStore

internal class AndroidGeofencePlatform(private val context: Context) : GeofenceRegistrationController.Platform {
    private val client by lazy { LocationServices.getGeofencingClient(context) }
    override fun replace(sites: List<GeofenceStore.Site>, completed: (Boolean) -> Unit) {
        try {
            val pending = pendingIntent(context)
            client.removeGeofences(pending).addOnCompleteListener(DepartureRuntime.executor) { removal ->
                if (!removal.isSuccessful) completed(false)
                else if (sites.isEmpty()) completed(true)
                else try {
                    if (!hasPermission(context)) { completed(false); return@addOnCompleteListener }
                    val request = GeofencingRequest.Builder()
                        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
                        .addGeofences(sites.map { site ->
                            Geofence.Builder().setRequestId(site.registrationKey)
                                .setCircularRegion(site.latitude, site.longitude, site.radiusMeters.toFloat())
                                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                                .setNotificationResponsiveness(60_000)
                                .setExpirationDuration(Geofence.NEVER_EXPIRE).build()
                        }).build()
                    client.addGeofences(request, pending).addOnCompleteListener(DepartureRuntime.executor) {
                        completed(it.isSuccessful)
                    }
                } catch (_: RuntimeException) { completed(false) }
            }
        } catch (_: RuntimeException) { completed(false) }
    }

    companion object {
        const val ACTION = "io.github.centerionware.clockrove.GEOFENCE_TRANSITION"
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 208,
            Intent(context, GeofenceTransitionReceiver::class.java).setAction(ACTION)
                .setData(Uri.parse("clockrove://automation/geofence")),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    }
}
