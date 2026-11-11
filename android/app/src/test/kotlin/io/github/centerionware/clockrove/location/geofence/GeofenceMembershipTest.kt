package io.github.centerionware.clockrove.location.geofence

import org.junit.Assert.*
import org.junit.Test
import io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence.*

class GeofenceMembershipTest {
    private val sites = listOf(GeofenceMembership.Site("a", "job"), GeofenceMembership.Site("b", "job"))
    @Test fun overlappingSitesAndUnknownReadingsDoNotFabricateDeparture() {
        var result = GeofenceMembership.apply(GeofenceMembership.State(), sites, setOf("a"), INSIDE, 1000)
        assertEquals(setOf("job"), result.enteredJobs)
        result = GeofenceMembership.apply(result.state, sites, setOf("a"), OUTSIDE, 2000)
        assertTrue(result.departedJobs.isEmpty()) // b remains unknown, not outside.
        result = GeofenceMembership.apply(result.state, sites, setOf("b"), OUTSIDE, 3000)
        assertEquals(setOf("job"), result.departedJobs)
        result = GeofenceMembership.apply(result.state, sites, setOf("a", "b"), INSIDE, 4000)
        result = GeofenceMembership.apply(result.state, sites, setOf("a"), OUTSIDE, 5000)
        assertEquals(setOf("job"), result.insideJobs)
        assertTrue(result.departedJobs.isEmpty())
    }

    @Test fun duplicateAndLateCallbacksCannotReverseNewerMembership() {
        val initial = GeofenceMembership.apply(GeofenceMembership.State(), sites, setOf("a", "b"), INSIDE, 2000)
        val duplicate = GeofenceMembership.apply(initial.state, sites, setOf("a", "b"), INSIDE, 2000)
        assertEquals(initial.state, duplicate.state)
        assertTrue(duplicate.enteredJobs.isEmpty())
        val late = GeofenceMembership.apply(initial.state, sites, setOf("a"), OUTSIDE, 1000)
        assertEquals(setOf("a"), late.lateOrConflictingSites)
        assertEquals(setOf("job"), late.insideJobs)
        val conflicting = GeofenceMembership.apply(initial.state, sites, setOf("a"), OUTSIDE, 2000)
        assertEquals(setOf("a"), conflicting.lateOrConflictingSites)
    }

    @Test fun activeTimerNeverSwitchesAndMultipleMatchesRequireChoice() {
        val overlapping = listOf(GeofenceMembership.Site("a", "job-a"), GeofenceMembership.Site("b", "job-b"))
        val result = GeofenceMembership.apply(GeofenceMembership.State(), overlapping, setOf("a", "b"), INSIDE, 1000)
        val jobs = setOf("job-a", "job-b")
        val active = GeofenceMembership.selectAutoStart(result, jobs, jobs, emptySet(), "running-session")
        assertNull(active.startJobId)
        assertTrue(active.choices.isEmpty())
        val choices = GeofenceMembership.selectAutoStart(result, jobs, jobs, emptySet(), null)
        assertNull(choices.startJobId)
        assertEquals(jobs, choices.choices)
        assertEquals("job-b", GeofenceMembership.selectAutoStart(result, jobs, jobs, setOf("job-a"), null).startJobId)
        assertNull(GeofenceMembership.selectAutoStart(result, jobs, emptySet(), emptySet(), null).startJobId)
    }

    @Test fun singleQualifiedEntryStartsOnceAndConflictsRequireReconciliation() {
        val jobs = setOf("job")
        val entry = GeofenceMembership.apply(GeofenceMembership.State(), sites, setOf("a", "b"), INSIDE, 2000)
        assertEquals("job", GeofenceMembership.selectAutoStart(entry, jobs, jobs, emptySet(), null).startJobId)
        val duplicate = GeofenceMembership.apply(entry.state, sites, setOf("a", "b"), INSIDE, 2000)
        assertNull(GeofenceMembership.selectAutoStart(duplicate, jobs, jobs, emptySet(), null).startJobId)
        val conflicting = GeofenceMembership.apply(entry.state, sites, setOf("a"), OUTSIDE, 2000)
        assertNull(GeofenceMembership.selectAutoStart(conflicting, jobs, jobs, emptySet(), null).startJobId)
        assertNull(GeofenceMembership.selectAutoStart(entry, jobs, jobs, jobs, null).startJobId)
    }

    @Test fun archivedDisabledAndUnknownSiteIdsCannotCreateMatches() {
        val configured = listOf(GeofenceMembership.Site("disabled", "off", false), GeofenceMembership.Site("old", "archived"))
        val result = GeofenceMembership.apply(GeofenceMembership.State(), configured,
            setOf("disabled", "old", "missing"), INSIDE, 1000, setOf("archived"))
        assertTrue(result.insideJobs.isEmpty())
        assertEquals(setOf("disabled", "old", "missing"), result.ignoredSites)
    }

    @Test fun capacityAndMissingGpsAreExplicitlyRejected() {
        val maximum = (1..100).map { GeofenceMembership.Site("site-$it", "job") }
        assertEquals(100, GeofenceMembership.apply(GeofenceMembership.State(), maximum,
            maximum.map { it.id }.toSet(), OUTSIDE, 1000).state.sites.size)
        try {
            GeofenceMembership.apply(GeofenceMembership.State(), maximum + GeofenceMembership.Site("extra", "job"), emptySet(), OUTSIDE, 1000)
            fail("Capacity exceeded silently")
        } catch (_: IllegalArgumentException) { }
        try {
            GeofenceMembership.apply(GeofenceMembership.State(), sites, setOf("a"), UNKNOWN, 1000)
            fail("Missing GPS treated as a transition")
        } catch (_: IllegalArgumentException) { }
    }
}
