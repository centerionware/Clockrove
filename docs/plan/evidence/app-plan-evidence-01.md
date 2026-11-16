# Implementation evidence from app_plan.md — part 1

_Source: `app_plan.md §12`. Entries: 2026-11-11 — milestone 1 foundation in progress through Owner-directed job lifecycle (implementation in progress)_

## 12. Progress and evidence ledger

| Deliverable | Status | Evidence / next action |
| --- | --- | --- |
| Product plan | Written | This file captures decisions, milestones, defaults and external gates |
| Public agent instructions | Written | `AGENTS.md` |
| Local skills | Written; basic checks passed | All three passed direct frontmatter/name/description/body checks. Bundled skill validator could not run because Python `yaml` is unavailable; rerun when PyYAML is installed |
| Ignore rules | Validated | Isolated temporary Git metadata: 12 private paths correctly ignored; 8 public/build-input paths correctly allowed. Workspace `.git` was not changed |
| GitHub repository | Identity verified; not created | centerionware via user 1000; intended repository absent; publication awaits terms review |
| Source/contribution license | Drafted; review pending | docs/SOURCE_TERMS_DRAFT.md and CONTRIBUTING.md; owner review requested before publication |
| Toolchain/app scaffold | In progress | Flutter/Dart run; lockfile and analysis pass; JDK configured; Android build running |
| Milestones 2–8 | In progress; see `plan_status.md` | PR #45 exact head `942659a` / Actions run `43626467570` passed formatting, analysis, Flutter behavior tests, Android debug build, native tests, PDF generation/viewer checks, locked release tooling and the complete API 24/35 emulator suite. XML confirms 37 actual Android tests per API with zero failures/errors/skips, including the deterministic billing settings flow, invoice copies, payments, quotes and task media. The quote Manage customers add/save/return/select test passed; the 49-sample route and map settings ran in the same device matrix. PR #45 was squash-merged to `main` as `69e1dab`. Post-merge validation `43628166015` exposed a test-harness scroll issue on API 35 while revealing **View original PDF**; screenshot/accessibility evidence shows the swipe began under the Android navigation bar. The test helper now clamps swipes above system bars, falls back to a gesture if accessibility scrolling does not reveal the requested control, taps a field if a semantic click does not focus it, and retries through select-all/IME typing if API 24 reports `ACTION_SET_TEXT` success without changing text. On exact PR #46 head `ae4bc08`, run `43634037678` passed the API-24 billing journey, but API 35 timed out revealing the invalid job-tax message after save. Its screenshot still showed Job settings at the bottom with the Save button, without the inline error; the device test is being changed to tap visible controls by screen bounds because accessibility `ACTION_CLICK` can acknowledge without dispatching a Flutter pointer event. The next exact head `c9f8b6e` / run `43636374365` passed Flutter tests, build and native tests but exposed API-24 accessibility nodes that report an actionable Flutter button as `clickable=false`; the test now taps visible matched-node bounds regardless of that flag. At exact PR #46 head `274ce49` / Actions run `43640563667`, formatting, analysis, Flutter behavior tests, Android build, locked release checks and all 614 native tests passed. Downloaded XML confirms 37 actual Android tests on each API 24 and 35 with zero failures, errors or skips; both extended billing journeys, invoices, payments, quotes, task media and map/widget journeys passed. The device test first applies `ACTION_SET_TEXT` and only accepts an exact readback, retaining the IME fallback. PR #46 was squash-merged as `66e063a`; merged-main validation and Play delivery details are recorded in `plan_status.md`. Prior accessibility and API-24 emulator-install failures are detailed in `plan_status.md`. The internal Play delivery workflow for `66e063a` was skipped after merged-main validation failed; no upload is claimed. M6.6 source and PR-head CI evidence are complete; a CI-only follow-up guards device taps from system navigation, restores global Settings to a known top position, and waits through IME transitions before typing. Its exact-head Actions run must pass before fresh main validation. Physical owner-device acceptance remains. Keyless OpenStreetMap is default and authenticated custom HTTPS tiles use Android Keystore. Google Maps is deferred beyond launch by owner decision dated 2026-11-15; its runtime-key feasibility is no longer a launch gate. M7 backup and the M7.6 full-app UI/E2E gate remain before closed testing. |
| M7.1 crypto/KDF benchmark | 1 MiB benchmark and full-device PR #52 validation passed; 64 MiB crypto-only memory measured too high for enablement | PR #52 merged to `main` as `cc6870931920016e7ee9bc4f5fc4be6e96b26523`. Exact-head run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557) on `defb4e6` passed formatting, analysis, Flutter behavior tests, Android/instrumentation builds, 635 native tests, the API 24/35 device matrix, invoice-viewer and release-tooling checks; each API ran 38 actual Android tests with zero failures, errors or skips. The 1 MiB benchmark measured 755/301 ms encrypt/decrypt on API 24 and 869/273 ms on API 35; each envelope was 1,048,648 bytes. PR #59's 64 MiB crypto-only benchmark [43700311041](https://github.com/centerionware/Clockrove/actions/runs/43700311041), head `02931e5`, emitted a 67,108,936-byte envelope and measured API 24 encrypt/decrypt 12,593/12,134 ms, baseline VmHWM 274,336 KiB, peak 823,572 KiB (delta 549,236 KiB); API 35 measured 13,746/12,211 ms, baseline 409,484 KiB, peak 951,840 KiB (delta 542,356 KiB). These are crypto-only process peaks; complete export adds archive/media/ciphertext copies. Keep the Settings route withheld pending streaming or a materially lower measured memory bound. |
| M7.2 native snapshot/export boundary, encrypted codec, preview and user-directed export | PR #60 and PR #61 exact-head plus merged-main validation passed; user-facing Settings export remains withheld pending full-path memory evidence; transactional restore, M7.3 and M7.6 remain outstanding | PR #51 run [43678616256](https://github.com/centerionware/Clockrove/actions/runs/43678616256) validated the schema-16 snapshot boundary; PR #52 merged the deterministic bounded archive/crypto codec and passed run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557). Standalone SAF bridge head `5978bfb` passed run [43681226462](https://github.com/centerionware/Clockrove/actions/runs/43681226462). PR #60 run [43702626041](https://github.com/centerionware/Clockrove/actions/runs/43702626041) reported 223 passed, 5 failed and 14 skipped; run [43705585212](https://github.com/centerionware/Clockrove/actions/runs/43705585212) reported 224 passed, 4 failed and 14 skipped because of an ambiguous test scroll finder. Exact head `a6ba266` / run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) passed format, analysis, Flutter behavior, Android build, instrumentation assembly and recovery synthetics, then failed 16/675 native tests: the local-only `invoice_time_claims` table was queried from the staging DB and the exception was mislabeled `INVALID_REVISION_GRAPH`. Commit `f9b223f`, rebased onto PR #58 merge `fefdcdf`, checks incoming claims on `localDb` and adds an empty-preview regression; the existing local-overlap test exercises a real local claim. PR #60 rebased head `ead1a8518fda0f3f4d65da0ab7f7ed4ce1f3b158` / Actions run [43708093424](https://github.com/centerionware/Clockrove/actions/runs/43708093424) passed formatting, analysis, Flutter behavior (228 passed, 14 skipped), Android build, release Java compilation, instrumentation assembly, install-recovery synthetics, and the complete API 24/35 workflow. Native JUnit totaled 676 tests with zero failures, errors or skips, including two `NativeVisualPreviewTest` cases. The earlier run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) also included those two cases in its 675-test JUnit total; the new aggregate is not a count that excludes them. API 24 and API 35 each passed 38 Android tests with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt 878/280 ms on API 24 and 877/287 ms on API 35; each envelope was 1,048,648 bytes. Invoice viewer, artifact export, and locked release tooling passed. Commit `9dc1542` makes expiry rescheduling fail closed: it detaches the active snapshot, wipes the key, attempts artifact deletion and retries failed deletions on the next store interaction. Regression coverage injects a later scheduler failure and a deferred deletion failure. Exact-head run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843) passed on SHA `9dc154288111af1219166d5eaeeea8661dfee1aa`: formatting, analysis, Flutter behavior, Android build/release compile, instrumentation assembly, bounded install-recovery synthetic, 677 native tests (zero failures/errors/skips, including two `NativeVisualPreviewTest` cases), 38 actual Android tests each on API 24 and 35 (zero failures/errors/skips), invoice-viewer validation, artifact exports and locked release tooling. API 24 benchmark attempt 1 timed out before test start during APK install; recovery confirmed a changed emulator boot ID and boot completion, then the single retry passed. The 1 MiB benchmark measured encrypt/decrypt 742/273 ms on API 24 and 951/292 ms on API 35; both envelopes were 1,048,648 bytes. PR #58 exact head passed [43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294) and merged as `fefdcdf`; Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644) passed on `fefdcdf` with 38 actual Android tests per API 24/35 and zero failures/errors/skips. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) completed successfully on exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; the internal AAB upload/submission was accepted at 11:30:34 UTC. This proves upload/submission only, not review approval or tester availability. Native fixes also gate readiness on local timers, validate GPS revision privacy and clean up SAF staging after expiry reschedule failure. No local build/test/formatter/analyzer ran. Keep Settings export withheld after the high crypto-only peak memory measurement; full exporter peak can be higher. Transactional restore, conflict choices and full M7.3/M7.6 acceptance remain outstanding. PR #61 pre-rebase head `a84dba0a2473e443be8c057a8c16b01219b141c4` / Actions run [43714608406](https://github.com/centerionware/Clockrove/actions/runs/43714608406) passed before rebasing onto PR #60. It passed formatting, analysis, Flutter behavior, Android/release builds, instrumentation assembly, 635 native tests, invoice-viewer/artifact/release tooling, and 38 actual Android tests each on API 24 and 35 with zero failures, errors or skips. The 64 MiB v2 codec gate passed: API 24 encrypt/decrypt 10,565/9,219 ms, codec peak RSS delta 18,564 KiB; API 35 9,473/8,777 ms, delta 21,948 KiB. Both produced a 67,108,864-byte archive and 67,134,601-byte envelope using 19 MiB / 2-iteration / parallelism-1 KDF settings. Independent post-codec verification raised RSS deltas to 37,916 KiB and 58,056 KiB respectively; this is logged separately from the codec gate. The rebased head `391984d962587b8ad275b824fc361286ff91f37d` includes current main `7a70658` and PR #60 export work but has not yet been validated; the old-head run is not evidence for the rebased tree. Exact-head run [43717579441](https://github.com/centerionware/Clockrove/actions/runs/43717579441) was canceled after cross-review found that cancellation during the final manifest sink write could still reach commit. Before cancellation it passed formatting, analysis, Flutter behavior, Android build, release Java compilation, instrumentation assembly, and native unit tests; real-device validation did not complete. The pre-commit manifest-write regression remains. An operation-scoped cancellation token now races pending encrypt/decrypt reads, cancels the upstream iterator before sink discard, and has deterministic partial-encrypt and post-manifest EOF-decrypt stall regressions; final exact-head validation is pending. Intermediate head `9cb3d9f` / Actions run [43718937946](https://github.com/centerionware/Clockrove/actions/runs/43718937946) was canceled before tests after review found that a polled bool could not interrupt `StreamIterator.moveNext()`. It is an obsolete intermediate head and provides no pass, failure, or validation evidence. Exact-head Actions run [43721230239](https://github.com/centerionware/Clockrove/actions/runs/43721230239) on `3eca0a95` failed at `Require clean formatting`. Workflow artifact `formatter-source-patch` (artifact ID `14164302366`) contained the exact formatter correction applied to the formatted successor. Analyze, behavior, build, native and device stages were skipped; this run provides no test result. Exact-head Actions run [43721683574](https://github.com/centerionware/Clockrove/actions/runs/43721683574) on `a1683505ee519403f54953a8705bc1fd2fdfd9b5` passed formatting, then Analyze failed with nine `undefined_function` errors for `Completer` and `StreamController` in `test/backup_crypto_v2_test.dart` because `dart:async` was not imported. Flutter behavior, builds, native tests, and device stages were skipped, so this is not a behavior or device test result. The import correction is in the successor; it still needs fresh exact-head Actions. |
| iOS/server | Deferred | Preserve contracts; do not implement during Android launch work |

