package io.github.centerionware.clockrove.tracking

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTimeDeviceTest {
    @Test fun repeatedHourReturnsBothExplicitUtcOffsetsInOrder() {
        val choices = SessionTime.resolve("2026-11-01T01:30:00.123", "America/New_York")
        assertEquals(2, choices.size)
        assertEquals(listOf(-14400, -18000), choices.map { it.offsetSeconds })
        assertEquals(1793511000123L, choices[0].instant)
        assertEquals(3600000L, choices[1].instant - choices[0].instant)
        assertTrue(choices[0].label.endsWith("-04:00")); assertTrue(choices[1].label.endsWith("-05:00"))
    }
    @Test fun gapAndSkippedDateNeverNormalizeIntoDifferentWork() {
        assertTrue(SessionTime.resolve("2026-03-08T02:30:00.000", "America/New_York").isEmpty())
        assertTrue(SessionTime.resolve("2011-12-30T12:00:00.000", "Pacific/Apia").isEmpty())
        val normal = SessionTime.resolve("2026-03-08T03:30:00.000", "America/New_York").single()
        assertEquals(-14400, normal.offsetSeconds)
        assertEquals("2026-03-08T03:30:00.000 -04:00", normal.label)
    }
    @Test fun exactMillisecondsAndStrictCalendarValidation() {
        val utc = SessionTime.resolve("1970-01-01T00:00:00.789", "UTC").single()
        assertEquals(789L, utc.instant); assertEquals(0, utc.offsetSeconds)
        for (local in listOf("2026-02-30T12:00:00.000", "2026-01-01T24:00:00.000", "2026-01-01T12:00:60.000", "2026-01-01T12:00")) {
            try { SessionTime.resolve(local, "UTC"); fail("Invalid local time accepted") } catch (_: IllegalArgumentException) {}
        }
        try { SessionTime.resolve("2026-01-01T12:00:00.000", "Invented/Zone"); fail("Unknown zone accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun formattingRoundTripsBothRepeatedInstantsAndOriginalMilliseconds() {
        for ((instant, zone) in listOf(1793511000123L to "America/New_York", 1793514600123L to "America/New_York",
            -1L to "UTC", 0L to "Pacific/Apia", -14831769600000L to "UTC")) {
            val formatted = SessionTime.format(instant, zone)
            val choices = SessionTime.resolve(formatted.localTime, zone)
            val restored = choices.single { it.instant == instant }
            assertEquals(formatted.offsetSeconds, restored.offsetSeconds)
            assertEquals(formatted.label, restored.label)
        }
        assertEquals("1969-12-31T23:59:59.999", SessionTime.format(-1L, "UTC").localTime)
        try { SessionTime.format(0, "Invented/Zone"); fail("Unknown zone accepted") } catch (_: IllegalArgumentException) {}
        try { SessionTime.format(Long.MAX_VALUE, "UTC"); fail("Unsupported date accepted") } catch (_: IllegalArgumentException) {}
    }

}
