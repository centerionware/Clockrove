package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
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
@Config(sdk = [24, 35])
class WidgetConfigurationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var now = 10000L
    private var sequence = 0
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        app.deleteDatabase("clockrove.db"); open(); job = repository.createJob("Synthetic widget default")
    }
    private fun open() { repository = TimerRepository(app, wallClock = { now }) }
    @After fun close() { repository.close() }
    private fun save(widget: Int, jobId: String? = job, id: String = "configuration-${sequence++}", expected: Long = version) =
        repository.saveWidgetConfiguration(id, expected, widget, jobId)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun start(widget: Int, expected: Long = version): TimerRepository.Outcome {
        val binding = repository.widgetSnapshot(widget).configuration
        return repository.startConfiguredWidget(widget, expected, binding.token!!, binding.jobId!!)
    }
    private fun stop(): TimerRepository.Outcome {
        val session = repository.snapshot().running!!
        return repository.execute(TimerRepository.Command("stop-${sequence++}", version, TimerRepository.Action.STOP,
            null, session.startUtcMillis + 1000, "UTC", 0, TimerRepository.Origin.MANUAL))
    }
    @Test fun independentDefaultAndPickerBindingsPersistWithoutStartingTimers() {
        val unconfigured = repository.widgetSnapshot(1)
        assertNull(unconfigured.configuration.token); assertFalse(unconfigured.missingDefault)
        assertNull(save(1).conflict); assertNull(save(2, null).conflict)
        val first = repository.widgetSnapshot(1); val second = repository.widgetSnapshot(2)
        assertEquals(job, first.defaultJob!!.id); assertNull(second.defaultJob)
        assertNotEquals(first.configuration.token, second.configuration.token)
        repository.close(); open()
        assertEquals(first.configuration, repository.widgetSnapshot(1).configuration)
        assertEquals(second.configuration, repository.widgetSnapshot(2).configuration)
        assertNull(repository.snapshot().running)
    }
    @Test fun configurationCommandsReplayOriginalOutcomesAndRejectStaleOrInvalidDrafts() {
        val captured = version
        val result = save(1, id = "same", expected = captured)
        val binding = repository.widgetSnapshot(1).configuration
        assertEquals(result, save(1, id = "same", expected = captured))
        assertEquals(binding, repository.widgetSnapshot(1).configuration)
        assertEquals("COMMAND_ID_REUSED", save(1, null, "same", captured).conflict)
        assertEquals("STALE_STATE", save(2, expected = captured).conflict)
        assertNull(repository.widgetSnapshot(2).configuration.token)
        assertEquals("INVALID_WIDGET", save(0).conflict)
        assertEquals("JOB_UNAVAILABLE", save(3, "missing").conflict)
        assertNull(repository.widgetSnapshot(3).configuration.token)
        assertEquals(result.version, version)
    }
    @Test fun startUsesTheNativeClockAndWidgetOriginWithoutFlutterOrLocation() {
        assertNull(save(1).conflict)
        val earliest = System.currentTimeMillis()
        assertNull(start(1).conflict)
        val latest = System.currentTimeMillis()
        val running = repository.snapshot().running!!
        assertEquals(job, running.jobId); assertTrue(running.startUtcMillis in earliest..latest)
        val entry = repository.sessionHistory(job).page.entries.single()
        assertEquals("WIDGET", entry.origin); assertNull(entry.end)
        assertTrue(repository.locationPoints(running.id).isEmpty())
    }
    @Test fun oldConfigurationCannotStartANewDefaultEvenWithACurrentStateVersion() {
        assertNull(save(1).conflict)
        val old = repository.widgetSnapshot(1).configuration
        val other = repository.createJob("Synthetic replacement")
        assertNull(save(1, other).conflict)
        assertEquals("WIDGET_CONFIGURATION_CHANGED", repository.startConfiguredWidget(1, version, old.token!!, job).conflict)
        assertNull(repository.snapshot().running)
        assertNull(start(1).conflict); assertEquals(other, repository.snapshot().running!!.jobId)
    }
    @Test fun unavailableDefaultStaysDistinctFromPickerThroughArchiveRestoreAndPurge() {
        assertNull(save(1).conflict)
        val binding = repository.widgetSnapshot(1).configuration
        assertNull(repository.archiveJob("archive", version, job).conflict)
        assertTrue(repository.widgetSnapshot(1).missingDefault)
        assertEquals("JOB_UNAVAILABLE", start(1).conflict)
        assertNull(repository.restoreJob("restore", version, job).conflict)
        assertFalse(repository.widgetSnapshot(1).missingDefault)
        assertEquals(binding, repository.widgetSnapshot(1).configuration)
        assertNull(repository.snapshot().running)
        assertNull(repository.archiveJob("archive-again", version, job).conflict)
        now += 30L * 24 * 60 * 60 * 1000
        assertNull(repository.deleteJob("purge", version, job).conflict)
        assertTrue(repository.widgetSnapshot(1).missingDefault)
        assertEquals(binding, repository.widgetSnapshot(1).configuration)
        assertNull(repository.widgetSnapshot(1).defaultJob)
        assertEquals("JOB_UNAVAILABLE", start(1).conflict)
        assertNull(repository.snapshot().running)
    }
    @Test fun duplicateStartAfterStopOrReconfigurationNeverRestartsWork() {
        assertNull(save(1).conflict)
        val binding = repository.widgetSnapshot(1).configuration
        val captured = version
        val original = start(1, captured)
        assertNull(original.conflict); assertNull(stop().conflict)
        assertNull(save(1, null).conflict)
        val after = version
        assertEquals(original, repository.startConfiguredWidget(1, captured, binding.token!!, job))
        assertEquals(after, version); assertNull(repository.snapshot().running)
        assertEquals("COMMAND_ID_REUSED", repository.startConfiguredWidget(1, captured, binding.token!!, "different-job").conflict)
    }
    @Test fun anotherRunningJobIsNeverSilentlySwitched() {
        assertNull(save(1).conflict)
        val other = repository.createJob("Synthetic currently running")
        assertNull(repository.execute(TimerRepository.Command("manual-start", version, TimerRepository.Action.START,
            other, 1000, "UTC", 0, TimerRepository.Origin.MANUAL)).conflict)
        val before = repository.snapshot()
        assertEquals("ALREADY_RUNNING", start(1).conflict)
        assertEquals(before, repository.snapshot())
    }
    @Test fun competingWidgetsCommitAtMostOneOpenSession() {
        val other = repository.createJob("Synthetic second widget")
        assertNull(save(1).conflict); assertNull(save(2, other).conflict)
        val captured = version
        val ready = CountDownLatch(2); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { id -> pool.submit<TimerRepository.Outcome> {
                ready.countDown(); check(release.await(10, TimeUnit.SECONDS)); start(id, captured)
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); release.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.conflict == null })
            assertEquals(1, outcomes.count { it.conflict == "STALE_STATE" })
            inspect { db -> db.rawQuery("SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) } }
        } finally { pool.shutdownNow() }
    }
    @Test fun widgetDeletionAndIdReuseFenceOldPendingIntentsWithoutAffectingOtherWidgets() {
        assertNull(save(1).conflict); assertNull(save(2, null).conflict)
        val old = repository.widgetSnapshot(1).configuration
        val other = repository.widgetSnapshot(2).configuration
        repository.removeWidgetConfigurations(listOf(1, 1))
        assertNull(repository.widgetSnapshot(1).configuration.token)
        assertEquals(other, repository.widgetSnapshot(2).configuration)
        val after = version
        repository.removeWidgetConfigurations(listOf(1)); assertEquals(after, version)
        assertNull(save(1).conflict)
        assertNotEquals(old.token, repository.widgetSnapshot(1).configuration.token)
        assertEquals("WIDGET_CONFIGURATION_CHANGED", repository.startConfiguredWidget(1, version, old.token!!, job).conflict)
        assertNull(repository.snapshot().running)
        try { repository.removeWidgetConfigurations(listOf(2, 0)); fail("Malformed removal accepted") } catch (_: IllegalArgumentException) {}
        assertEquals(other, repository.widgetSnapshot(2).configuration)
    }
    @Test fun platformRemovalResolvesSaveRetriesWithoutResurrectingOrReconfiguringReusedIds() {
        val captured = version
        val committed = repository.saveWidgetConfiguration("lost-save", captured, 1, job)
        assertNull(committed.conflict)
        repository.removeWidgetConfigurations(listOf(1))
        assertEquals(committed, repository.saveWidgetConfiguration("lost-save", captured, 1, job, available = false))
        assertNull(repository.widgetSnapshot(1).configuration.token)
        val before = version
        val unavailable = repository.saveWidgetConfiguration("removed-save", before, 2, job, available = false)
        assertEquals("WIDGET_UNAVAILABLE", unavailable.conflict)
        assertEquals(before, version)
        assertNull(repository.widgetSnapshot(2).configuration.token)
        assertNull(save(2, null).conflict)
        val replacement = repository.widgetSnapshot(2).configuration
        assertEquals(unavailable, repository.saveWidgetConfiguration("removed-save", before, 2, job, available = true))
        assertEquals(replacement, repository.widgetSnapshot(2).configuration)
    }
    @Test fun versionFiveUpgradePreservesWorkReceiptsSettingsAndBoardWithoutCreatingBindings() {
        repository.close()
        app.deleteDatabase("clockrove.db")
        job = "legacy-widget-job"
        // Frozen released DDL, not today's helper with its version number lowered.
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("clockrove.db"), null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            javaClass.getResourceAsStream("/schema_v5.sql")!!.bufferedReader().use { source ->
                source.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            }
            db.execSQL("INSERT INTO jobs(id,name) VALUES('legacy-widget-job','Synthetic legacy widget job')")
            db.execSQL("INSERT INTO kanban_columns VALUES('legacy-column','legacy-widget-job','To do',0,0)")
            db.execSQL("INSERT INTO sessions VALUES('legacy-session','legacy-widget-job',1000,NULL,'UTC',0,'MANUAL')")
            db.execSQL("INSERT INTO location_points VALUES('old-point','legacy-session',1500,1600,1.0,2.0,3.0)")
            db.execSQL("UPDATE state SET version=42")
            db.execSQL("UPDATE app_settings SET company_name='Synthetic migration company',default_hourly_minor=12500")
            val fingerprint = """[41,"START","legacy-widget-job",1000,"UTC",0,"MANUAL"]"""
            db.execSQL("INSERT INTO commands VALUES('old-start',?,42,NULL)", arrayOf(fingerprint))
            db.version = 5
        }
        open()
        assertEquals(42L, version)
        assertEquals(TimerRepository.Session("legacy-session", job, 1000, "Synthetic legacy widget job"), repository.snapshot().running)
        assertEquals(listOf("legacy-column"), repository.boardSnapshot(job).board.columns.map { it.id })
        assertEquals("old-point", repository.locationPoints("legacy-session").single().id)
        assertEquals("Synthetic migration company", repository.businessSnapshot().settings.companyName)
        assertEquals(java.math.BigInteger.valueOf(12500), repository.businessSnapshot().settings.defaultHourlyMinor)
        assertNull(repository.executeFromSurface("old-start", 41, TimerRepository.Action.START, job, TimerRepository.Origin.MANUAL).conflict)
        assertNull(repository.widgetSnapshot(1).configuration.token)
        assertEquals("", repository.businessSnapshot().settings.receivablesEmail)
        inspect { db ->
            assertEquals(8, db.version)
            db.rawQuery("SELECT COUNT(*) FROM invoice_payments", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun failedVersionFiveUpgradeRollsBackWithoutReplacingExistingState() {
        assertNull(save(1).conflict)
        val binding = repository.widgetSnapshot(1).configuration
        val captured = version
        repository.close()
        // A conflicting table is malformed legacy input, not permission to drop it.
        inspect { it.version = 5 }
        open()
        try { repository.snapshot(); fail("Conflicting migration succeeded") } catch (_: android.database.SQLException) {}
        inspect { db ->
            assertEquals(5, db.version)
            db.rawQuery("SELECT token FROM widget_configurations WHERE widget_id=1", null).use {
                assertTrue(it.moveToFirst()); assertEquals(binding.token, it.getString(0))
            }
            db.rawQuery("SELECT version FROM state WHERE id=1", null).use { assertTrue(it.moveToFirst()); assertEquals(captured, it.getLong(0)) }
        }
    }

}
