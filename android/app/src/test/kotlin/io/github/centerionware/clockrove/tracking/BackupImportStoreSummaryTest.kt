package io.github.centerionware.clockrove.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackupImportStoreSummaryTest : BackupImportStoreTestBase() {
    @Test
    fun previewCountsReportDeletionMarkersIndependentlyFromUnchangedRows() {
        val cardId = "summary-deleted-card"
        val columnId = database.rawQuery(
            "SELECT id FROM kanban_columns WHERE job_id=? ORDER BY position,id LIMIT 1",
            arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        val currency = database.rawQuery(
            "SELECT currency FROM jobs WHERE id=?", arrayOf(jobId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertEquals(null, repository.saveCard(
            "summary-create-card", repository.snapshot().version,
            BoardStore.Draft(cardId, jobId, columnId, "Summary card", currency = currency),
        ).conflict)
        assertEquals(null, repository.removeCard(
            "summary-delete-card", repository.snapshot().version, jobId, cardId,
        ).conflict)

        val snapshot = exportStore.start(database)
        val started = stageSnapshot(snapshot)
        val preview = importStore.finish(database, started.importId)

        assertEquals(0L, preview.addedCount)
        assertEquals(0L, preview.changedCount)
        assertEquals(snapshot.recordCount, preview.unchangedCount)
        assertTrue(preview.deletedCount > 0L)
        assertEquals(0L, preview.unresolvedReferenceCount)
        assertEquals(0L, preview.timeOverlapCount)
        assertEquals(0L, preview.conflictCount)
        assertTrue(preview.unchangedCount + preview.deletedCount > preview.recordCount)
        assertTrue(importStore.close(started.importId))
        assertTrue(exportStore.close(snapshot.snapshotId))
    }
}
