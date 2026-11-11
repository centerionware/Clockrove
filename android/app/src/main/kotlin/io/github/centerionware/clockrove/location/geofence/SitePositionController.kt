package io.github.centerionware.clockrove.location.geofence

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.flutter.plugin.common.MethodChannel

internal interface SitePositionProvider {
    fun request(completed: (Location?) -> Unit, failed: () -> Unit): AutoCloseable
}

/** One foreground fix for setup, with no repository or history writer. */
internal class FusedSitePositionProvider(private val context: Context) : SitePositionProvider {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    override fun request(completed: (Location?) -> Unit, failed: () -> Unit): AutoCloseable {
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0).setDurationMillis(15_000).build()
        client.getCurrentLocation(request, cancellation.token).addOnCompleteListener {
            if (it.isSuccessful) completed(it.result) else failed()
        }
        return AutoCloseable { cancellation.cancel() }
    }
}

/** All public calls are from the activity thread; provider callbacks are marshalled there. */
internal class SitePositionController(
    private val provider: SitePositionProvider,
    private val visible: () -> Boolean,
    private val precise: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val main = Handler(Looper.getMainLooper())
    private class Request(val result: MethodChannel.Result) { var cancellation: AutoCloseable? = null; var timeout: Runnable? = null }
    private var active: Request? = null
    private var detached = false
    val busy get() = active != null

    fun request(purposeAccepted: Boolean, result: MethodChannel.Result) {
        when {
            detached -> result.error("ACTIVITY_DETACHED", "Reopen Clockrove to select a site", null)
            !visible() -> result.error("FOREGROUND_REQUIRED", "Open Clockrove to select a site", null)
            busy -> result.error("REQUEST_PENDING", "A location request is pending", null)
            !purposeAccepted -> result.error("PURPOSE_REQUIRED", "Confirm the site setup purpose first", null)
            !precise() -> result.error("PRECISE_REQUIRED", "Grant precise location or select the site on the map", null)
            else -> {
                val request = Request(result)
                active = request
                request.timeout = Runnable { complete(request, null) }
                main.postDelayed(requireNotNull(request.timeout), 20_000)
                try {
                    request.cancellation = provider.request(
                        { location -> main.post { complete(request, location) } },
                        { main.post { complete(request, null) } })
                } catch (_: RuntimeException) { complete(request, null) }
            }
        }
    }

    private fun complete(request: Request, location: Location?) {
        if (active !== request) return
        active = null
        request.timeout?.let { main.removeCallbacks(it) }
        request.cancellation?.close()
        val received = now()
        val valid = location != null && location.hasAccuracy() && location.accuracy.isFinite() &&
            location.accuracy >= 0 && location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.time > 0 && location.time <= received && received - location.time <= 30_000
        if (!visible()) request.result.error("FOREGROUND_REQUIRED", "Open Clockrove to select a site", null)
        else if (!precise()) request.result.error("PRECISE_REQUIRED", "Precise location is no longer available", null)
        else if (!valid) request.result.error("POSITION_UNAVAILABLE", "No fresh location fix; choose the site on the map or enter coordinates", null)
        else {
            val fix = requireNotNull(location)
            request.result.success(mapOf("latitude" to fix.latitude, "longitude" to fix.longitude,
                "accuracy" to fix.accuracy.toDouble(), "measured" to fix.time, "received" to received))
        }
    }

    fun cancel(code: String = "FOREGROUND_REQUIRED") {
        val request = active ?: return
        active = null
        request.timeout?.let { main.removeCallbacks(it) }
        request.cancellation?.close()
        request.result.error(code, "Site location request cancelled; manual timekeeping remains available", null)
    }
    fun detach() { detached = true; cancel("ACTIVITY_DETACHED") }
}