For each implemented milestone append: date, commit/build reference, changed behavior, commands/checks run, results, device details where applicable, remaining blocker and next action. Never use this ledger to imply implementation or testing that has not happened.

### 2026-11-11 — milestone 1 foundation in progress

No commit/build reference yet. User 1000 authenticates via gh as centerionware;
intended repository does not exist. No remote created or public files pushed.
Protected root .git preserved; scripts/git-local uses ignored alternate metadata.
Source terms drafted for owner review in docs/SOURCE_TERMS_DRAFT.md, with
contribution rules, README, architecture and dependency notes. Publication remains
gated on concrete terms review.

Verified Flutter 3.47.9 archive checksum; host is ARM64, so verified matching Dart
3.13.7 ARM64 archive and rebuilt Flutter CLI. Version command succeeds. Generated
Android scaffold uses io.github.centerionware.clockrove and Clockrove display name.
Dependency resolution produced pubspec.lock; dart format checked two files and
flutter analyze reported no issues. No timer or native behavior implemented yet.
OS backup disabled and release debug-signing fallback removed.

Android build attempted and failed: no JDK/JAVA_HOME. Doctor located an external
Android SDK (API/build-tools 36); device discovery failed on sandbox adb socket
permissions. No Android build, device test, CI, upload or publication passed.
Next: pinned JDK setup, Android tool compatibility/build verification, lock build
inputs and nonprivileged CI. Full milestone/task status is in plan_status.md.

