package io.github.centerionware.clockrove.tracking

import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TimerHostApiTest {
    private lateinit var repository: TimerRepository
    private lateinit var host: TimerHostApi
    private val messenger = TestMessenger()
    private val trackingStarts = AtomicInteger()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app)
        host = TimerHostApi(app, messenger, repository) { trackingStarts.incrementAndGet() }
    }

    @After fun teardown() {
        host.detach()
        repository.close()
    }

    @Test fun aCommittedManualClockInStartsLocationAndClockOutDoesNot() {
        val job = repository.createJob("Synthetic manual GPS job")
        val started = messenger.invoke("execute", mapOf(
            "id" to "manual-start", "version" to repository.snapshot().version,
            "action" to "START", "jobId" to job,
        ))
        until { started.finished }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(started.error)
        assertNotNull(repository.snapshot().running)
        assertEquals(1, trackingStarts.get())

        val stopped = messenger.invoke("execute", mapOf(
            "id" to "manual-stop", "version" to repository.snapshot().version,
            "action" to "STOP", "jobId" to null,
        ))
        until { stopped.finished }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(stopped.error)
        assertNull(repository.snapshot().running)
        assertEquals(1, trackingStarts.get())
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Native timer command did not complete", condition())
    }

    private class Response {
        @Volatile var finished = false
        var error: String? = null
    }

    private class TestMessenger : BinaryMessenger {
        private val handlers = mutableMapOf<String, BinaryMessenger.BinaryMessageHandler>()
        override fun send(channel: String, message: ByteBuffer?) { }
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) { callback?.reply(null) }
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            if (handler == null) handlers.remove(channel) else handlers[channel] = handler
        }
        fun invoke(method: String, arguments: Map<String, Any?>): Response {
            val response = Response()
            val codec = StandardMethodCodec.INSTANCE
            val request = codec.encodeMethodCall(MethodCall(method, arguments))
            request.position(0)
            handlers.getValue("clockrove/timer/v1").onMessage(request) { data ->
                try {
                    val reply = requireNotNull(data)
                    reply.flip()
                    codec.decodeEnvelope(reply)
                } catch (failure: FlutterException) {
                    response.error = failure.code
                } finally {
                    response.finished = true
                }
            }
            return response
        }
    }
}
