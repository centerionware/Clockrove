# M7.3 streamed backup restore UI

Status as of 2026-11-17: source and exact-head GitHub Actions validation are
green on PR #74. On-device SAF-provider acceptance and the remaining M7.3/M7.6
gates are still open.

## Scope

`codex/m73-restore-ui` adds the user-facing half of the streamed restore:

- Android SAF input staging (`BackupDocumentInputStore`) that copies only the
  bounded `CLKROVE2` envelope into private app storage and exposes an opaque
  handle with sequential 64 KiB reads.
- `BackupFileApi` plus `BackupRestoreImporter`, which feed that ciphertext into
  the existing two-pass authenticated preview without writing a plaintext file.
- A Settings route to `BackupRestoreScreen`: password entry, authenticated
  preview, bounded conflict pages, explicit choices, local-open-timer blocking,
  final confirmation, and native transactional apply.
- Flutter state-provider and installed-widget refresh after a changed restore
  commit, and an `Export encrypted backup` route beside restore.
- Native staging, Dart file API, restore screen, and Settings navigation
  regression tests, plus the Actions restore benchmark adapted to the
  encrypted-source interface.

## Rebase onto merged main

The branch was stacked on the pre-squash `codex/m72-streamed-restore` tip
(`def60fa`), whose content reached `main` as `d032f16` (#71) and was then
extended by #75. A plain rebase replayed the already-merged native restore work
and conflicted, and the old head no longer analyzed against the current preview
contract (`changedCount`, `deletedCount`, `unresolvedReferenceCount`,
`timeOverlapCount` were reported missing).

The PR's own change set (16 files) was reapplied on merged `main` at `f627efc`
and the PR base was retargeted from `codex/m72-streamed-restore` to `main`.
Conflict decisions:

- Kept `main`'s stricter conflict contract: an unresolved conflict carries no
  selection, and a resolved one must select an allowed choice. Added coverage
  for invalid defaults, invalid selections, and unresolved selections.
- Kept the split plan documents rather than the branch's monolithic
  `app_plan.md`/`plan_status.md`, and recorded this work here.
- Retained the branch's Flutter 3.47 semantics fix (`SemanticsData.isChecked` is
  gone; assert through `flagsCollection`).
- Removed two redundant `dart:typed_data` imports and converted two preview
  runners from closure variables to function declarations because Analyze runs
  with fatal infos.

## Exact-head validation

Runs before the green head are retained because each exposed a real defect:

