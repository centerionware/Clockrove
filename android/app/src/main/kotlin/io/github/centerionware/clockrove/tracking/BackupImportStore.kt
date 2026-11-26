package io.github.centerionware.clockrove.tracking

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Base64
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Bounded native staging and read-only preview for authenticated backup imports. */
internal class BackupImportStore(
    context: Context,
    internal val catalogProvider: (SQLiteDatabase) -> Map<String, BackupExportStore.ImportTable>,
    internal val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    internal val deleteFile: (File) -> Boolean = { it.delete() },
    internal val idleTtlMillis: Long = BackupExportStore.IDLE_EXPIRY_MILLIS,
    internal val scheduler: BackupScheduler = ExecutorBackupScheduler,
    internal val wallClock: () -> Long = { System.currentTimeMillis() },
    internal val stateVersionReader: ((SQLiteDatabase) -> Long)? = null,
    internal val closeDatabase: (SQLiteDatabase) -> Unit = { database -> database.close() },
) {
    data class Started(
        val importId: String,
        val archiveFormatVersion: Int,
        val schemaVersion: Int,
        val maxPageBytes: Int,
        val maxBlobChunkBytes: Int,
        val idleExpiryMillis: Long,
    )

    data class Preview(
        val importId: String,
        val previewToken: String,
        val schemaVersion: Int,
        val localStateVersion: Long,
        val recordCount: Long,
        val mediaCount: Long,
        val mediaBytes: Long,
        val stagedRecordBytes: Long,
        val addedCount: Long,
        val changedCount: Long,
        val deletedCount: Long,
        val unchangedCount: Long,
        val unresolvedReferenceCount: Long,
        val timeOverlapCount: Long,
        val conflictCount: Long,
        val openSessionCount: Long,
        val localOpenSessionCount: Long,
        val applyReady: Boolean,
    )

    data class ConflictPage(
        val importId: String,
        val previewToken: String,
        val nextCursor: String?,
        val done: Boolean,
        val conflicts: List<Map<String, Any?>>,
    )

    data class Applied(
        val importId: String,
        val stateVersion: Long,
        val insertedCount: Long,
        val skippedCount: Long,
    )

    class Failure(val code: String, message: String) : IllegalStateException(message)

    internal data class Expected(
        val archiveFormatVersion: Int,
        val schemaVersion: Int,
        val archiveDigest: String,
        val recordCount: Long,
        val mediaCount: Long,
        val mediaBytes: Long,
    )

    internal data class Handle(
        val id: String,
        val database: SQLiteDatabase,
        val databaseFile: File,
        val mediaFile: File,
        val catalog: Map<String, BackupExportStore.ImportTable>,
        val expected: Expected,
        internal val secret: ByteArray,
        var recordCount: Long = 0,
        var mediaCount: Long = 0,
        var allocatedMediaBytes: Long = 0,
        var receivedMediaBytes: Long = 0,
        var rawBytes: Long = 0,
        var lastAccessElapsedMillis: Long,
        var preview: Preview? = null,
        var revisionGraphValidated: Boolean = false,
    ) {
        internal fun wipeSecret() { secret.fill(0) }
    }

    internal data class NormalizedRow(
        val table: String,
        val values: Map<String, Any?>,
        val primaryKeyJson: String,
        val rowJson: String,
        val rowBytes: Int,
        val blobFields: List<BlobField>,
        val sessionStartMillis: Long?,
        val sessionEndMillis: Long?,
    )

    internal data class ApplyRow(
        val ordinal: Long,
        val table: String,
        val values: Map<String, Any?>,
        val blobs: Map<String, LongArray>,
    )

    internal data class ForeignKeySpec(
        val parentTable: String,
        val childColumns: List<String>,
        val parentColumns: List<String>,
    )

    internal data class HeadAdvance(
        val kind: String,
        val entityId: String,
        val expectedLocalRevision: String,
        val backupRevision: String,
    )

    internal data class DivergentEntityData(
        val entityId: String,
        val table: String,
        val ordinal: Long,
        val values: Map<String, Any?>,
        val payloadValues: Map<String, Any?>,
        val localPayloadValues: Map<String, Any?>,
        val blobs: Map<String, LongArray>,
        val local: LocalRow,
        val spec: BackupExportStore.ImportTable,
    )

    internal data class PlannedDivergentMerge(
        val kind: String,
        val entityId: String,
        val choice: String,
        val localRevision: String,
        val backupRevision: String,
        val data: DivergentEntityData,
    )

    internal data class PlannedCustomerDeletionResolution(
        val entityId: String,
        val choice: String,
        val localRevision: String,
        val backupRevision: String,
        val backupRowOrdinal: Long?,
        val backupValues: Map<String, Any?>?,
    )

    internal data class OmittedBackupGraph(
        val sessionIds: Set<String>,
        val locationPointIds: Set<String>,
        val revisionEntityKeys: Set<String>,
        val revisionIds: Set<String>,
        val gpsDeletionParentsByPoint: Map<String, Set<String>>,
        val omittedRowOrdinals: Set<Long>,
    )

    internal data class RevisionContent(
        val kind: String,
        val entityId: String,
        val jobId: String?,
        val payload: String?,
        val deleted: Long,
    )

    internal data class RevisionTombstoneRow(val kind: String, val entityIdColumn: String)

    internal data class ValidationColumn(
        val name: String,
        val declaredType: String,
        val notNull: Boolean,
        val defaultSql: String?,
    )

    internal data class BlobField(val id: String, val column: String, val sizeBytes: Long)

    companion object {
        const val MAX_REQUEST_BYTES = BackupExportStore.MAX_PAGE_BYTES
        const val MAX_PAGE_BYTES = MAX_REQUEST_BYTES - 1024
        const val MAX_BLOB_CHUNK_BYTES = 255 * 1024
        const val MAX_ROW_COUNT_PER_PAGE = 512
        const val MAX_CONFLICT_PAGE_BYTES = 256 * 1024
        internal const val MIN_CONFLICT_PAGE_BYTES = 1024
        internal const val STORAGE_CHUNK_BYTES = 32 * 1024
        internal const val MAX_STAGE_DATABASE_BYTES = 96L * 1024L * 1024L
        internal const val MAX_STAGE_PHYSICAL_BYTES = 128L * 1024L * 1024L
        internal const val REVISION_VALIDATION_BATCH_SIZE = 256
        internal val revisionValidationTables = listOf(
            "validation_revision_batch",
            "validation_revision_ready",
            "validation_revision_pending",
            "validation_reference_keys",
            "validation_time_claims",
            "validation_invoice_jobs",
            "validation_points",
            "validation_ranges",
            "validation_heads",
            "validation_parents",
            "validation_revisions",
        )
        internal const val FILE_PREFIX = "clockrove-import-"
        internal val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        internal val blobIdPattern = Regex("[A-Za-z0-9_-]{43}")
        internal val digestPattern = Regex("(?:[0-9a-f]{64}|[A-Za-z0-9_-]{43})")
        internal val immutableTables = setOf(
            "entity_revisions", "revision_parents", "job_deletions", "location_points",
            "location_deletion_ranges", "task_comment_events", "invoices", "invoice_copies",
            "invoice_payments", "invoice_payment_events",
            "invoice_payment_reversals", "invoice_voids", "quote_revisions", "quote_decisions",
            "quote_pdfs", "quote_invoice_links", "quotes", "quote_scope_lines", "quote_scope_revisions",
        )
        internal val mergeOnlyTables = setOf("app_settings", "automation_policies", "entity_heads", "quote_counter")
        internal val summaryColumns = setOf(
            "id", "name", "job_name", "customer_name", "number", "title", "start_ms", "end_ms",
            "occurred_ms", "amount_minor", "currency", "kind", "category",
        )
        internal val uniqueColumns = mapOf(
            "invoices" to listOf(listOf("number")),
            "invoice_payment_events" to listOf(listOf("command_version", "ordinal")),
            "invoice_payment_reversals" to listOf(listOf("payment_id")),
            "invoice_voids" to listOf(listOf("replacement_id")),
            "job_deletions" to listOf(listOf("revision_id")),
            "quotes" to listOf(listOf("number")),
            "quote_pdfs" to listOf(listOf("quote_id", "revision")),
            "quote_scope_lines" to listOf(listOf("task_id")),
            "location_deletion_ranges" to listOf(listOf("revision_id")),
        )
        internal val revisionDataTables = mapOf(
            "job" to "jobs",
            "customer" to "customers",
            "site" to "work_sites",
            "column" to "kanban_columns",
            "card" to "kanban_cards",
            "template" to "task_templates",
            "session" to "sessions",
            "automation" to "automation_policies",
            "task_comment" to "task_comments",
            "task_attachment" to "task_attachments",
            "actual_cost" to "actual_costs",
            "settings" to "app_settings",
            "job_tax_rate" to "job_tax_rates",
            "billing_tax_settings" to "billing_tax_settings",
            "job_location_recording" to "job_location_recording",
            "location_recording_settings" to "location_recording_settings",
            "job_invoice_billing_policy" to "job_invoice_billing_policy",
            "invoice_billing_defaults" to "invoice_billing_defaults",
            "invoice_accounting_settings" to "invoice_accounting_settings",
            "invoice_coverage" to "invoice_coverage",
            "invoice_task_claim" to "invoice_task_claims",
            "invoice_time_claim" to "invoice_time_claims",
            "template_quantity" to "template_quantities",
            "settings_money" to "settings_money",
            "job_money" to "job_money",
            "card_money" to "card_money",
            "template_money" to "template_money",
            "task_quantity" to "task_quantities",
            "payment_destination" to "payment_destinations",
            "quote_draft" to "quote_drafts",
            "quote_decision" to "quote_decisions",
            "quote_invoice_link" to "quote_invoice_links",
        )
        internal val revisionDataColumns = mapOf(
            "job" to BusinessSchema.jobColumns,
            "customer" to BusinessStore.customerColumns,
            "site" to BusinessSchema.siteColumns,
            "column" to BusinessSchema.columnColumns,
            "card" to BoardStore.cardColumns,
            "template" to BoardStore.templateColumns,
            "session" to BusinessSchema.sessionColumns,
            "automation" to listOf("job_id", "enabled", "auto_start", "automatic_departure", "grace_minutes"),
            "task_comment" to listOf("id", "task_id", "body", "created_ms", "updated_ms"),
            "task_attachment" to listOf("id", "task_id", "comment_id", "created_ms", "mime"),
            "actual_cost" to ActualCostStore.revisionColumns,
            "settings" to BusinessSchema.settingsColumns,
            "job_tax_rate" to listOf("job_id", "tax_units"),
            "billing_tax_settings" to listOf("id", "default_tax_units"),
            "job_location_recording" to listOf("job_id", "enabled"),
            "location_recording_settings" to listOf("id", "enabled"),
            "job_invoice_billing_policy" to listOf(
                "job_id", "hourly_mode", "minimum_hours", "task_minimum_minor",
            ),
            "invoice_billing_defaults" to listOf(
                "id", "hourly_mode", "minimum_hours", "task_minimum_minor",
            ),
            "invoice_accounting_settings" to listOf("id", "receivables_email"),
            "invoice_coverage" to listOf("invoice_id", "state", "reviewed_ms", "review_note"),
            "invoice_task_claim" to listOf("id", "invoice_id", "job_id", "task_id", "released_ms"),
            "invoice_time_claim" to listOf(
                "id", "invoice_id", "job_id", "session_id", "start_ms", "end_ms", "timezone", "released_ms",
            ),
            "template_quantity" to listOf("template_id", "quantity_units"),
            "settings_money" to listOf("id", "minor_text", "job_id"),
            "job_money" to listOf("id", "minor_text", "job_id"),
            "card_money" to listOf("id", "minor_text", "job_id"),
            "template_money" to listOf("id", "minor_text", "job_id"),
            "task_quantity" to listOf("card_id", "job_id", "quantity_units"),
            "payment_destination" to listOf("payment_id", "invoice_id"),
            "quote_draft" to listOf("id", "payload"),
            "quote_decision" to listOf("quote_id", "revision", "decision", "note", "recorded_ms"),
            "quote_invoice_link" to listOf(
                "invoice_id", "quote_id", "revision", "original_invoice_id", "created_ms", "payload",
            ),
        )
        internal val revisionDataKeyColumns = mapOf(
            "automation" to listOf("job_id"),
            "job_tax_rate" to listOf("job_id"),
            "job_location_recording" to listOf("job_id"),
            "job_invoice_billing_policy" to listOf("job_id"),
            "invoice_coverage" to listOf("invoice_id"),
            "template_quantity" to listOf("template_id"),
            "task_quantity" to listOf("card_id"),
            "payment_destination" to listOf("payment_id"),
            "quote_draft" to listOf("quote_id"),
            "quote_decision" to listOf("quote_id", "revision"),
            "quote_invoice_link" to listOf("invoice_id"),
        )
        internal val integerRevisionDataKinds = setOf(
            "billing_tax_settings", "invoice_accounting_settings", "invoice_billing_defaults", "settings",
            "location_recording_settings", "settings_money",
        )
        internal val revisionDataKinds = revisionDataTables.entries.associate { (kind, table) -> table to kind }
        /**
         * Kinds emitted by the schema-17 RevisionStore writers, including dynamic
         * payment/money kinds and lifecycle tombstones. An archive with a new kind
         * must be explicitly taught how its entity graph is represented before it
         * can be previewed or applied.
         */
        internal val supportedRevisionKinds = setOf(
            "actual_cost", "automation", "billing_tax_settings", "card", "card_money", "column",
            "customer", "invoice", "invoice_accounting_settings", "invoice_billing_defaults",
            "invoice_copy", "invoice_coverage", "invoice_payment", "invoice_payment_event",
            "invoice_payment_reversal", "invoice_task_claim", "invoice_time_claim", "invoice_void",
            "job", "job_invoice_billing_policy", "job_location_recording", "job_money", "job_tax_rate",
            "location", "location_deletion_range", "location_recording_settings", "logo",
            "payment_destination", "quote_decision", "quote_draft", "quote_invoice_link", "quote_revision",
            "quote_scope_line", "quote_scope_revision", "session", "settings", "settings_money", "site",
            "task_attachment", "task_comment", "task_quantity", "template", "template_money",
            "template_quantity",
        )
        internal val revisionTombstoneRows = mapOf(
            "jobs" to RevisionTombstoneRow("job", "id"),
            "customers" to RevisionTombstoneRow("customer", "id"),
            "work_sites" to RevisionTombstoneRow("site", "id"),
            "kanban_columns" to RevisionTombstoneRow("column", "id"),
            "kanban_cards" to RevisionTombstoneRow("card", "id"),
            "task_comments" to RevisionTombstoneRow("task_comment", "id"),
            "task_attachments" to RevisionTombstoneRow("task_attachment", "id"),
            "sessions" to RevisionTombstoneRow("session", "id"),
            "task_templates" to RevisionTombstoneRow("template", "id"),
            "automation_policies" to RevisionTombstoneRow("automation", "job_id"),
            "job_tax_rates" to RevisionTombstoneRow("job_tax_rate", "job_id"),
            "job_money" to RevisionTombstoneRow("job_money", "id"),
            "card_money" to RevisionTombstoneRow("card_money", "id"),
            "template_money" to RevisionTombstoneRow("template_money", "id"),
            "task_quantities" to RevisionTombstoneRow("task_quantity", "card_id"),
            "template_quantities" to RevisionTombstoneRow("template_quantity", "template_id"),
            "actual_costs" to RevisionTombstoneRow("actual_cost", "id"),
            "job_invoice_billing_policy" to RevisionTombstoneRow("job_invoice_billing_policy", "job_id"),
            "job_location_recording" to RevisionTombstoneRow("job_location_recording", "job_id"),
            "quote_drafts" to RevisionTombstoneRow("quote_draft", "quote_id"),
            "logo_assets" to RevisionTombstoneRow("logo", "id"),
        )
        internal val divergentHeadMergeChoices = setOf(
            "MERGE_IDENTICAL_CONTENT",
            "MERGE_KEEP_LOCAL_CONTENT",
            "MERGE_USE_BACKUP_CONTENT",
        )
        // Issued decisions and invoice provenance are protected by immutable-table triggers.
        // A restore can retain both branches while selecting the currently committed content.
        internal val immutableDivergentMergeKinds = setOf("quote_decision", "quote_invoice_link")
        internal val customerDeletionChoices = setOf(
            "PRESERVE_LOCAL_DELETION",
            "RESTORE_BACKUP_CONTENT",
            "KEEP_LOCAL",
            "APPLY_BACKUP_DELETION",
        )
    }

    internal val appContext = context.applicationContext
    internal val directory = File(appContext.noBackupFilesDir, "clockrove-backup-imports")
    internal val random = SecureRandom()
    internal val cleanupPending = mutableSetOf<String>()
    internal val pendingFiles = mutableSetOf<File>()
    internal var active: Handle? = null
    internal var expiryTask: BackupScheduledTask? = null
    internal var cleanupTask: BackupScheduledTask? = null

    init {
        require(idleTtlMillis > 0)
        try {
            ensureDirectory()
            retryPendingCleanup()
        } catch (_: Exception) {
            // Backup storage errors must not prevent ordinary timekeeping.
        }
    }

    @Synchronized
    fun begin(
        db: SQLiteDatabase,
        archiveFormatVersion: Int,
        schemaVersion: Int,
        archiveDigest: String,
        recordCount: Long,
        mediaCount: Long,
        mediaBytes: Long,
    ): Started = beginImportCore(db, archiveFormatVersion, schemaVersion, archiveDigest, recordCount, mediaCount, mediaBytes)

    /** Adds a contiguous bounded page of canonical rows; table and column names are compiled. */
    @Synchronized
    fun appendPage(
        importId: String,
        firstOrdinal: Long,
        records: List<*>,
        encodedRequestBytes: Int,
    ): Long = appendPageCore(importId, firstOrdinal, records, encodedRequestBytes)

    /** Writes a sequential chunk to an opaque media reference declared by an imported row. */
    @Synchronized
    fun writeBlob(
        importId: String,
        blobId: String,
        offset: Long,
        bytes: ByteArray,
        encodedRequestBytes: Int,
    ): Long = writeBlobCore(importId, blobId, offset, bytes, encodedRequestBytes)

    @Synchronized
    fun finish(db: SQLiteDatabase, importId: String): Preview = finishImportCore(db, importId)

    /** Stores one explicit conflict choice in the private staging database. */
    @Synchronized
    fun resolveConflict(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        expectedLocalStateVersion: Long,
        conflictId: String,
        choice: String,
    ) = resolveConflictCore(db, importId, previewToken, expectedLocalStateVersion, conflictId, choice)







    /** One range decision resolves the staged range row and its matching revision head together. */


    /** Applies the previewed rows and verified no-content descendant head advances atomically. */
    @Synchronized
    fun apply(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        expectedLocalStateVersion: Long,
    ): Applied {
        val handle = requireHandle(importId)
        val preview = handle.preview ?: throw Failure("PREVIEW_NOT_READY", "The import preview is not ready.")
        verifyPreviewIdentity(handle, preview, previewToken, expectedLocalStateVersion)
        var applyCounts = BackupImportApplyCounts(inserted = 0L, skipped = 0L, changed = 0L)
        var committedVersion = expectedLocalStateVersion
        try {
            db.beginTransaction()
            try {
                // The repository serializes native commands; the SQL transaction also protects
                // against another connection writing between the freshness check and commit.
                if (stateVersion(db) != expectedLocalStateVersion ||
                    expectedLocalStateVersion != preview.localStateVersion) {
                    throw Failure("PREVIEW_STALE", "Local data changed after this preview; create a new preview.")
                }
                if (scalarLong(db, "SELECT COUNT(*) FROM sessions WHERE end_ms IS NULL") != 0L) {
                    throw Failure("LOCAL_TIMER_RUNNING", "Stop the local timer before restoring a backup.")
                }
                applyCounts = applyRowsWithinTransaction(db, handle, preview, expectedLocalStateVersion)
                committedVersion = if (applyCounts.changed > 0L) {
                    Math.addExact(expectedLocalStateVersion, 1L)
                } else {
                    expectedLocalStateVersion
                }
                db.setTransactionSuccessful()
            } finally {
                try {
                    db.endTransaction()
                } finally {
                    if (handle.revisionGraphValidated) clearRevisionValidation(handle)
                }
            }
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")
        }

        // The durable transaction has succeeded. Retire the one-shot preview even if staged-file
        // cleanup must be retried later; callers must never reapply a committed import.
        active = null
        cancelExpiry()
        handle.wipeSecret()
        try { handle.database.close() } catch (_: Exception) { }
        if (!deleteArtifacts(handle.id)) {
            try { scheduleCleanupRetry() } catch (_: Exception) { }
        }
        handle.lastAccessElapsedMillis = elapsedClock()
        return Applied(importId, committedVersion, applyCounts.inserted, applyCounts.skipped)
    }











    /** Records restore-time safety normalization as a child of the imported revision. */






    /** Revalidate the graph after conflict choices have removed staged rows. */


    internal fun appliedGraphInvalid(): Nothing =
        throw Failure("APPLY_FAILED", "The backup could not be applied atomically.")

    @Synchronized
    fun readConflictPage(
        db: SQLiteDatabase,
        importId: String,
        previewToken: String,
        cursor: String?,
        requestedMaxBytes: Int?,
    ): ConflictPage = readConflictPageCore(db, importId, previewToken, cursor, requestedMaxBytes)

    @Synchronized
    fun close(importId: String): Boolean = closeImportCore(importId)

    @Synchronized
    fun closeAll() = closeAllImportsCore()







    internal data class LocalRow(val values: Map<String, Any?>, val blobSizes: Map<String, Long?>)





    /** Checks revision ownership and ancestry inside the bounded staging database. */


    /** Kahn traversal is backed by SQLite so deep histories do not use JVM recursion. */


    /** Validation projections are needed only while building the preview. */




    internal fun invalidRevisionGraph(detail: String = "unspecified"): Nothing =
        throw Failure(
            "INVALID_REVISION_GRAPH",
            "The backup revision history is invalid (${safeDiagnostic(detail)}).",
        )

    /** All writers serialize a JSON object; null is reserved for intentional redaction. */




    /** Every live revisioned row in a schema-17 archive must be backed by its exact head payload. */


    internal fun invalidReference(detail: String = "unspecified"): Nothing =
        throw Failure(
            "INVALID_REFERENCE",
            "The backup contains an unresolved record reference (${safeDiagnostic(detail)}).",
        )

    /** Exercises the installed CHECK/NOT NULL/type rules against one staged row at a time. */








    internal fun invalidArchiveRow(): Nothing =
        throw Failure("INVALID_ARCHIVE", "The backup contains an invalid record.")

    /** Uses the installed SQLite schema as the FK contract for every portable row. */


    /** Mirrors invoice-claim triggers which are not present in the validation schema. */
















    /**
     * A covered backup point can be omitted only when a local range already fences the same
     * measured time and scope, or local state has a causally valid point head. Omission removes
     * the point row and its complete revision/head graph together.
     */










    /**
     * A user-selected incoming range deletion writes local point tombstones with both the
     * previous point head and every selected range as direct parents, then removes the rows.
     */












    /** Adds one scoped reference edge to both rows in the staged dependency conflict. */




    /** Stable aggregate IDs bind conflicts and rows that must be imported as one snapshot. */




    /** Labels the imported head's graph position without guessing from clocks or state versions. */
    /** Fast-forwards only a proven descendant whose live business row and payload are unchanged. */










    /** Maps archived business rows back to the revision entity whose choice owns them. */






    /** A job deletion fence owns its job and operational child heads as one conflict. */


    /** A data-row tombstone and its revision head are one local-deletion decision. */








    internal fun validLiveRevision(revision: RevisionContent, kind: String, entityId: String): Boolean =
        revision.kind == kind && revision.entityId == entityId && revision.deleted == 0L && revision.payload != null















    internal fun divergentMergeKey(kind: String, entityId: String): String = "$kind:$entityId"











    /**
     * If a backup row depends on another staged row that already needs a choice, require an
     * explicit omission for the child whenever no local row can satisfy that reference. This
     * keeps a KEEP_LOCAL/OMIT choice on a parent from turning into a late foreign-key failure.
     */










    internal fun previewToken(handle: Handle, localVersion: Long, conflictCount: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("preview\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.id.toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.expected.archiveDigest.toByteArray(StandardCharsets.UTF_8))
        mac.update(ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(localVersion).putLong(conflictCount).array())
        return Base64.encodeToString(mac.doFinal(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun conflictId(handle: Handle, rowOrdinal: Long, table: String, key: String, reason: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("conflict\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(handle.id.toByteArray(StandardCharsets.UTF_8))
        mac.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(rowOrdinal).array())
        mac.update(table.toByteArray(StandardCharsets.UTF_8))
        mac.update(key.toByteArray(StandardCharsets.UTF_8))
        mac.update(reason.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(mac.doFinal(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun encodeCursor(handle: Handle, preview: Preview, index: Int): String {
        val position = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(index).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("conflict-cursor\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(preview.previewToken.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(position + mac.doFinal(position), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun decodeCursor(handle: Handle, preview: Preview, cursor: String): Int {
        val token = try {
            Base64.decode(cursor, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        }
        if (token.size != Int.SIZE_BYTES + 32) throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        val position = token.copyOfRange(0, Int.SIZE_BYTES)
        val signature = token.copyOfRange(Int.SIZE_BYTES, token.size)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handle.secret, "HmacSHA256"))
        mac.update("conflict-cursor\u0000".toByteArray(StandardCharsets.UTF_8))
        mac.update(preview.previewToken.toByteArray(StandardCharsets.UTF_8))
        if (!MessageDigest.isEqual(signature, mac.doFinal(position))) {
            throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        }
        val index = ByteBuffer.wrap(position).order(ByteOrder.BIG_ENDIAN).int
        if (index < 0 || index.toLong() > preview.conflictCount) throw Failure("INVALID_CURSOR", "The conflict page cursor is invalid.")
        return index
    }

    internal fun encodedEnvelopeSize(value: Any): Int =
        StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(value).position()

    internal fun ConflictPage.toWireMap(): Map<String, Any?> = mapOf(
        "importId" to importId,
        "previewToken" to previewToken,
        "nextCursor" to nextCursor,
        "done" to done,
        "conflicts" to conflicts,
    )













    @Synchronized
    internal fun expireIfIdle(importId: String) {
        val handle = active
        if (handle == null || handle.id != importId) return
        expiryTask = null
        val now = elapsedClock()
        if (now >= handle.lastAccessElapsedMillis && now - handle.lastAccessElapsedMillis >= idleTtlMillis) {
            active = null
            destroyHandle(handle)
        } else {
            try { scheduleExpiry(handle) } catch (_: Failure) { }
        }
    }







    internal fun stateVersion(db: SQLiteDatabase): Long = stateVersionReader?.invoke(db)
        ?: scalarLong(db, "SELECT version FROM state WHERE id=1")

    internal fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long = db.rawQuery(sql, args).use {
        if (!it.moveToFirst()) throw Failure("STORAGE_UNAVAILABLE", "The local backup state is unavailable.")
        it.getLong(0)
    }

/** Keeps restore diagnostics single-line, bounded and free of archive payloads. */
internal fun safeDiagnostic(value: String): String = value
    .replace(Regex("[\\r\\n\\t]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(240)
    .ifEmpty { "unspecified" }


    internal fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