### 2026-11-11 — JDK and nonprivileged CI continuation

Found and copied existing ARM64 Temurin 17.0.20.1+1 JDK into ignored local tools;
version command succeeds. Gradle 9.3.1 wrapper jar matches upstream checksum and
its distribution checksum is pinned. Minimum API 24 / compile and target 36,
build tools 36.0.0 and NDK 28.2.13676358 are explicit. Validation-only workflow
uses immutable action commits and pinned tool versions; no store credentials or
binary uploads. GitHub workflow execution still pending publication/repository.
Locked offline pub resolution, format check, and analysis pass. No substantive
application tests exist yet. JDK-configured Android build is live in session
15110; completion unproven. Concrete license review requested from owner.
See plan_status.md for detailed validation and the next action after build output.

JDK-configured build ended with an x64 AAPT2 execution failure after 238.4s.
Validated existing ARM64 SDK AAPT2 and configured an ignored local override;
retry session 56789 is running. Added .kotlin/ generated-state ignore. Android
build success remains unproven until that process completes.

### Owner constraint — GitHub-hosted validation only

Owner explicitly prohibits building/testing on this VM. All future builds and
tests, including formatting/analysis validation, run via GitHub Actions. Local
edits and read-only inspection continue. This overrides earlier local-build setup
instructions and local-build portions of milestone acceptance; clean GitHub CI
must prove build reproducibility. Local retry session 56789 was cancelled and
closed with no APK success evidence; do not count it as passed. Intended repository
may remain private for CI until concrete source terms review permits publication.

Private staging repository created and verified at https://github.com/centerionware/Clockrove.
Reviewed 40 staged public-safe source paths and pushed foundation commit
be442a3c417b8dc163e3845c24e4998894679b98. GitHub validation run 42980408630 is
in progress. Public visibility remains gated on owner terms review. No build/test
will be run on the VM following the owner's instruction.

First Actions run failed in setup-java before build/test because 17.0.20.1+1
has unsupported SemVer notation. CI corrected to exact verified x64 Temurin
17.0.17+10. No local validation used for the correction.

### 2026-11-11 — Actions SDK setup correction

Run 42980514022 passed JDK/verified Flutter setup, then failed with sdkmanager
not found (127). Workflow now downloads checksum-pinned command-line tools 19.0
(build 13114758), installs the selected Android packages in a job-local SDK root,
and exports that root. No VM build/test executed. Task details in plan_status.md.

### 2026-11-11 — foundation checks passed; compilation live

Same run 42980838050 passed JDK/Flutter/Android SDK setup, enforced lockfile,
formatting and analysis. No application behavior tests exist yet; the conditional
test step is not proof of native behavior. Debug APK compilation started at
07:39:33 UTC and is still in progress. Native unit task and job conclusion pending.
No VM validation ran. Public terms review remains pending independently.

### 2026-11-11 — first successful Actions build and native repository slice

Run 42980838050 on d6dbe94 concluded success. Logs show debug APK built and
native test task NO-SOURCE. Foundation checks/build are proven; native behavior
was not tested by that run. Public license/visibility gate remains pending.

Started independent milestone 2 implementation: native SQLite timer repository
and five Robolectric tests; serialized transactions, persisted retry outcomes,
version conflicts, UTC context, one-open-session index and atomic switch.
Automation rejects commands until policy is implemented. Test/build validation
for this code is pending GitHub Actions. No UI/widget/notification integration,
location/map experiment or physical-device success is claimed. Full migration
and revision schema remains milestone 3 work. No VM build/test was executed.

### 2026-11-11 — native widget action implementation

Added prototype AppWidgetProvider, layout/resources and receiver declaration.
It reads the native repository, displays actual running job and timestamp-based
elapsed time, and submits deduplicated version-checked clock-out commands without
Flutter. Native surface commands persist the first tap timestamp/timezone and
reuse outcomes for retries. Sixth repository test covers retry/stale-tap safety.
Idle widget opens the app; configuration, typed Flutter controls, complete refresh
propagation, ongoing location notification/service, maps and physical validation
remain incomplete. No VM validation ran; code awaits Actions and runtime evidence.

### 2026-11-11 — explicit public publication authorization

Owner instructed “make it public now.” gh changed the intended repository to
PUBLIC and verified isPrivate=false at https://github.com/centerionware/Clockrove.
This overrides the earlier publication wait. Draft terms remain draft; no license
adoption is inferred from the visibility instruction. No build/test ran on VM.

### 2026-11-11 — typed prototype bridge

Added explicit Dart/Kotlin timer adapters with background native handling,
engine lifecycle registration, eligible-job snapshots and widget refresh after app
commands. Raw transport values stay inside adapters. Added three codec/retry tests
for Actions; mocks do not prove device/native delivery. Full UI, event stream,
notification/service/location/maps and physical feasibility still incomplete.
Public repository state is reflected in README/status; draft terms remain draft.
No VM builds/tests executed. See docs/TIMER_CONTRACT.md for contract details.

### Owner-directed initial Play setup artifact

Owner explicitly requests a signed first-upload AAB before full app completion,
using Actions only. This is a setup/testing bundle, not the paid production launch.
Owner accepts public artifact availability and one-day minimum retention, authorizes
new upload-key generation and repository-secret persistence, and requires key
reuse for all future CI uploads. Prepared initial-play-bundle workflow pinned to
validated foundation d6dbe94 / run 42980838050, versionCode 1. Private recovery is
CMS-encrypted; keys/passwords must never be plaintext artifacts or Git content.
SERVICE_ACCOUNT_JSON exists; no Play API upload is performed for this request.
No signing/build result is claimed until Actions finishes. See
 docs/INITIAL_PLAY_UPLOAD.md and plan_status.md for the operation and key lifecycle.

### 2026-11-11 — initial setup AAB build and key persistence verified

