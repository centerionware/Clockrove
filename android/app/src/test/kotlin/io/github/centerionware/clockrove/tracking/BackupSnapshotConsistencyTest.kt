package io.github.centerionware.clockrove.tracking

import io.flutter.plugin.common.StandardMethodCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class BackupSnapshotConsistencyTest {
    private lateinit var repository: TimerRepository

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 1000L })
        repository.snapshot()
    }

    @After fun close() { repository.close() }

    private fun rows(id: String): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = repository.readBackupSnapshotPage(id, cursor, null)
            result.addAll(page.records)
            assertTrue("Page must advance or finish", page.done || page.nextCursor != cursor)
            cursor = page.nextCursor
            if (page.done) break
        } while (true)
        return result
    }

    @Test fun detachedPagesRemainStableAfterLaterCommittedCommands() {
        repository.createJob("Before capture")
        val committed = repository.snapshot()
        val snapshot = repository.startBackupSnapshot()
        assertEquals(committed.version, snapshot.stateVersion)
        assertEquals(committed, repository.snapshot())
        val original = rows(snapshot.snapshotId)
        repository.createJob("After capture")
        assertTrue(repository.snapshot().version > snapshot.stateVersion)
        assertEquals(original, rows(snapshot.snapshotId))
        val names = original.filter { it["table"] == "jobs" }
            .map { (it["values"] as Map<*, *>)["name"] }
        assertTrue(names.contains("Before capture"))
        assertFalse(names.contains("After capture"))
        assertTrue(repository.closeBackupSnapshot(snapshot.snapshotId))
        val next = repository.startBackupSnapshot()
        assertEquals(repository.snapshot().version, next.stateVersion)
        assertTrue(rows(next.snapshotId).filter { it["table"] == "jobs" }
            .any { (it["values"] as Map<*, *>)["name"] == "After capture" })
        repository.closeBackupSnapshot(next.snapshotId)
    }

    @Test fun encodedPagesRespectTheTransportBudgetWithoutDroppingRows() {
        repeat(24) { repository.createJob("Synthetic page job $it") }
        val snapshot = repository.startBackupSnapshot()
        var cursor: String? = null
        var pages = 0
        var count = 0L
        do {
            val page = repository.readBackupSnapshotPage(snapshot.snapshotId, cursor, 8192)
            val envelope = StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(mapOf(
                "snapshotId" to page.snapshotId,
                "nextCursor" to page.nextCursor,
                "done" to page.done,
                "records" to page.records,
            ))
            assertTrue("Encoded response exceeds requested byte budget", envelope.position() <= 8192)
            count += page.records.size
            pages++
            if (page.done) break
            assertNotNull(page.nextCursor)
            assertNotEquals(cursor, page.nextCursor)
            cursor = page.nextCursor
        } while (true)
        assertTrue("Fixture must exercise multiple pages", pages > 1)
        assertEquals(snapshot.recordCount, count)
        repository.closeBackupSnapshot(snapshot.snapshotId)
    }

    @Test fun closingInvalidatesReadsWithoutChangingCommittedTimerState() {
        repository.createJob("Synthetic job")
        val before = repository.snapshot()
        val snapshot = repository.startBackupSnapshot()
        assertTrue(repository.closeBackupSnapshot(snapshot.snapshotId))
        try {
            repository.readBackupSnapshotPage(snapshot.snapshotId, null, null)
            fail("Closed snapshot must not remain readable")
        } catch (expected: BackupExportStore.Failure) {
            assertEquals("SNAPSHOT_UNAVAILABLE", expected.code)
        }
        assertEquals(before, repository.snapshot())
        assertFalse(repository.closeBackupSnapshot(snapshot.snapshotId))
    }
}
