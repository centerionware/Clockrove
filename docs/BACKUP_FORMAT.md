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
      "values": {"id": "invoice-id"},
      "blobs": [{"id": "opaque blob id", "column": "pdf", "sizeBytes": 1234, "sha256": "<base64 digest>"}]
    }

The row value map contains SQLite null, integer, floating-point, boolean-like
integer, and string values. Binary bytes never cross in a row record. A null
binary value remains null in values; a non-null value is represented only by
one entry in blobs, and that column is omitted from values.
readBackupBlob returns offset, nextOffset, done, and a Uint8List bytes chunk.
Offsets may be resumed at any byte boundary. Blob IDs are generated for one
snapshot and cannot be used with another handle.

Each media descriptor includes `sha256`, the canonical standard Base64 encoding
of the 32-byte SHA-256 digest computed while native copies that media into the
immutable snapshot. The digest is included in the encoded page-size limit. Dart
checks the streamed media against it while writing the archive; a mismatch
aborts export before the encrypted destination is committed.

Rows are ordered by allowlisted table name, then by that table's declared
primary key. Blob data is emitted in full, including cached invoice and quote
PDFs and normalized task images. In particular, invoice_copies exports every
kind, including financial RECEIPT rows and their PDFs.

The Dart archive writer must verify complete cursor traversal, contiguous blob
offsets, declared byte totals, and final archive bounds. It must not parse or
merge partial snapshots after any native paging error. Before encryption, Dart
measures the exact binary plaintext size, including scalar framing, row records,
blob descriptors, raw media and the fixed archive header. If it exceeds 64 MiB,
fail the entire export visibly and close the snapshot; never omit comments or
media to fit the limit. Streaming preflight replays the detached,
immutable native pages instead of retaining all Dart row maps: it scans once to
validate and measure, once to emit row records, and once to emit media. Each
pass holds at most one bounded native page. Preflight retains only a bounded
set of blob IDs (at most 10,000) for uniqueness and incremental row digests; it
does not build per-revision Dart maps. The native snapshot checks revision
heads, entity ownership, and parent references in SQLite. It copies parent
edges to a private SQLite validation file capped at 256 MiB (four times the
archive ceiling), streams node IDs and parent edges from the active source
transaction into that file with bounded cursors, and runs a batched Kahn
traversal there. The scratch file is removed after
success or failure. `RevisionStore` also creates fresh revision IDs and links
only to already-existing parents, while restore validates its selected union
graph before writing. Authenticated archive decode still validates the
complete untrusted revision graph.

## Limits and lifecycle

- One active snapshot per process; concurrent starts fail with SNAPSHOT_BUSY.
- Native detached staging input budget: 64 MiB, including framed UTF-8 row
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

## Streaming encrypted envelope v2 proposal

This section is a wire-contract proposal, not an implemented or accepted format.
Envelope version 2 carries one complete archive byte stream. The inner archive
format remains independently versioned. Version 1 (`CLKROVE1`) files keep their
current reader unchanged; readers dispatch by the eight-byte magic, and a file
identified as v2 must never fall back to the v1 reader after a parse or
authentication failure. Once v2 export is enabled, new exports use v2 while
both readers remain available.

### Fixed header

All multibyte integers are unsigned big-endian. The header is exactly 64 bytes;
unknown IDs, nonzero reserved bytes, alternate lengths, or noncanonical
parameters fail before password derivation.

| Offset | Bytes | Field | Required value |
| --- | ---: | --- | --- |
| 0 | 8 | Magic | ASCII `CLKROVE2` |
| 8 | 2 | Envelope version | 2 |
| 10 | 2 | Header length | 64 |
| 12 | 1 | KDF ID | 1 = Argon2id |
| 13 | 1 | Cipher ID | 1 = AES-256-GCM |
| 14 | 1 | Nonce scheme ID | 1 = frame type plus sequence |
| 15 | 1 | Reserved | 0 |
| 16 | 4 | Argon2id memory, KiB | 19,456 |
| 20 | 4 | Argon2id iterations | 2 |
| 24 | 1 | Argon2id parallelism | 1 |
| 25 | 1 | Salt length | 16 |
| 26 | 1 | GCM tag length | 16 |
| 27 | 1 | Reserved | 0 |
| 28 | 4 | Plaintext bytes per data frame | 65,536 |
| 32 | 4 | Maximum archive plaintext bytes | 67,108,864 |
| 36 | 4 | Maximum data-frame count | 1,024 |
| 40 | 16 | Random Argon2id salt | New cryptographically random value per file |
| 56 | 8 | Reserved | All zero |

The password is encoded as UTF-8 with the existing 12–1,024-byte bounds.
Argon2id runs once per backup operation with the fixed profile above and returns
one 32-byte AES key. The header is validated against fixed limits before this
memory-hard operation; values from a file never raise those limits. Each file
gets a fresh 16-byte salt. Key material is never persisted and its local
references are released when the operation exits.