One-time Actions run 42984008259 succeeded: validated foundation source d6dbe94,
version 0.1.0+1, RSA-4096 upload signing and strict jarsigner verification. Signed
artifact 13839295891 expires 2026-11-12T08:08:17Z (owner accepted one-day public
artifact). CI-generated upload keystore/passwords saved in UPLOAD_KEYSTORE_BASE64,
UPLOAD_STORE_PASSWORD, UPLOAD_KEY_ALIAS and UPLOAD_KEY_PASSWORD repository secrets.
Temporary encrypted recovery artifact and bootstrap recipient secret deleted;
remaining artifact includes AAB and public upload certificate only. Same permanent
key must be used for future CI. No Play upload or finished-app release claimed.

### 2026-11-11 — native committed-version propagation

Added repository subscriptions, version replay on reconnect and event-channel
invalidation hints. Events publish only after committed changes; failures/retries
produce no false changes. Engine cleanup cancels subscriptions/queued delivery.
Seventh repository test covers commit/replay/unsubscribe semantics. Corrected long
Dart test descriptions flagged by Actions; formatter failures now print source
diffs in CI for read-only diagnosis. No VM format/test/build executed. Validation
for current prototype remains pending; no runtime/widget/device proof claimed.

Current run 42985050236 on fa360ff passes formatting/analysis/Dart tests; Android
compilation remains live. Preserve that run until terminal. API-24 compatibility
correction and minimum-API native tap test are prepared locally after source
review found undesugared java.time usage. These pending edits are unvalidated.

### Owner handoff to automatic internal and tagged production delivery

Owner confirms initial Play upload and authorizes each validated main push to
internal testing. Version tags select production versionName and trigger release
once eligibility/app readiness gates are satisfied. CI must allocate strictly
increasing versionCodes for both paths with no manual bump, serialize uploads,
and reuse the saved key. Owner confirmation is not API upload verification.

Native run 42985050236 failed Kotlin compilation on three mixed SQL argument
arrays; explicit Any? arrays prepared, plus API-24 capture fix/eighth native test.
Dart checks passed. Preparing pinned Ruby/Fastlane lockfile via Actions before
publishing workflow activation. No VM validation or Play upload attempted.

### Automatic main/internal and version-tag/production implementation

Locked release tools resolved by successful Actions run 42986036667. Added Play
delivery workflow, Fastlane lanes and automatic version policy tests. Main builds
publish only after successful validation of exact main SHA; vX.Y.Z pins production
versionName without pubspec editing and requires tested main ancestry/readiness.
Store-wide code inspection plus time floor allocates increasing unique codes
under a shared publishing queue, including reruns. Main validation queues instead
of cancelling pushes. Production gate is one-time readiness, not per-release
approval. Saved upload key is reused; credentials cleaned and no public binary
artifacts in regular delivery. No actual internal/production upload proven yet.
See docs/PLAY_DELIVERY.md and plan_status.md for operational status.

Native run 42986013201 built debug APK but failed host-test task graph validation:
AGP packaging lacked dependency on copyFlutterAssetsDebug. Added explicit producer
dependency for variant host-test packaging. No test execution success or automatic
internal upload is claimed. Subsequent validation must pass before Play delivery.

### 2026-11-11 — prototype manual controls on feature branch

Actions-resolved Riverpod 3.0.3 lockfile applied. Added prototype Flutter controls
for native job creation/start/stop/switch, New job last, resume refresh and explicit
location-unavailable state. Riverpod consumes native version invalidations and
snapshots; uncertain commands retain IDs and block competing app intents pending
retry. Added three presentation tests; physical/native/UI runtime proof pending.
Branch remains separate from main until validated; no VM builds/tests/formatting.

Actions dependency preparation 42987027150 on e52b1fd concluded success, including
Android compilation, native test task, version-policy tests and Fastfile syntax.
This validates the pre-controls branch's native repository tests and build only.
New UI/controller commit 787916b is isolated in draft PR #1, validation 42987698700;
its behavior is not covered by the earlier successful run. Main compilation and
first automatic internal delivery remain in progress/unproven respectively.

Main run 42986727241 passed all checks and automatically triggered Play delivery
42988068348 (live; upload acceptance pending). PR #1 formatting failed in run
42987698700; applied reviewed Actions-generated source patch and added prototype
screen journey test. No VM formatter/test/build executed. New UI remains unmerged
and unvalidated until the next PR run passes.

Automatic delivery run 42988068348 failed before allocation/upload: Google Play
begin_edit rejected caller permission. Owner input needed for service-account app
visibility/testing-release access in Play Console. No upload success claimed;
avoid repeated unchanged permission attempts and continue app implementation.

Owner reports Play service-account invitation and asks to retry. Failed delivery
job in run 42988068348 was re-run after that change; result pending. Same upload
key/source and automatic code allocator apply. No upload acceptance claimed yet.

PR #1 passed complete Actions validation 42988271415 and was merged at 6713ddf.
Prototype controls are now on main; merged-main validation 42989132915 is live.
Play retry 42988068348 attempt 2 successfully read history/allocated a code after
owner invitation and is building the signed AAB; no upload acceptance yet.

First automatic internal delivery succeeded in run 42988068348 attempt 2.
Play accepted versionCode 216549708 at 08:45:37 UTC after signed AAB build and
verification with the permanent upload key. This proves upload/submission only;
no tester installation/review/production availability claimed. New manual-controls
merge 6713ddf is still in main validation 42989132915 for its subsequent delivery.

### 2026-11-11 — native measured-location storage and migration slice

Feature branch native-location-prototype adds schema-2 measured point storage,
active-session binding, sample ID integrity, coordinate/accuracy/time validation,
and transactional post-end pruning on timer stop/switch. Actual previous schema
captured as fixed synthetic migration fixture. Four Robolectric tests cover
validation/dedup, late/off-clock/pruned samples, preserved upgrade and failed
migration rollback. These storage changes are unvalidated until Actions runs;
no fused callbacks, foreground notification, device GPS or map success claimed.
All builds/tests remain on GitHub, never the VM.

### Foreground location prototype source

Added pinned fused-location dependency 21.3.0, declared location FGS type/permissions,
service callback/session binding, target cadence, notification with independent
native clock-out receiver, cleanup and stable measured-point mapper/test. Startup
permission/UI adapter is still pending; Flutter continues to show tracking
unavailable. These additions are source-only until Actions and real device checks
prove them. No synthetic GPS or guaranteed ten-minute delivery is claimed.

Storage PR #2 passed complete Actions run 42990150091 and merged at d6e4d9c.
Foreground service source is isolated on foreground-location-prototype for separate
validation; no runtime/device proof yet. Automatic manual-controls delivery
42989964414 accepted versionCode 216550241; this is not installation evidence.

