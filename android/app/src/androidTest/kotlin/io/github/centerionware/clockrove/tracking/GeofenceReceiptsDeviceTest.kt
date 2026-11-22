package io.github.centerionware.clockrove.tracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeofenceReceiptsDeviceTest {
    @Test fun androidKeystoreSignsStableAndDistinctGeofenceReceipts() {
        val exit = """["OUTSIDE",1000,["synthetic-site"]]"""
        val arrival = """["INSIDE",2000,["synthetic-site"]]"""

        val firstReceipt = GeofenceEventDigest.digest(exit)

        assertTrue(firstReceipt.matches(Regex("[0-9a-f]{64}")))
        assertEquals(firstReceipt, GeofenceEventDigest.digest(exit))
        assertNotEquals(firstReceipt, GeofenceEventDigest.digest(arrival))
    }
}