### Frames and authentication

Each frame is a 9-byte header followed by ciphertext and a 16-byte GCM tag. The
frame header is: type (1 byte), sequence (4 bytes), and plaintext length
(4 bytes). Data type is `0x01`; final-manifest type is `0x7f`. The length is the
plaintext/ciphertext byte count and excludes the tag. The 96-bit GCM nonce is
the frame type encoded as a big-endian uint32 followed by the sequence encoded
as a big-endian uint64. The sequence and type combination is unique within the
file key: data frames use sequences `0` through `count - 1`; the manifest uses
sequence `count` with its distinct frame type.

For every frame, AES-GCM associated data is the exact 64-byte file header
concatenated with that frame's exact 9-byte header. This authenticates the KDF
profile, salt, limits, frame type, order and length. Each data frame is
independently authenticated. Non-final data frames must contain exactly 65,536
plaintext bytes; the last contains 1–65,536 bytes. Empty archives are rejected.
The reader requires contiguous data sequence numbers beginning at zero and no
more than 1,024 data frames. Unknown frame types, duplicate/reordered frames,
short non-final frames, over-limit lengths, or trailing bytes are rejected.

Exactly one final-manifest frame must follow the data frames. Its 48-byte
plaintext is `END2` (four ASCII bytes), data-frame count (uint32), total archive
plaintext bytes (uint64), and SHA-256 of the complete inner archive byte stream
(32 bytes). The manifest is encrypted and authenticated using the final-frame
type, the next sequence number, and the same associated-data rule. The reader
requires its count, byte total and digest to match the observed stream, then
requires end-of-file. A missing manifest, truncation, duplicate, reordering,
extension, altered header, altered frame, or bad tag fails the whole import.
Wrong-password and authentication failures use the same user-facing unlock
error; unsupported versions or unsafe fixed-header parameters may be reported
before deriving a key.

With the 64 MiB archive ceiling, the maximum encoded v2 file is 67,134,601
bytes: 64 header bytes, 67,108,864 payload bytes, at most 1,024 data-frame
headers/tags, and the 73-byte final frame. This bound is checked before staging
or allocating according to file-supplied sizes.

### Bounded serialization, staging and restore

Chunking the encrypted file alone is insufficient: v1's current Dart encoder,
`BackupSnapshotExporter`, and `BackupCrypto.encrypt` retain whole collections
or whole-file buffers. A v2 implementation must not build a `List` of all
rows/media or a whole archive/envelope `Uint8List`. It processes at most one
bounded record and one 65,536-byte plaintext/ciphertext frame at a time, plus
the existing bounded method-channel transfer. Total archive and frame counts
are checked as bytes are emitted.

The proposed writer streams the existing inner archive format where possible.
Because that format has record/media lengths in its header and puts media after
all records, its encoder must preflight a repeatable immutable native snapshot
with bounded per-record state (including streamed blob hashes), then traverse
the same snapshot again to emit the header, records and media into the v2 frame
writer. Both passes must use the snapshot ID and captured state version returned
by the original start call; they must verify those identifiers match on every
page. App writes after snapshot creation do not invalidate or abort export,
because the detached snapshot is immutable. Global revision-graph validation
must use bounded/disk-backed state.
If those guarantees cannot be made, define a separately versioned streaming
archive payload rather than collecting every row to reproduce v1 ordering.
The import side adds a bounded reader for the inner archive; the existing
whole-buffer v1 APIs remain available for compatibility fixtures and old files.

Export keeps the native snapshot detached and app-private while bytes stream to
the selected document. It does not report success until the final manifest,
flush and close succeed. Cancellation, size-limit failure, channel failure,
snapshot-handle expiry or destination-write failure closes the snapshot and
removes private staging. Concurrent app writes are allowed and do not cancel
the detached export. Destination deletion is attempted after a partial SAF
write; when a document provider refuses deletion, the UI must say the file may
be incomplete and must not report a successful backup.

Import is two-pass so the complete envelope is authenticated before archive
parsing, preserving the v1 safety boundary. Pass one reads sequentially,
authenticates every frame, checks the end manifest and rejects trailing data;
it does not decode archive records. Pass two reopens the source, rechecks every
frame and feeds authenticated plaintext chunks to a bounded archive parser. The
parser streams decoded rows/media into an uncommitted, detached, app-private
import staging database/files. If the picked document cannot be reopened, keep
a bounded private copy of the encrypted file and use it for both passes. Never
stage decrypted whole-archive bytes on disk. Staged rows/media and partial
conflict results remain invisible to preview and restore APIs until the full
second pass, archive validation and native staging checks succeed and the stage
is explicitly sealed by the transactional sink. Any error or cancellation
discards the whole stage.

Commit rechecks the preview token and captured local state version, applies all
selected merge choices in one native database transaction, and rolls back the
entire merge on any error. It never starts a timer or enables imported
automation. Discard/cancel closes snapshot/import handles and removes every
private staging file. Expiry and next-start cleanup cover process death; the
user-selected encrypted source file is never modified or deleted.

