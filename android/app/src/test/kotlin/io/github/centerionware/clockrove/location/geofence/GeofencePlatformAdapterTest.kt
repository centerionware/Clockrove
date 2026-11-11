package io.github.centerionware.clockrove.location.geofence

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import io.github.centerionware.clockrove.tracking.GeofenceStore
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class GeofencePlatformAdapterTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var clock = 1000L
    private var allowed = true
    private var cleanupRequired = false
    private var sequence = 0
    private val calls = mutableListOf<Pair<List<GeofenceStore.Site>, (Boolean) -> Unit>>()
    private lateinit var registry: GeofenceRegistrationController
    private val starts = mutableListOf<String>()
    private var choices = 0
    private lateinit var handler: GeofenceCallbackHandler

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, eventDigest = { value ->
            // Public synthetic test key, not Android Keystore/provider evidence.
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 42 }, "HmacSHA256"))
            mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }, wallClock = { clock })
        job = repository.createJob("Synthetic platform work")
        registry = GeofenceRegistrationController(repository, object : GeofenceRegistrationController.Platform {
            override fun replace(sites: List<GeofenceStore.Site>, completed: (Boolean) -> Unit) { calls.add(sites to completed) }
        }, { allowed }, { cleanupRequired }, { cleanupRequired = it })
        handler = GeofenceCallbackHandler(repository, { allowed }, { starts.add(it) }, { choices++ })
    }
    @After fun teardown() { repository.close() }
    private fun configure(id: String = job, site: String = "site") {
        assertNull(repository.saveSite("site-${sequence++}", repository.snapshot().version,
            GeofenceStore.Site(site, id, "Synthetic site", 10.0, 20.0)).conflict)
        settings(id, true)
    }
    private fun settings(id: String = job, enabled: Boolean) {
        assertNull(repository.saveAutomation("settings-${sequence++}", repository.snapshot().version,
            GeofenceStore.Settings(id, enabled, true, true, 1)).conflict)
    }
    private fun event(presence: GeofenceMembership.Presence, at: Long, keys: Set<String> = repository.sitesToRegister().map { it.registrationKey }.toSet()) =
        handler.receive(keys, presence, at, at + 10)
    private fun startAndDepart(): String {
        event(GeofenceMembership.Presence.OUTSIDE, 2000)
        event(GeofenceMembership.Presence.INSIDE, 3000)
        val session = repository.snapshot().running!!.id
        event(GeofenceMembership.Presence.OUTSIDE, 4000)
        return session
    }

    @Test fun defaultIdleNeverContactsPlayServicesOrRequestsPermission() {
        registry.reconcile()
        assertTrue(calls.isEmpty())
        assertFalse(cleanupRequired)
        assertEquals(GeofenceRegistrationController.Health.IDLE, registry.health)
    }

    @Test fun missingPermissionDoesNotRegisterAndReadinessRequiresActualTaskSuccess() {
        configure()
        allowed = false
        registry.reconcile()
        assertTrue(calls.isEmpty())
        assertEquals(GeofenceRegistrationController.Health.PERMISSION_MISSING, registry.health)
        allowed = true
        registry.reconcile()
        assertTrue(cleanupRequired)
        assertEquals(GeofenceRegistrationController.Health.REGISTERING, registry.health)
        calls.single().second(true)
        assertEquals(GeofenceRegistrationController.Health.READY, registry.health)
        registry.reconcile()
        assertEquals(1, calls.size)
    }

    @Test fun registrationFailureHoldsDepartureAndRetriesOnlyAfterExplicitRequest() {
        configure()
        val session = startAndDepart()
        registry.reconcile()
        clock = 5000
        calls.single().second(false)
        assertEquals(GeofenceRegistrationController.Health.UNAVAILABLE, registry.health)
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(session, 90000))
        registry.reconcile()
        assertEquals(1, calls.size)
        registry.reconcile(force = true)
        assertEquals(2, calls.size)
    }

    @Test fun changingGeometryDuringRegistrationCannotReportOldInventoryAsReady() {
        configure()
        registry.reconcile()
        val old = calls.single().first.single().registrationKey
        val site = repository.sites().single()
        clock = 2000
        assertNull(repository.saveSite("move", repository.snapshot().version, site.copy(latitude = 11.0)).conflict)
        registry.reconcile()
        assertEquals(1, calls.size)
        calls[0].second(true)
        assertEquals(2, calls.size)
        assertNotEquals(old, calls[1].first.single().registrationKey)
        assertEquals(GeofenceRegistrationController.Health.REGISTERING, registry.health)
        calls[1].second(true)
        assertEquals(GeofenceRegistrationController.Health.READY, registry.health)
    }

    @Test fun disablingDuringRegistrationRemovesOldInventoryWithoutFalseReadiness() {
        configure()
        registry.reconcile()
        settings(enabled = false)
        calls[0].second(true)
        assertTrue(calls[1].first.isEmpty())
        assertTrue(cleanupRequired)
        calls[1].second(true)
        assertFalse(cleanupRequired)
        assertEquals(GeofenceRegistrationController.Health.IDLE, registry.health)
    }

    @Test fun processRecoveryHoldsCandidateBeforeAnyAlarmCanFinalizeIt() {
        configure()
        val session = startAndDepart()
        cleanupRequired = true
        clock = 90000
        registry.prepareRecovery()
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(session, clock))
        assertNull(repository.schedulableDeparture())
        assertEquals(session, repository.snapshot().running!!.id)
        registry.reconcile()
        assertEquals(1, calls.size)
    }

    @Test fun permissionRevocationRemovesRegistrationsAndPreservesManualTiming() {
        configure()
        registry.reconcile()
        calls[0].second(true)
        val session = startAndDepart()
        clock = 5000
        allowed = false
        registry.reconcile()
        assertTrue(calls[1].first.isEmpty())
        calls[1].second(true)
        assertEquals(GeofenceRegistrationController.Health.PERMISSION_MISSING, registry.health)
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(session, 90000))
        assertNull(repository.execute(TimerRepository.Command("manual-stop", repository.snapshot().version,
            TimerRepository.Action.STOP, null, 100000, "Etc/UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
    }

    @Test fun providerErrorInvalidatesReadinessAndResumeCanRetry() {
        configure()
        registry.reconcile()
        calls[0].second(true)
        registry.providerUnavailable()
        assertEquals(GeofenceRegistrationController.Health.UNAVAILABLE, registry.health)
        registry.reconcile(force = true)
        assertEquals(2, calls.size)
    }

    @Test fun definiteCallbacksStartOnceAndReplayDoesNotRepeatLocationHandoff() {
        configure()
        event(GeofenceMembership.Presence.INSIDE, 1500)
        assertTrue(starts.isEmpty())
        event(GeofenceMembership.Presence.OUTSIDE, 2000)
        event(GeofenceMembership.Presence.INSIDE, 3000)
        val session = repository.snapshot().running!!.id
        event(GeofenceMembership.Presence.INSIDE, 3000)
        assertEquals(listOf(session), starts)
        assertTrue(repository.locationPoints(session).isEmpty())
    }

    @Test fun overlappingJobsNotifyOnceWithoutStartingOrSwitchingWork() {
        configure()
        val other = repository.createJob("Synthetic overlapping work")
        configure(other, "other-site")
        event(GeofenceMembership.Presence.OUTSIDE, 2000)
        event(GeofenceMembership.Presence.INSIDE, 3000)
        event(GeofenceMembership.Presence.INSIDE, 3000)
        assertEquals(1, choices)
        assertNull(repository.snapshot().running)
        assertTrue(starts.isEmpty())
        assertNull(repository.execute(TimerRepository.Command("manual-choice", repository.snapshot().version,
            TimerRepository.Action.START, job, 4000, "Etc/UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
        event(GeofenceMembership.Presence.OUTSIDE, 5000)
        event(GeofenceMembership.Presence.INSIDE, 6000)
        assertEquals(job, repository.snapshot().running!!.jobId)
        assertEquals(1, choices)
    }

    @Test fun missingMeasurementAndRevokedPermissionNeverManufactureDepartureOrStart() {
        configure()
        val session = startAndDepart()
        clock = 5000
        handler.receive(repository.sitesToRegister().map { it.registrationKey }.toSet(), GeofenceMembership.Presence.INSIDE, null, 5000)
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, repository.finalizeDeparture(session, 90000))
        assertEquals(session, repository.snapshot().running!!.id)
        assertEquals(1, starts.size)
        allowed = false
        event(GeofenceMembership.Presence.INSIDE, 6000)
        assertEquals(1, starts.size)
    }

    @Test fun obsoleteRegistrationCallbacksCannotStartWork() {
        configure()
        event(GeofenceMembership.Presence.OUTSIDE, 2000)
        val old = repository.sitesToRegister().map { it.registrationKey }.toSet()
        clock = 2500
        val site = repository.sites().single()
        repository.saveSite("move", repository.snapshot().version, site.copy(latitude = 11.0))
        event(GeofenceMembership.Presence.INSIDE, 3000, old)
        assertNull(repository.snapshot().running)
        assertTrue(starts.isEmpty())
    }

    @Test fun androidTokenIsExplicitMutableAndReceiverIsPrivateWithSeparateBackgroundPermission() {
        val app = RuntimeEnvironment.getApplication()
        val token = AndroidGeofencePlatform.pendingIntent(app)
        val pending = shadowOf(token)
        assertEquals(GeofenceTransitionReceiver::class.java.name, pending.savedIntent.component!!.className)
        assertEquals(AndroidGeofencePlatform.ACTION, pending.savedIntent.action)
        assertFalse(pending.isImmutable)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(pending.flags and android.app.PendingIntent.FLAG_MUTABLE != 0)
        assertFalse(app.packageManager.getReceiverInfo(ComponentName(app, GeofenceTransitionReceiver::class.java), 0).exported)
        val permissions = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)
        val granted = shadowOf(app)
        granted.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(AndroidGeofencePlatform.hasPermission(app))
        granted.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) {
            granted.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            assertFalse(AndroidGeofencePlatform.hasPermission(app))
            granted.grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        assertTrue(AndroidGeofencePlatform.hasPermission(app))
        GeofenceTransitionReceiver().onReceive(app, Intent("unrelated"))
    }
}
