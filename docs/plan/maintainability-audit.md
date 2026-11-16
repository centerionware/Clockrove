# Maintained-file size audit and split plan

## Rule

Keep every maintained source, test, and documentation file at or below 1,000 lines. Split by cohesive feature or topic before extending an oversized file. Do not split a file while an open PR is actively changing it. Track existing exceptions and a conflict-safe order here.

## Main baseline and split boundaries

Counts below are tracked text lines from verified `main` at `fcd6d7978aa42ab03abc6dfe0b352f5c27d682ea`, read from GitHub on 2026-11-16. They include a final unterminated line and exclude binary and generated build output.

| Maintained file on main | Lines | Safe split boundary |
| --- | ---: | --- |
| `android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStore.kt` | 1,958 | Split import staging/expiry, preview validation, conflict planning, revision merge, GPS deletion conflicts, transactional apply, and staged-file cleanup behind the existing store API. Keep apply on the serialized SQLite transaction boundary. |
| `android/app/src/test/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStoreTest.kt` | 1,400 | Split staging/expiry, preview validation, revision merge, GPS/deletion conflicts, apply/rollback, and cleanup suites; move common fixtures to focused test support. |
| `plan_status.md` | 4,420 | Keep current status and active milestone summary in the index; archive chronological validation entries at complete heading boundaries. This PR does so. |
| `app_plan.md` | 3,444 | Keep the product-contract index concise; move start/context, product contract, CI references, milestones, release gates, and chronological evidence to linked topical files. This PR does so. |
| `lib/backup/backup_archive.dart` | 1,416 | Split schema/models, buffered codec/validation, streaming plan/encoder, and binary reader/writer/digest primitives while preserving archive compatibility. |
| `android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/TimerRepository.kt` | 1,244 | Separate timer/session, location/geofence, board/conversation, business/billing, quote/invoice, and backup collaborators. Keep the repository as serialized command coordinator and move schema lifecycle to a focused database component. |
| `lib/tracking/history_screen.dart` | 1,265 | PR #69 extracts list/GPS dialog, editor, audit, revisions, and formatter into `lib/tracking/history/`; preserve the public barrel. |
| `lib/business/job_screen.dart` | 1,112 | PR #68 extracts board UI into `lib/business/board/`; preserve the existing screen import through an export barrel. |

## Current open-PR snapshots

The following line counts were checked from exact GitHub heads on 2026-11-16. All listed PRs remain open; none of #65, #66, #68, or #69 has merged. GitHub currently reports each PR mergeable against `main`, which does not mean they can be combined with each other.

- PR #65, head `056156b8060567d6ac7dcdd44e58c874a9c8ef0a`: over-limit `BackupImportStore.kt` (1,958), `BackupImportStoreTest.kt` (1,410), `app_plan.md` (3,462), `plan_status.md` (4,439), `TimerRepository.kt` (1,244), `history_screen.dart` (1,265), and `job_screen.dart` (1,112). The archive split reduced `backup_archive.dart` to a 10-line compatibility entrypoint.
- PR #66, head `05db4127a864a39c391fd699fd88bfebd14627cb`: over-limit `app_plan.md` (3,447), `plan_status.md` (4,428), `backup_archive.dart` (1,416), `history_screen.dart` (1,265), and `job_screen.dart` (1,112). `BackupImportStore.kt` is 906 lines, `TimerRepository.kt` is 932, and the old `BackupImportStoreTest.kt` is split into focused test files.
- PR #67, head `6f21fd4e109d7e2eb18baa5ede25b7b40e3bab94`: all plan/status/audit documents are at or below 1,000 lines. Six source/test exceptions inherited from `main` remain: `BackupImportStore.kt`, `BackupImportStoreTest.kt`, `backup_archive.dart`, `TimerRepository.kt`, `history_screen.dart`, and `job_screen.dart`.
- PR #68, head `4f0bef36d7d2039cc92b7061b704c62661699653`: the board split is correctly located at `lib/business/board/board_view.dart` (922 lines), with `job_screen.dart` at 1 line. The seven other over-limit files are the baseline files except `job_screen.dart`.
- PR #69, head `19ba36c44c78a38f2efd413cbc7fb48bea4a7299`: `history_screen.dart` is a 6-line public barrel; extracted files are at most 599 lines. The six other over-limit files are the baseline files except `history_screen.dart`.

## Integration and validation evidence

- PR #67 conflicts with #65 and #66 in `app_plan.md` and `plan_status.md`; its current mergeability only describes the base `main`. Keep #67 draft until the backup PRs' plan changes are reconciled with this split, then refresh the branch and rerun Actions on its exact head.
- PRs #65 and #66 also conflict with each other: `BackupImportStoreTest.kt` is modified by #65 and deleted by #66, and `backup_archive.dart`, `app_plan.md`, and `plan_status.md` have content conflicts. Validate a deliberate integration of both behavior branches before merging either. Preserve archive compatibility and the single serialized SQLite apply/write boundary.
- A read-only `git merge-tree` check found no conflicts between #67 and #68 or #69, nor between #68 and #69. The board and history source splits do not update plan files. #69 does not need plan-evidence changes because of #68's split; this audit now records both actual paths and line counts.
- PR #65 exact-head Actions run [43790124516](https://github.com/centerionware/Clockrove/actions/runs/43790124516) is in progress. PR #66 exact-head run [43789836359](https://github.com/centerionware/Clockrove/actions/runs/43789836359) failed during Android Kotlin compilation in `BackupImportStore.kt`; Flutter behavior tests passed, while Android build and later native/device gates did not run. Fix and rerun before considering it validated.
- PR #68 exact-head run [43786829951](https://github.com/centerionware/Clockrove/actions/runs/43786829951) is in progress after the earlier head failed because the quote journey could not find “Move task”. PR #69 exact-head run [43786465867](https://github.com/centerionware/Clockrove/actions/runs/43786465867) passed full Validate. Hold #69 until #68 resolves, then refresh #69 against the resulting base and rerun Actions.
- The pre-refresh PR #67 head `6f21fd4` failed Validate run [43788953889](https://github.com/centerionware/Clockrove/actions/runs/43788953889) during the API-24 backup crypto benchmark: APK installation timed out on both attempts, so no benchmark test ran. Earlier steps and the other reported device journeys passed, but this is not a full Validate pass. Any documentation update creates a new head; only a completed run on that new exact head counts.

## Safe order

1. Finish and validate the behavior in backup PRs #65 and #66. Resolve their code and planning-file conflicts on a deliberately stacked integration branch or owning branches.
2. Keep every maintained file changed by the integrated backup work at or below 1,000 lines. Current heads already split some files, but #65 still has the oversized import store/test and TimerRepository, while #66 still has the oversized archive file; both retain oversized plan files until reconciled with #67.
3. Refresh #67's plan split from the integrated backup evidence and run Actions on the exact integrated head. Do not merge it against stale main content.
4. Resolve #68's task-move behavior and green exact-head run. Then rebase/refresh #69 onto the new main and rerun its complete validation before merging.
5. Re-audit all maintained source, tests, and docs after integration; split remaining over-limit files on their owning changes before those changes merge.
