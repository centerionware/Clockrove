# Maintained-file size audit and split plan

## Rule

Keep every maintained source, test, and documentation file at or below 1,000 lines. Split by cohesive feature or topic before extending an oversized file. Do not split a file while an open PR is actively changing it. Track existing exceptions and a conflict-safe order here.

## Main baseline and split boundaries

The line counts below were measured from verified main commit 025cc10f5aac80ce6f56005f694e448ee4473761 on 2026-11-16. The scan covered tracked text files and excludes binary and generated build output. Four maintained files exceed the limit on this main baseline:

| Maintained file on main | Lines | Safe split boundary |
| --- | ---: | --- |
| android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStore.kt | 1,958 | Split import staging/expiry, preview validation, conflict planning, revision merge, GPS deletion conflicts, transactional apply, and staged-file cleanup behind the existing store API. Keep apply on the serialized SQLite transaction boundary. |
| android/app/src/test/kotlin/io/github/centerionware/clockrove/tracking/BackupImportStoreTest.kt | 1,400 | Split staging/expiry, preview validation, revision merge, GPS/deletion conflicts, apply/rollback, and cleanup suites; move common fixtures to focused test support. |
| lib/backup/backup_archive.dart | 1,416 | Split schema/models, buffered codec/validation, streaming plan/encoder, and binary reader/writer/digest primitives while preserving archive compatibility. |
| android/app/src/main/kotlin/io/github/centerionware/clockrove/tracking/TimerRepository.kt | 1,244 | Separate timer/session, location/geofence, board/conversation, business/billing, quote/invoice, and backup collaborators. Keep the repository as serialized command coordinator and move schema lifecycle to a focused database component. |

The plan index and status index are each below 1,000 lines after this PR's split. PR #68 merged as 85cda95718169f9b51fb568178f1eee571a7a61 and removes the task-move confirmation; PR #69 merged the history-screen split. No other tracked maintained text file exceeded 1,000 lines in the main scan.

## Current open-PR snapshots

Exact heads and line counts were checked on 2026-11-16. PRs #65 and #66 are still based on eb07857ed24344c4d7cbe4bd3e04d9ff5a33b4a7, behind current main 025cc10f5aac80ce6f56005f694e448ee4473761.

- PR #65, head a9d38f4f6800e42f627a7d963b3d1cf01f7fd0fb: over-limit files are BackupImportStore.kt (1,958), BackupImportStoreTest.kt (1,410), TimerRepository.kt (1,244), app_plan.md (3,468), and plan_status.md (4,449). Its archive split is already present on that head. Exact-head Actions run [43802442172](https://github.com/centerionware/Clockrove/actions/runs/43802442172) failed in the real Android PDF generation/rendering stage; that step ended with timeout exit code 124.
- PR #66, head a6952bb7efa9d614bcaf35f764e9f2a73475bca8: over-limit files are app_plan.md (3,447), backup_archive.dart (1,416), and plan_status.md (4,459). BackupImportStore.kt is 906 lines and TimerRepository.kt is 932 lines. Exact-head Actions run [43803416725](https://github.com/centerionware/Clockrove/actions/runs/43803416725) failed Android Kotlin compilation in BackupImportPreviewPlanner.kt: List<String> was passed where Set<String> is required, and a nullable String where String is required.
- PR #67 is rebased onto current main 025cc10. Its plan and status documents are below 1,000 lines; the four source/test exceptions inherited from main are listed above. Keep it draft until refreshed exact-head Actions complete and the backup-branch documentation conflicts are resolved.
- PRs #68 and #69 are merged into main; their screen splits are part of the baseline.

## Integration and validation evidence

- Read-only merge-tree checks show that PR #67 conflicts with both #65 and #66 in app_plan.md and plan_status.md. PRs #65 and #66 also conflict with one another: #66 deletes the test file modified by #65, and their app_plan.md, backup_archive.dart, and plan_status.md changes conflict. Their GitHub mergeability against main does not prove a conflict-free combination.
- PR #70 exact head 58ca416dfe8c3b82fff8d58898db9ca7acc86a26 passed Validate run [43798809470, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43798809470/attempts/2), then merged to main as 025cc10f5aac80ce6f56005f694e448ee4473761. Merged-main Validate [43804094163](https://github.com/centerionware/Clockrove/actions/runs/43804094163) is active on that commit; Play delivery waits for its result. See [invoice PDF action evidence](INVOICE_PDF_ACTION_VISIBILITY.md).
- PR #65 and #66 are both behind current main. Integrate their source and plan changes deliberately, retaining the cohesive evidence split and validating the resulting exact head.
- PR #67's previous head 6f21fd4e109d7e2eb18baa5ede25b7b40e3bab94 failed run [43788953889](https://github.com/centerionware/Clockrove/actions/runs/43788953889) during the API-24 backup crypto benchmark: APK installation timed out on both attempts, so the benchmark never started. That result does not validate the rebased branch. Only a completed run on the refreshed exact head counts.

## Safe order

1. Finish and validate backup PRs #65 and #66, then deliberately reconcile their source and plan changes.
2. Keep every maintained file changed by integrated backup work at or below 1,000 lines; avoid splitting source files while their open PRs are actively changing them.
3. Refresh and validate #67 against the reconciled backup evidence, retaining the concise plan/status index and linked history.
4. Re-audit every maintained source, test, and documentation file after integration; split remaining over-limit files on their owning changes before those changes merge.
