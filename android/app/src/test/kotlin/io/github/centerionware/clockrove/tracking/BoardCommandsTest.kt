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
@Config(sdk = [24])
class BoardCommandsTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var clock = 10000L
    private var sequence = 0
    private val state get() = repository.boardSnapshot(job)
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { clock }); job = repository.createJob("Synthetic board job")
    }
    @After fun close() { repository.close() }
    private fun save(value: BoardStore.Draft) = repository.saveCard("save-${sequence++}", state.version, value)
    private fun draft(id: String, column: String = state.board.columns.first().id, due: String? = null, price: Long? = 12000) =
        BoardStore.Draft(id, job, column, "Synthetic task $id", "Synthetic reusable description", due, true, price?.let(java.math.BigInteger::valueOf), state.board.currency)
    private fun move(id: String, column: String, position: Int) = repository.moveCard("move-${sequence++}", state.version, job, id, column, position)
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)

    @Test fun taskCrudPersistsPriceDateAndIndependentTemplateWithoutStartingATimer() {
        assertNull(save(draft("one", due = "2028-02-29")).conflict)
        val first = state.board.cards.single()
        assertEquals(java.math.BigInteger.valueOf(12000L), first.priceMinor); assertEquals("2028-02-29", first.dueDate)
        assertNull(first.completedMillis); assertNull(repository.snapshot().running)
        assertEquals(first.title, repository.taskTemplates().single().title)
        assertNull(save(draft("one", due = null, price = 0).copy(title = "Synthetic revised")).conflict)
        assertEquals(java.math.BigInteger.valueOf(0L), state.board.cards.single().priceMinor)
        assertEquals("Synthetic revised", repository.taskTemplates().single().title)
        assertNull(repository.removeCard("delete", state.version, job, "one").conflict)
        assertTrue(state.board.cards.isEmpty()); assertEquals(1, repository.taskTemplates().size)
    }
    @Test fun invalidDatePriceAndCurrencyAreRejectedBeforeAnyPartialWrites() {
        val initial = state
        for (date in listOf("2027-02-29", "1900-02-29", "2026-04-31", "2026-13-01", "0000-01-01"))
            assertEquals("INVALID_TASK", save(draft("bad", due = date)).conflict)
        assertEquals("INVALID_TASK", save(draft("bad", price = -1)).conflict)
        assertEquals("CURRENCY_MISMATCH", save(draft("bad").copy(currency = "INVALID")).conflict)
        assertEquals(initial, state); assertTrue(repository.taskTemplates().isEmpty())
        assertTrue(BoardStore.validDate("2000-02-29")); assertTrue(BoardStore.validDate(null))
    }
    @Test fun movingCompletesAndReopensTasksWithNativeDatesAndContiguousOrder() {
        val todo = state.board.columns[0].id; val done = state.board.columns[2].id
        listOf("one", "two", "three").forEach { assertNull(save(draft(it)).conflict) }
        assertNull(move("three", todo, 0).conflict)
        assertEquals(listOf("three", "one", "two"), state.board.cards.filter { it.columnId == todo }.sortedBy { it.position }.map { it.id })
        clock = 20000
        assertNull(move("one", done, 0).conflict); assertEquals(20000L, state.board.cards.first { it.id == "one" }.completedMillis)
        clock = 30000
        assertNull(move("one", done, 0).conflict); assertEquals(20000L, state.board.cards.first { it.id == "one" }.completedMillis)
        assertNull(move("one", todo, 1).conflict); assertNull(state.board.cards.first { it.id == "one" }.completedMillis)
        assertEquals(listOf(0, 1, 2), state.board.cards.filter { it.columnId == todo }.sortedBy { it.position }.map { it.position })
        assertNull(repository.snapshot().running)
    }
    @Test fun populatedColumnRequiresDestinationAndMergesCardsAtomically() {
        val source = state.board.columns[0]; val destination = state.board.columns[2]
        save(draft("one")); save(draft("two")); save(draft("existing", destination.id))
        val before = state
        assertEquals("DESTINATION_REQUIRED", repository.removeColumn("missing", state.version, job, source.id, null).conflict)
        assertEquals(before, state)
        assertNull(repository.removeColumn("merge", state.version, job, source.id, destination.id).conflict)
        assertEquals(listOf("existing", "one", "two"), state.board.cards.filter { it.columnId == destination.id }.sortedBy { it.position }.map { it.id })
        assertTrue(state.board.cards.all { it.completedMillis != null })
        assertEquals(listOf(0, 1), state.board.columns.map { it.position })
    }
    @Test fun lastColumnAndForeignColumnCannotBeDeletedOrUsedAsDestinations() {
        val other = repository.createJob("Synthetic unrelated job")
        val foreign = repository.boardSnapshot(other).board.columns.first().id
        save(draft("one"))
        assertEquals("COLUMN_UNAVAILABLE", move("one", foreign, 0).conflict)
        val second = state.board.columns[1].id
        assertNull(repository.removeColumn("remove-second", state.version, job, second, null).conflict)
        val third = state.board.columns.last().id
        assertNull(repository.removeColumn("remove-third", state.version, job, third, null).conflict)
        assertEquals("LAST_COLUMN", repository.removeColumn("last", state.version, job, state.board.columns.single().id, null).conflict)
        assertEquals(1, state.board.cards.size)
    }
    @Test fun columnRoleIsIndependentOfItsNameAndOrdering() {
        val todo = state.board.columns.first()
        save(draft("one"))
        assertNull(repository.saveColumn("rename", state.version, todo.id, job, "Invoice ready", true).conflict)
        assertNotNull(state.board.cards.single().completedMillis)
        val order = state.board.columns.map { it.id }.reversed()
        assertNull(repository.reorderColumns("reorder", state.version, job, order).conflict)
        assertEquals(order, state.board.columns.map { it.id })
        assertEquals("INVALID_ORDER", repository.reorderColumns("bad-order", state.version, job, listOf(order[0], order[0])).conflict)
        assertEquals(order, state.board.columns.map { it.id })
    }
    @Test fun templatesCopyContentAndPriceButNeverOldIdentityDueDateCompletionOrTimer() {
        val done = state.board.columns.last().id
        save(draft("old", done, "2026-12-31"))
        val template = repository.taskTemplates().single()
        val other = repository.createJob("Synthetic receiving job")
        val target = repository.boardSnapshot(other)
        assertNull(repository.copyTaskTemplate("copy", target.version, template.id, "fresh", other, target.board.columns.first().id, true).conflict)
        val copy = repository.boardSnapshot(other).board.cards.single()
        assertEquals("fresh", copy.id); assertEquals(other, copy.jobId); assertNull(copy.dueDate); assertNull(copy.completedMillis)
        assertEquals(java.math.BigInteger.valueOf(12000L), copy.priceMinor); assertNotEquals(template.id, copy.templateId)
        assertNull(repository.snapshot().running)
        assertEquals("TASK_ALREADY_EXISTS", repository.copyTaskTemplate("bad-copy", repository.snapshot().version, template.id, "fresh", other, target.board.columns.first().id, true).conflict)
    }
    @Test fun taskClassificationPersistsOnCardsTemplatesAndCopiesAndRejectsUnknownValues() {
        assertNull(save(draft("supplies").copy(chargeType = TaskClassification.SUPPLIES)).conflict)
        val source = state.board.cards.single()
        val template = repository.taskTemplates().single()
        assertEquals(TaskClassification.SUPPLIES, source.chargeType)
        assertEquals(TaskClassification.SUPPLIES, template.chargeType)

        val receivingJob = repository.createJob("Synthetic classification receiver")
        val receiving = repository.boardSnapshot(receivingJob)
        assertNull(repository.copyTaskTemplate("copy-supplies", receiving.version, template.id, "copied-supplies", receivingJob,
            receiving.board.columns.first().id, true).conflict)
        assertEquals(TaskClassification.SUPPLIES, repository.boardSnapshot(receivingJob).board.cards.single().chargeType)
        assertEquals(TaskClassification.SUPPLIES, repository.taskTemplates().single { it.id != template.id }.chargeType)

        val before = state
        val templatesBeforeInvalid = repository.taskTemplates().size
        assertEquals("INVALID_TASK", save(draft("unknown").copy(chargeType = "MATERIALS")).conflict)
        assertEquals(before, state)
        assertEquals(templatesBeforeInvalid, repository.taskTemplates().size)
        inspect { db ->
            db.rawQuery("SELECT r.payload FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id WHERE h.kind='card' AND h.entity_id='supplies'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(TaskClassification.SUPPLIES, org.json.JSONObject(it.getString(0)).getString("charge_type"))
            }
        }
    }
    @Test fun classificationIsPartOfCommandIdentityWithoutChangingLegacyLaborRetries() {
        val before = state.version
        val labor = draft("retry-task").copy(chargeType = TaskClassification.LABOR)
        assertNull(repository.saveCard("classification-retry", before, labor).conflict)
        assertEquals("COMMAND_ID_REUSED", repository.saveCard("classification-retry", before,
            labor.copy(chargeType = TaskClassification.SUPPLIES)).conflict)
        assertEquals(TaskClassification.LABOR, state.board.cards.single().chargeType)
    }
    @Test fun archivedSourcesRemainSearchableAndTemplateRemovalDoesNotDeleteCards() {
        save(draft("old"))
        val template = repository.taskTemplates("reusable").single()
        inspect { it.execSQL("UPDATE jobs SET archived=1,archived_ms=? WHERE id=?", arrayOf<Any?>(clock, job)) }
        assertEquals(template.id, repository.taskTemplates("reusable").single().id)
        assertEquals("JOB_UNAVAILABLE", save(draft("new")).conflict)
        assertNull(repository.removeTaskTemplate("remove-template", repository.snapshot().version, template.id).conflict)
        assertTrue(repository.taskTemplates().isEmpty()); assertEquals(1, state.board.cards.size); assertNull(state.board.cards.single().templateId)
    }
    @Test fun taskIdsCannotMoveBetweenJobsAndInvalidPositionsPreserveState() {
        save(draft("one"))
        val before = state
        assertEquals("INVALID_POSITION", move("one", state.board.columns.first().id, 99).conflict)
        assertEquals(before, state)
        val other = repository.createJob("Synthetic other")
        val target = repository.boardSnapshot(other)
        assertEquals("TASK_JOB_IMMUTABLE", repository.saveCard("steal", target.version, draft("one").copy(jobId = other,
            columnId = target.board.columns.first().id, currency = target.board.currency)).conflict)
        assertEquals(job, state.board.cards.single().jobId)
    }
    @Test fun duplicateConcurrentCreationProducesOneCardTemplateAndCommittedOutcome() {
        val initial = state; val task = draft("one")
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map { pool.submit<TimerRepository.Outcome> { start.await(); repository.saveCard("same-command", initial.version, task) } }
            start.countDown()
            val outcomes = requests.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(outcomes[0], outcomes[1]); assertNull(outcomes[0].conflict)
            assertEquals(1, state.board.cards.size); assertEquals(1, repository.taskTemplates().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun failedColumnMergeRollsBackPositionsCompletionRevisionsAndOutcome() {
        val source = state.board.columns.first().id; val target = state.board.columns.last().id
        save(draft("one")); save(draft("two"))
        val before = state
        val revisions = inspect { db -> db.rawQuery("SELECT COUNT(*) FROM entity_revisions", null).use { it.moveToFirst(); it.getLong(0) } }
        inspect { it.execSQL("CREATE TRIGGER fail_card_move BEFORE UPDATE ON kanban_cards WHEN NEW.id='two' AND NEW.column_id!=OLD.column_id BEGIN SELECT RAISE(ABORT,'synthetic move failure'); END") }
        try { repository.removeColumn("failing", state.version, job, source, target); fail("Partial merge succeeded") }
        catch (_: android.database.SQLException) {}
        assertEquals(before, state)
        inspect { db ->
            db.rawQuery("SELECT COUNT(*) FROM entity_revisions", null).use { it.moveToFirst(); assertEquals(revisions, it.getLong(0)) }
            db.rawQuery("SELECT id FROM commands WHERE id='failing'", null).use { assertFalse(it.moveToFirst()) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun calendarDueDateDoesNotChangeWithTheDevicesTimezone() {
        val old = java.util.TimeZone.getDefault()
        try {
            save(draft("one", due = "2026-11-11"))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals("2026-11-11", state.board.cards.single().dueDate)
            repository.close(); repository = TimerRepository(RuntimeEnvironment.getApplication(), wallClock = { clock })
            assertEquals("2026-11-11", state.board.cards.single().dueDate)
        } finally { java.util.TimeZone.setDefault(old) }
    }
}
