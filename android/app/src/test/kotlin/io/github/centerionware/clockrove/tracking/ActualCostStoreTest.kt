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

    @Test fun customerFilterUsesTheCustomerCapturedWhenCostWasRecorded() {
        val original = BusinessStore.Customer("cost-customer-a", "Original customer")
        val replacement = BusinessStore.Customer("cost-customer-b", "Later customer")
        assertNull(repository.saveCustomer("customer-a", version, original).conflict)
        assertNull(repository.saveCustomer("customer-b", version, replacement).conflict)
        assertNull(repository.saveJobSettings("assign-original", version,
            repository.jobDetail(jobId).job.settings.copy(customerId = original.id)).conflict)
        assertNull(repository.saveActualCost("cost-snapshot", version, draft(id = "customer-cost", task = null, amount = "100")).conflict)
        assertNull(repository.saveJobSettings("assign-later", version,
            repository.jobDetail(jobId).job.settings.copy(customerId = replacement.id)).conflict)
        assertNull(repository.saveActualCost("edit-snapshot", version, draft(id = "customer-cost", task = null, amount = "200")).conflict)

        val entry = repository.actualCosts().page.entries.single()
        assertEquals(original.id, entry.customerId)
        assertEquals(original.name, entry.customerName)
    }

    @Test fun v14CustomerSnapshotMigrationPreservesCostsWithoutGuessingTheirCustomer() {
        val currentCustomer = BusinessStore.Customer("legacy-current-customer", "Current customer")
        assertNull(repository.saveCustomer("legacy-customer-save", version, currentCustomer).conflict)
        assertNull(repository.saveJobSettings("legacy-job-customer", version,
            repository.jobDetail(jobId).job.settings.copy(customerId = currentCustomer.id)).conflict)
        assertNull(repository.saveActualCost("legacy-cost-save", version, draft(
            id = "legacy-cost", task = null, amount = "1234",
        )).conflict)
        repository.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX actual_costs_date")
            db.execSQL("DROP INDEX actual_costs_job_date")
            db.execSQL("ALTER TABLE actual_costs RENAME TO actual_costs_future")
            db.execSQL("CREATE TABLE actual_costs(id TEXT PRIMARY KEY,job_id TEXT,job_name TEXT,task_id TEXT,task_name TEXT,amount_minor TEXT,currency TEXT,category TEXT,occurred_ms INTEGER,timezone TEXT,offset_seconds INTEGER,vendor TEXT,reference TEXT,note TEXT,created_ms INTEGER,updated_ms INTEGER,deleted_ms INTEGER)")
            db.execSQL("INSERT INTO actual_costs SELECT id,job_id,job_name,task_id,task_name,amount_minor,currency,category,occurred_ms,timezone,offset_seconds,vendor,reference,note,created_ms,updated_ms,deleted_ms FROM actual_costs_future")
            db.execSQL("DROP TABLE actual_costs_future")
            db.execSQL("CREATE INDEX actual_costs_date ON actual_costs(occurred_ms,id)")
            db.execSQL("CREATE INDEX actual_costs_job_date ON actual_costs(job_id,occurred_ms,id)")
            db.execSQL("DROP TABLE location_deletion_ranges") // Added in schema 16.
            // Real schema-14 heads predate the customer columns; remove them here too.
            val legacyHeads = db.rawQuery(
                "SELECT id,payload FROM entity_revisions WHERE kind='actual_cost'", null,
            ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) } }
            legacyHeads.forEach { (id, payload) ->
                val stripped = org.json.JSONObject(payload).apply {
                    remove("customer_id")
                    remove("customer_name")
                }.toString()
                db.execSQL("UPDATE entity_revisions SET payload=? WHERE id=?", arrayOf<Any?>(stripped, id))
            }
            db.version = 14
        }

        repository = TimerRepository(app, wallClock = { clock })
        val entry = repository.actualCosts().page.entries.single()
        assertEquals(BigInteger("1234"), entry.amountMinor)
        assertNull(entry.customerId)
        assertEquals("", entry.customerName)
        assertNull(repository.saveActualCost("legacy-cost-edit", version, draft(
            id = "legacy-cost", task = null, amount = "1235",
        )).conflict)
        assertNull(repository.actualCosts().page.entries.single().customerId)
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(17, db.version)
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='actual_costs_customer_date'", null).use { it.moveToFirst(); it.getInt(0) })
            db.rawQuery(
                "SELECT r.payload,r.deleted FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                    "WHERE h.kind='actual_cost' AND h.entity_id='legacy-cost'",
                null,
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(1))
                val head = org.json.JSONObject(rows.getString(0))
                assertTrue(head.has("customer_id"))
                assertTrue(head.has("customer_name"))
            }
        }
    }

    @Test fun purgedJobKeepsRetainedCostHeadsLive() {
        assertNull(repository.saveActualCost("cost-before-purge", version, draft()))
        assertNull(repository.archiveJob("archive-cost-job", version, jobId).conflict)
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE jobs SET archived_ms=0 WHERE id=?", arrayOf<Any?>(jobId))
        }
        assertNull(repository.deleteJob("purge-cost-job", version, jobId).conflict)
        assertEquals(1, repository.actualCosts().page.entries.size)
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT r.payload,r.deleted FROM entity_heads h JOIN entity_revisions r ON r.id=h.revision_id " +
                    "WHERE h.kind='actual_cost' AND h.entity_id='cost-one'",
                null,
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(1))
                assertTrue(org.json.JSONObject(rows.getString(0)).has("customer_name"))
            }
        }
    }

    @Test fun v13UpgradeAddsAnEmptyCostLedgerWithoutChangingExistingTasks() {
        val beforeBoard = repository.boardSnapshot(jobId).board
        repository.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE actual_costs")
            db.execSQL("DROP TABLE location_deletion_ranges") // Added in schema 16.
            db.version = 13
        }

        repository = TimerRepository(app, wallClock = { clock })
        assertTrue(repository.actualCosts().page.entries.isEmpty())
        assertEquals(beforeBoard.cards.map { it.id }, repository.boardSnapshot(jobId).board.cards.map { it.id })
        SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(17, db.version)
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