| Run | Head | Result |
| --- | --- | --- |
| [44024714534](https://github.com/centerionware/Clockrove/actions/runs/44024714534) | `e69b393` | Analyze failed: removed `SemanticsData.isChecked` getter in the restore choice test |
| [44025086491](https://github.com/centerionware/Clockrove/actions/runs/44025086491) | `252fe85` | Analyze failed on fatal infos: redundant `dart:typed_data` imports and closure-variable runners |
| [44025772975](https://github.com/centerionware/Clockrove/actions/runs/44025772975) | `ea641bb` | 282 passed, 4 failed, 14 skipped: `ensureVisible` on lazily built restore content |
| [44026546506](https://github.com/centerionware/Clockrove/actions/runs/44026546506) | `a044497` | 282 passed, 4 failed, 14 skipped: one target needed scrolling back to the top, an ambiguous dialog label, an undisposed semantics handle, and a leaked `closeBackupImport` from the prior test |
| [44027104394](https://github.com/centerionware/Clockrove/actions/runs/44027104394) | `7a0d228` | 285 passed, 1 failed, 14 skipped: stale-preview notice asserts needed scrolling |
| [44027556847](https://github.com/centerionware/Clockrove/actions/runs/44027556847) | `57e0606` | 285 passed, 1 failed, 14 skipped: refresh notice below the preview card |
| [44027991208](https://github.com/centerionware/Clockrove/actions/runs/44027991208) | `e8dcf8f` | **Passed** |

Green run `44027991208` on `e8dcf8f`:

- Formatting, locked dependency resolution, Analyze, and Android/release builds
  passed.
- Flutter behavior: 285 passed, 14 skipped.
- Native JUnit artifact `native-test-results`: 823 tests, 0 failures, 0 errors,
  0 skipped.
- Device artifact `android-device-test-results`: 38 actual Android tests on each
  of API 24 and API 35, 0 failures, 0 errors, 0 skipped.
- Real Android PDF generation/rendering and invoice-viewer checks passed on both
  APIs, along with the bounded install-recovery and locked release-tooling
  checks.

No build, test, formatter, or analyzer ran on the development VM.

## Remaining M7.3 gaps

- The native preview API returns aggregate counts and conflict pages, so the
  screen cannot itemize individual additions, unchanged records, and deletions.
- Preview requires explicit omission for imported open sessions. The product
  contract permits omission, and the restore flow offers no close-time editor.
- The Android system document picker acceptance run is pending. Cancellation
  cannot interrupt an in-flight method-channel read.
- Data-changing restore acceptance and M7.6 remain open. The near-limit
  preview and replay results follow below.

### Near-limit restore preview and apply

The merged PR #74 head ran the near-limit preview on API 24 and 35 in
[Actions 44031580892](https://github.com/centerionware/Clockrove/actions/runs/44031580892).
Both used a 67,087,784-byte archive with 16 synthetic media files. Peak RSS
increases were 20,252 KiB and 21,376 KiB, respectively, below the 64 MiB
preview budget. The benchmark uses an encrypted private file and native
preview staging. It does not exercise the Android system document picker.

The follow-up extends the same device journey through native apply. It checks
that replay inserts zero rows, preserves the state version and attachment
identities and sizes, and retires the preview handle. A data-changing restore via the system
picker remains open.

Actions [45013742618](https://github.com/centerionware/Clockrove/actions/runs/45013742618)
passed on `d87f5a7`, the first commit that calls native apply in the near-limit
benchmark. API 24 and 35 authenticated and previewed 67,087,784 archive bytes,
then applied the same data with zero new rows and no state-version change.
Both preserved attachment IDs and sizes and retired the preview handle.
Preview-only RSS increases were 34,496 and 20,580 KiB, respectively. The
benchmark stops RSS sampling before apply. This evidence does not cover the
later system-picker journey or a data-changing merge.

### Android system document picker

[Actions 45018958919](https://github.com/centerionware/Clockrove/actions/runs/45018958919)
passed the API 24 near-limit replay and exported the encrypted synthetic
fixture. The device workflow stopped before the picker test because Flutter
placed the fixture under app-private `code_cache` and the transfer script
expected `cache`. The follow-up on `ed39e48` accepts either app-private
location and transfers only the named test fixture to the disposable
emulator's Downloads directory. [Actions 45021006125](https://github.com/centerionware/Clockrove/actions/runs/45021006125)
is the first run of that correction. Keep the picker journey open until
its results confirm cancellation, authentication, conflict resolution, and
committed state from a data-changing merge on both API levels.

Run `45021006125` transferred the 8,438-byte fixture and reached API 24
instrumentation. The screenshot shows the running app and its overflow
menu, but Android did not expose the tooltip as a discoverable menu label.
The follow-up gives the menu icon an explicit accessibility label. The picker
and merge assertions were not reached.

[Actions 45022991467](https://github.com/centerionware/Clockrove/actions/runs/45022991467)
reached Restore encrypted backup on API 24. Its screenshot shows the Android
resolver presenting both Documents and the task-photo test fixture. The
follow-up disables that fixture activity on the disposable emulator before
starting the backup picker test. The system picker and merge are still pending.

[Actions 45026504990](https://github.com/centerionware/Clockrove/actions/runs/45026504990)
confirmed that the shell cannot disable the fixture activity by component.
The test now handles the visible Android resolver and explicitly selects
Documents when necessary. It still requires the system picker to open.

[Actions 45028456600](https://github.com/centerionware/Clockrove/actions/runs/45028456600)
reached Documents on API 24, then Android rejected Back from the app-scoped
instrumentation API because Documents belongs to another package. The test
now sends Back through the same UI automation channel used by the other
Android device tests. Cancellation and the committed restore remain pending.

[Actions 45030155865](https://github.com/centerionware/Clockrove/actions/runs/45030155865)
opened Documents but API 24 did not accept raw key-event injection through
its test automation connection. The follow-up sends the standard shell Back
action on the disposable emulator and still waits for Clockrove’s visible
cancellation state before testing an import.

[Actions 45032596924](https://github.com/centerionware/Clockrove/actions/runs/45032596924)
reached the visible cancellation state and reopened the picker. On the second
launch, Android remembered Documents as the last choice and showed a different
resolver layout. The follow-up recognizes both layouts and continues with the
system Documents picker. Authentication and apply remain unverified.

[Actions 45034436817, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/45034436817/attempts/2)
reopened Documents and reached its roots without a resolver failure. The API
24 Downloads provider did not expose the fixture copied by ADB. The test
now navigates to the actual shared-storage Download directory via Documents,
which lists files directly. The restore has yet to apply on device.

[Actions 45037826639](https://github.com/centerionware/Clockrove/actions/runs/45037826639)
showed that Back exits API 24 Documents from its roots drawer. The test now
closes the drawer through the visible dimmed pane before opening the storage
options. Back is still exercised separately as a deliberate cancellation.
