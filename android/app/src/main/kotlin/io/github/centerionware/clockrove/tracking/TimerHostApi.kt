package io.github.centerionware.clockrove.tracking

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.github.centerionware.clockrove.widget.TimerWidget

/** Versioned codec adapter. Raw transport values do not escape this boundary. */
class TimerHostApi(context: Context, messenger: BinaryMessenger) {
    private val appContext = context.applicationContext
    private val repository = TimerRepository.get(appContext)
    private val channel = MethodChannel(messenger, "clockrove/timer/v1",
        io.flutter.plugin.common.StandardMethodCodec.INSTANCE, messenger.makeBackgroundTaskQueue())

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "snapshot" -> result.success(encode(repository.snapshot()))
                    "createJob" -> {
                        val name = requireNotNull(call.argument<String>("name"))
                        val id = repository.createJob(name)
                        TimerWidget.refreshAll(appContext)
                        result.success(id)
                    }
                    "execute" -> {
                        val id = requireNotNull(call.argument<String>("id"))
                        val version = requireNotNull(call.argument<Number>("version")).toLong()
                        val action = TimerRepository.Action.valueOf(requireNotNull(call.argument<String>("action")))
                        val job = call.argument<String>("jobId")
                        require(version >= 0)
                        val outcome = repository.executeFromSurface(id, version, action, job, TimerRepository.Origin.MANUAL)
                        TimerWidget.refreshAll(appContext)
                        result.success(mapOf("version" to outcome.version, "conflict" to outcome.conflict))
                    }
                    else -> result.notImplemented()
                }
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

    fun detach() { channel.setMethodCallHandler(null) }

    private fun encode(snapshot: TimerRepository.Snapshot): Map<String, Any?> = mapOf(
        "version" to snapshot.version,
        "jobs" to snapshot.jobs.map { mapOf("id" to it.id, "name" to it.name) },
        "running" to snapshot.running?.let { mapOf(
            "id" to it.id, "jobId" to it.jobId, "jobName" to it.jobName, "startUtcMillis" to it.startUtcMillis,
        ) },
    )
}
