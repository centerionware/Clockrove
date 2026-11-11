package io.github.centerionware.clockrove.location.geofence

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Looper
import android.provider.Settings
import io.flutter.plugin.common.*
import io.github.centerionware.clockrove.tracking.GeofenceStore
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
@Config(sdk = [29, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class AutomationHostApiTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var repository: TimerRepository
    private lateinit var api: AutomationHostApi
    private lateinit var job: String
    private val messenger = TestMessenger()
    private var visible = true
    private var permissionChanges = 0
    @Before fun setup() {
        activity = controller.setup().get()
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        repository = TimerRepository(app)
        job = repository.createJob("Synthetic site settings")
        api = AutomationHostApi(activity, messenger, repository, { permissionChanges++ }) { visible }
    }
    @After fun teardown() { api.detach(); repository.close(); controller.pause().stop().destroy() }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1) }
        assertTrue("Native asynchronous response did not complete", condition())
    }
    @Test fun foregroundAndPurposeAreRequiredBeforeRequestingLocation() {
        assertEquals("PURPOSE_REQUIRED", messenger.invoke("requestPrecise").error)
        visible = false
        assertEquals("FOREGROUND_REQUIRED", messenger.invoke("requestPrecise", mapOf("purposeAccepted" to true)).error)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun precisePromptIsSeparateAndDuplicateRequestsCannotReplaceIt() {
        val first = messenger.invoke("requestPrecise", mapOf("purposeAccepted" to true))
        assertFalse(first.finished)
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            shadowOf(activity).lastRequestedPermission.requestedPermissions)
        assertEquals("REQUEST_PENDING", messenger.invoke("requestBackground", mapOf("purposeAccepted" to true)).error)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(api.permissionResult(2411))
        assertEquals(false, (first.value as Map<*, *>)["precise"])
        assertEquals(false, (first.value as Map<*, *>)["background"])
        assertEquals(1, permissionChanges)
        assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun backgroundRequiresPreciseAndUsesTheOsSpecificSeparateFlow() {
        assertEquals("PRECISE_REQUIRED", messenger.invoke("requestBackground", mapOf("purposeAccepted" to true)).error)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val result = messenger.invoke("requestBackground", mapOf("purposeAccepted" to true))
        if (Build.VERSION.SDK_INT == 29) {
            assertArrayEquals(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), shadowOf(activity).lastRequestedPermission.requestedPermissions)
            assertFalse(result.finished)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            api.permissionResult(2412)
            assertEquals(true, (result.value as Map<*, *>)["background"])
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
            assertNull(shadowOf(activity).lastRequestedPermission)
            assertTrue(result.finished)
            assertEquals(false, (result.value as Map<*, *>)["background"])
        }
    }
    @Test fun snapshotCarriesCoherentCandidateAndNeverExposesRegistrationKeys() {
        repository.saveSite("site", repository.snapshot().version, GeofenceStore.Site("site-id", job, "Synthetic place", 10.0, 20.0))
        repository.execute(TimerRepository.Command("start", repository.snapshot().version, TimerRepository.Action.START,
            job, 1000, "Etc/UTC", 0, TimerRepository.Origin.MANUAL))
        repository.beginDeparture(repository.snapshot().running!!.id, 2000, 30)
        val response = messenger.invoke("snapshot", mapOf("jobId" to job))
        until { response.finished }
        assertNull(response.error)
        val snapshot = response.value as Map<*, *>
        assertEquals(repository.snapshot().version, (snapshot["version"] as Number).toLong())
        assertEquals(2000L, ((snapshot["pending"] as Map<*, *>)["detected"] as Number).toLong())
        val site = (snapshot["sites"] as List<*>).single() as Map<*, *>
        assertEquals("site-id", site["id"])
        assertFalse(site.containsKey("registrationKey"))
        assertFalse(site.containsKey("observed_ms"))
    }
    @Test fun staleSiteMutationIsRejectedWithoutChangingInventory() {
        val version = repository.snapshot().version
        repository.createJob("Synthetic concurrent job")
        val response = messenger.invoke("saveSite", mapOf("id" to "edit", "version" to version, "jobId" to job,
            "siteId" to "site", "name" to "Synthetic", "latitude" to 10.0, "longitude" to 20.0, "radius" to 150.0, "enabled" to true))
        until { response.finished }
        assertEquals("STALE_STATE", (response.value as Map<*, *>)["conflict"])
        assertTrue(repository.sites().isEmpty())
    }
    @Test fun detachResolvesPendingAndLateCallbacksCannotStartAnything() {
        val response = messenger.invoke("requestPrecise", mapOf("purposeAccepted" to true))
        api.detach()
        assertEquals("ACTIVITY_DETACHED", response.error)
        assertTrue(api.permissionResult(2411))
        assertEquals(0, permissionChanges)
        assertNull(shadowOf(activity).nextStartedService)
    }
    @Test fun setupPositionCodecCancelsOnStopAndNeverMutatesWorkHistory() {
        api.detach()
        val callbacks = mutableListOf<(android.location.Location?) -> Unit>()
        var cancelled = 0
        val provider = object : SitePositionProvider {
            override fun request(completed: (android.location.Location?) -> Unit, failed: () -> Unit): AutoCloseable {
                callbacks.add(completed)
                return AutoCloseable { cancelled++ }
            }
        }
        api = AutomationHostApi(activity, messenger, repository, {}, provider) { visible }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val before = repository.snapshot()
        val first = messenger.invoke("currentPosition", mapOf("purposeAccepted" to true))
        api.foregroundStopped()
        assertEquals("FOREGROUND_REQUIRED", first.error)
        callbacks[0](android.location.Location("synthetic").apply { latitude = 10.0; longitude = 20.0; accuracy = 25f; time = System.currentTimeMillis() })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("FOREGROUND_REQUIRED", first.error)
        val second = messenger.invoke("currentPosition", mapOf("purposeAccepted" to true))
        callbacks[1](android.location.Location("synthetic").apply { latitude = 10.0; longitude = 20.0; accuracy = 25f; time = System.currentTimeMillis() })
        until { second.finished }
        assertNull(second.error)
        assertEquals(10.0, (second.value as Map<*, *>)["latitude"])
        assertTrue((second.value as Map<*, *>)["measured"] is Number)
        assertEquals(before, repository.snapshot())
        assertTrue(repository.locationPoints("off-clock").isEmpty())
        assertEquals(2, cancelled)
    }
    @Test fun monitoringRetryRequiresForegroundAndDoesNotStartATimerOrRequestPermission() {
        val before = repository.snapshot()
        visible = false
        assertEquals("FOREGROUND_REQUIRED", messenger.invoke("retryMonitoring").error)
        assertEquals(0, permissionChanges)
        visible = true
        val response = messenger.invoke("retryMonitoring")
        assertTrue(response.finished)
        assertNull(response.error)
        assertEquals(1, permissionChanges)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedService)
        assertEquals(before, repository.snapshot())
    }
    private class Response {
        @Volatile var finished = false
        var value: Any? = null
        var error: String? = null
    }
    private class TestMessenger : BinaryMessenger {
        private val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()
        override fun send(channel: String, message: ByteBuffer?) {}
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { callback?.reply(null) }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            if (handler == null) handlers.remove(channel) else handlers[channel] = handler
        }
        fun invoke(method: String, arguments: Map<String, Any?>? = null): Response {
            val response = Response()
            val codec = StandardMethodCodec.INSTANCE
            val request = codec.encodeMethodCall(MethodCall(method, arguments)).apply { flip() }
            handlers.getValue("clockrove/automation/v1").onMessage(request) { data ->
                try { val reply = requireNotNull(data).apply { flip() }; response.value = codec.decodeEnvelope(reply) }
                catch (failure: FlutterException) { response.error = failure.code }
                finally { response.finished = true }
            }
            return response
        }
    }
}
