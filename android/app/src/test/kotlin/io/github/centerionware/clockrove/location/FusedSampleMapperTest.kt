package io.github.centerionware.clockrove.location

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class FusedSampleMapperTest {
    @Test fun preservesMeasuredMetadataAndDeduplicatesDeliveryIdentity() {
        val location = Location("synthetic").apply {
            latitude = 10.0; longitude = 20.0; time = 1000L
            elapsedRealtimeNanos = 123000L; accuracy = 25f
        }
        val first = FusedSampleMapper.map("session-a", location, 1500)!!
        val duplicate = FusedSampleMapper.map("session-a", location, 2000)!!
        assertEquals(first.id, duplicate.id)
        assertEquals(1000L, first.measuredUtcMillis)
        assertEquals(1500L, first.receivedUtcMillis)
        assertEquals(25.0, first.accuracyMeters, 0.0)
        assertNotEquals(first.id, FusedSampleMapper.map("session-b", location, 2000)!!.id)
        location.removeAccuracy()
        assertNull(FusedSampleMapper.map("session-a", location, 2000))
    }
}
