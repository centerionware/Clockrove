# Clockrove — implementation plan and handoff to Luna

Prepared: 2026-11-11. Status: M7 encrypted-backup implementation is in progress; native tracking device acceptance, full M7.6 UI/E2E acceptance and public terms review remain pending; see `plan_status.md` for task-level evidence.

## 1. Start here

Read `AGENTS.md` and the `app_plan.md` index, then follow the product contract and ordered milestones linked there. Validate each milestone against `release-readiness.md`; record current status in `plan_status.md` and append evidence to the linked evidence archive. Do not replace unimplemented native capabilities with simulated success.

The user explicitly requested this handoff after creating agent instructions and local skills. Deliver the application described here; do not infer that application code, builds, GitHub setup, or store releases already exist.

### Current workspace facts

- Workspace: `/workspace/TimesheetTracker`; protected root `.git` remains untouched.
- `scripts/git-local` uses ignored `.local/git` metadata with the intended origin.
- `centerionware/Clockrove` is public after explicit owner instruction on 2026-11-11.
  `docs/SOURCE_TERMS_DRAFT.md` remains a proposal, not an adopted license.
- User 1000's authenticated `gh` identity is `centerionware`.
- Android Flutter scaffold, pubspec.lock and pinned Gradle wrapper are committed.
- All builds/tests now run in GitHub Actions, per owner instruction. VM tool setup
  and attempted builds in the historical ledger predate that instruction.
- CI pins Flutter 3.47.9, Dart 3.13.7, Temurin 17.0.17+10, AGP 9.1.0,
  Kotlin 2.4.0, Gradle 9.3.1, API 24 minimum / 36 target, build tools 36.0.0,
  and NDK 28.2.13676358. Compatibility requires a successful Actions build.
- Foundation debug build and signed setup AAB are proven in Actions. Physical-device tracking evidence remains unproven. Automatic Play internal
  uploads are confirmed; production and tester installation are not confirmed.
