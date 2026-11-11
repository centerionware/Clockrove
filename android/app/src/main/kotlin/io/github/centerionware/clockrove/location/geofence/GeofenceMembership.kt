package io.github.centerionware.clockrove.location.geofence

/** Native transition facts, not a location trail or a second timer writer. */
internal object GeofenceMembership {
    enum class Presence { UNKNOWN, INSIDE, OUTSIDE }
    data class Site(val id: String, val jobId: String, val enabled: Boolean = true)
    data class Observation(val presence: Presence, val observedUtcMillis: Long)
    data class State(
        val sites: Map<String, Observation> = emptyMap(),
        val lastDefiniteJobs: Map<String, Presence> = emptyMap(),
    )
    data class Result(
        val state: State,
        val insideJobs: Set<String>,
        val enteredJobs: Set<String>,
        val departedJobs: Set<String>,
        val lateOrConflictingSites: Set<String>,
        val ignoredSites: Set<String>,
    )
    data class StartSelection(val startJobId: String? = null, val choices: Set<String> = emptySet())

    fun apply(
        state: State,
        configuredSites: List<Site>,
        eventSiteIds: Set<String>,
        presence: Presence,
        observedUtcMillis: Long,
        archivedJobs: Set<String> = emptySet(),
    ): Result {
        require(presence != Presence.UNKNOWN) { "Missing GPS is not a transition" }
        require(configuredSites.all { it.id.isNotBlank() && it.jobId.isNotBlank() })
        require(configuredSites.map { it.id }.distinct().size == configuredSites.size) { "Duplicate site IDs" }
        val eligible = configuredSites.filter { it.enabled && it.jobId !in archivedJobs }
        require(eligible.size <= 100) { "Android supports at most 100 active sites" }
        val siteIds = eligible.map { it.id }.toSet()
        val groups = eligible.groupBy { it.jobId }
        val records = state.sites.filterKeys { it in siteIds }.toMutableMap()
        val definitive = state.lastDefiniteJobs.filterKeys { it in groups }.toMutableMap()
        val previous = groups.mapValues { (job, sites) ->
            definitive[job] ?: jobPresence(sites, records)
        }
        val late = mutableSetOf<String>()
        val ignored = eventSiteIds - siteIds
        for (id in eventSiteIds.intersect(siteIds)) {
            val prior = records[id]
            if (prior != null && (observedUtcMillis < prior.observedUtcMillis ||
                    (observedUtcMillis == prior.observedUtcMillis && prior.presence != presence))) {
                late.add(id)
            } else records[id] = Observation(presence, observedUtcMillis)
        }
        val entered = mutableSetOf<String>()
        val departed = mutableSetOf<String>()
        val inside = mutableSetOf<String>()
        groups.forEach { (job, sites) ->
            val current = jobPresence(sites, records)
            if (current == Presence.INSIDE) inside.add(job)
            if (current != Presence.UNKNOWN) {
                if (current == Presence.INSIDE && previous[job] != Presence.INSIDE) entered.add(job)
                if (current == Presence.OUTSIDE && previous[job] == Presence.INSIDE) departed.add(job)
                definitive[job] = current
            }
            // Unknown does not erase an earlier inside observation or prove exit.
        }
        return Result(State(records.toMap(), definitive.toMap()), inside, entered, departed, late, ignored)
    }

    /** Caller persists arming/suppression and uses the serialized repository for commands. */
    fun selectAutoStart(result: Result, enabledJobs: Set<String>, armedJobs: Set<String>,
        suppressedJobs: Set<String>, activeSessionId: String?): StartSelection {
        if (activeSessionId != null || result.lateOrConflictingSites.isNotEmpty()) return StartSelection()
        val matches = result.insideJobs.intersect(enabledJobs).intersect(armedJobs) - suppressedJobs
        if (result.enteredJobs.intersect(matches).isEmpty()) return StartSelection()
        return if (matches.size == 1) StartSelection(startJobId = matches.single())
            else StartSelection(choices = matches)
    }

    private fun jobPresence(sites: List<Site>, records: Map<String, Observation>): Presence = when {
        sites.any { records[it.id]?.presence == Presence.INSIDE } -> Presence.INSIDE
        sites.all { records[it.id]?.presence == Presence.OUTSIDE } -> Presence.OUTSIDE
        else -> Presence.UNKNOWN
    }
}
