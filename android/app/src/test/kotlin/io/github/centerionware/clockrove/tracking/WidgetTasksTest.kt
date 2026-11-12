package io.github.centerionware.clockrove.tracking

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
class WidgetTasksTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var sequence = 0
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app)
        job = repository.createJob("Synthetic due work")
    }
    @After fun close() { repository.close() }
    private fun card(id: String, date: String?, jobId: String = job, completed: Boolean = false) {
        val board = repository.boardSnapshot(jobId).board
        val column = board.columns.first { it.completed == completed }
        assertNull(repository.saveCard("save-${sequence++}", repository.snapshot().version,
            BoardStore.Draft(id, jobId, column.id, "Synthetic $id", dueDate = date, currency = board.currency)).conflict)
    }
    private fun utc(value: String): Long = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.parse(value)!!.time
    @Test fun dueListFiltersCompletedUndatedFutureAndArchivedButKeepsAllOverdueAndSorts() {
        card("later", "2026-11-18"); card("old", "2026-01-01"); card("today", "2026-11-12")
        card("undated", null); card("outside", "2026-11-19"); card("done", "2026-11-12", completed = true)
        val other = repository.createJob("Synthetic other")
        card("other", "2026-11-13", other)
        val data = repository.widgetTasks(1, utc("2026-11-12T12:00:00"), "UTC")
        assertEquals(listOf("old", "today", "other", "later"), data.items.map { it.id })
        assertEquals("2026-11-18", data.through)
        assertNull(repository.saveWidgetConfiguration("bind", repository.snapshot().version, 2, job).conflict)
        assertEquals(listOf("old", "today", "later"), repository.widgetTasks(2, utc("2026-11-12T12:00:00"), "UTC").items.map { it.id })
        assertNull(repository.archiveJob("archive", repository.snapshot().version, job).conflict)
        assertTrue(repository.widgetTasks(2, utc("2026-11-12T12:00:00"), "UTC").items.isEmpty())
        assertEquals(listOf("other"), repository.widgetTasks(1, utc("2026-11-12T12:00:00"), "UTC").items.map { it.id })
        assertNull(repository.snapshot().running)
    }
    @Test fun collectionIdentityRefreshesCommittedEditsAndLocalDateWithoutStartingTimers() {
        val before = repository.widgetSnapshot(1)
        val first = io.github.centerionware.clockrove.widget.TimerWidget.taskAdapterUri(before, 1, "2026-11-12")
        assertEquals(first, io.github.centerionware.clockrove.widget.TimerWidget.taskAdapterUri(before, 1, "2026-11-12"))
        assertNotEquals(first, io.github.centerionware.clockrove.widget.TimerWidget.taskAdapterUri(before, 1, "2026-11-13"))
        card("new-due-task", "2026-11-12")
        assertNotEquals(first, io.github.centerionware.clockrove.widget.TimerWidget.taskAdapterUri(repository.widgetSnapshot(1), 1, "2026-11-12"))
        assertEquals("2026-03-07", WidgetTasksStore.localDate(utc("2026-03-08T04:30:00"), "America/New_York"))
        assertEquals("2027-01-01", WidgetTasksStore.localDate(utc("2026-12-31T16:00:00"), "Asia/Tokyo"))
        assertNull(repository.snapshot().running)
    }
    @Test fun horizonUsesDeviceCalendarAcrossDstAndYearBoundaryAndNeverTruncatesToOnePage() {
        val instant = utc("2026-03-08T04:30:00") // Still March 7 in New York, before spring forward.
        val ny = repository.widgetTasks(1, instant, "America/New_York")
        assertEquals("2026-03-07", ny.today); assertEquals("2026-03-13", ny.through)
        val tokyo = repository.widgetTasks(1, utc("2026-12-31T16:00:00"), "Asia/Tokyo")
        assertEquals("2027-01-01", tokyo.today); assertEquals("2027-01-07", tokyo.through)
        repeat(125) { card("many-${it.toString().padStart(3, '0')}", "2026-03-13") }
        assertEquals(125, repository.widgetTasks(1, instant, "America/New_York").items.size)
    }
}
