package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import io.flutter.plugin.common.MethodCall
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
class JobCreationTest {
    private lateinit var repository: TimerRepository
    private lateinit var dispatch: TimerDispatch
    private var now = 1000L
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now }); dispatch = TimerDispatch(repository)
        repository.snapshot() // Open the lazy SQLite helper before direct fixture inspection.
    }
    @After fun close() { repository.close() }
    private fun create(command: String = "create", v: Long = version, job: String = "job", name: String = "Synthetic job") = repository.createJob(command, v, job, name)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    @Test fun originalReceiptReplaysWithoutDuplicatingJobBoardOrRevision() {
        val v = version; val first = create(v = v)
        assertNull(first.conflict); assertEquals(first, create(v = v))
        assertEquals(1, repository.snapshot().jobs.size); assertNull(repository.snapshot().running)
        assertEquals(listOf("To do", "Doing", "Done"), repository.boardSnapshot("job").board.columns.map { it.name })
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='job'", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
        repository.saveJobSettings("rename", version, repository.jobDetail("job").job.settings.copy(name = "Changed"))
        val after = version; assertEquals(first, create(v = v)); assertEquals(after, version)
        assertEquals("Changed", repository.snapshot().jobs.single().name)
    }
    @Test fun reusedIdsAndStaleVersionRejectWithoutPartialCreation() {
        assertNull(create().conflict)
        assertEquals("JOB_ID_EXISTS", create("second").conflict)
        assertEquals("COMMAND_ID_REUSED", create(v = 0, name = "Different").conflict)
        val stale = create("stale", 0, "second")
        assertEquals("STALE_STATE", stale.conflict)
        assertEquals(stale, create("stale", 0, "second"))
        assertEquals(1, repository.snapshot().jobs.size)
    }
    @Test fun invalidNamesAndIdsCannotCreateOrSeedAnything() {
        for ((job, name) in listOf("" to "Synthetic", "x".repeat(129) to "Synthetic", "job" to "  ", "job" to "x".repeat(241))) {
            assertEquals("INVALID_JOB", create("invalid-${job.length}-${name.length}", job = job, name = name).conflict)
        }
        assertEquals(0L, version); assertTrue(repository.snapshot().jobs.isEmpty())
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM kanban_columns", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
    }
    @Test fun defaultCurrencyIsCapturedOnlyAtFirstCreation() {
        repository.saveBusinessSettings("settings", version, repository.businessSnapshot().settings.copy(currency = "GBP"))
        val captured = version; val first = create(v = captured, name = "  Synthetic  ")
        repository.saveBusinessSettings("changed-settings", version, repository.businessSnapshot().settings.copy(currency = "EUR"))
        assertEquals(first, create(v = captured, name = "  Synthetic  "))
        assertEquals("GBP", repository.jobDetail("job").job.settings.currency)
        assertEquals("Synthetic", repository.snapshot().jobs.single().name)
        assertNull(create("next", job = "next").conflict); assertEquals("EUR", repository.jobDetail("next").job.settings.currency)
    }
    @Test fun concurrentDuplicateRequestsCommitOneIdentityAndBoard() {
        val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1); val v = version
        try {
            val futures = (1..2).map { pool.submit<TimerRepository.Outcome> { gate.await(); create(v = v) } }
            gate.countDown(); val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1]); assertNull(results[0].conflict)
            assertEquals(v + 1, version); assertEquals(1, repository.snapshot().jobs.size)
            assertEquals(3, repository.boardSnapshot("job").board.columns.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun seedFailureRollsBackJobRevisionsVersionAndReceiptThenRetryCanCommit() {
        repository.snapshot()
        val revisionsBefore = inspect { db -> db.rawQuery("SELECT COUNT(*) FROM entity_revisions", null).use { it.moveToFirst(); it.getInt(0) } }
        inspect { it.execSQL("CREATE TRIGGER fail_board BEFORE INSERT ON kanban_columns BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        try { create(); fail("Creation succeeded") } catch (_: android.database.SQLException) {}
        assertEquals(0L, version); assertTrue(repository.snapshot().jobs.isEmpty())
        inspect { db ->
            for (table in listOf("commands", "kanban_columns")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions", null).use { it.moveToFirst(); assertEquals(revisionsBefore, it.getInt(0)) }
            db.execSQL("DROP TRIGGER fail_board")
        }
        assertNull(create().conflict)
    }
    @Test fun deletedIdentityIsFencedWhileOriginalCreationReceiptRemainsReplayable() {
        val first = create(v = 0)
        repository.archiveJob("archive", version, "job"); now += JobLifecycleStore.retentionMillis
        assertNull(repository.deleteJob("delete", version, "job").conflict)
        val after = version
        assertEquals(first, create(v = 0)); assertEquals(after, version); assertTrue(repository.snapshot().jobs.isEmpty())
        assertEquals("JOB_ID_EXISTS", create("resurrect").conflict); assertTrue(repository.snapshot().jobs.isEmpty())
    }
    @Test fun actualTimerCodecRequiresIntegralVersionsAndIgnoresCallerOriginsAndTimes() {
        val payload = mapOf<String, Any?>("id" to "create", "version" to 0L, "jobId" to "job", "name" to "Synthetic")
        for (method in listOf("createJob", "execute")) {
            try { dispatch.call(MethodCall(method, payload + mapOf("version" to 0.5, "action" to "START"))); fail("Floating version accepted") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(repository.snapshot().jobs.isEmpty())
        assertNull((dispatch.call(MethodCall("createJob", payload)) as TimerRepository.Outcome).conflict)
        val command = mapOf("id" to "start", "version" to version, "jobId" to "job", "action" to "START", "origin" to "GEOFENCE", "atUtcMillis" to Long.MAX_VALUE)
        val before = System.currentTimeMillis()
        assertNull((dispatch.call(MethodCall("execute", command)) as TimerRepository.Outcome).conflict)
        val after = System.currentTimeMillis()
        val captured = repository.snapshot().running!!.startUtcMillis
        assertTrue(captured in minOf(before, after)..maxOf(before, after))
        inspect { db -> db.rawQuery("SELECT origin FROM sessions", null).use { it.moveToFirst(); assertEquals("MANUAL", it.getString(0)) } }
        val snapshot = dispatch.call(MethodCall("snapshot", null)) as Map<*, *>
        assertEquals(version, snapshot["version"]); assertNull(dispatch.call(MethodCall("unknown", null)))
    }
}