### 2026-11-11 — explicit location startup and degraded state

Service PR #3 passed Actions 42991221956 and merged at a99faa9. Permission/start
follow-up is isolated on location-permission-flow: visible activity request,
fine/coarse and separate notification permissions, typed status and service-health
invalidation, purpose dialog before native calls and independent location stop.
Denied/restricted states preserve manual timing. New transport/permission-purpose
UI checks are pending Actions; no physical callback/FGS success is claimed.
No VM tests/builds/formatter ran. Geofence/map/recovery feasibility remains open.

PR #4 run 42992333516 failed formatting; applied reviewed Actions-generated patch.
Added native permission lifecycle tests and corrected concurrent enable/cancel
handling and asynchronous storage-error completion. New code remains unvalidated
until the next PR check; native test shadows are not physical-device evidence.
No local formatter/build/test executed.

### Owner-directed clean modern UI from prototype onward

Owner explicitly requests clean modern UI now. Added isolated modern-timer-ui
branch with consistent light/dark Material 3 theme, restrained forest/neutral
palette, clear active timer hierarchy, timestamp-derived tabular elapsed display,
readable content width and focused job/location controls. Only implemented actions
are shown. Added time-display/DST/long-session tests, large-text check and CI-only
synthetic rendered previews for visual inspection; no rendering success claimed.
Permission PR #4 passed Actions 42993338165 and merged at 0262d86. New visual work
requires separate CI and preview inspection. No VM builds/tests/formatting.

PR #5 run 42994742872 failed formatting before preview rendering. Applied reviewed
Actions-generated source patch and guarded display refresh against initial
background lifecycle state. No VM tests/build/format executed. New UI still needs
CI success and actual light/dark preview inspection; no visual success claimed.

UI run 42995204970 passed formatting, then analysis flagged two unnecessary imports.
Removed the flagged imports without weakening analysis. UI preview rendering is
still pending and visual quality has not yet been verified from rendered output.

UI run 42995708218 passed analysis and ten tests, including large text, but failed
journey scroll/tap frame timing and preview package-URI lookup unsupported by
Flutter tester. Corrected harness to settle scroll frames and load fonts through
explicit pinned FLUTTER_ROOT. Kept all assertions/preview generation enabled.
No rendering success or visual approval claimed; no VM validation executed.

First modern UI preview artifact 13843514422 from run 42996201315 was downloaded
and both themes visually inspected; hierarchy/contrast/spacing are sound. Fixed
preview debug ribbon and added scrolled location captures. Also added atomic
tracking-observation status with actual latest measured time/accuracy, choosing
measurement order over callback order; native/codec tests added. Changes need new
CI/visual review. No VM build/render/test ran and no physical GPS evidence claimed.

Modern design PR #5 passed complete Actions 42996201315, both preview themes were
visually inspected, and exact head dadb445 merged at 9e32c90. This validates the
prototype UI/layout, not launch/device readiness. Latest measured-status and
clean debug/scrolled preview changes are isolated on measured-tracking-status.
Reusable owner UI direction recorded in docs/UI_DESIGN.md for later features.

PR #6 run 42997495311 failed formatting; applied reviewed CI-generated source patch
without local formatting. Actual measured-status/preview validation remains
pending. Departure-state persistence follow-up remains separate and unscheduled.

### Prototype durable departure state

Dependent branch departure-state-persistence adds schema-3 pending exit/deadline
storage, timely re-entry cancellation, duplicate exit stability, manual clear and
retrospective end/pruning guarded by session ID. Forward migrations and four native
persistence/grace/recovery tests added. No scheduler/geofence callback/UI activation
is included; internals await CI and later integration. No GPS absence is treated
as departure and no new session is silently stopped. No VM checks executed.

### 2026-11-11 — clean UI refinement validated and merged

Measured-status PR #6 passed complete Actions run 42998378446 and merged at
db7c24b3110228550bf9107fd9858b9273757128. All four clean light/dark and scrolled
location previews from artifact 13844847336 were visually inspected. Timer/actions,
job groups and actual sample metadata are readable; no debug ribbon remains.
Dart/UI, Android build, native and version-policy checks passed in Actions only.
Departure PR #7 passed run 42998556045; rebased onto merged main after squash
ancestry conflict, with byte-identical source tree. New-head validation pending.

### Qualified geofence membership source

Isolated geofence-membership adds native membership/selection facts and six tests:
overlapping same-job sites, unknown/duplicate/late/conflicting observations,
archive/disabled filtering, 100-site capacity and unambiguous qualified entry.
Active sessions never select a new job; supplied suppression and arming filter
candidates. This does not persist suppression/arming or register callbacks, post
selection notifications or start timers. Native integration still requires those
steps and device evidence. Source is unvalidated until Actions completes; no VM
build, test, formatter or rendering was run.

Departure PR #7 passed rebased-head Actions 42999724652 and merged as
b0d93f5eb4d3a56a6bfb5a36ff38f87d42df820b. Membership source was rebased onto
that main commit; only append-only evidence sections needed conflict resolution.
Combined-head validation pending. Main UI validation 42999610367 also succeeded.

### Android visual identity follow-up

Isolated native-visual-identity replaces starter launcher rasters with vector
clock artwork, adaptive and monochrome layers, and matching light/dark launch
backgrounds including API-31 system splash attributes. No new runtime dependency.
Added an Actions-only native-resource rendering harness/artifact for image review;
it does not establish real launcher masking or device launch transitions. Source
and previews await CI. No VM rendering/build/test/formatter was run.

Departure PR #7 passed rebased-head Actions 42999724652 and merged as
b0d93f5eb4d3a56a6bfb5a36ff38f87d42df820b. Native visual source was rebased onto
that main commit; only append-only evidence sections needed conflict resolution.
New combined-head validation pending; no runtime automation/device proof inferred.

Native visual run 43000557414 passed Dart/UI checks and Android APK compilation,
then failed Kotlin test compilation: Robolectric getApplication has no type
parameter. Removed the unsupported generic argument. Rendering remains unproven;
all preview checks remain enabled. No VM tests/build/format/render were run.

### Native visual review and combined main validation

Corrected native visual run 43001359856 concluded SUCCESS: complete Dart/UI,
Android debug build, native tests/resource rendering and release version-policy
checks. Downloaded artifact 13845869738 and visually inspected all five PNGs:
legacy/adaptive/themed mark and light/dark launch backgrounds. No VM rendering.
Membership PR #8 passed exact-head run 43000880173 and merged at dc26e64.
Native visual branch rebased onto that main; conflicts were evidence appendices
only. Added public prototype device checklist and corrected outdated README and
timer contract status. New combined-head Actions validation pending; device and
runtime automation acceptance remain unproven. No production release submitted.

