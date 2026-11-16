# Maintained-file size audit and split plan

## Rule

Keep every maintained source, test, and documentation file at or below 1,000 lines. Split by cohesive feature or topic before extending an oversized file. Do not split a file while an open PR is actively changing it. Track current exceptions and a conflict-safe order here.

## Current main baseline at `d032f16`

Scanned verified main commit `d032f16da65b1a6a587bb99f8feddb2c30e9ac84` on 2026-11-16. The scan counted tracked UTF-8 text files and excluded binary and generated build output. Only the two unsplit plan files exceed 1,000 lines:

| Maintained file on main `d032f16` | Lines | Safe split boundary |
| --- | ---: | --- |
| `app_plan.md` | 3,513 | Move the product contract, milestone handoff, and evidence into the focused `docs/plan/` index and topic files. |
| `plan_status.md` | 4,532 | Keep a concise current-status index and move dated validation history into linked history files. |

PR #71's merged source split brought the previously oversized backup implementation and test files below the limit. Main now has `BackupImportStore.kt` at 862 lines and `TimerRepository.kt` at 932; `BackupImportStoreTest.kt` has been replaced by focused test files. The archive is split under `lib/backup/`. No other tracked UTF-8 text file exceeded 1,000 lines in the main scan.

PR #67 reduces `app_plan.md` to 28 lines and `plan_status.md` to 306 lines, and divides their content among focused contract, milestone, evidence, and history files. Its refreshed branch is based on `d032f16`; the exact-head Actions result is pending. All changed maintained documentation files remain under 1,000 lines.

## Backup integration state

- PR #65 merged as `b05c035579f099f4ecc7d8e8bce1d5e7d935c5a8`.
- PR #66 closed after its native import work was incorporated in PR #71.
- PR #71 merged as `d032f16da65b1a6a587bb99f8feddb2c30e9ac84`. Exact-head Validate run [43885189418, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43885189418/attempts/2) passed; the detailed restore evidence is in [validation history 06](status-history/validation-history-06.md).

These source integrations remove the earlier backup-code exceptions from the main baseline. M7.3 conflict and restore acceptance remains a separate follow-up; this audit does not mark it complete.

## PR #67 refresh and conflict status

PR #67 was rebased onto main `d032f16`. The initial plan-split commit conflicted in `app_plan.md` and `plan_status.md`; those conflicts were resolved by keeping the concise split indexes and preserving the existing detail in the split plan archive. The remaining commits replayed cleanly. The branch now has no base-branch conflict. A fresh exact-head Validate run is required before merge.

## Safe order

1. Complete exact-head Actions for refreshed PR #67, then merge the documentation split.
2. Re-audit every maintained source, test, and documentation file on merged main.
3. Continue M7.3 as a separate restore/conflict-resolution milestone; keep its native and UI changes on a focused follow-up and validate the combined exact head before merging.
