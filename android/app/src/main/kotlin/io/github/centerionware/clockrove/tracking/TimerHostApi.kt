package io.github.centerionware.clockrove.tracking

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import android.os.Handler
import android.os.Looper
import io.github.centerionware.clockrove.widget.TimerWidget
import io.github.centerionware.clockrove.location.WorkLocationService

/** Versioned codec adapter. Raw transport values do not escape this boundary. */
class TimerHostApi(
    context: Context,
    messenger: BinaryMessenger,
    private val repository: TimerRepository = TimerRepository.get(context.applicationContext),
    private val startTracking: (Context) -> Unit = { WorkLocationService.startForManualTimer(it) },
) {
    private val appContext = context.applicationContext
    private val channel = MethodChannel(messenger, "clockrove/timer/v1",
        io.flutter.plugin.common.StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val events = EventChannel(messenger, "clockrove/timer/versions/v1",
        io.flutter.plugin.common.StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())
    private var subscription: AutoCloseable? = null
    @Volatile private var generation = 0L

    init {
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                subscription?.close()
                val current = ++generation
                subscription = repository.subscribe { version ->
                    mainHandler.post {
                        if (current == generation) sink.success(version)
                    }
                }
            }
            override fun onCancel(arguments: Any?) {
                ++generation
                subscription?.close()
                subscription = null
            }
        })
        channel.setMethodCallHandler { call, result ->
            try {
                val response = TimerDispatch(repository).call(call)
                if (response == null) result.notImplemented()
                else if (response is TimerRepository.Outcome) {
                    TimerWidget.refreshAll(appContext)
                    val action = (call.arguments as? Map<*, *>)?.get("action") as? String
                    if (response.conflict == null && (action == "START" || action == "SWITCH") && repository.snapshot().running != null) {
                        mainHandler.post { startTracking(appContext) }
                    }
                    result.success(mapOf("version" to response.version, "conflict" to response.conflict))
                } else result.success(response)
            } catch (invalid: IllegalArgumentException) {
                result.error("INVALID_ARGUMENT", "Invalid timer request", null)
            } catch (invalid: ClassCastException) {
                result.error("INVALID_ARGUMENT", "Invalid timer request", null)
            } catch (failure: Exception) {
                // Never expose database contents, file paths or personal data through errors.
                result.error("STORAGE_UNAVAILABLE", "Timer storage is unavailable", null)
            }
        }
    }

    fun detach() {
        ++generation
        subscription?.close()
        subscription = null
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
    }

}

/** Strict transport; callers cannot choose timer timestamps or automation origins. */
internal class TimerDispatch(private val repository: TimerRepository) {
    fun call(call: io.flutter.plugin.common.MethodCall): Any? {
        val args = (call.arguments ?: emptyMap<String, Any?>()) as Map<*, *>
        fun string(key: String) = requireNotNull(args[key] as? String)
        fun version(): Long {
            val value = args["version"]; require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it >= 0) }
        }
        return when (call.method) {
            "snapshot" -> encode(repository.snapshot())
            "createJob" -> repository.createJob(string("id"), version(), string("jobId"), string("name"))
            "execute" -> {
                val rawJob = args["jobId"]; require(rawJob == null || rawJob is String)
                repository.executeFromSurface(string("id"), version(), TimerRepository.Action.valueOf(string("action")), rawJob as String?, TimerRepository.Origin.MANUAL)
            }
            else -> null
        }
    }

    private fun encode(snapshot: TimerRepository.Snapshot): Map<String, Any?> = mapOf(
        "version" to snapshot.version,
        "jobs" to snapshot.jobs.map { mapOf("id" to it.id, "name" to it.name) },
        "running" to snapshot.running?.let { mapOf(
            "id" to it.id, "jobId" to it.jobId, "jobName" to it.jobName, "startUtcMillis" to it.startUtcMillis,
        ) },
    )
}
