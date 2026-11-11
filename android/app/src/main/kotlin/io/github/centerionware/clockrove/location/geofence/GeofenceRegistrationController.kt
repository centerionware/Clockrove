package io.github.centerionware.clockrove.location.geofence

import io.github.centerionware.clockrove.tracking.GeofenceStore
import io.github.centerionware.clockrove.tracking.TimerRepository

/** All methods and completion callbacks run on the native automation worker. */
internal class GeofenceRegistrationController(
    private val repository: TimerRepository,
    private val platform: Platform,
    private val permitted: () -> Boolean,
    private val wasRequested: () -> Boolean,
    private val rememberRequested: (Boolean) -> Unit,
    private val onHealth: (Health) -> Unit = {},
) {
    interface Platform {
        /** Empty inventory removes this app's geofences without registering replacements. */
        fun replace(sites: List<GeofenceStore.Site>, completed: (Boolean) -> Unit)
    }
    enum class Health { IDLE, PERMISSION_MISSING, REGISTERING, READY, UNAVAILABLE }
    var health = Health.IDLE
        private set(value) {
            if (field == value) return
            field = value
            onHealth(value)
        }
    private data class Request(val keys: List<String>, val permitted: Boolean)
    private var attempted: Request? = null
    private var observedRequest: Request? = null
    private var faultEpoch = 0L
    private var inFlight = false
    private var initial = true
    private var retry = false

    fun prepareRecovery() {
        if (initial) {
            initial = false
            if (wasRequested() && repository.sitesToRegister().isNotEmpty())
                repository.geofenceProblem(TimerRepository.GeofenceProblem.RECOVERY_UNCERTAIN)
        }
    }

    fun providerUnavailable() {
        faultEpoch++
        health = Health.UNAVAILABLE
        repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
    }

    fun reconcile(force: Boolean = false) {
        prepareRecovery()
        val sites = repository.sitesToRegister()
        val allowed = permitted()
        val request = Request(sites.map { it.registrationKey }.sorted(), allowed)
        val changed = request != observedRequest
        observedRequest = request
        if (changed && sites.isNotEmpty() && !allowed)
            repository.geofenceProblem(TimerRepository.GeofenceProblem.LOCATION_PERMISSION_MISSING)
        if (inFlight) { retry = retry || force || request != attempted; return }
        if (request == attempted && !force) return
        attempted = request
        val target = if (allowed) sites else emptyList()
        val idleHealth = if (sites.isEmpty()) Health.IDLE else Health.PERMISSION_MISSING
        if (target.isEmpty() && !wasRequested()) { health = idleHealth; return }
        inFlight = true
        health = Health.REGISTERING
        // Persist only a cleanup hint, never coordinates, keys or a claim of success.
        if (target.isNotEmpty()) rememberRequested(true)
        val epoch = faultEpoch
        platform.replace(target) { success ->
            inFlight = false
            val current = Request(repository.sitesToRegister().map { it.registrationKey }.sorted(), permitted())
            if (current != request || retry) {
                retry = false
                reconcile(force = true)
            } else if (epoch != faultEpoch) {
                health = Health.UNAVAILABLE
            } else if (success) {
                rememberRequested(target.isNotEmpty())
                health = if (target.isEmpty()) idleHealth else Health.READY
            } else {
                health = Health.UNAVAILABLE
                repository.geofenceProblem(TimerRepository.GeofenceProblem.REGISTRATION_UNAVAILABLE)
            }
        }
    }
}
