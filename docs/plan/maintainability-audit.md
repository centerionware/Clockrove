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

All three audited snapshots contain these eight files over 1,000 lines. The docs-only PR reduces the two planning files below the limit; six existing source/test files remain follow-up work.

## Safe order

1. Let W2's PR #66 and W3's PR #65 finish. They currently share `BackupExportStore.kt`, its test, `BackupImportStoreTest.kt`, `app_plan.md`, `docs/BACKUP_FORMAT.md`, `lib/backup/backup_archive.dart`, `plan_status.md`, and `test/backup_archive_test.dart`. Do not extract or rewrite those files while either branch is active.
2. After both are integrated, split `BackupImportStore.kt` behind its stable public API. Start with lifecycle/staging and cleanup, then preview validation, conflict planners, and revision/GPS merge logic; keep apply atomic and version-checked.
3. Split the oversized import test into the domain suites in the table, retaining one shared fixture. Run the full existing Actions validation at the exact commit after each extraction step; do not use this as a reason to run builds or tests on the VM.
4. Split the archive codec and its tests after W3's benchmark work is integrated. Keep archive-format compatibility fixtures and streaming bounds tests with the codec they cover.
5. Split `TimerRepository.kt` while preserving one native write/command boundary. Then split history and board UI files along the widget/editor/screen boundaries listed above.

## Import test grouping detail

At PR #66's audited head, the 4,748-line test file groups naturally as follows: expiry/media limits (roughly lines 71–277); portable-row and revision validation (278–762); revision ancestry, merge and aggregate conflict cases (763–2,032); GPS range/tombstone behavior (2,033–2,525); staging, uniqueness, overlap and deletion-fence cases (2,526–3,549); idempotency and transactional apply/rollback (3,550–4,232); expiry cleanup (4,233–4,309); shared fixture and assertion helpers (4,310 onward). Reconfirm these boundaries after W2/W3 merge because both PRs edit the test file.