### Native visual merge and default-map source

Previous goal turn: progress (UI/refinement and departure/membership merges,
reviewed native previews, accurate contributor/device handoff). Continued by
polling the same live validation 43002359530; it concluded SUCCESS. Exact tested
head a2eaea5 merged via PR #9 as f9207b5. Matching Android visual identity is on main;
actual launcher/launch-transition device validation remains pending.

Pinned flutter_map 8.3.2 / latlong2 0.10.1 / http 1.6.0 / url_launcher 6.3.2 and
obtained Actions-resolved lockfile in 43002798859 / artifact 13846228897. New
osm-map-prototype source adds a current stored-point map with measured timestamp,
accuracy circle, always-visible attribution, proper User-Agent, cache policy and
shared 429/503 backoff. Release manifest includes Internet permission. Native
status now returns the same latest observation's coordinates and clears on idle.
HTTP policy, synthetic success/failure/navigation/zoom UI checks and native/codec
checks added. CI map images use synthetic tiles; no live OSM/device proof inferred.
Source validation and new visual review pending. No VM build/test/format/render.

OSM source run 43003804914 failed at formatting before analysis/tests. Applied
its reviewed Actions-generated source patch. Added responsive summary scrolling
and a large-text landscape check while keeping map controls/attribution visible;
enabled semantics in map accessibility checks. Dependency preparation 43002798859
completed SUCCESS independently; new map behavior still awaits its own CI.
No local build, formatter, tests or rendering ran.

OSM run 43004367001 required one additional formatter wrapping correction in the
landscape test; applied its source patch. Added an actual TimerScreen journey
through failed map decoding back to manual clock-out and updated synthetic
tracking previews to include valid coordinates. No native/physical or live-tile
success claimed. Next Actions run must pass analysis, all tests and visual review.

OSM run 43004745776 stopped on import-group formatting in the new timer journey;
applied its exact CI correction. Map point/accuracy ink now stays forest green
with a white marker halo so standard light OSM tiles remain legible in dark mode.
The marker is centered on the measured coordinate; no displaced pin tip. New
validation remains required before merging. No VM validation executed.

OSM run 43005218796 passed formatting, then analysis reported two redundant
Dart typed-data imports provided by Flutter services. Removed only those imports;
checks remain enabled. No map behavior/native/device success is inferred before
the next full Actions run and rendered-image inspection. No VM validation ran.

OSM run 43005707318 passed formatting/analysis and 20 Dart/UI checks, including
the real timer-screen map-outage/manual-stop journey. Failed malformed Retry-After
handling (Dart HttpDate throws HttpException) and two end-of-test accessibility
handle cleanup checks. Corrected the exception type and moved semantic-handle
disposal into test-body finally blocks; assertions remain active. Compact map
controls use a horizontal arrangement and the large-text check now covers a
320-high landscape viewport with full button bounds. CI exported six synthetic
previews (artifact 13848461149); visual inspection pending. No native/device/live
OSM success inferred; no VM validation ran.

OSM run 43006137440 required two formatter wraps after the stronger cleanup/bounds
checks; applied exact Actions patch. First map light/dark and location-card previews
from artifact 13848461149 were visually inspected: restrained layout, readable
measured metadata/attribution and consistent action styling. Tiles are synthetic.
CI now continues independent behavior/build checks after collecting a formatter
correction, with an explicit final Require clean formatting failure gate. Failed
formatting cannot produce a successful validation or trigger Play delivery. This
improves Actions-only feedback without disabling any checks or exposing secrets.
New full map validation remains pending; no VM checks executed.

### OSM prototype validation and merge

Actions 43006507700 on exact head 94451c2 concluded SUCCESS: clean formatting,
analysis, 23 Dart/UI tests, Android debug APK, native unit/resource-rendering tests,
locked Ruby tooling and release-version policy. All six synthetic images from
artifact 13849240735 were visually inspected (timer, scrolled location and map,
each in light/dark). Exact tested source merged through PR #10 as
bb2a9eb1a5a78074434472cb601d8755168f95e5. No VM build/test/format/render executed.

This proves source compilation, synthetic map/overlay layout, HTTP policy and
manual-timer UI isolation during tile failure. It does not prove live OSM service,
physical GPS/background/widget operation, Google runtime-key initialization or
full reporting/site editing. Those gates remain open. Native visual merge f9207b5
also completed main validation 43003375715 and Play internal delivery 43004256117;
that is upload acceptance, not tester installation or production availability.

Disposable owner-dispatched validation 43007665309 at fa4437e deliberately changes
only Dart whitespace to exercise the final formatter failure gate. It has no PR
and is not intended for main. Result is pending; do not count the negative-path
check as passed until its final failure and preceding check results are observed.
Merged-map main validation 43007569229 is independently in progress.

Formatter negative-path run 43007665309 completed with the intended FAILURE at
Require clean formatting. Analysis, all 23 Dart/UI tests, Android compilation,
native tests and release-version policy passed first; the exported source patch
confirms the intentional whitespace difference. This proves continued diagnostic
checks do not turn invalid formatting into a green validation. Probe branch is
being removed after terminal evidence. Main map validation 43007569229 and the
documentation-only PR #11 run 43007883446 both completed SUCCESS independently.
The only new changes after those runs are evidence text; application/workflow
source is unchanged. Physical/device/provider gates and full app milestones remain
incomplete. No VM validation executed.

### Durable geofence policy and native event transaction source

Previous goal turn: progress (OSM prototype merged after full CI and six-image
review; formatter failure gate proven in Actions). Continued from clean main
4a50778. Added isolated durable-geofence-policy source: schema 4 site inventory,
settings/arming/suppression, registration generations, membership facts, persisted
event outcomes and late-event review state. Configuration edits rotate registration
IDs and clear obsolete candidates; labels preserve known geometry/facts. Manual
stop/switch updates suppression in the same transaction. Eligible native events
can start one job, require choice for multiple jobs, never switch an active job,
and reconcile departure/re-entry with retrospective pruning and delayed wake.
Raw Flutter timer commands still cannot claim geofence origin.

