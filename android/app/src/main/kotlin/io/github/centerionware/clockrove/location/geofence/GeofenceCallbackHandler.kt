package io.github.centerionware.clockrove.location.geofence

import io.github.centerionware.clockrove.tracking.TimerRepository
import org.json.JSONArray

/** No callback coordinates enter history. Only definite, timestamped membership enters SQL. */
internal class GeofenceCallbackHandler(
    private val repository: TimerRepository,
    private val permitted: () -> Boolean,
    private val startLocation: (String) -> Unit,
    private val showChoices: () -> Unit,
) {
    fun receive(keys: Set<String>, presence: GeofenceMembership.Presence?, observed: Long?, received: Long) {
        if (!permitted()) {
            repository.geofenceProblem(TimerRepository.GeofenceProblem.LOCATION_PERMISSION_MISSING)
            return
        }
        if (presence == null || presence == GeofenceMembership.Presence.UNKNOWN ||
            observed == null || observed <= 0 || observed > received || keys.isEmpty() || keys.size > 100) {
            repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
            return
        }
        val id = JSONArray(listOf(presence.name, observed, JSONArray(keys.sorted()))).toString()
        val outcome = repository.geofenceEvent(id, keys, presence, observed, received)
        if (outcome.replayed) return
        val current = repository.snapshot()
        if (outcome.startedSessionId != null && current.running?.id == outcome.startedSessionId && permitted())
            startLocation(outcome.startedSessionId)
        else if (outcome.choices.isNotEmpty() && current.running == null) showChoices()
    }
}
