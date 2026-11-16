# Maintained-file size audit and split plan

## Rule

Keep every maintained source, test, and documentation file at or below 1,000 lines. Split by cohesive feature or topic before extending an oversized file. Existing exceptions below are tracked follow-up work; files actively changed by an open PR must wait until that PR lands.

## Evidence

Counts are from read-only GitHub source archives inspected on 2026-11-16: verified `main` at `fcd6d7978aa42ab03abc6dfe0b352f5c27d682ea`, PR #65 at `414a052f9cbcf98754c6caffae02fb00e20ecbaf`, and PR #66 at `adbae1d904f2a8bcab6d5d5542450e1ba2b6fcbd`. Earlier requested PR snapshots `0ee5fda` and `a5a6e32` had already moved. Counts include tracked text files, count a final unterminated line, and exclude binary and generated build output.

| Maintained file | Main (`fcd6d79`) | PR #65 (`414a052`) | PR #66 (`adbae1d`) | Safe split boundary |
| --- | ---: | ---: | ---: | --- |
| `android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStore.kt` | 1,958 | 1,958 | 4,996 | Split import session staging/expiry, preview validation, conflict planning, revision merge, GPS deletion conflicts, transactional apply, and staged-file cleanup behind the existing store API. Keep apply on the same serialized SQLite transaction boundary. |
| `android/app/src/test/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStoreTest.kt` | 1,400 | 1,410 | 4,748 | Split into staging/expiry, preview validation, revision merge, GPS/deletion conflicts, apply/rollback, and cleanup suites; move common DB/archive helpers to `BackupImportTestSupport`. |
| `plan_status.md` | 4,420 | 4,435 | 4,428 | Keep current status and active milestone summary in the index; archive chronological validation entries at complete heading boundaries. This PR does so. |
| `app_plan.md` | 3,444 | 3,458 | 3,445 | Keep the product-contract index concise; move start/context, product contract, CI references, milestones, release gates, and chronological evidence to linked topical files. This PR does so. |
| `lib/backup/backup_archive.dart` | 1,416 | 1,404 | 1,416 | Split schema/model definitions, buffered codec and validation, streaming plan/encoder, and binary reader/writer/digest primitives under `lib/backup/archive/`. |
| `android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/TimerRepository.kt` | 1,244 | 1,244 | 1,269 | Separate timer/session, location/geofence, board/conversation, business/billing, quote/invoice, and backup collaborators. Keep the repository as the serialized command coordinator and move schema lifecycle to a focused database component. |
| `lib/tracking/history_screen.dart` | 1,265 | 1,265 | 1,265 | Move the history list, GPS range deletion dialog, history editor, audit screen, and revision screen into `lib/tracking/history/`. |
| `lib/business/job_screen.dart` | 1,112 | 1,112 | 1,112 | Move the board screen, task editor, column editor, and task-template picker into `lib/business/boards/`. |

All three audited snapshots contain these eight files over 1,000 lines. The docs-only PR reduces the two planning files below the limit; six existing source/test files remain in the audited base. PR #68 separately moves the board implementation: at head `b167b7ad2173a7bdd78eb6cf2f1e0c46b7c86bee`, `lib/business/job_screen.dart` is a one-line compatibility export and `lib/business/board/board_view.dart` is 922 lines. That PR is still awaiting Actions validation.

## Safe order

1. Finish the behavior changes in PRs #65 and #66 first, but do not merge either while a maintained file in its change set remains over 1,000 lines. They currently share `BackupExportStore.kt`, its test, `BackupImportStoreTest.kt`, `app_plan.md`, `docs/BACKUP_FORMAT.md`, `lib/backup/backup_archive.dart`, `plan_status.md`, and `test/backup_archive_test.dart`.
2. Before merging the backup work, split the oversized files on their owning branches or on a validated integration branch stacked on both heads. Cover `BackupImportStore.kt`, the shared `BackupImportStoreTest.kt`, `backup_archive.dart`, and `TimerRepository.kt`; keep each new file at or below 1,000 lines. Preserve the stable import API, portable archive compatibility, and one serialized SQLite write/apply boundary. Run Actions against the exact integration head before merging.
3. PR #68 already moves the board implementation to `lib/business/board/board_view.dart` (922 lines) and leaves `job_screen.dart` as a one-line compatibility export. Merge it only after its exact-head Actions check passes.
4. Split `history_screen.dart` independently from `main` into `lib/tracking/history/`, keeping `lib/tracking/history_screen.dart` as the public export entrypoint. This does not overlap the backup branches; validate its exact head in Actions.
5. Re-audit every maintained file after those changes and split any remaining over-limit source or test files before merging the affected work. This PR itself keeps all plan, status, and audit documents within the limit.


## Import test grouping detail

At PR #66's audited head, the 4,748-line test file groups naturally as follows: expiry/media limits (roughly lines 71–277); portable-row and revision validation (278–762); revision ancestry, merge and aggregate conflict cases (763–2,032); GPS range/tombstone behavior (2,033–2,525); staging, uniqueness, overlap and deletion-fence cases (2,526–3,549); idempotency and transactional apply/rollback (3,550–4,232); expiry cleanup (4,233–4,309); shared fixture and assertion helpers (4,310 onward). Reconfirm these boundaries after W2/W3 merge because both PRs edit the test file.
