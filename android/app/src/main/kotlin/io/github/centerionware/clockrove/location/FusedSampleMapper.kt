package io.github.centerionware.clockrove.location

import android.location.Location
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.util.UUID

internal object FusedSampleMapper {
    fun map(sessionId: String, observed: Location, receivedUtcMillis: Long): TimerRepository.LocationPoint? {
        if (!observed.hasAccuracy()) return null
        val identity = "$sessionId:${observed.elapsedRealtimeNanos}:${observed.time}:${observed.latitude}:${observed.longitude}"
        return TimerRepository.LocationPoint(
            UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString(), sessionId,
            observed.time, receivedUtcMillis, observed.latitude, observed.longitude, observed.accuracy.toDouble(),
        )
    }
}