- Optional ignored local skills remain supplements, never contributor dependencies.
- Current M7 work: PR #52 merged to `main` as `cc6870931920016e7ee9bc4f5fc4be6e96b26523`. Its exact PR head `defb4e6` / Actions run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557) passed formatting, analysis, Flutter behavior tests, Android and instrumentation builds, 635 native tests, API 24/35 device validation, invoice-viewer checks and release-tooling checks. Each API reported 38 actual Android tests with zero failures/errors/skips. The 1 MiB crypto benchmark measured encrypt/decrypt 755/301 ms on API 24 and 869/273 ms on API 35; each envelope was 1,048,648 bytes. PR #59's 64 MiB crypto-only run [43700311041](https://github.com/centerionware/Clockrove/actions/runs/43700311041) measured peak VmHWM of 823,572 KiB on API 24 and 951,840 KiB on API 35 (about 804/930 MiB); end-to-end exporter memory can be higher because it buffers rows, media, archive and ciphertext. The Settings export entry remains withheld until streaming or a materially lower memory bound is validated. PR #60 run [43702626041](https://github.com/centerionware/Clockrove/actions/runs/43702626041) passed formatting and analysis but failed Flutter behavior (223 passed, 5 failed, 14 skipped). Follow-up [43705585212](https://github.com/centerionware/Clockrove/actions/runs/43705585212) passed formatting and analysis but had 224 passing, 4 failing and 14 skipped widget tests; every remaining failure was the non-unique `Scrollable` finder. Run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) on `a6ba266` passed formatting, analysis, Flutter behavior, Android build, instrumentation assembly and the bounded install-recovery synthetic, then failed 16 of 675 native tests. All 16 failures were preview cases masked as `INVALID_REVISION_GRAPH` because a local time-claim overlap query ran on the staging SQLite connection. The rebased source fix checks incoming active claims against `invoice_time_claims` on the local database, with empty-preview and local-overlap regression cases. PR #60 rebased head `ead1a8518fda0f3f4d65da0ab7f7ed4ce1f3b158` / Actions run [43708093424](https://github.com/centerionware/Clockrove/actions/runs/43708093424) passed formatting, analysis, Flutter behavior (228 passed, 14 skipped), Android build, release Java compilation, instrumentation assembly, install-recovery synthetics, and the complete API 24/35 workflow. Native JUnit totaled 676 tests with zero failures, errors or skips, including two `NativeVisualPreviewTest` cases. The earlier run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) also included those two cases in its 675-test JUnit total; the new aggregate is not a count that excludes them. API 24 and API 35 each passed 38 Android tests with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt 878/280 ms on API 24 and 877/287 ms on API 35; each envelope was 1,048,648 bytes. Invoice viewer, artifact export, and locked release tooling passed. Commit `9dc1542` makes expiry rescheduling fail closed: it detaches the active snapshot, wipes the key, attempts artifact deletion and retries failed deletions on the next store interaction. Regression coverage injects a later scheduler failure and a deferred deletion failure. Exact-head run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843) passed on SHA `9dc154288111af1219166d5eaeeea8661dfee1aa`: formatting, analysis, Flutter behavior, Android build/release compile, instrumentation assembly, bounded install-recovery synthetic, 677 native tests (zero failures/errors/skips, including two `NativeVisualPreviewTest` cases), 38 actual Android tests each on API 24 and 35 (zero failures/errors/skips), invoice-viewer validation, artifact exports and locked release tooling. API 24 benchmark attempt 1 timed out before test start during APK install; recovery confirmed a changed emulator boot ID and boot completion, then the single retry passed. The 1 MiB benchmark measured encrypt/decrypt 742/273 ms on API 24 and 951/292 ms on API 35; both envelopes were 1,048,648 bytes. PR #58 exact head `a51fcd7` passed run [43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294) and merged as `fefdcdf`; Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644) passed on main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`. JUnit reports 38 actual Android tests on each API 24 and 35 with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt at 884/363 ms on API 24 and 1,105/343 ms on API 35; each envelope was 1,048,648 bytes. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) completed successfully on exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; the internal AAB upload/submission was accepted at 11:30:34 UTC. This proves upload/submission only, not review approval or tester availability. Restore apply and full M7.3/M7.6 acceptance remain pending. Full evidence is in `plan_status.md`.

### Exact money contract (owner priority, 2026-11-12)

All money input, persistence, transport and calculations use canonical decimal
strings and arbitrary-precision decimal/integer libraries, never binary floating
point or fixed-width money arithmetic. Use pinned Dart `decimal` and native
`java.math.BigDecimal`/`BigInteger`; parse strings directly. Remove current 64-bit
rate/unit-price limits through a conservative migration preserving released values,
issued documents, source claims and command receipts. Reject malformed/excess
currency precision explicitly; never silently round input. Quantity and tax math
stay exact until the documented currency boundary: half-up once per task line,
hourly total and invoice-subtotal tax. Payments, credits and balances conserve each
minor unit and never narrow large invoice totals. Pin/lock new dependencies through
Actions only, and verify migration, large amounts, tiny fractions and conservation.
Arbitrary precision must not be described as unlimited input: enforce explicit
resource bounds before arithmetic/allocation, reject out-of-range quantities and
amounts without writes, and preserve the entered draft. Never wrap, clamp or
truncate an amount to fit a machine integer. Currency-boundary rounding is an
explicit business rule, separate from numerical precision loss.

For user-entered currency amounts, accept a leading-decimal shorthand such as
`.01` as `0.01`. Normalize it before exact decimal parsing; never reject it as
invalid syntax or route it through binary floating point.

### Next concrete action