Added ten minimum-API native tests covering enable-inside/exit-entry, recovery,
manual suppression/explicit restart, overlapping sites and timestamp ordering,
late re-entry review, zero grace, paid travel, ambiguous jobs, capacity/settings
rollback, ID reuse, concurrent native exit/manual stop and fixed schema-3 upgrade
plus failed-upgrade rollback. Existing schema 1/2 and command tests remain enabled.
These changes await Actions. Platform geofence registration/receiver, background
permissions, scheduler, health/provisional UI and physical-device proof are not
included yet; source tests do not establish runtime automation. Full revisions,
site UI and backup format remain later milestone work. No VM validation ran.

Main evidence validation 43008859456 completed SUCCESS. OSM source bb2a9eb also
completed Play internal delivery 43008466949; this is upload acceptance, not tester
installation or physical behavior. New durable-policy source remains isolated
until its own full Actions validation passes.

Prepared an explicit native accept-current-history review action: it preserves
session boundaries, resolves the alert and disarms/rotates registration state until
new qualified exit/entry. Extended the late-reentry test to prove no silent history
rewrite. Renamed requested inventory to sitesToRegister so it cannot be confused
with actual Android registration success. Contract is in docs/GEOFENCE_CONTRACT.md.
Initial run 43011366016 passed Android compilation and is running native checks;
review-action additions need new validation. No VM tests/build/formatter ran.

Initial policy run 43011366016 passed Android compilation and 37 of 38 native
checks; the capacity rejection hit API-24 transaction dispatch of ROLLBACK TO.
Confirmed the platform behavior in Android 7 DatabaseUtils/SQLiteSession source.
Moved capacity checks before mutation within the same serialized transaction;
kept rejection/key-preservation assertions and full migration rollback checks.
Native JUnit XML will now be exported from Actions for precise failure diagnosis.

Tightened idle-data retention before platform activation: event IDs/fingerprints
are device-keyed HMAC-SHA256 receipts, persisted outcomes omit raw choice/warning
IDs, and review state holds one current alert per job until explicit acceptance.
Current membership facts remain; no historical raw off-clock event payload is
retained. Unit tests inject a public synthetic HMAC fixture and inspect persisted
receipts, so Android Keystore persistence is explicitly still a device gate.
Review action, capacity correction and minimized receipts need the next full CI.
No VM validation ran; automation is not exposed in the app yet.

Added an eleventh native policy case for unavailable receipt keys: a possibly
unprocessed re-entry blocks automatic expiry for review while manual clock-out
still works. This is tested with a synthetic provider error, not a claimed Android
Keystore hardware/provider result. Late-event acceptance preserves existing time
history and disarms until fresh outside/entry facts. New source remains pending CI.

Corrected policy run 43014180179 on 32ec72d completed SUCCESS: Dart/UI, Android
compilation, 38 native tests (0 failures/errors/skips, XML artifact inspected),
resource previews and release-version policy. This verifies the capacity correction,
minimized receipts and explicit review acceptance in native unit conditions.
The additional unavailable-key expiry guard/test is a new source change and must
pass a subsequent full Actions run before merge. Device Keystore, receiver and
scheduler behavior are still unproven; no VM validation executed.

Added a twelfth policy case for invalid event clocks and provider faults, with a
finite native problem API for upcoming registration/permission/recovery adapters.
Known processing uncertainty now holds automatic expiry for explicit review instead
of losing a possible re-entry, while manual commands remain independent. This is
new source beyond the previously successful run and still needs Actions validation.

Refined recovery to avoid unnecessary approval loops: idle faults disarm current
facts and recover on fresh outside/entry, and known timely re-entry clears current
departure uncertainty automatically. Review alerts now reference affected work
sessions, not an idle movement history. Manual stop resolves its current uncertainty;
historical alerts cannot block expiry of a different manually started session.
Added a thirteenth native policy case for automatic recovery with fresh facts.
Provider/clock recovery additions still need the next full Actions run.

Review semantics are now scoped to affected work-session IDs. Benign pre-manual
callbacks do not create idle history or an approval loop. Idle provider faults
reset/disarm facts, and fresh outside/entry recovers automatically; known timely
re-entry clears active departure uncertainty. A historical alert cannot block a
new manually started session's expiry. Explicit review acceptance still preserves
recorded times and resets the job's automation context. Thirteen policy cases
are prepared; all latest refinements await a new Actions run.

Separated registration-generation time from current-fact freshness. The late-entry
case now includes an intervening idle provider fault: recovery must not hide a
valid late re-entry relevant to a finalized work session. Configuration changes
still invalidate obsolete keys; current facts after faults require fresh evidence.
Latest recovery refinements remain isolated and unvalidated until the next CI run.

Added a fourteenth policy case: a historical late-exit ambiguity survives later
provider faults and manual clock-out, while a newer manually started session can
still finalize normally. A sticky history-review flag prevents transient recovery
from silently accepting ambiguous past work. Only explicit review resolves it.
Latest source remains pending Actions; no VM validation executed.

### Milestone 2 — departure wake-up source (pending CI)

Added a native inexact AlarmManager adapter, private deadline/recovery receivers,
and commit subscription on the shared repository singleton. The SQLite writer
checks expected candidate deadline/session, due time and review before expiry;
re-entry/manual changes cancel wake-ups. Boot or clock changes hold uncertain
work instead of inventing an exit. No exact-alarm permission or GPS timer added.
Seven synthetic cases target API 24 and 35: inexact immutable alarm configuration,
delayed pruning, early/stale delivery, cancellation, recovery hold/manual action,
reopening and malformed broadcasts. They have not run yet; all builds/tests stay
in GitHub Actions. Platform geofence registration and app activation UI remain
unfinished, and physical wake/recovery evidence is still missing.

Durable policy exact head 87f566c passed full Actions run 43020856586. Inspected
JUnit XML: 42 native tests, zero failures/errors/skips, including fourteen new
policy cases. PR #12 merged as 87539048b9c5f468f8f52ebec88aac38de981b8b.
Dart/UI, Android build and release-version checks passed in that run. Alarm
adapter additions remain separate and need their own CI; milestone 2 is incomplete.

Alarm adapter run 43021499336 compiled the Android app, but native test
compilation rejected a nullable PackageInfo permission array in the new manifest
assertion. Added an explicit empty-array fallback without changing the permission
assertions. Runtime tests remain unverified until the corrected Actions run.

### Milestone 2 — Android geofence adapter source (pending CI)

