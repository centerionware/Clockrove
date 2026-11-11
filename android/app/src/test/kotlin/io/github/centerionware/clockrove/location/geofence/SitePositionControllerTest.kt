package io.github.centerionware.clockrove.location.geofence

import android.location.Location
import android.os.Looper
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class SitePositionControllerTest {
    private class Response : MethodChannel.Result {
        var value: Any? = null
        var error: String? = null
        var replies = 0
        override fun success(result: Any?) { value = result; replies++ }
        override fun error(code: String, message: String?, details: Any?) { error = code; replies++ }
        override fun notImplemented() { error = "NOT_IMPLEMENTED"; replies++ }
    }
    private class Provider : SitePositionProvider {
        val callbacks = mutableListOf<(Location?) -> Unit>()
        var closes = 0
        override fun request(completed: (Location?) -> Unit, failed: () -> Unit): AutoCloseable {
            callbacks.add(completed)
            return AutoCloseable { closes++ }
        }
    }
    private val provider = Provider()
    private var visible = true
    private var precise = true
    private val position = SitePositionController(provider, { visible }, { precise }) { 100000L }
    private fun fix(at: Long = 99000) = Location("synthetic").apply { latitude = 10.0; longitude = 20.0; accuracy = 25f; time = at }
    private fun complete(index: Int, location: Location?) { provider.callbacks[index](location); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun purposePermissionAndForegroundAreRequiredBeforeTouchingProvider() {
        val purpose = Response(); position.request(false, purpose); assertEquals("PURPOSE_REQUIRED", purpose.error)
        precise = false
        val denied = Response(); position.request(true, denied); assertEquals("PRECISE_REQUIRED", denied.error)
        precise = true; visible = false
        val background = Response(); position.request(true, background); assertEquals("FOREGROUND_REQUIRED", background.error)
        assertTrue(provider.callbacks.isEmpty())
    }
    @Test fun freshMeasuredFixReturnsOnceWithoutCreatingAnyWorkHistory() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        val repository = TimerRepository(app)
        try {
            val before = repository.snapshot()
            val response = Response(); position.request(true, response)
            complete(0, fix()); complete(0, fix())
            assertEquals(1, response.replies)
            assertNull(response.error)
            val value = response.value as Map<*, *>
            assertEquals(10.0, value["latitude"])
            assertEquals(25.0, value["accuracy"])
            assertEquals(99000L, value["measured"])
            assertEquals(100000L, value["received"])
            assertEquals(before, repository.snapshot())
            assertTrue(repository.locationPoints("no-session").isEmpty())
        } finally { repository.close() }
    }
    @Test fun overlappingRequestCannotReplaceTheFirstCallback() {
        val first = Response(); position.request(true, first)
        val second = Response(); position.request(true, second)
        assertEquals("REQUEST_PENDING", second.error)
        assertEquals(1, provider.callbacks.size)
        complete(0, fix())
        assertEquals(1, first.replies)
        assertNull(first.error)
    }
    @Test fun missingStaleFutureOrInvalidFixNeverBecomesASelection() {
        val invalid = listOf(null, fix(69999), fix(100001), fix().apply { latitude = Double.NaN },
            fix().apply { accuracy = Float.POSITIVE_INFINITY }, Location("synthetic").apply { latitude = 10.0; longitude = 20.0; time = 99000 })
        invalid.forEachIndexed { i, sample ->
            val response = Response(); position.request(true, response); complete(i, sample)
            assertEquals("POSITION_UNAVAILABLE", response.error)
            assertNull(response.value)
            assertFalse(position.busy)
        }
    }
    @Test fun cancellationAndDetachIgnoreLateFixesWithoutCancellingANewerRequest() {
        val first = Response(); position.request(true, first); position.cancel()
        assertEquals("FOREGROUND_REQUIRED", first.error)
        val newer = Response(); position.request(true, newer)
        complete(0, fix())
        assertEquals(0, newer.replies)
        assertTrue(position.busy)
        position.detach(); complete(1, fix())
        assertEquals("ACTIVITY_DETACHED", newer.error)
        assertEquals(1, newer.replies)
        assertEquals(2, provider.closes)
    }
    @Test fun unresponsiveProviderTimesOutAndLateCompletionIsIgnored() {
        val response = Response(); position.request(true, response)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21))
        assertEquals("POSITION_UNAVAILABLE", response.error)
        assertFalse(position.busy)
        assertEquals(1, provider.closes)
        complete(0, fix())
        assertEquals(1, response.replies)
        assertNull(response.value)
    }
    @Test fun permissionOrVisibilityLostDuringRequestRejectsTheLateMeasurement() {
        val denied = Response(); position.request(true, denied); precise = false; complete(0, fix())
        assertEquals("PRECISE_REQUIRED", denied.error)
        precise = true
        val background = Response(); position.request(true, background); visible = false; complete(1, fix())
        assertEquals("FOREGROUND_REQUIRED", background.error)
        assertNull(background.value)
    }
}