The quote baseline, final rendered documents, and invoice-style preview merged
in #39 at `8da7113`; main validation 43384059350 passed and Play accepted
`0.1.0-internal.29.1` / version code `216718138` in delivery run 43385810795.
Documentation follow-up #42 merged at `650e201`. Main validation 43389226159
passed with 563 native tests and 30 actual Android tests on each of API 24 and 35,
all with zero failures/errors/skips. Automatic Play delivery 43391432210 accepted
`0.1.0-internal.30.1` / version code `216720875` at 08:19:21 UTC. Review approval,
availability and physical installation remain separate.

M6.5 quote, comments, private image attachment, GPS controls and map fallback
source are on PR #43 (`milestone-quote-media-gps`). Exact head `81d227f` / Actions
run `43489413018` passed formatting, analysis, Flutter tests, Android build,
native tests, the actual Android journey suite on API 24 and 35, invoice viewer
checks, and release tooling. Downloaded XML reports 32 actual Android tests per
API (23 base, 1 billing, 5 immutable copies, 1 payment, 1 quote and 1 task-media),
all with zero failures/errors/skips. Reviewed camera-review and task/comment
attachment screenshots on both APIs. They confirm explicit photo review,
attachment persistence, comment ownership, and the correct “Photo attached to
this comment” confirmation. The E2E journey asserts separate task/comment
messages. Its non-exported camera fixture is debug-only and enabled by the
instrumentation preference; release builds still use the phone's camera. M6.5
emulator journeys pass. Physical map display, GPS sampling and hardware-camera
acceptance remain owner-device checks. No VM build/test/formatter has run.

