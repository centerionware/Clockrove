package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.math.BigInteger
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
class ActualCostStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var clock = 1_800_000_000_000L
    private lateinit var repository: TimerRepository
    private lateinit var jobId: String
    private lateinit var taskId: String
    private val version get() = repository.snapshot().version

    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { clock })
        jobId = repository.createJob("Synthetic cost job")
        val board = repository.boardSnapshot(jobId).board
        taskId = "synthetic-cost-task"
        assertNull(repository.saveCard("cost-task-create", version, BoardStore.Draft(
            taskId, jobId, board.columns.first().id, "Synthetic tile materials", currency = board.currency,
        )).conflict)
    }

    @After fun close() { repository.close() }

    private fun draft(
        id: String = "cost-one",
        job: String? = jobId,
        task: String? = taskId,
        amount: String = "123456789012345678901234567890123456789",
        category: String = ActualCostStore.MATERIALS,
        occurred: Long = 1_719_835_200_000L,
        timezone: String = "America/Los_Angeles",
        vendor: String = "Synthetic Supply",
        reference: String = "Receipt-42",
        note: String = "Synthetic tile adhesive",
    ) = ActualCostStore.Draft(id, job, task, amount, "USD", category, occurred, timezone, vendor, reference, note)

    @Test fun costsKeepExactAmountsAndSnapshotsAcrossEditsAndDeletion() {
        val commandVersion = version
        val first = repository.saveActualCost("cost-add", commandVersion, draft())
        assertNull(first.conflict)
        assertEquals(first, repository.saveActualCost("cost-add", commandVersion, draft()))

        var entries = repository.actualCosts().page.entries
        assertEquals(1, entries.size)
        assertEquals(BigInteger("123456789012345678901234567890123456789"), entries.single().amountMinor)
        assertEquals("Synthetic cost job", entries.single().jobName)
        assertEquals("Synthetic tile materials", entries.single().taskName)
        assertEquals(-25200, entries.single().offsetSeconds)
        assertEquals(ActualCostStore.MATERIALS, entries.single().category)

        clock += 10_000
        val editVersion = version
        val edited = repository.saveActualCost("cost-edit", editVersion, draft(
            amount = "900000000000000000000000000000000000001",
            category = ActualCostStore.OTHER_DIRECT,
            vendor = "Synthetic Hardware",
            note = "Corrected receipt amount",
        ))
        assertNull(edited.conflict)
        entries = repository.actualCosts().page.entries
        assertEquals(1, entries.size)
        assertEquals(BigInteger("900000000000000000000000000000000000001"), entries.single().amountMinor)
        assertEquals("Synthetic Hardware", entries.single().vendor)

        clock += 10_000
        assertNull(repository.removeActualCost("cost-delete", version, "cost-one").conflict)
        assertTrue(repository.actualCosts().page.entries.isEmpty())
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM actual_costs WHERE id='cost-one' AND deleted_ms IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(3, db.rawQuery("SELECT COUNT(*) FROM entity_revisions WHERE kind='actual_cost' AND entity_id='cost-one'", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(2, db.rawQuery("SELECT COUNT(*) FROM revision_parents p JOIN entity_revisions r ON r.id=p.revision_id WHERE r.kind='actual_cost' AND r.entity_id='cost-one'", null).use { it.moveToFirst(); it.getInt(0) })
        }
    }

    @Test fun costsRequirePositiveExactMoneyAndValidJobTaskRelationships() {
        assertEquals("INVALID_ACTUAL_COST_AMOUNT", repository.saveActualCost("cost-zero", version, draft(amount = "0")).conflict)
        assertEquals("INVALID_ACTUAL_COST_AMOUNT", repository.saveActualCost("cost-float", version, draft(amount = "1.25e2")).conflict)
        assertEquals("INVALID_ACTUAL_COST", repository.saveActualCost("cost-no-job-task", version, draft(job = null, task = taskId)).conflict)
        assertEquals("COST_TASK_UNAVAILABLE", repository.saveActualCost("cost-wrong-task", version, draft(task = "not-this-task")).conflict)
        assertTrue(repository.actualCosts().page.entries.isEmpty())
    }

    @Test fun v13UpgradeAddsAnEmptyCostLedgerWithoutChangingExistingTasks() {
        val beforeBoard = repository.boardSnapshot(jobId).board
        repository.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE actual_costs")
            db.version = 13
        }

        repository = TimerRepository(app, wallClock = { clock })
        assertTrue(repository.actualCosts().page.entries.isEmpty())
        assertEquals(beforeBoard.cards.map { it.id }, repository.boardSnapshot(jobId).board.cards.map { it.id })
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(14, db.version)
            assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM actual_costs", null).use { it.moveToFirst(); it.getInt(0) })
        }
    }

    @Test fun ledgerPagesAreStableAndDoNotRepeatEqualTimestampRows() {
        val occurred = 1_719_835_200_000L
        val ids = listOf("page-a", "page-b", "page-c", "page-d", "page-e")
        for (id in ids) {
            val result = repository.saveActualCost("save-$id", version, draft(id = id, task = null, occurred = occurred, amount = "1"))
            assertNull(result.conflict)
        }
        val first = repository.actualCosts(limit = 2).page
        assertEquals(listOf("page-e", "page-d"), first.entries.map { it.id })
        assertNotNull(first.next)
        val second = repository.actualCosts(first.next, limit = 2).page
        assertEquals(listOf("page-c", "page-b"), second.entries.map { it.id })
        val third = repository.actualCosts(second.next, limit = 2).page
        assertEquals(listOf("page-a"), third.entries.map { it.id })
        assertNull(third.next)
    }
}