### Required v2 evidence before enabling export

- Keep v1 golden files readable, including wrong-password and tamper cases.
- Add exact v2 framing vectors and round-trips at 1 byte, frame boundaries,
  multiple frames and the full 64 MiB archive limit.
- Reject changed header/KDF fields before KDF work, wrong passwords, invalid
  tags, duplicate/reordered/missing/truncated frames, bad manifest totals/digest,
  trailing bytes and unsupported inner schemas.
- Verify cancel/failure cleanup for both passes, no preview/apply before full
  authentication, local-version changes before commit, and atomic rollback.
- On GitHub Actions, run maximum-size end-to-end export and restore on API 24 and
  API 35. Sample process `VmRSS` every 20 ms through export/encryption and record
  the sampled peak delta; it must stay at or below 64 MiB on each API. Brief RSS
  spikes shorter than the sampling interval may not be observed. Also assert
  that no Dart row-map collection grows with total record count, report elapsed
  time, and verify no private staging remains after success, cancellation,
  corruption or process restart.
- Keep the Settings export/restore route withheld until these exact-head checks
  pass. No VM build, test, format or analyzer run is permitted.

PR #65 exact-head run [43820166609](https://github.com/centerionware/Clockrove/actions/runs/43820166609) passed the full-path RSS limit on API 24 and API 35, with sampled deltas of 52,980 KiB and 56,156 KiB. The exported archives were 66,072,344 and 66,072,097 bytes, about 1 MiB below the 67,108,864-byte maximum, so this does not prove maximum-size export memory. The next fixture targets the archive ceiling minus 16 KiB and rejects archives more than 64 KiB below it; that exact-head run is still required.

Exact-head run [43824139548](https://github.com/centerionware/Clockrove/actions/runs/43824139548) passed formatting, analysis, Flutter behavior, Android compilation, instrumentation assembly and native tests, then failed on API 24 before archive creation. The seed contained 19 media objects totaling 67,091,803 bytes, but native snapshot staging rejected media plus framed row data at its 64 MiB staging limit. This run produced no archive-size or RSS measurement; API 35 did not start. The follow-up fixture targets media 64 KiB below the archive ceiling with a 2 KiB seed tolerance, reserves space for native row staging and still requires the resulting archive to be within 64 KiB of the archive maximum. The exact-head result is pending; keep user-facing export withheld.

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

Exact-head run [43827154354](https://github.com/centerionware/Clockrove/actions/runs/43827154354) on `69443d6` passed source, compilation and native gates, then failed on API 24 before the memory test ran. Seeding succeeded with 19 media objects / 67,041,922 bytes. Flutter’s APK install then timed out after 226.8 seconds with ADB exit -15; after confirmed reboot, both separate Flutter reseed install attempts timed out too. PackageManager responded and the app path existed, but the bounded filtered logcat showed no install cause (artifact ID `14207839292`). No archive-size or RSS value was produced, and API 35 did not start. The successor restores the generated-byte crypto benchmark to the pre-native position, keeps the full-path test after native journeys to isolate its app-data reset and synthetic fixture, and retries the memory test directly without a second Flutter reseed install. It retains the existing 64 KiB archive bound and 65,536 KiB RSS limit; exact-head Actions remain required.

The next exact-head run [43832935667](https://github.com/centerionware/Clockrove/actions/runs/43832935667) on `58f510f` passed source, compilation and native gates. API 24 passed 38 native device tests with zero failures, errors or skips. The full-path seed APK install then timed out twice (226.7 and 226.4 seconds; ADB exit -15), including after a confirmed emulator reboot. The device step ended with exit 124 before the full-path benchmark ran; artifact `14210623617` records the failed attempts. PackageManager responded and the package path existed, but filtered logcat did not reveal the underlying install cause. No full-path archive or RSS value was produced, and API 35 did not start. A separate crypto-only test did produce an exact maximum-size archive (67,108,864 bytes; 67,134,601-byte envelope) and RSS deltas of 25,572 KiB for crypto and 60,176 KiB for verification. This is not full-path exporter/encryption evidence. The archive-size and 65,536 KiB full-path RSS gates remain unchanged; exact-head API 24/35 full-path validation is still required.

Comparison with the earlier passing full-path setup (`056156b` / run [43790124516](https://github.com/centerionware/Clockrove/actions/runs/43790124516)) shows it explicitly reinstalled the preserved app and instrumentation APKs through the bounded ADB helper before seeding. The `58f510f` run reused matching installed versions and then timed out during Flutter's seed-test APK install. The source successor restores those explicit installs while preserving crypto-before-native and full-path-after-native ordering. This is a likely harness-interaction hypothesis, not a confirmed emulator or PackageManager root cause. The change retains the 64 MiB fixture and existing archive/RSS checks; exact-head Actions remain required.
