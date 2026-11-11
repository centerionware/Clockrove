package io.github.centerionware.clockrove.location.geofence

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
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
class DepartureAlarmControllerTest {
    private lateinit var context: Context
    private lateinit var repository: TimerRepository
    private lateinit var controller: DepartureAlarmController
    private lateinit var job: String
    private var now = 1000L
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        open()
        job = repository.createJob("Synthetic alarm work")
    }
    private fun open() {
        repository = TimerRepository(context, wallClock = { now })
        controller = DepartureAlarmController(context, repository) { now }
    }
    @After fun teardown() { repository.close() }
    private fun command(id: String, action: TimerRepository.Action, at: Long) =
        repository.execute(TimerRepository.Command(id, repository.snapshot().version, action, job,
            at, "Etc/UTC", 0, TimerRepository.Origin.MANUAL))
    private fun depart(grace: Int = 1): String {
        assertNull(command("start", TimerRepository.Action.START, 1000).conflict)
        val session = repository.snapshot().running!!.id
        assertEquals(TimerRepository.DepartureResult.RECORDED, repository.beginDeparture(session, 2000, grace))
        controller.reconcile()
        return session
    }
    private fun deliveredIntent() = Intent(shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent)

    @Test fun idleSchedulesNothingAndDeadlineUsesOneInexactImmutableExplicitWakeup() {
        controller.reconcile()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        val session = depart()
        controller.reconcile()
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(62000L, alarm.triggerAtTime)
        assertNotEquals(0L, alarm.windowLengthMs)
        assertEquals(0L, alarm.interval)
        assertTrue(alarm.allowWhileIdle)
        val intent = deliveredIntent()
        assertEquals(DepartureAlarmReceiver::class.java.name, intent.component!!.className)
        assertEquals(session, intent.getStringExtra(DepartureAlarmController.SESSION))
        assertTrue(shadowOf(alarm.operation!!).isImmutable)
    }

    @Test fun delayedWakeFinalizesAtExitPrunesTravelAndDuplicateDeliveryIsHarmless() {
        val session = depart()
        repository.recordLocation(TimerRepository.LocationPoint("inside", session, 1500, 1600, 10.0, 20.0, 25.0))
        repository.recordLocation(TimerRepository.LocationPoint("after-exit", session, 3000, 3100, 10.0, 20.0, 25.0))
        val intent = deliveredIntent()
        now = 90000
        assertEquals(TimerRepository.DepartureResult.FINALIZED, controller.deliver(intent))
        assertNull(repository.snapshot().running)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(listOf("inside"), repository.locationPoints(session).map { it.id })
        assertEquals(TimerRepository.DepartureResult.NO_PENDING, controller.deliver(intent))
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("clockrove.db"), null).use { db ->
            db.rawQuery("SELECT end_ms FROM sessions WHERE id=?", arrayOf(session)).use {
                assertTrue(it.moveToFirst()); assertEquals(2000L, it.getLong(0))
            }
        }
    }

    @Test fun earlyAlarmAndOldCandidateOrSessionCannotCloseCurrentWork() {
        val session = depart()
        val old = deliveredIntent()
        now = 61000
        assertEquals(TimerRepository.DepartureResult.NOT_DUE, controller.deliver(old))
        repository.reenterBeforeDeadline(session, 3000)
        repository.beginDeparture(session, 4000, 1)
        controller.reconcile()
        now = 100000
        assertEquals(TimerRepository.DepartureResult.STALE_CANDIDATE, controller.deliver(old))
        assertEquals(session, repository.snapshot().running!!.id)
        val second = deliveredIntent()
        command("manual-stop", TimerRepository.Action.STOP, 100000)
        command("manual-start", TimerRepository.Action.START, 110000)
        val newer = repository.snapshot().running!!.id
        repository.beginDeparture(newer, 120000, 1)
        now = 200000
        assertEquals(TimerRepository.DepartureResult.STALE_CANDIDATE, controller.deliver(second))
        assertEquals(newer, repository.snapshot().running!!.id)
    }

    @Test fun reentryCancelsAlarmAndAnAlreadyDeliveredIntentCannotEndWork() {
        val session = depart()
        val old = deliveredIntent()
        repository.reenterBeforeDeadline(session, 3000)
        controller.reconcile()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        now = 90000
        assertEquals(TimerRepository.DepartureResult.NO_PENDING, controller.deliver(old))
        assertEquals(session, repository.snapshot().running!!.id)
    }

    @Test fun recoveryHoldsExpiryWithoutAnAlarmLoopAndManualStopStillWorks() {
        val session = depart()
        val old = deliveredIntent()
        now = 90000
        controller.recover()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(TimerRepository.DepartureResult.NEEDS_REVIEW, controller.deliver(old))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(session, repository.snapshot().running!!.id)
        assertNull(command("manual-stop-after-recovery", TimerRepository.Action.STOP, 100000).conflict)
        controller.reconcile()
        assertNull(repository.pendingDeparture())
    }

    @Test fun reopeningReadsDurableDeadlineAndOverdueWakeRetainsRecordedBoundary() {
        depart()
        repository.close()
        now = 90000
        open()
        controller.reconcile()
        assertEquals(90000L, alarms.scheduledAlarms.single().triggerAtTime)
        assertEquals(TimerRepository.DepartureResult.FINALIZED, controller.deliver(deliveredIntent()))
        assertNull(repository.snapshot().running)
    }

    @Test fun malformedAlarmsAndUnrelatedBroadcastsHaveNoEffects() {
        depart()
        val before = repository.snapshot()
        assertNull(controller.deliver(Intent("unrelated")))
        assertNull(controller.deliver(Intent(DepartureAlarmController.ACTION)))
        DepartureRecoveryReceiver().onReceive(context, Intent("unrelated"))
        DepartureAlarmReceiver().onReceive(context, Intent("unrelated"))
        assertEquals(before, repository.snapshot())
        val manager = context.packageManager
        for (receiver in listOf(DepartureAlarmReceiver::class.java, DepartureRecoveryReceiver::class.java)) {
            assertFalse(manager.getReceiverInfo(android.content.ComponentName(context, receiver), 0).exported)
        }
        val upgradeReceivers = manager.queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0)
        assertTrue(upgradeReceivers.any { it.activityInfo.name == DepartureRecoveryReceiver::class.java.name })
        val permissions = manager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.USE_EXACT_ALARM" in permissions)
    }
}