The owner-requested photo navigation direction correction is in PR #55: left
swipe advances to the next chronological task/comment photo, and right swipe
returns to the prior photo. Its first exact-head run passed formatting, analysis,
Flutter behavior, Android build and native unit tests, then stopped in the API-24
device journey while revealing **View original PDF**. The viewer direction is
proven on Android devices by corrected exact head `1f3662e` / Actions run
[43681914649](https://github.com/centerionware/Clockrove/actions/runs/43681914649),
which passed formatting, analysis, Flutter behavior, Android build, native unit
tests and the API-24/35 device suite. The device journey verifies right swipe
from photo 4 to 3 and left swipe back to 4. PR #55 also includes the shared
billing-device-test corrections for text replacement, short settled scrolls,
tax-error visibility and covered-task checkbox visibility. No VM
build/test/formatter has run.
The M7.1 backup-crypto source uses pinned `cryptography` 2.9.0, a bounded
authenticated header, fixed Argon2id parameters, AES-256-GCM, and an API 24/35
Flutter integration benchmark.
Actions run [43664789329](https://github.com/centerionware/Clockrove/actions/runs/43664789329)
generated the proposed lockfile and formatter patch, then Analyze reported a
missing widget import in the benchmark and an invalid `List<int>` to
`TypedData` handoff in decryption. Those static issues are corrected from the
CI evidence. Locked rerun [43665136368](https://github.com/centerionware/Clockrove/actions/runs/43665136368)
passed dependency resolution and formatting but Analyze found a redundant
`dart:typed_data` import in the benchmark; it has now been removed. The run
again did not reach behavioral or device checks. The third locked run
[43665431324](https://github.com/centerionware/Clockrove/actions/runs/43665431324)
passed lock resolution, formatting, analysis, Flutter behavior tests, Android
debug build and native unit tests, then failed while assembling the Android
instrumentation APK. `integration_test` contributed runner 1.3.0 to the debug
runtime graph while `androidTestImplementation` requested 1.7.0; Gradle's
consistent-resolution constraint made those versions incompatible. The API
24/35 emulator journeys and crypto benchmark did not run. The build now aligns
`debugImplementation` with runner 1.7.0 and assembles
`:app:assembleDebugAndroidTest` before emulator setup. Run
[43667170760](https://github.com/centerionware/Clockrove/actions/runs/43667170760)
passed all fast gates, API-24 native/device cases and the crypto benchmark
(1 MiB payload: encrypt 878 ms, decrypt 284 ms). On API 35, the actual-app suite
recorded five failures and one error after MainActivity opened to a blank
screen. The root cause is confirmed from the workflow and artifact: the API-24
Flutter integration benchmark rebuilt the shared `app-debug.apk` with its test
entrypoint, then the API-35 loop installed that overwritten APK. The CI script
now preserves immutable copies of the real app and native test APKs before the
emulator loop and installs those on both APIs. Exact PR #52 run
[43671872207](https://github.com/centerionware/Clockrove/actions/runs/43671872207)
passed formatting, analysis, Flutter behavior, Android build, instrumentation
assembly and native tests. API 24 again passed crypto benchmarking. API 35 now
reached the real app but stopped at the shared billing invalid-tax lookup before
its crypto benchmark. The shared device-test correction from `36e27ba` is
included in the prepared main-based reconciliation: it verifies text was
cleared before replacement and focuses the tax field before checking the
validation error. The follow-up billing test corrections from `88d88b8`,
`00531dc` and `32d6154` are now validated at PR #54 head `32d6154`: exact Actions
run [43679591795](https://github.com/centerionware/Clockrove/actions/runs/43679591795)
passed all gates, including the API-24/35 Android device matrix. This validates
the shared device-test path. PR #52's later exact head `e87eebf` / Actions run
[43683558885](https://github.com/centerionware/Clockrove/actions/runs/43683558885)
also passed the full workflow and benchmark on both APIs: encrypt/decrypt was
874/337 ms on API 24 and 1,124/348 ms on API 35, with 37 actual Android tests
per API and zero failures, errors or skips. That result predates the latest main
base. PR #52 merged to `main` as `cc6870931920016e7ee9bc4f5fc4be6e96b26523`. Its exact PR head `defb4e6` / Actions run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557) passed formatting, analysis, Flutter behavior tests, Android/instrumentation builds, 635 native tests, the full API-24/35 device matrix, invoice-viewer checks and release tooling. JUnit reports 38 actual Android tests per API with zero failures, errors or skips. The 1 MiB crypto benchmark measured 755/301 ms encrypt/decrypt on API 24 and 869/273 ms on API 35; both envelopes were 1,048,648 bytes. The empty instrumentation AAPT version code, normal-install AAPT avoidance and bounded install recovery passed. This is PR-head evidence, not a post-merge main run.

The M7.2 successor PR #60 retains the native import preview, SAF document-save bridge and bounded Dart export source and is rebased onto PR #58 merge `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`. Its first exact-head run [43701264644](https://github.com/centerionware/Clockrove/actions/runs/43701264644) stopped at `Require clean formatting`; the workflow-exported formatter patch was applied to six Dart source/test files. Run [43701749563](https://github.com/centerionware/Clockrove/actions/runs/43701749563) passed formatting and stopped at Analyze on an invalid named-callback parameter in a widget test and related import/lint cleanup. Run [43702265074](https://github.com/centerionware/Clockrove/actions/runs/43702265074) also passed formatting, then flagged one remaining redundant typed-data import. Run [43702626041](https://github.com/centerionware/Clockrove/actions/runs/43702626041) passed formatting and analysis but Flutter behavior tests reported 223 passed, 5 failed, and 14 skipped. Run [43705585212](https://github.com/centerionware/Clockrove/actions/runs/43705585212) passed formatting and analysis; behavior tests reported 224 passed, 4 failed and 14 skipped. The error was a test-only non-unique `Scrollable` finder passed to `scrollUntilVisible`; the MethodChannel `PlatformException` expectation and actual `+0` benchmark test-progress retry refusal passed. The finder is now scoped to the backup screen's ListView. Run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) on `a6ba266` passed formatting, analysis, Flutter behavior, Android build, instrumentation assembly and bounded install-recovery tests; native tests then failed 16/675. The XML locates all failures in `BackupImportStoreTest`: the local invoice-time overlap query joined a local-only table through the staging connection, causing a generic exception to be reported as `INVALID_REVISION_GRAPH`. Commit `f9b223f` moves those overlap probes to the local database and adds an empty-preview regression; the existing local-overlap test covers a real local claim. It is rebased onto PR #58 merge `fefdcdf`. PR #60 rebased head `ead1a8518fda0f3f4d65da0ab7f7ed4ce1f3b158` / Actions run [43708093424](https://github.com/centerionware/Clockrove/actions/runs/43708093424) passed formatting, analysis, Flutter behavior (228 passed, 14 skipped), Android build, release Java compilation, instrumentation assembly, install-recovery synthetics, and the complete API 24/35 workflow. Native JUnit totaled 676 tests with zero failures, errors or skips, including two `NativeVisualPreviewTest` cases. The earlier run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) also included those two cases in its 675-test JUnit total; the new aggregate is not a count that excludes them. API 24 and API 35 each passed 38 Android tests with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt 878/280 ms on API 24 and 877/287 ms on API 35; each envelope was 1,048,648 bytes. Invoice viewer, artifact export, and locked release tooling passed. Commit `9dc1542` makes expiry rescheduling fail closed: it detaches the active snapshot, wipes the key, attempts artifact deletion and retries failed deletions on the next store interaction. Regression coverage injects a later scheduler failure and a deferred deletion failure. Exact-head run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843) passed on SHA `9dc154288111af1219166d5eaeeea8661dfee1aa`: formatting, analysis, Flutter behavior, Android build/release compile, instrumentation assembly, bounded install-recovery synthetic, 677 native tests (zero failures/errors/skips, including two `NativeVisualPreviewTest` cases), 38 actual Android tests each on API 24 and 35 (zero failures/errors/skips), invoice-viewer validation, artifact exports and locked release tooling. API 24 benchmark attempt 1 timed out before test start during APK install; recovery confirmed a changed emulator boot ID and boot completion, then the single retry passed. The 1 MiB benchmark measured encrypt/decrypt 742/273 ms on API 24 and 951/292 ms on API 35; both envelopes were 1,048,648 bytes. PR #58's exact head `a51fcd7` passed [43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294); it merged as `fefdcdf`. Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644) passed on main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`. JUnit reports 38 actual Android tests on each API 24 and 35 with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt at 884/363 ms on API 24 and 1,105/343 ms on API 35; each envelope was 1,048,648 bytes. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) completed successfully on exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; the internal AAB upload/submission was accepted at 11:30:34 UTC. This proves upload/submission only, not review approval or tester availability. Native safeguards gate readiness on no local timer, allowlist non-null GPS point revision payloads while preserving redacted nulls, validate invoice claim ownership/overlap rules, and delete a private SAF stage if expiry cannot be rescheduled. No local build, test, formatter or analyzer ran. PR #59 measured 64 MiB crypto-only peak VmHWM at 823,572 KiB on API 24 and 951,840 KiB on API 35; the full export path retains additional copies. Keep the Settings export route withheld pending streaming or materially lower measured memory. Transactional restore, M7.3 and M7.6 acceptance remain open. Current-main validation and guarded Play upload/submission passed on `fefdcdf`; PR #60 exact-head Actions run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843) passed on `9dc1542` and the PR remains unmerged. Owner-device acceptance, transactional restore, and M7.6 gates remain open.

The whole-app UI/E2E milestone remains M7.6 after M6.5, M6.6 and backup M7.

After M6.5, complete M7 backup and the remaining requested business features.
Only after all launch functionality is implemented, M7.6 is the whole-app
first-use UI overhaul and exhaustive actual-app E2E/screenshot inventory. The
owner explicitly requires every feature, screen and option to pass this reviewed
coverage before moving from internal to closed testing. All builds, tests,
formatters and renders stay in GitHub Actions.
