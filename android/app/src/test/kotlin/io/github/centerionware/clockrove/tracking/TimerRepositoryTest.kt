package io.github.centerionware.clockrove.tracking

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimerRepositoryTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("clockrove.db")
        repository = TimerRepository(context)
        job = repository.createJob("Synthetic job")
    }
    @After fun teardown() { repository.close() }

    private fun command(id: String, action: TimerRepository.Action, at: Long,
        version: Long = repository.snapshot().version, selected: String? = job,
        origin: TimerRepository.Origin = TimerRepository.Origin.MANUAL) =
        TimerRepository.Command(id, version, action, selected, at, "Etc/UTC", 0, origin)

    @Test fun concurrentSurfacesProduceOneOpenSessionAndStableRetry() {
        val version = repository.snapshot().version
        val commands = listOf(
            command("app", TimerRepository.Action.START, 1000, version),
            command("widget", TimerRepository.Action.START, 1000, version, origin = TimerRepository.Origin.WIDGET),
            command("notification", TimerRepository.Action.START, 1000, version, origin = TimerRepository.Origin.NOTIFICATION),
        )
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = pool.invokeAll(commands.map { c -> Callable { repository.execute(c) } }).map { it.get() }
            assertEquals(1, results.count { it.conflict == null })
            assertEquals(2, results.count { it.conflict == "STALE_STATE" })
            assertNotNull(repository.snapshot().running)
            commands.zip(results).forEach { (c, result) -> assertEquals(result, repository.execute(c)) }
        } finally { pool.shutdownNow() }
    }

    @Test fun switchIsAtomicAndInvalidClockBoundaryLeavesOriginalSession() {
        repository.execute(command("start", TimerRepository.Action.START, 1000))
        val original = repository.snapshot()
        val other = repository.createJob("Synthetic other")
        assertEquals("INVALID_BOUNDARY", repository.execute(command("bad", TimerRepository.Action.SWITCH, 900, selected = other)).conflict)
        assertEquals(original.running, repository.snapshot().running)
        assertNull(repository.execute(command("switch", TimerRepository.Action.SWITCH, 2000, selected = other)).conflict)
        assertEquals(other, repository.snapshot().running!!.jobId)
        assertEquals(2000L, repository.snapshot().running!!.startUtcMillis)
        assertEquals("INVALID_BOUNDARY", repository.execute(command("zero", TimerRepository.Action.STOP, 2000)).conflict)
    }

    @Test fun committedStateAndDeduplicationSurviveRepositoryReopen() {
        val start = command("persist", TimerRepository.Action.START, 1000)
        val result = repository.execute(start)
        val snapshot = repository.snapshot()
        repository.close()
        repository = TimerRepository(RuntimeEnvironment.getApplication())
        assertEquals(snapshot, repository.snapshot())
        assertEquals(result, repository.execute(start))
        assertEquals("COMMAND_ID_REUSED", repository.execute(start.copy(atUtcMillis = 2000)).conflict)
    }

    @Test fun finalizedIntervalsRejectBackwardStartAndAllowAdjacentStart() {
        repository.execute(command("start", TimerRepository.Action.START, 1000))
        assertNull(repository.execute(command("stop", TimerRepository.Action.STOP, 2000)).conflict)
        assertEquals("OVERLAP", repository.execute(command("overlap", TimerRepository.Action.START, 1999)).conflict)
        assertNull(repository.execute(command("adjacent", TimerRepository.Action.START, 2000)).conflict)
    }

    @Test fun nativeSurfaceRetryKeepsOriginalOutcomeAndStaleTapCannotStopAnotherSession() {
        repository.execute(command("start", TimerRepository.Action.START, 1000))
        val displayedVersion = repository.snapshot().version
        val first = repository.executeFromSurface("widget-stop", displayedVersion,
            TimerRepository.Action.STOP, null, TimerRepository.Origin.WIDGET)
        assertNull(first.conflict)
        assertEquals(first, repository.executeFromSurface("widget-stop", displayedVersion,
            TimerRepository.Action.STOP, null, TimerRepository.Origin.WIDGET))
        val now = System.currentTimeMillis() + 1000
        repository.execute(command("restart", TimerRepository.Action.START, now))
        val current = repository.snapshot()
        assertEquals("STALE_STATE", repository.executeFromSurface("stale-widget", displayedVersion,
            TimerRepository.Action.STOP, null, TimerRepository.Origin.WIDGET).conflict)
        assertEquals(current, repository.snapshot())
    }

    @Test fun automationCannotUseIncompletePrototypePolicy() {
        assertEquals("AUTOMATION_NOT_READY", repository.execute(command("geo", TimerRepository.Action.START, 1000,
            origin = TimerRepository.Origin.GEOFENCE)).conflict)
        assertNull(repository.snapshot().running)
    }
}
