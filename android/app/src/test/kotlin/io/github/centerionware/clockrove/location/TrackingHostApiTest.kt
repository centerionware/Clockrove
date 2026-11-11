package io.github.centerionware.clockrove.location

import android.Manifest
import android.app.Activity
import android.os.Looper
import io.flutter.plugin.common.*
import io.github.centerionware.clockrove.tracking.TimerRepository
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TrackingHostApiTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var repository: TimerRepository
    private lateinit var api: TrackingHostApi
    private val messenger = TestMessenger()
    private var visible = true

    @Before fun setup() {
        activity = controller.setup().get()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app)
        val job = repository.createJob("Synthetic permissions job")
        repository.execute(TimerRepository.Command("start", repository.snapshot().version,
            TimerRepository.Action.START, job, 1000, "Etc/UTC", 0, TimerRepository.Origin.MANUAL))
        api = TrackingHostApi(activity, messenger, repository) { visible }
    }
    @After fun teardown() {
        api.detach()
        repository.close()
        controller.pause().stop().destroy()
    }

    private fun until(condition: () -> Boolean) {
        val limit = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < limit) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Native asynchronous response did not complete", condition())
    }

    @Test fun duplicateEnableCannotReplacePermissionCallbackAndCancelPreventsLateStart() {
        val first = messenger.invoke("enable")
        val second = messenger.invoke("enable")
        assertEquals("REQUEST_PENDING", second.error)
        until { shadowOf(activity).lastRequestedPermission != null }
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            shadowOf(activity).lastRequestedPermission.requestedPermissions)
        assertFalse(first.finished)
        val disabled = messenger.invoke("disable")
        until { disabled.finished }
        assertEquals("CANCELLED", first.error)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(api.permissionResult(2401))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedService)
        assertNotNull(repository.snapshot().running)
    }

    @Test fun approximateGrantStartsOnlyFromVisibleActivityAndKeepsTimer() {
        val response = messenger.invoke("enable")
        until { shadowOf(activity).lastRequestedPermission != null }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        api.permissionResult(2401)
        until { response.finished }
        assertNull(response.error)
        assertEquals("approximate", (response.value as Map<*, *>)["permission"])
        assertEquals(WorkLocationService::class.java.name, shadowOf(activity).nextStartedService.component!!.className)
        assertNotNull(repository.snapshot().running)
    }

    @Test fun backgroundEntryFailsWithoutPermissionPromptOrTimerMutation() {
        visible = false
        val before = repository.snapshot()
        val response = messenger.invoke("enable")
        assertEquals("FOREGROUND_REQUIRED", response.error)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedService)
        assertEquals(before, repository.snapshot())
    }

    @Test fun statusReturnsLatestActualSampleMetadata() {
        val session = repository.snapshot().running!!.id
        repository.recordLocation(TimerRepository.LocationPoint("later", session, 1800, 1900, 10.0, 20.0, 25.0))
        repository.recordLocation(TimerRepository.LocationPoint("delivered-late", session, 1500, 2000, 10.1, 20.1, 40.0))
        val response = messenger.invoke("status")
        until { response.finished }
        assertNull(response.error)
        val status = response.value as Map<*, *>
        assertEquals(1800L, (status["lastMeasuredMillis"] as Number).toLong())
        assertEquals(25.0, (status["lastAccuracyMeters"] as Number).toDouble(), 0.0)
        assertEquals(10.0, (status["lastLatitude"] as Number).toDouble(), 0.0)
        assertEquals(20.0, (status["lastLongitude"] as Number).toDouble(), 0.0)
        repository.execute(TimerRepository.Command("stop", repository.snapshot().version,
            TimerRepository.Action.STOP, null, 3000, "Etc/UTC", 0, TimerRepository.Origin.MANUAL))
        val idle = messenger.invoke("status")
        until { idle.finished }
        val idleStatus = idle.value as Map<*, *>
        assertNull(idleStatus["lastMeasuredMillis"])
        assertNull(idleStatus["lastLatitude"])
        assertNull(idleStatus["lastLongitude"])
    }

    private class Response {
        @Volatile var finished = false
        var value: Any? = null
        var error: String? = null
    }
    private class TestMessenger : BinaryMessenger {
        private val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()
        override fun send(channel: String, message: ByteBuffer?) { }
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { callback?.reply(null) }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            if (handler == null) handlers.remove(channel) else handlers[channel] = handler
        }
        fun invoke(method: String): Response {
            val result = Response()
            val codec = StandardMethodCodec.INSTANCE
            val request = codec.encodeMethodCall(MethodCall(method, null))
            request.flip()
            handlers.getValue("clockrove/tracking/v1").onMessage(request) { data ->
                try {
                    val reply = requireNotNull(data)
                    reply.flip()
                    result.value = codec.decodeEnvelope(reply)
                } catch (failure: FlutterException) { result.error = failure.code }
                finally { result.finished = true }
            }
            return result
        }
    }
}
