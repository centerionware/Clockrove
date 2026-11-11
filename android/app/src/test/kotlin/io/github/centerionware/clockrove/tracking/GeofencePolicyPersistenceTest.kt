package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class GeofencePolicyPersistenceTest {
    private lateinit var repository: TimerRepository
    private var clock = 1000L
    private var counter = 0
    private var digestUnavailable = false
    private lateinit var job: String
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, eventDigest = ::fixtureDigest) { clock }
        job = repository.createJob("Synthetic geofence work")
    }
    @After fun teardown() { repository.close() }
    // Public synthetic test key; this does not exercise the device Keystore provider.
    private fun fixtureDigest(value: String): String {
        if (digestUnavailable) throw java.security.GeneralSecurityException("Synthetic unavailable receipt key")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 42 }, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
    private fun saveSite(site: GeofenceStore.Site) = repository.saveSite("config-${counter++}", repository.snapshot().version, site)
    private fun settings(value: GeofenceStore.Settings) = repository.saveAutomation("settings-${counter++}", repository.snapshot().version, value)
    private fun configure(jobId: String = job, ids: List<String> = listOf("site"), grace: Int = 30, departure: Boolean = true) {
        ids.forEach { assertNull(saveSite(GeofenceStore.Site(it, jobId, "Synthetic $it", 10.0, 20.0)).conflict) }
        assertNull(settings(GeofenceStore.Settings(jobId, true, true, departure, grace)).conflict)
    }
    private fun keys(vararg ids: String) = repository.sites().filter { it.id in ids }.map { it.registrationKey }.toSet()
    private fun event(id: String, presence: io.github.centerionware.clockrove.location.geofence.GeofenceMembership.Presence,
        at: Long, vararg ids: String, received: Long = at + 10) = repository.geofenceEvent(id, keys(*ids), presence, at, received)
    private fun stop(id: String, at: Long) = repository.execute(TimerRepository.Command(id, repository.snapshot().version,
        TimerRepository.Action.STOP, null, at, "Etc/UTC", 0, TimerRepository.Origin.NOTIFICATION))
    private fun manualStart(id: String, at: Long, selected: String = job) = repository.execute(TimerRepository.Command(id,
        repository.snapshot().version, TimerRepository.Action.START, selected, at, "Etc/UTC", 0, TimerRepository.Origin.MANUAL))
    private fun reopen() {
        repository.close()
        repository = TimerRepository(RuntimeEnvironment.getApplication(), eventDigest = ::fixtureDigest) { clock }
    }

    @Test fun enableInsideRequiresExitThenEntryAndSuppressionSurvivesRecovery() {
        configure()
        assertNull(event("initial-inside", INSIDE, 1100, "site").startedSessionId)
        assertNull(repository.snapshot().running)
        event("outside", OUTSIDE, 1200, "site")
        val started = event("entry", INSIDE, 1300, "site")
        assertNotNull(started.startedSessionId)
        val version = repository.snapshot().version
        val retry = event("entry", INSIDE, 1300, "site", received = 1400)
        assertTrue(retry.replayed)
        assertEquals(started, retry.copy(replayed = false))
        assertEquals(version, repository.snapshot().version)
        assertNull(stop("manual-stop", 1400).conflict)
        reopen()
        assertTrue(repository.automationPolicies().single().suppressed)
        assertNull(event("still-inside", INSIDE, 1450, "site").startedSessionId)
        assertNull(manualStart("explicit-start", 1460).conflict)
        assertNull(stop("explicit-stop", 1470).conflict)
        event("qualified-exit", OUTSIDE, 1500, "site")
        assertFalse(repository.automationPolicies().single().suppressed)
        assertNotNull(event("qualified-reentry", INSIDE, 1600, "site").startedSessionId)
    }

    @Test fun overlappingSitesUseLatestExitAndKnownTimelyReentryWinsOverDelayedWake() {
        configure(ids = listOf("a", "b"), grace = 1)
        event("outside", OUTSIDE, 1100, "a", "b")
        val session = event("inside", INSIDE, 1500, "a", "b").startedSessionId!!
        event("exit-a", OUTSIDE, 2000, "a")
        assertNull(repository.pendingDeparture())
        event("exit-b-later-delivery", OUTSIDE, 1900, "b", received = 2100)
        assertEquals(TimerRepository.Departure(session, 2000, 62000), repository.pendingDeparture())
        event("return-before-deadline", INSIDE, 61999, "b", received = 70000)
        assertNull(repository.pendingDeparture())
        assertEquals(session, repository.snapshot().running!!.id)
        repository.recordLocation(TimerRepository.LocationPoint("kept", session, 70000, 70010, 10.0, 20.0, 30.0))
        event("leave-again", OUTSIDE, 76000, "b")
        repository.recordLocation(TimerRepository.LocationPoint("pruned", session, 80000, 80010, 10.1, 20.1, 30.0))
        reopen()
        assertEquals(TimerRepository.DepartureResult.FINALIZED, repository.finalizeDeparture(session, 150000))
        assertEquals(listOf("kept"), repository.locationPoints(session).map { it.id })
        clock = 155000
        repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
        assertTrue(repository.geofenceReviewJobs().isEmpty())
        val late = event("late-timely-reentry", INSIDE, 100000, "b", received = 160000)
        assertEquals("TRANSITION_NEEDS_REVIEW", late.conflict)
        assertNull(repository.snapshot().running)
        assertEquals(setOf(job), repository.geofenceReviewJobs())
        clock = 170000
        assertNull(repository.acceptGeofenceReview("accept-history", repository.snapshot().version, job).conflict)
        assertTrue(repository.geofenceReviewJobs().isEmpty())
        assertNull(event("inside-after-review", INSIDE, 180000, "b").startedSessionId)
        event("outside-after-review", OUTSIDE, 190000, "a", "b")
        assertNotNull(event("new-entry-after-review", INSIDE, 200000, "b").startedSessionId)
        SQLiteDatabase.openOrCreateDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db"), null).use { db ->
            db.rawQuery("SELECT end_ms FROM sessions WHERE id=?", arrayOf(session)).use {
                assertTrue(it.moveToFirst()); assertEquals(76000L, it.getLong(0))
            }
        }
    }

    @Test fun unavailableReceiptKeyBlocksAutomaticFinalizationButKeepsManualStopAvailable() {
        configure(grace = 1)
        event("outside", OUTSIDE, 1100, "site")
        val session = event("entry", INSIDE, 2000, "site").startedSessionId!!
        event("exit", OUTSIDE, 3000, "site")
        digestUnavailable = true
        assertEquals("GEOFENCE_RECEIPTS_UNAVAILABLE", event("return-during-key-failure", INSIDE, 4000, "site").conflict)
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(session, 100000))
        assertEquals(session, repository.snapshot().running!!.id)
        assertNull(stop("manual-during-key-failure", 100100).conflict)
        assertNull(repository.pendingDeparture())
        assertNull(repository.snapshot().running)
    }

    @Test fun invalidClockEventsAndProviderFailuresHoldExpiryForExplicitReview() {
        configure(grace = 1)
        event("outside", OUTSIDE, 1100, "site")
        val first = event("entry", INSIDE, 2000, "site").startedSessionId!!
        event("exit", OUTSIDE, 3000, "site")
        assertEquals("INVALID_GEOFENCE_EVENT", event("future-return", INSIDE, 5000, "site", received = 4000).conflict)
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(first, 100000))
        assertNull(stop("manual-after-clock-error", 100100).conflict)
        clock = 110000
        assertNull(repository.acceptGeofenceReview("review-clock-error", repository.snapshot().version, job).conflict)
        event("fresh-outside", OUTSIDE, 111000, "site")
        val second = event("fresh-entry", INSIDE, 112000, "site").startedSessionId!!
        event("fresh-exit", OUTSIDE, 113000, "site")
        clock = 114000
        assertEquals("PROVIDER_UNAVAILABLE", repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE).conflict)
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(second, 200000))
        assertEquals(second, repository.snapshot().running!!.id)
        assertNull(stop("manual-after-provider-error", 201000).conflict)
    }

    @Test fun freshFactsRecoverIdleFaultsAndTimelyReturnsWithoutExtraManualApproval() {
        configure(grace = 1)
        event("outside", OUTSIDE, 1100, "site")
        clock = 1200
        repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
        assertTrue(repository.geofenceReviewJobs().isEmpty())
        assertNull(event("inside-after-idle-fault", INSIDE, 1300, "site").startedSessionId)
        event("fresh-outside", OUTSIDE, 1400, "site")
        val session = event("fresh-entry", INSIDE, 1500, "site").startedSessionId!!
        event("leave", OUTSIDE, 1600, "site")
        clock = 1700
        repository.geofenceProblem(TimerRepository.GeofenceProblem.PROVIDER_UNAVAILABLE)
        assertEquals(setOf(job), repository.geofenceReviewJobs())
        event("known-timely-return", INSIDE, 1800, "site")
        assertNull(repository.pendingDeparture())
        assertTrue(repository.geofenceReviewJobs().isEmpty())
        assertEquals(session, repository.snapshot().running!!.id)
    }

    @Test fun zeroGraceClosesAtExitAndAQualifiedLaterEntryStartsNewWork() {
        configure(grace = 0)
        event("outside", OUTSIDE, 1100, "site")
        val first = event("entry", INSIDE, 2000, "site").startedSessionId!!
        event("zero-exit", OUTSIDE, 3000, "site")
        assertNull(repository.snapshot().running)
        assertNull(repository.pendingDeparture())
        val next = event("later-entry", INSIDE, 4000, "site")
        assertNotNull(next.startedSessionId)
        assertNotEquals(first, next.startedSessionId)
        assertTrue(repository.geofenceReviewJobs().isEmpty())
    }

    @Test fun paidTravelAndOtherJobsNeverSwitchTheActiveSession() {
        val other = repository.createJob("Synthetic other")
        configure(departure = false)
        configure(other, listOf("other-site"))
        event("both-out", OUTSIDE, 1100, "site", "other-site")
        val first = event("first-entry", INSIDE, 2000, "site").startedSessionId!!
        event("travel", OUTSIDE, 3000, "site")
        assertNull(repository.pendingDeparture())
        val otherEntry = event("other-entry", INSIDE, 4000, "other-site")
        assertNull(otherEntry.startedSessionId)
        assertTrue(otherEntry.choices.isEmpty())
        assertEquals(first, repository.snapshot().running!!.id)
        stop("stop-travel", 5000)
        event("duplicate-inside", INSIDE, 5100, "other-site")
        assertNull(repository.snapshot().running)
        event("other-out", OUTSIDE, 5200, "other-site")
        assertNotNull(event("other-back", INSIDE, 5300, "other-site").startedSessionId)
    }

    @Test fun multipleEligibleJobsRequireChoiceWithoutCreatingAnySession() {
        val other = repository.createJob("Synthetic second")
        configure()
        configure(other, listOf("other-site"))
        event("both-out", OUTSIDE, 1100, "site", "other-site")
        val result = event("ambiguous-entry", INSIDE, 2000, "site", "other-site")
        assertEquals(setOf(job, other), result.choices)
        assertNull(result.startedSessionId)
        assertNull(repository.snapshot().running)
        assertTrue(result.choiceRequired)
        val replay = event("ambiguous-entry", INSIDE, 2000, "site", "other-site")
        assertTrue(replay.replayed)
        assertTrue(replay.choiceRequired)
        assertTrue(replay.choices.isEmpty())
        SQLiteDatabase.openOrCreateDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db"), null).use { db ->
            db.rawQuery("SELECT c.id,c.fingerprint,r.payload FROM commands c JOIN geofence_event_results r ON r.id=c.id", null).use {
                while (it.moveToNext()) {
                    assertTrue(it.getString(0).matches(Regex("geofence/[0-9a-f]{64}")))
                    assertTrue(it.getString(1).matches(Regex("[0-9a-f]{64}")))
                    val payload = it.getString(2)
                    assertFalse(payload.contains(job))
                    assertFalse(payload.contains(other))
                    repository.sites().forEach { site -> assertFalse(payload.contains(site.registrationKey)) }
                }
            }
        }
    }

    @Test fun configurationRotatesKeysButLabelOnlyEditsPreserveFactsAndPendingBoundary() {
        configure()
        event("outside", OUTSIDE, 1100, "site")
        val session = event("entry", INSIDE, 1300, "site").startedSessionId!!
        event("exit", OUTSIDE, 1400, "site")
        val before = repository.sites().single()
        val pending = repository.pendingDeparture()
        assertNull(saveSite(before.copy(name = "Renamed site")).conflict)
        assertEquals(before.registrationKey, repository.sites().single().registrationKey)
        assertEquals(pending, repository.pendingDeparture())
        clock = 1500
        assertNull(saveSite(before.copy(latitude = 11.0)).conflict)
        assertNotEquals(before.registrationKey, repository.sites().single().registrationKey)
        assertNull(repository.pendingDeparture())
        val version = repository.snapshot().version
        repository.geofenceEvent("old-registration", setOf(before.registrationKey), INSIDE, 1600, 1610)
        assertEquals(version, repository.snapshot().version)
        event("cached-before-config", INSIDE, 1450, "site", received = 1700)
        assertTrue(repository.geofenceReviewJobs().isEmpty())
        assertEquals(session, repository.snapshot().running!!.id)
        event("fresh-inside", INSIDE, 1800, "site")
        event("fresh-outside", OUTSIDE, 1900, "site")
        assertNotNull(repository.pendingDeparture())
        assertNull(settings(GeofenceStore.Settings(job, enabled = false)).conflict)
        assertNull(repository.pendingDeparture())
        assertTrue(repository.sitesToRegister().isEmpty())
        assertEquals(session, repository.snapshot().running!!.id)
    }

    @Test fun capacityValidationPreservesSettingsAndRegistrationKeysAndKeepsRetryOutcome() {
        configure(ids = (1..100).map { "site-$it" })
        val before = repository.sites()
        val version = repository.snapshot().version
        val site = GeofenceStore.Site("overflow", job, "Synthetic overflow", 10.0, 20.0)
        val failure = repository.saveSite("overflow-command", version, site)
        assertEquals("SITE_CAPACITY_EXCEEDED", failure.conflict)
        assertEquals(before, repository.sites())
        assertEquals(version, repository.snapshot().version)
        val other = repository.createJob("Synthetic inactive")
        val off = GeofenceStore.Site("off-site", other, "Synthetic off", 10.0, 20.0)
        assertNull(saveSite(off).conflict)
        assertEquals("SITE_CAPACITY_EXCEEDED", settings(GeofenceStore.Settings(other, enabled = true)).conflict)
        assertFalse(repository.automationPolicies().first { it.settings.jobId == other }.settings.enabled)
        assertEquals(100, repository.sitesToRegister().size)
        assertNull(repository.removeSite("remove", repository.snapshot().version, "site-100").conflict)
        assertEquals(failure, repository.saveSite("overflow-command", version, site))
        assertNull(settings(GeofenceStore.Settings(other, enabled = true)).conflict)
        assertEquals(100, repository.sitesToRegister().size)
        assertEquals("INVALID_SITE", saveSite(site.copy(radiusMeters = 149.0)).conflict)
        assertEquals("INVALID_SITE", saveSite(site.copy(latitude = Double.NaN)).conflict)
    }

    @Test fun oldEventsCannotOverrideManualWorkAndCommandIdsShareOneDedupBoundary() {
        configure()
        event("out", OUTSIDE, 1100, "site")
        event("in", INSIDE, 1300, "site")
        stop("manual-stop", 1400)
        val late = event("late", OUTSIDE, 1350, "site", received = 1500)
        assertEquals("TRANSITION_IGNORED", late.conflict)
        assertTrue(repository.geofenceReviewJobs().isEmpty())
        assertTrue(repository.automationPolicies().single().suppressed)
        assertNull(repository.snapshot().running)
        assertNull(manualStart("manual-overrides-review", 1600).conflict)
        assertEquals("COMMAND_ID_REUSED", repository.executeFromSurface(repository.geofenceCommandId("late"), repository.snapshot().version,
            TimerRepository.Action.STOP, null, TimerRepository.Origin.WIDGET).conflict)
        assertEquals("COMMAND_ID_REUSED", event("late", INSIDE, 1700, "site").conflict)
        assertNotNull(repository.snapshot().running)
    }

    @Test(timeout = 20000) fun simultaneousManualStopAndNativeExitLeaveNoOpenSessionOrCandidate() {
        configure()
        event("out", OUTSIDE, 1100, "site")
        event("in", INSIDE, 1300, "site")
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        try {
            val exit = pool.submit { ready.await(); event("concurrent-exit", OUTSIDE, 1500, "site") }
            val stopTask = pool.submit {
                ready.await()
                // A fresh native intent captures its version at the serialized boundary.
                synchronized(repository) { stop("concurrent-stop", 1600) }
            }
            ready.countDown()
            exit.get(10, TimeUnit.SECONDS)
            stopTask.get(10, TimeUnit.SECONDS)
            assertNull(repository.snapshot().running)
            assertNull(repository.pendingDeparture())
        } finally { pool.shutdownNow() }
    }

    @Test fun schemaThreeUpgradePreservesPendingAndPointsAndFailedUpgradeRollsBack() {
        repository.close()
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        fun legacy(conflictingIndex: Boolean) {
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
                javaClass.getResourceAsStream("/schema_v3.sql")!!.bufferedReader().use { input ->
                    input.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
                }
                db.execSQL("INSERT INTO jobs VALUES ('old-job','Synthetic old',0)")
                db.execSQL("INSERT INTO sessions VALUES ('old-session','old-job',1000,NULL,'Etc/UTC',0,'MANUAL')")
                db.execSQL("INSERT INTO location_points VALUES ('old-point','old-session',1500,1600,10.0,20.0,30.0)")
                db.execSQL("INSERT INTO pending_departure VALUES ('old-session',2000,62000)")
                if (conflictingIndex) db.execSQL("CREATE INDEX sites_job ON jobs(name)")
                db.version = 3
            }
        }
        legacy(false)
        repository = TimerRepository(app, eventDigest = ::fixtureDigest) { clock }
        assertEquals("old-session", repository.snapshot().running!!.id)
        assertEquals(listOf("old-point"), repository.locationPoints("old-session").map { it.id })
        assertEquals(TimerRepository.Departure("old-session", 2000, 62000), repository.pendingDeparture())
        assertTrue(repository.sites().isEmpty())
        repository.close()
        app.deleteDatabase("clockrove.db")
        legacy(true)
        repository = TimerRepository(app, eventDigest = ::fixtureDigest) { clock }
        try { repository.snapshot(); fail("Conflicting migration accepted") }
        catch (_: android.database.sqlite.SQLiteException) { }
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
            assertEquals(3, db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='automation_policies'", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("SELECT id FROM location_points", null).use { assertTrue(it.moveToFirst()); assertEquals("old-point", it.getString(0)) }
        }
    }
}
