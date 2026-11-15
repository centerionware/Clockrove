# Clockrove backup format and native snapshot boundary

This document defines the native-to-Dart export boundary for archive format 1.
The native snapshot API exports committed portable records and media. It does
not encrypt, serialize the final archive container, inspect imported data, or
write the database during export.

## Snapshot metadata

The clockrove/backup/v1 method channel provides:

- getBackupSnapshotInfo: read-only capability metadata; it creates no handle.
- startBackupSnapshot: creates one consistent detached snapshot and returns
  snapshotId, archiveFormatVersion, schemaVersion, stateVersion,
  capturedAtUtcMillis, recordCount, mediaCount, mediaBytes, and
  openSessionCount.
- readBackupSnapshotPage: pages portable row records using an opaque cursor.
- readBackupBlob: reads a bounded byte range from a blob identified by an
  opaque snapshot-scoped ID.
- closeBackupSnapshot: closes and removes the temporary snapshot.

The authoritative export metadata comes from startBackupSnapshot. The
read-only info response is only a capability/current-state estimate; a later
write can change it before the snapshot begins.

archiveFormatVersion is independent of the Android SQLite schemaVersion.
Format version 1 currently supports SQLite schema version 16. Schema 16 adds
portable GPS deletion-range coverage; the range bounds and coordinate-free
revisions allow restored histories to retain GPS deletions. A future schema
must update the explicit native allowlist and the archive adapter before export
is enabled for that schema. Unknown schemas fail closed.

## Row and media records

readBackupSnapshotPage returns a map with snapshotId, nextCursor, done, and
records. Each row record has type=row, an allowlisted table name, a values map,
and a blobs list:

    {
      "type": "row",
      "table": "invoices",
      "values": {"id": "invoice-id", "pdf": null},
      "blobs": [{"id": "opaque blob id", "column": "pdf", "sizeBytes": 1234}]
    }

The row value map contains SQLite null, integer, floating-point, boolean-like
integer, and string values. Binary columns never cross in a row record. A null
binary value remains null in values; a non-null value is represented by one
entry in blobs, with the corresponding field set to null in values.
readBackupBlob returns offset, nextOffset, done, and a Uint8List bytes chunk.
Offsets may be resumed at any byte boundary. Blob IDs are generated for one
snapshot and cannot be used with another handle.

Rows are ordered by allowlisted table name, then by that table's declared
primary key. Blob data is emitted in full, including cached invoice and quote
PDFs and normalized task images. In particular, invoice_copies exports every
kind, including financial RECEIPT rows and their PDFs.

The Dart archive writer must verify complete cursor traversal, contiguous blob
offsets, declared byte totals, and final archive bounds. It must not parse or
merge partial snapshots after any native paging error. Before encryption, Dart
must measure its exact serialized plaintext size, including framing, JSON
escaping, base64 or other media representation, and all records. If it exceeds
64 MiB, fail the entire export visibly and close the snapshot; never omit
comments or media to fit the limit.

## Limits and lifecycle

- One active snapshot per process; concurrent starts fail with SNAPSHOT_BUSY.
- Native detached staging input budget: 60 MiB, including framed UTF-8 row
  records and media. This does not guarantee that the Dart archive encoding
  will fit the final limit.
- Final archive plaintext limit remains 64 MiB in the Dart crypto contract.
- Maximum 500,000 records and 10,000 media objects.
- Maximum encoded row size: 128 KiB.
- Maximum encoded method-channel row page: 256 KiB, including the success
  envelope. A page request may be between 1 KiB and 256 KiB.
- Maximum media transfer: 256 KiB per readBackupBlob call.
- Idle handle expiry: 15 minutes using monotonic elapsed time. A scheduled
  native cleanup removes staging files without waiting for another request.
  Close, expiry, startup after process death, or an export failure also removes
  staging files.
- Staging files live under the application-private noBackupFilesDir; the
  method channel never accepts a path. Files are deleted on close, expiry, or
  next process startup after a crash.

The source database read transaction and repository serialization boundary are
held only while the detached snapshot is constructed. Paging reads the staged
copy, so later committed edits neither block on nor alter a running export.
Export never stops, closes, or starts a timer. Open sessions remain provisional
rows; restore preview must require an explicit omit or close-time choice.

## Portable dataset allowlist

Only these table names are emitted:

actual_costs, app_settings, automation_policies, billing_tax_settings,
card_money, customers, entity_heads, entity_revisions,
invoice_accounting_settings, invoice_billing_defaults, invoice_copies,
invoice_coverage, invoice_payment_events, invoice_payment_reversals,
invoice_payments, invoice_task_claims, invoice_time_claims, invoice_voids,
invoices (including retained issued documents after migration staging is
renamed), job_deletions, job_invoice_billing_policy, job_location_recording,
job_money, job_tax_rates, jobs, kanban_cards, kanban_columns,
location_deletion_ranges, location_points, location_recording_settings, logo_assets,
payment_destinations, quote_counter, quote_decisions, quote_drafts,
quote_invoice_links, quote_pdfs, quote_revisions, quote_scope_lines,
quote_scope_revisions, quotes, revision_parents, sessions, settings_money,
task_attachments, task_comment_events, task_comments, task_quantities,
task_templates, template_money, template_quantities, and work_sites.

work_sites includes site configuration fields but omits Android registration
keys, observed presence, and observed timestamps. automation_policies includes
the user's enabled/start/departure/grace choices and configuration timestamp
but omits armed state, suppression state, last observed presence, facts-since
state, and manual barriers. Restore must still keep imported automation
disabled until explicit user action.

`location_deletion_ranges` preserves half-open measured-UTC deletion bounds,
optional job scope (`NULL` means all jobs), timezone context, deletion time,
and the range revision reference. Its revision payload contains only range
identity, scope, bounds, and timezone. Point-deletion revisions and their
revision-parent rows are also portable, so the causal link from a later point
to every covering deletion range survives export. No coordinates are stored in
these range or tombstone revisions.

Binary fields are limited to logo_assets.bytes, invoices.pdf,
invoice_copies.pdf, quote_pdfs.bytes, and task_attachments.data.

The export excludes command receipts, geofence callback replay results,
pending/finalized departure runtime rows, geofence conflicts, widget bindings,
map provider credentials/settings, OS registrations, caches, keystore keys,
and temporary files. Revision rows, parent links, heads, job-deletion fences,
location points, invoice/payment/receipt records, quote history, expenses,
comments, and comment events remain portable.

This is the export contract only. It does not claim that restore preview,
conflict resolution, transactional merge, or cross-version migration has
passed validation.