Added Play Services inventory replacement, explicit mutable callback token/private
receiver, precise/background permission checks, asynchronous readiness tracking
and a cleanup-only preference. The registry rejects stale readiness results and
avoids endless provider retries. Startup holds uncertain prior registrations before
an alarm can finalize work. Callback/alarm work shares one native executor; events
use measured time without retaining off-clock coordinates, honor replay outcomes,
recheck the running session for FGS handoff and offer manual job selection for
ambiguous entries. No background permission request or automation activation UI
has been added yet. Fifteen synthetic cases target API 24/35; they use fake
platform completions and a public HMAC fixture, not real registration or Keystore
proof. All source still requires Actions validation and device acceptance.

### Milestone 2 — alarm evidence and automation controls source

Alarm head c76e806 passed full Actions run 43022813646. Inspected native XML:
56 tests, zero failures/errors/skips, including seven alarm cases on API 24 and
35. PR #13 merged f845937ecf90838da0b1f7dc6c6da15e779e0bd4. Policy main
validation 43021843305 and Play internal delivery 43022739286 also succeeded.
Geofence adapter head e42b37e passed run 43023461269 with 86 native tests,
zero failures/errors/skips (fifteen adapter cases on API 24/35). PR #14 was rebased
onto merged alarm code; its new exact head d0ef67b needs final validation.

Added unvalidated work-site UI source with native versioned save/remove/settings
commands, background-purpose education, separate precise/background permission
flows, measured pending-departure display and recorded-work review. Acceptance
preserves shown history and explicitly resets pending automation. Command retries
retain their original ID/version. The timer surface links to real site controls and
shows pending/review state. Six native API cases target API 29/35; nine Dart/UI
cases include light/dark site/editor/departure previews and large-text layout.
All tests, formatting and rendering remain in Actions. These additions do not
prove physical geofence/FGS/alarm delivery, nor complete milestones 2 or 5.

Final geofence adapter run 43024844407 passed on exact head d0ef67b. Inspected
JUnit XML: 86 tests, zero failures/errors/skips. PR #14 merged as
edc7dddaa40c4218a766fbc6ad4befb2f42bcf04. Work-site/permission/review UI
remains isolated in PR #15 and awaits formatting, behavior and visual validation.

First controls run 43026170277 exported formatter corrections but stopped at
eight Dart flow-control brace lints. Applied the Actions-generated patch and
added braces; no local formatter/test/build executed. Behavior and previews
remain pending the corrected full run.

Controls run 43026618644 passed formatting/analyze and all Dart/UI checks;
Android/native checks were still live at this observation. Reviewed the six new
site/editor/departure and two changed timer previews in both themes (artifact
13857262562): clear spacing, wrapping and consistent hierarchy. Review revealed
a copy ambiguity: pending work held for review must not promise automatic expiry.
Added conditional held-work copy and explicit year/seconds/UTC offset to review
boundaries; these source refinements require the next CI. Coordinate editing is
implemented; map-tap/current-position selection and full correction UI remain
planned, along with physical acceptance. No VM builds/tests/rendering ran.

Controls revision run 43027348013 passed formatting/analyze and 31/32 Dart/UI
cases. The added held-departure copy assertion searched an off-screen, lazily
unbuilt card after scrolling to the review action. The test now reveals the
pending card before checking that copy, then reveals recorded boundaries and
acceptance separately. Both assertions remain; a corrected Actions run is required.

### Milestone 2 — verified control UI and site selection source

Controls exact head 8a266c4 passed full Actions run 43028074243. Native XML:
98 tests, zero failures/errors/skips; Dart/UI checks and Android build passed.
Reviewed final light/dark departure images plus the previously reviewed site,
editor and timer previews. PR #15 merged 897e120bc0c802eb6ff42adfb4aab430a93851e0.
Main geofence adapter validation 43026215698 and internal delivery 43027191550
also succeeded. This is automated/visual source evidence, not device acceptance.

Added unvalidated map-tap/current-location site selection on that merged base.
OSM picker reuses identified/cached/backed-off tiles, radius overlay, attribution,
zoom and coordinate fallback. Polar coordinates remain editable without forcing
invalid Web Mercator rendering. A foreground-only cancellable fused one-shot
requests a fresh fix with a 15-second provider duration and a 20-second UI timeout; measured time/accuracy are displayed
in the draft, and no setup point enters work history. Backgrounding/detach cancels
and late callbacks cannot overwrite a newer request. The site form builds all
validators in one scroll view so off-screen fields cannot evade validation.
Seven native controller cases (API 24/35), one bridge case (API 29/35), eight UI/map
cases and one Dart codec case are prepared. All builds, formatter, tests and preview
rendering remain in Actions. Actual fused fixes, live OSM, device permissions and
background tracking remain unproven; milestones 2 and 5 are incomplete.

First site-selection run 43064678311 exported formatter changes, then stopped
at an invalid const Semantics construction and an unused test import. Applied
the Actions formatter patch, corrected both analyzer issues and retained all
behavior assertions. No VM validation ran; corrected CI evidence is still needed.

Owner reiterated the current-location work-site button with a precise permission
request only when missing. That flow is implemented in the site editor/native
preflight; added a direct UI case asserting one precise request, no background
request, a fresh fix retry, and explicit Save site. The already-granted case now
asserts zero permission requests. These refinements await Actions validation.

Site selection run 43065083546 passed full CI on 8d26499: inspected native
XML shows 114 tests, zero failures/errors/skips. Reviewed map/editor previews
from artifact 13875397031. The dark marker needed contrast refinement; reviewed
that corrected light/dark rendering from artifact 13874933679. Owner-specific
permission case then passed all behavior/build checks in run 43066607243 on
53f08f8, including 114 clean native tests; only the final formatting gate failed.
Applied its Actions-generated formatter patch. Final exact-head CI is pending;
actual device fixes and live tiles remain unproven. No VM validation ran.

### Owner-directed job lifecycle (implementation in progress)

The owner requested a red, confirmed permanent Delete job action, then refined
that policy: jobs must first be archived continuously for 30 days before deletion.
Archived jobs can be unarchived to return to clock-in and automation choices.
Implement archive/restore without losing history or preferences; require clock-out
before archiving the running job. Restoration resets the retention period and
site detection, preserves automation preferences, and respects the 100-site limit.

Deletion is unavailable for active jobs or before 30 elapsed UTC days. The native
serialized writer must enforce this independently of UI state. Eligible deletion
must show a red action and explicit permanent-loss confirmation, remove the job's
work/sites/location data transactionally, and keep only opaque deletion metadata
needed to prevent future silent backup resurrection. Command receipt payloads
must not retain deleted names/coordinates/times; deduplication must still work.
Legacy archived rows without an archive date start a fresh retention period on
migration rather than becoming immediately deletable. These owner-requested job
operations add foundation work while physical milestone-2 gates remain pending;
no broader milestone-3 completion is implied.
