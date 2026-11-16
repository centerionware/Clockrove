# Maintained-file size audit and split plan

## Rule

Keep every maintained source, test, and documentation file at or below 1,000 lines. Split by cohesive feature or topic before extending an oversized file. Do not split a file while an open PR is actively changing it. Track existing exceptions and a conflict-safe order here.

## Main baseline at `025cc10`

These counts are from verified main commit
`025cc10f5aac80ce6f56005f694e448ee4473761`, scanned on 2026-11-16. The scan
counted tracked UTF-8 text files and excluded binary and generated build output.
**This table describes main only.** Six maintained files exceed 1,000 lines on
that commit:

| Maintained file on main `025cc10` | Lines | Safe split boundary |
| --- | ---: | --- |
| `app_plan.md` | 3,444 | Split product contract, milestone handoff, and evidence into the focused `docs/plan/` index and topic files. |
| `plan_status.md` | 4,420 | Keep the current status index concise and move dated validation history into linked history files. |
| `android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStore.kt` | 1,958 | Split import staging/expiry, preview validation, conflict planning, revision merge, GPS deletion conflicts, transactional apply, and staged-file cleanup behind the existing store API. Keep apply on the serialized SQLite transaction boundary. |
| `android/app/src/test/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStoreTest.kt` | 1,400 | Split staging/expiry, preview validation, revision merge, GPS/deletion conflicts, apply/rollback, and cleanup suites; move common fixtures to focused test support. |
| `lib/backup/backup_archive.dart` | 1,416 | Split schema/models, buffered codec/validation, streaming plan/encoder, and binary reader/writer/digest primitives while preserving archive compatibility. |
| `android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/TimerRepository.kt` | 1,244 | Separate timer/session, location/geofence, board/conversation, business/billing, quote/invoice, and backup collaborators. Keep the repository as serialized command coordinator and move schema lifecycle to a focused database component. |

On the current PR #67 branch, the documentation split reduces `app_plan.md`
to 28 lines and `plan_status.md` to 306. It is not merged, so main remains at
the six-file baseline above. No other tracked UTF-8 text file exceeded 1,000
lines in the main scan. PR #68 merged as
`85cda95718169f9b51fb568178f1eee571a7a61` and removes the task-move
confirmation; PR #69 merged the history-screen split.

## Current open backup PR snapshots

Checked against main `025cc10` on 2026-11-16. Both backup PR heads contain that
main commit (zero commits behind); these are branch snapshots, not merged-main
evidence.

- **PR #65**, current pushed head
  [`a2242b9`](https://github.com/centerionware/Clockrove/commit/a2242b99f03d403d79d62c96c20a435ff7bbcb93):
  Validate run
  [43807071868](https://github.com/centerionware/Clockrove/actions/runs/43807071868)
  failed during real Android PDF generation/rendering. The pushed archive split
  has all files under `lib/backup/` below 1,000 lines (largest:
  `backup_archive_codec.dart`, 906). The remaining over-limit files on this
  head are `BackupImportStore.kt` (1,958), `BackupImportStoreTest.kt` (1,410),
  `TimerRepository.kt` (1,244), `app_plan.md` (3,472), and `plan_status.md`
  (4,453). W3's later unpushed rewrite is not represented by this head or run
  and has no validation or line-count claim here.
- **PR #66**, current pushed head
  [`75290c5`](https://github.com/centerionware/Clockrove/commit/75290c5a4a588e2727f1ffc1281a7c925668266b):
  Validate run
  [43812503590](https://github.com/centerionware/Clockrove/actions/runs/43812503590)
  failed in native tests: 724 cases completed with five failures covering
  aggregate conflict, stale pre-purge, and three tombstone/deletion-choice
  cases. Its source/test splits bring `BackupImportStore.kt` to 854 lines,
  `BackupImportStoreRevisionMergeTest.kt` to 921, and `TimerRepository.kt` to
  932. The old importer test is split into focused files, each below 1,000.
  `app_plan.md` (3,447), `plan_status.md` (4,476), and
  `lib/backup/backup_archive.dart` (1,416) remain over the limit on this branch.
- PR #67's current docs branch keeps `app_plan.md` and `plan_status.md` below
  1,000 lines, but these changes are not on main. Keep PR #67 draft until the
  backup work is validated and its documentation conflicts are reconciled.

## Exact-head merge-conflict check

A no-worktree merge-tree check used main `025cc10`, PR #65 head `a2242b9`, and
PR #66 head `75290c5`, together with PR #67's current split. It found:

| Compared branches | Conflicting paths |
| --- | --- |
| PR #67 with PR #65 | `app_plan.md`, `plan_status.md` |
| PR #67 with PR #66 | `app_plan.md`, `plan_status.md` |
| PR #65 with PR #66 | `android/app/src/test/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStoreTest.kt`, `app_plan.md`, `lib/backup/backup_archive.dart`, `plan_status.md` |

These exact heads cannot be integrated as-is. Recheck the conflict set if any
head changes; a PR's mergeability against main does not establish conflict-free
combination with another open PR.

## Integration and validation evidence

- PR #70 exact head `58ca416dfe8c3b82fff8d58898db9ca7acc86a26` passed Validate
  run [43798809470, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43798809470/attempts/2),
  then merged to main as `025cc10f5aac80ce6f56005f694e448ee4473761`. Main
  Validate [43804094163](https://github.com/centerionware/Clockrove/actions/runs/43804094163)
  passed on that exact SHA. Play delivery
  [43806883748](https://github.com/centerionware/Clockrove/actions/runs/43806883748)
  accepted internal submission of `0.1.0-internal.53.1` / version code
  `216968203`; this does not establish review approval, tester availability,
  or closed-track readiness. See
  [invoice PDF action evidence](INVOICE_PDF_ACTION_VISIBILITY.md).
- PR #67's previous head `6f21fd4e109d7e2eb18baa5ede25b7b40e3bab94` failed
  run [43788953889](https://github.com/centerionware/Clockrove/actions/runs/43788953889)
  during the API-24 backup crypto benchmark: APK installation timed out on both
  attempts, so the benchmark never started. That result does not validate the
  refreshed docs branch. Keep the PR draft until its dependencies are
  reconciled; full Validate is intentionally deferred until then.

## Safe order

1. Finish and validate backup PRs #65 and #66, then deliberately reconcile their source and plan changes.
2. Keep every maintained file changed by integrated backup work at or below 1,000 lines; avoid splitting source files while their open PRs are actively changing them.
3. Refresh and validate #67 against the reconciled backup evidence, retaining the concise plan/status index and linked history.
4. Re-audit every maintained source, test, and documentation file after integration; split remaining over-limit files on their owning changes before those changes merge.
