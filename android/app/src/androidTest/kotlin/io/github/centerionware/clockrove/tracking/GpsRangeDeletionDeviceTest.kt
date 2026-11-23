package io.github.centerionware.clockrove.tracking

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises GPS-only deletion in the installed app and verifies committed SQLite state. */
@RunWith(AndroidJUnit4::class)
class GpsRangeDeletionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val captures get() = File(app.getExternalFilesDir(null), "gps-captures").apply { mkdirs() }
    private val token = UUID.randomUUID().toString()
    private val pointPrefix = "synthetic-gps-range-$token-point-"
    private var jobId: String? = null
    private var sessionId: String? = null
    private var sessionStart = 0L
    private var sessionEnd = 0L
    private var timezoneId = "UTC"
    private var pointCount = 49

    private fun find(label: String, allowContentDescriptionPrefix: Boolean = false): AccessibilityNodeInfo? {
        fun matches(value: CharSequence?) = value?.toString()?.lineSequence()
            ?.any { it.trim() == label } == true

        fun descriptionMatches(value: CharSequence?): Boolean {
            if (matches(value)) return true
            if (!allowContentDescriptionPrefix) return false
            return value?.toString()?.lineSequence()
                ?.any { it.trim().startsWith("$label ") } == true
        }

        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return if (node.isVisibleToUser && (matches(node.text) || descriptionMatches(node.contentDescription))) node else null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun waitFor(
        label: String,
        timeoutMillis: Long = 30000,
        allowContentDescriptionPrefix: Boolean = false,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            find(label, allowContentDescriptionPrefix)?.let { return it }
            SystemClock.sleep(100)
        }
        diagnostic("missing-${label.replace(Regex("[^a-zA-Z0-9]+"), "-")}")
        throw AssertionError("Actual Android app never exposed '$label'")
    }

    private fun waitForDateInputMode(timeoutMillis: Long = 30000): List<AccessibilityNodeInfo> {
        val dateValue = Regex("\\d{1,2}/\\d{1,2}/\\d{4}")
        fun visit(node: AccessibilityNodeInfo, found: MutableList<AccessibilityNodeInfo>) {
            if (!node.refresh()) return
            val value = node.text?.toString()?.trim().orEmpty()
            if (node.isVisibleToUser && node.isEditable && dateValue.matches(value)) found.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, found) }
        }

        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val found = mutableListOf<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let { visit(it, found) }
            val dateFields = found.distinctBy { it.text?.toString()?.trim() }
            if (dateFields.size >= 2 && dateFields.any { it.isFocused }) return dateFields
            SystemClock.sleep(100)
        }
        diagnostic("missing-gps-date-inputs")
        throw AssertionError("Input-mode GPS picker did not expose both focused date fields")
    }

    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            if ((node.isClickable || node.isCheckable) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Actual Android app could not click '$label'")
    }

    private fun capture(name: String) {
        automation.waitForIdle(250, 5000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(captures, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun diagnostic(name: String) {
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            if (dump.length > 150000 || depth > 50) return
            dump.append(" ".repeat(depth)).append(node).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { describe(it, 0) }
        File(captures, "$name-accessibility.txt").writeText(dump.toString())
        automation.takeScreenshot()?.let { bitmap ->
            File(captures, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun seedFixture(): TimerRepository {
        val repository = TimerRepository.get(app)
        assertNull("Fixture requires an idle timer", repository.snapshot().running)
        val job = repository.createJob("Synthetic GPS deletion journey")
        jobId = job
        val settings = repository.jobDetail(job).job.settings.copy(locationRecordingOverride = true)
        assertNull(
            repository.saveJobSettings("gps-range-enable-location-$token", repository.snapshot().version, settings).conflict,
        )
        assertTrue(repository.locationRecordingEnabled(job))

        val zone = TimeZone.getDefault()
        timezoneId = zone.id
        val now = System.currentTimeMillis()
        val startOfToday = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endOfToday = Calendar.getInstance(zone).apply {
            timeInMillis = startOfToday
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val latestSessionEnd = SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery("SELECT COALESCE(MAX(end_ms),0) FROM sessions", null).use { rows ->
                assertTrue(rows.moveToFirst())
                rows.getLong(0)
            }
        }
        sessionStart = maxOf(startOfToday, now - 49L * 300000L, latestSessionEnd)
        assertTrue("Fixture samples must begin within today's selectable GPS range", sessionStart < endOfToday)
        pointCount = (((endOfToday - sessionStart - 1L) / 300000L) + 1L).toInt().coerceIn(1, 49)
        val offset = zone.getOffset(sessionStart) / 1000
        val started = repository.execute(
            TimerRepository.Command(
                "gps-range-start-$token",
                repository.snapshot().version,
                TimerRepository.Action.START,
                job,
                sessionStart,
                zone.id,
                offset,
                TimerRepository.Origin.MANUAL,
            ),
        )
        assertNull("Fixture session could not start", started.conflict)
        val session = requireNotNull(repository.snapshot().running)
        sessionId = session.id

        repeat(pointCount) { index ->
            val measured = sessionStart + index * 300000L
            val result = repository.recordLocation(
                TimerRepository.LocationPoint(
                    "$pointPrefix$index",
                    session.id,
                    measured,
                    measured,
                    37.4219983 + index * 0.00003,
                    -122.084 + index * 0.00003,
                    8.0,
                ),
            )
            assertEquals("Synthetic location point $index was rejected", TimerRepository.SampleResult.RECORDED, result)
        }

        sessionEnd = sessionStart + (pointCount - 1) * 300000L + 1000L
        val stopped = repository.execute(
            TimerRepository.Command(
                "gps-range-stop-$token",
                repository.snapshot().version,
                TimerRepository.Action.STOP,
                null,
                sessionEnd,
                zone.id,
                zone.getOffset(sessionEnd) / 1000,
                TimerRepository.Origin.MANUAL,
            ),
        )
        assertNull("Fixture session could not stop", stopped.conflict)
        assertNull(repository.snapshot().running)

        val entry = repository.sessionHistory(job).page.entries.single { it.id == session.id }
        assertEquals(sessionStart, entry.start)
        assertEquals(sessionEnd, entry.end)
        assertEquals(pointCount, repository.locationPoints(session.id).size)
        return repository
    }

    private fun date(instant: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone(timezoneId) }
        .format(Date(instant))

    private fun assertReportPreserved(repository: TimerRepository, pointCount: Int) {
        val job = requireNotNull(jobId)
        val session = requireNotNull(sessionId)
        val report = repository.report(
            ReportStore.Selection(date(sessionStart), date(sessionEnd), timezoneId, listOf(job)),
        ).report
        assertEquals(pointCount, report.points.count { it.sessionId == session })
        assertEquals(sessionEnd - sessionStart, report.totals.single().durationMillis.toLong())
        assertTrue(report.intervals.any { it.sessionId == session })
        val entry = repository.sessionHistory(job).page.entries.single { it.id == session }
        assertEquals(sessionEnd, entry.end)
    }

    private fun assertNoDeletionMarker() {
        val job = requireNotNull(jobId)
        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                "SELECT COUNT(*) FROM location_deletion_ranges WHERE job_id=?",
                arrayOf(job),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0L, rows.getLong(0))
            }
        }
    }

    private fun assertPersistedDeletion() {
        val job = requireNotNull(jobId)
        val session = requireNotNull(sessionId)
        val database = SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        database.use {
            val range = database.rawQuery(
                "SELECT id,revision_id,start_ms,end_ms,timezone_id FROM location_deletion_ranges WHERE job_id=?",
                arrayOf(job),
            ).use { rows ->
                assertTrue("Confirmed deletion did not persist a range", rows.moveToFirst())
                listOf(rows.getString(0), rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4))
            }
            val rangeId = range[0] as String
            val revisionId = range[1] as String
            assertTrue(range[2] as Long <= sessionStart)
            assertTrue(range[3] as Long > sessionStart + (pointCount - 1) * 300000L)
            assertEquals(timezoneId, range[4])

            database.rawQuery(
                "SELECT kind,deleted,payload FROM entity_revisions WHERE id=?",
                arrayOf(revisionId),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("location_deletion_range", rows.getString(0))
                assertEquals(0, rows.getInt(1))
                val keys = JSONObject(rows.getString(2)).keys().asSequence().toSet()
                assertEquals(setOf("id", "job_id", "start_ms", "end_ms", "timezone_id"), keys)
            }

            database.rawQuery(
                "SELECT COUNT(*) FROM entity_revisions WHERE kind='location' AND entity_id LIKE ? AND deleted=1 AND origin='GPS_RANGE_DELETE'",
                arrayOf("$pointPrefix%"),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(pointCount.toLong(), rows.getLong(0))
            }
            database.rawQuery(
                "SELECT COUNT(*) FROM entity_revisions child JOIN revision_parents edge ON edge.revision_id=child.id JOIN entity_revisions parent ON parent.id=edge.parent_id WHERE child.kind='location' AND child.entity_id LIKE ? AND child.deleted=1 AND child.origin='GPS_RANGE_DELETE' AND parent.kind='location_deletion_range' AND parent.entity_id=?",
                arrayOf("$pointPrefix%", rangeId),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Every point tombstone must cite its range revision", pointCount.toLong(), rows.getLong(0))
            }
            database.rawQuery(
                "SELECT payload FROM entity_revisions WHERE kind='location' AND entity_id LIKE ? AND deleted=1 AND origin='GPS_RANGE_DELETE' LIMIT 1",
                arrayOf("$pointPrefix%"),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(setOf("id", "session_id"), JSONObject(rows.getString(0)).keys().asSequence().toSet())
            }
            database.rawQuery(
                "SELECT COUNT(*) FROM location_points WHERE session_id=?",
                arrayOf(session),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0L, rows.getLong(0))
            }
        }
    }

    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        automation.waitForIdle(200, 3000)
    }

    private fun openReportAndReturnToHistory(
        expectedMapLabel: String,
        reportCapture: String,
        mapCapture: String? = null,
    ) {
        back()
        waitFor("Synthetic GPS deletion journey")
        click("Work report")
        waitFor("Work report")
        waitFor("Export CSV")
        waitFor(expectedMapLabel)
        capture(reportCapture)
        if (mapCapture != null) {
            click(expectedMapLabel)
            waitFor("Report location history")
            waitFor("Synthetic GPS deletion journey")
            waitFor("37.421998, -122.084000")
            val tileStates = listOf(
                "Map tiles loaded",
                "Map tiles are unavailable. Your recorded location is still available.",
            )
            val deadline = SystemClock.uptimeMillis() + 30000
            while (SystemClock.uptimeMillis() < deadline && tileStates.none { find(it) != null }) {
                SystemClock.sleep(150)
            }
            assertNotNull("Report map did not expose a tile state", tileStates.firstNotNullOfOrNull(::find))
            capture(mapCapture)
            back()
            waitFor("Work report")
        }
        back()
        waitFor("Synthetic GPS deletion journey")
        click("Work history")
        waitFor("Delete GPS by date range")
    }

    private fun removeFixture() {
        val job = jobId ?: return
        val repository = TimerRepository.get(app)
        repository.snapshot().running?.takeIf { it.jobId == job }?.let {
            repository.execute(
                TimerRepository.Command(
                    "gps-range-cleanup-stop-$token",
                    repository.snapshot().version,
                    TimerRepository.Action.STOP,
                    null,
                    maxOf(System.currentTimeMillis() + 1000, it.startUtcMillis + 1000),
                    timezoneId,
                    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000,
                    TimerRepository.Origin.MANUAL,
                ),
            )
        }
        val archived = repository.archiveJob("gps-range-cleanup-archive-$token", repository.snapshot().version, job)
        if (archived.conflict != null) return
        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                "UPDATE jobs SET archived_ms=? WHERE id=?",
                arrayOf<Any?>(System.currentTimeMillis() - JobLifecycleStore.retentionMillis - 1000, job),
            )
        }
        repository.deleteJob("gps-range-cleanup-delete-$token", archived.version, job)
    }

    @Test
    fun cancelThenConfirmRemovesOnlyGpsAndPersistsTombstonesOnDevice() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val repository = seedFixture()
        try {
            assertReportPreserved(repository, pointCount)
            val pointLabel = if (pointCount == 1) "1 GPS point." else "$pointCount GPS points."
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            waitFor("App settings and customers")
            click("Synthetic GPS deletion journey")
            waitFor("Work history")
            click("Work history")
            waitFor("Delete GPS by date range")
            click("Delete GPS by date range")
            waitFor("Select GPS dates", allowContentDescriptionPrefix = true)
            capture("gps-date-range-calendar")
            click("Review")
            waitFor("Matching saved GPS points: $pointLabel")
            waitFor("Delete GPS history?")
            waitFor("This job only · Synthetic GPS deletion journey")
            click("All jobs")
            waitFor("Matching saved GPS points: $pointLabel")
            click("This job only · Synthetic GPS deletion journey")
            waitFor("Matching saved GPS points: $pointLabel")
            capture("gps-cancel-review")
            click("Cancel")
            waitFor("Work history")
            assertEquals(pointCount, repository.locationPoints(requireNotNull(sessionId)).size)
            assertReportPreserved(repository, pointCount)
            assertNoDeletionMarker()
            capture("gps-after-cancel")
            openReportAndReturnToHistory(
                "Map · $pointCount measured samples",
                "gps-after-cancel-report",
                "gps-map-after-cancel",
            )

            click("Delete GPS by date range")
            waitFor("Select GPS dates", allowContentDescriptionPrefix = true)
            click("Switch to input")
            val dateFields = waitForDateInputMode()
            assertEquals("Input mode must expose both selected GPS dates", 2, dateFields.mapNotNull {
                it.text?.toString()?.trim()
            }.distinct().size)
            assertTrue("Input mode should focus one of its date fields", dateFields.any { it.isFocused })
            capture("gps-date-range-input")
            click("Review")
            waitFor("Matching saved GPS points: $pointLabel")
            waitFor("Delete GPS history?")
            waitFor("This job only · Synthetic GPS deletion journey")
            waitFor("All jobs")
            capture("gps-confirm-review")
            click("Delete GPS points")
            waitFor("Work history")

            assertEquals(0, repository.locationPoints(requireNotNull(sessionId)).size)
            assertReportPreserved(repository, 0)
            assertPersistedDeletion()
            capture("gps-after-confirm")
            openReportAndReturnToHistory(
                "Map · 0 measured samples",
                "gps-after-confirm-report",
            )
        } catch (failure: Throwable) {
            diagnostic("gps-deletion-device-failure")
            throw failure
        } finally {
            instrumentation.runOnMainSync {
                listOf(
                    androidx.test.runner.lifecycle.Stage.RESUMED,
                    androidx.test.runner.lifecycle.Stage.STARTED,
                    androidx.test.runner.lifecycle.Stage.CREATED,
                ).flatMap {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(it).toList()
                }.distinct().forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            removeFixture()
        }
    }
}
