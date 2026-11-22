# Clockrove task status

Geofence candidate fix and pending acceptance are tracked in [November 13 regression evidence](docs/plan/evidence/geofence-nov13.md).
Updated 2026-11-23. Product contract: [app_plan.md](app_plan.md).
Statuses describe evidence, not intent.

## November 23 geofence departure follow-up

- PR #83, the explicit in-place clock-in flow, merged to main as
  `e126bf1c1119ad6ea968bfb2be0dd1c12d6fdf8d`. Exact-head validation
  [45088251947](https://github.com/centerionware/Clockrove/actions/runs/45088251947)
  and merged-main validation
  [45091148930](https://github.com/centerionware/Clockrove/actions/runs/45091148930)
  passed. Internal Play delivery
  [45094669012](https://github.com/centerionware/Clockrove/actions/runs/45094669012)
  submitted the bundle; this does not prove installation or device geofence
  delivery.
- The owner reports that automatic arrival failed at a job, so they clocked in
  manually and updated the app while still working. Later, after leaving, the
  timer did not clock out. They had a 1-minute grace, raised the radius to 150 m,
  waited about 20 minutes and tapped **Retry site monitoring**. Automation and
  location permissions were already enabled. They saw the GPS icon twice; this
  does not establish that Android delivered a geofence exit. A separate 150 m
  home test did auto-start after a delay, then was manually stopped while still
  at home, so it did not test departure. The owner clarified the 15 m to 150 m
  radius change followed the app update. The reported current-location save was
  before that update; the later edit kept the site location and changed only
  radius. No fresh fix was reported with that edit, so it does not exercise this
  follow-up. Monitoring health after retry remains unconfirmed.
- Source tracing found that site edits rotate registration keys and reset
  membership to `UNKNOWN`. Before this follow-up, a current fix saved with a
  site could not establish the baseline for a running session's departure. The
  in-progress correction passes the measured fix to native state, seeds
  membership only when accuracy supports the classification, creates no session
  or location-history point, and stores the fix's accuracy/time in a digest in
  the retry receipt. It also adds accuracy, active-session and no-false-departure
  regressions. Run
  [45103688030](https://github.com/centerionware/Clockrove/actions/runs/45103688030)
  passed formatting, Analyze, Flutter tests and Android builds, then found one
  native regression: label-only site rename incorrectly rotated the fence key.
  The condition now preserves the previous behavior. Corrected exact-head
  Validate run
  [45104682175](https://github.com/centerionware/Clockrove/actions/runs/45104682175)
  passed on `7b64341`: formatting, Analyze, Flutter tests, Android build and
  release Java, 757 native tests with zero failures/errors/skips, and 40 actual
  Android tests on each of API 24 and API 35 with zero failures/errors/skips.
  Owner-device callback acceptance is still pending. The earlier docs-only
  exact-head run [45095882626](https://github.com/centerionware/Clockrove/actions/runs/45095882626)
  did not validate this code.

## November 22 release readiness update

- Geofence regression: [PR #77](https://github.com/centerionware/Clockrove/pull/77)
  merged as `abe6f5f`; its native and merged-main validation passed. The
  subsequent in-place setup UI, Help and test changes are in
  [PR #83](https://github.com/centerionware/Clockrove/pull/83), now merged as
  `e126bf1`. Earlier failed formatting, lint and widget-test runs were corrected
  on the way to the passing exact-head, main and internal-delivery runs recorded
  above. Android's 100–150 m guidance informs the 150 m default; smaller saved
  radii remain supported and may miss transitions.
- Backup and restore: [PR #74](https://github.com/centerionware/Clockrove/pull/74)
  and native deletion follow-up [PR #75](https://github.com/centerionware/Clockrove/pull/75)
  are merged. [PR #79](https://github.com/centerionware/Clockrove/pull/79)
  passed full exact-head validation on `04fb070`, including the Android system
  Documents picker journey that authenticated and transactionally restored a
  synthetic backup on API 24 and 35. The test verified imported sessions and
  site, a stopped timer, and disabled imported automation with no site
  registration. PR #79 merged as `4be7b83`; merged-main validation
  [45084756566](https://github.com/centerionware/Clockrove/actions/runs/45084756566)
  passed. Owner-device restore acceptance, full M7.3 conflict coverage, and
  M7.6 remain separate gates.
- Documentation: [PR #78](https://github.com/centerionware/Clockrove/pull/78)
  contains offline Help, the static website and draft policy and store copy.
  [Website source validation](https://github.com/centerionware/Clockrove/actions/runs/45023185584)
  passed. Android Help journey results, policy approval and publication,
  the whole-app UI and device coverage gate, and closed testing remain open.

**App localization is in progress.** The first slice adds offline YAML catalogs
for 17 locales and a device-default or explicit language choice in Settings.
The catalogs cover 36 messages across language selection, selected Settings
labels, the timekeeping dashboard and its navigation. Tests check placeholder
parity for messages containing dynamic values.
Catalog parity, locale preference tests, and a Flutter/Android source audit are
prepared for GitHub Actions. The audit reports remaining candidates during
migration, with strict mode available for a full-coverage check. Initial
[Actions validation](https://github.com/centerionware/Clockrove/actions/runs/45065644235)
completed the audit and stopped at the formatting gate before behavior tests.
The exported formatter corrections were reviewed and incorporated where they
apply to the current source.
The Android widget runtime now uses 13 generated catalog entries for its job
and task labels, error messages, and accessibility descriptions across the
same 17 locales. GitHub Actions verification is pending. Widget picker metadata and the existing
tracking and geofence notification resource strings are now also generated
from the Android catalogs. Other native copy still needs migration.
[Actions run 45066471967](https://github.com/centerionware/Clockrove/actions/runs/45066471967)
reported 294 Flutter tests passed, 2 failed, and 14 skipped. The two Settings
tests now scroll to the hourly rate field before using it, since adding the
language picker moved the field below the initial viewport.
Native widget contents and newly rendered notifications now also use the
saved language override. A native regression checks Spanish format arguments
and resetting the override on API 24 and 35. Validation is pending.
The recorded-location map and location sample labels now use 14 additional
Flutter messages, including translated date, time, and accuracy templates.
The existing catalog parity check covers their named placeholders.
The work-location permission dialog, action labels, and tracking statuses
now resolve from the Flutter catalogs. The dialog scrolls to accommodate
long translations, and displayed status notices follow locale changes.
Backup export and restore now resolve password fields, picker actions, and
the reviewed-merge confirmation controls from every shipped Flutter catalog.
Restore progress, picker cancellation, and stale-preview messages also use
catalog keys.
Restore conflict choices now use the same catalogs and preserve their
native choice codes. Unknown codes get a translated display label. Restore
confirmation, conflict pagination, and review instructions also use the
catalogs. The restore preview totals use translated labels, locale-formatted
record counts, and a translated media-size template. Conflict titles,
comparison headings, and boolean values also use the catalogs. Native record
field labels and remaining review copy and error paths are still open.
Restore password validation, staging instructions, timer and stale-preview
warnings, and the deletion-count explanation now use the catalogs.
The restore screen now maps cancellation, unlock failure, cleanup failure,
and other errors to catalog keys. Low-level format errors use a general
localized message, while an unlock failure still prompts users to check the
password and file.
Export phase labels, cancellation controls, and encrypted-byte progress now
use the 17 Flutter catalogs. Save outcomes and file-picker errors also use
catalog keys. Export instructions, password validation, and dynamic summary
text also use the catalogs. Backup restore review and other surfaces remain
open. Further localization commits are being kept local for a single final
GitHub Actions validation, per owner instruction.
Shared dialog actions, screen titles, map retry text, and zoom tooltips now
reuse existing catalogs at 25 points across 14 Flutter screens.
The in-app Help title, search, empty state, version label, tooltips, and all
eight topic titles are translated. The timekeeping, backup and location
history articles now have translations across all current catalogs and Help
search uses their localized bodies. Job board, reporting, and billing Help
articles now also use the catalogs. The automation and Android widget
articles complete the eight-topic Help catalog migration for the current 17
locales. Device verification is deferred to the final Actions run.
The Flutter widget configuration screen now uses translated picker labels,
instructions, save actions and recovery text across all current locales.
Shared business-command retry, unknown outcome, stale state, missing widget,
and other common failures are now catalog-backed. Feature-specific conflict
messages and validation errors still need migration.
Sixteen additional shared action, report, review, and archive labels are
translated across the 17 current catalogs and used in 15 Flutter files.
Work and business reports now use catalog entries for their titles,
refresh actions, job and customer filters, and business export actions.
Financial totals and the work report's finalized-time and billable-task
sections now use translated labels and empty states. Remaining dynamic
report messages still need migration. Margin and cost-count summaries,
financial activity headings, map sample totals, and exclusion statuses
now use translated templates.
Work report task prices, hourly rate breakdowns, rounding guidance, and
billed quantities also use catalog entries and placeholders.
Work and financial report export success, cancellation, and save failures
now resolve through the 17 current catalogs.
Job archive, restore, and permanent deletion now use catalog-backed labels
and warnings. The deletion dialogs share the detailed retention notice.
Settings and Job settings now use translated company and customer fields,
currency and tax labels, hourly billing labels, and invoice style controls.
Billing defaults, hourly policy choices, inherited tax and minimums, and
currency-labelled job overrides now also use the catalogs. Additional
descriptions and fields remain open. Tax, minimum hours, minimum task fee,
and receivables email validation now show translated feedback in Settings
and job preferences where applicable. Quote tax validation shares the same
messages.
Global and per-job location recording now use the catalogs for options,
privacy explanations, and map provider and worksite navigation labels.
Map provider selection, field labels, save controls, and reset dialog
actions now use the catalogs. Credential guidance, privacy notices, and
general save/reset failures are translated. The URL guidance preserves its
three literal tile tokens. The map provider input validator now returns
catalog keys for its specific URL, attribution, and credential errors.
The Google Maps SDK note, report map location history, timezone, accuracy,
privacy and unavailable-map notices now use the Flutter catalogs.
Quote and invoice document screens now localize their save, share, email and
page navigation controls, along with invoice copy titles and quote image
accessibility labels. Their save and cancel outcomes, email draft prompts,
and handoff failures now also use the catalogs. Quote and invoice editors,
generated PDFs, and app-generated email text remain in localization scope.
Quote draft line types, currency, optional links and line-add actions are
catalog-backed. Actual-work invoice preparation now localizes its title,
review actions, and current-tax and current-price override labels.
Native quote and invoice historical notices, historical email subject
prefixes, attachment labels, and share chooser titles now use localized
Android resources. Stored PDFs and saved email template expansions are
preserved. New quote and invoice PDFs and the Settings sample use localized
document titles, party labels, issue dates and page footers. Already cached
PDF bytes are reused. For a document whose PDF has yet to be cached, the
first render uses the device's current effective app language. Line-item
headings, amount fallbacks, subtotal, tax, total, payment state, balances,
credit labels, and cancellation notices use translated Android resources.
The PDF renderer reserves width for translated total labels before drawing
exact amounts. Default email templates, payment event descriptions stored
in snapshots, and rendered PDF locale and right-to-left verification remain
open.
Flutter and Android now take the set of registered locales from the same YAML
registry. Generation checks for missing or extra catalogs on both platforms
and supports language, script and region tags.
Flutter message keys and English fallback copy are now generated from the
English YAML catalog. GitHub Actions is configured to reject stale generated
sources and continues to check catalog keys and placeholders.
Remaining Flutter screens, Help, notifications, and generated document
copy are still pending. See [translation instructions](localizations/README.md).

The report below preserves earlier evidence and milestone history.

**M6.5 source and CI are complete.** Exact head `c9d20cc` / Actions run
[43610282595](https://github.com/centerionware/Clockrove/actions/runs/43610282595)
passed 35 actual Android tests on each of API 24 and 35, including task media,
GPS settings and the 49-sample map route. PR #45's later exact head `942659a` /
run [43626467570](https://github.com/centerionware/Clockrove/actions/runs/43626467570)
also passed the full API 24/35 suite and was merged as `69e1dab`. Quote customer
management and the report-map fix are included. Physical owner-device map, GPS
and camera acceptance remains pending.

**M6.6 source and CI are complete.** PR #49 exact-head run
[43665273986](https://github.com/centerionware/Clockrove/actions/runs/43665273986)
and merged-main run
[43667760290](https://github.com/centerionware/Clockrove/actions/runs/43667760290)
passed the full matrix. The requested billing, costs, reports and provider
settings are implemented; physical owner-device acceptance remains pending.
The earlier PR #45 native-test failure
[43590405687](https://github.com/centerionware/Clockrove/actions/runs/43590405687)
and API-24 journey failure
[43595000940](https://github.com/centerionware/Clockrove/actions/runs/43595000940),
and PR #70's first attempt
[43798809470](https://github.com/centerionware/Clockrove/actions/runs/43798809470/attempts/1),
are superseded by the later passing exact-head evidence below. PR #45's
merged-main invoice failure
[43628166015](https://github.com/centerionware/Clockrove/actions/runs/43628166015)
explains its skipped Play delivery
[43630004858](https://github.com/centerionware/Clockrove/actions/runs/43630004858);
PR #70 fixed the PDF action, followed by the successful merged-main validation
and internal submission below. Their detailed history is retained.

M7 encrypted backup and safe merge are incomplete. M7.6's route inventory is
preparation only; its whole-app UI overhaul and exhaustive E2E/screenshots have
not started. Per the milestone order, finish M7 before starting M7.6, and pass
M7.6 before moving from internal to closed testing. Owner-device and upgrade
checks remain separate launch-readiness work under M8.2. iOS and future service
remain deferred.

M7.3's Settings restore route and screen passed exact-head validation.
Rebased head `e8dcf8f` / Validate run
[44027991208](https://github.com/centerionware/Clockrove/actions/runs/44027991208)
passed formatting, Analyze, Flutter behavior (285 passed, 14 skipped), Android
and release builds, 823 native tests with zero failures, and 38 actual Android
tests on each of API 24 and 35 with zero failures. PR #79 later passed a
data-changing Android Documents picker journey on API 24 and 35, including
authentication, conflict resolution and committed-state assertions. Itemized
add/change/deletion review, a close-time choice for imported open sessions,
full deletion-choice coverage, and owner-device acceptance remain open.
Details: [restore UI evidence](docs/plan/evidence/m73-restore-ui.md).

PR #70 fixed the invoice PDF action visibility failure. Exact head
`58ca416dfe8c3b82fff8d58898db9ca7acc86a26` passed Validate run
[43798809470, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43798809470/attempts/2),
including 38 actual Android tests each on API 24 and 35 with zero failures,
errors or skips. On exact main SHA `025cc10f5aac80ce6f56005f694e448ee4473761`,
merged-main Validate run
[43804094163](https://github.com/centerionware/Clockrove/actions/runs/43804094163)
passed. Play delivery run
[43806883748](https://github.com/centerionware/Clockrove/actions/runs/43806883748)
then accepted internal submission of `0.1.0-internal.53.1` / version code
`216968203` on that SHA. This confirms upload/submission, not review approval,
tester availability or closed-track readiness. Details: [invoice PDF action
evidence](docs/plan/INVOICE_PDF_ACTION_VISIBILITY.md).

Current M7 priority: PR #52 merged to `main` as `cc6870931920016e7ee9bc4f5fc4be6e96b26523`. Its exact PR head `defb4e6` / Actions run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557) passed formatting, analysis, Flutter behavior tests, Android/instrumentation builds, 635 native tests, invoice-viewer and release-tooling checks, and 38 actual Android tests on each of API 24 and 35 with zero failures/errors/skips. The 1 MiB benchmark reported encrypt/decrypt 755/301 ms on API 24 and 869/273 ms on API 35; envelope size was 1,048,648 bytes on both. PR #59's 64 MiB crypto-only benchmark [43700311041](https://github.com/centerionware/Clockrove/actions/runs/43700311041), head `02931e5`, measured peak VmHWM of 823,572 KiB on API 24 and 951,840 KiB on API 35; the full Dart export path retains more copies. Keep Settings export withheld pending streaming or a materially lower memory bound. PR #60 run [43702626041](https://github.com/centerionware/Clockrove/actions/runs/43702626041) passed formatting and analysis but failed Flutter behavior (223 passed, 5 failed, 14 skipped). Follow-up run [43705585212](https://github.com/centerionware/Clockrove/actions/runs/43705585212) passed formatting and analysis, then had 224 passed, 4 failed, 14 skipped; all remaining failures were `scrollUntilVisible` using a non-unique `Scrollable` finder. The PlatformException assertion and +0 retry-policy synthetic passed. The finder now scopes scrolling to the export ListView. Exact head `a6ba266` / run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) passed formatting, analysis, Flutter behavior, Android build, instrumentation assembly and bounded install-recovery synthetics, then failed 16/675 native tests. JUnit shows all 16 were `BackupImportStoreTest` preview failures: the active local time-claim overlap query referenced a local-only table on the staging SQLite connection, and the generic exception was surfaced as `INVALID_REVISION_GRAPH`. Rebased commit `f9b223f` moves each incoming-claim probe to the local database and adds an empty-preview regression; the existing real local-overlap test covers the overlap case. PR #60 rebased head `ead1a8518fda0f3f4d65da0ab7f7ed4ce1f3b158` / Actions run [43708093424](https://github.com/centerionware/Clockrove/actions/runs/43708093424) passed formatting, analysis, Flutter behavior (228 passed, 14 skipped), Android build, release Java compilation, instrumentation assembly, install-recovery synthetics, and the complete API 24/35 workflow. Native JUnit totaled 676 tests with zero failures, errors or skips, including two `NativeVisualPreviewTest` cases. The earlier run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) also included those two cases in its 675-test JUnit total; the new aggregate is not a count that excludes them. API 24 and API 35 each passed 38 Android tests with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt 878/280 ms on API 24 and 877/287 ms on API 35; each envelope was 1,048,648 bytes. Invoice viewer, artifact export, and locked release tooling passed. Commit `9dc1542` makes expiry rescheduling fail closed: it detaches the active snapshot, wipes the key, attempts artifact deletion and retries failed deletions on the next store interaction. Regression coverage injects a later scheduler failure and a deferred deletion failure. Exact-head run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843) passed on SHA `9dc154288111af1219166d5eaeeea8661dfee1aa`: formatting, analysis, Flutter behavior, Android build/release compile, instrumentation assembly, bounded install-recovery synthetic, 677 native tests (zero failures/errors/skips, including two `NativeVisualPreviewTest` cases), 38 actual Android tests each on API 24 and 35 (zero failures/errors/skips), invoice-viewer validation, artifact exports and locked release tooling. API 24 benchmark attempt 1 timed out before test start during APK install; recovery confirmed a changed emulator boot ID and boot completion, then the single retry passed. The 1 MiB benchmark measured encrypt/decrypt 742/273 ms on API 24 and 951/292 ms on API 35; both envelopes were 1,048,648 bytes. PR #58 exact head `a51fcd7` passed run [43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294) and merged as `fefdcdf`. Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644) passed on main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`. JUnit reports 38 actual Android tests on each API 24 and 35 with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt at 884/363 ms on API 24 and 1,105/343 ms on API 35; each envelope was 1,048,648 bytes. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) completed successfully on exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; the internal AAB upload/submission was accepted at 11:30:34 UTC. This proves upload/submission only, not review approval or tester availability. No VM build, test, formatter or analyzer ran.

Current Play-release regression: main Play run
`43701872698` on source `cc687093` failed at
`:app:compileReleaseJavaWithJavac` because the release-generated
`GeneratedPluginRegistrant.java` referenced dev-only `integration_test`, which
is not on the release classpath. The AAB build failed before upload; the Play
submission step was skipped, so this run did not upload internally. PR #58 exact
head `a51fcd7` / Actions run
[43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294)
passed the release-aware configuration and non-secret release Java compile
check, then passed the full device suite; it merged to main as `fefdcdf`.
Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644)
passed on `fefdcdf` with 38 actual Android tests per API 24/35 and zero
failures/errors/skips. Guarded Play delivery run
[43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483)
completed successfully for exact SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; internal AAB upload/submission was accepted at 11:30:34 UTC. This does not prove review approval or tester availability. Keep M8.2 pending for owner-device and upgrade evidence. Config-only and
Java compilation do not prove a complete release bundle or Play delivery.

PR #58's prior exact head `004c6de` / Actions run `43700127667` is terminal
failure at the API-24/35 real Android PDF/device stage. Formatting, analysis,
Flutter behavior tests, Android build and native unit tests passed; the device
journey failed at `locationRecordingDefaultCanBeTurnedOffAndBackOnInActualSettings`.
That head predates the release-config changes above, so a fresh exact-head run
must validate both the new release Java check and the existing device suite.

PR #45 exact code head `c9d20cc` / Actions run `43610282595` passed formatting,
analysis, all Flutter behavior tests, Android debug build, 614 native tests,
and full API-24/API-35 PDF, device and invoice-viewer stages. Downloaded XML
reports 35 actual Android tests on each API with zero failures, errors or skips;
the 49-sample map journey passed on both APIs. The quote billing journey verifies
that an Unclassified task prompts for Labor or Supplies, then saves the selected
classification, edited quantity and price. Later map-provider source at exact
head `040961a` passed formatting and analysis but failed Flutter behavior tests
(174 passed, 19 failed, 14 skipped); Android build, native and device stages did
not run. The quote Manage customers test passed in that run. Its map tests lacked
a deterministic provider-settings fake, and provider-form tests searched lazy
list children before scrolling; source-only fixture corrections are pending an
exact-head rerun. Google Maps is deferred beyond launch by the owner decision dated 2026-11-15; OSM/custom HTTPS owner-device acceptance remains open, and M6.6 is not complete. No VM build, test or formatter ran.

| Task | Status | Evidence / next action |
| --- | --- | --- |
| M1.1 Verify GitHub identity and remote | Verified | Escalated network check: user 1000 (`agent`) authenticates as `centerionware`; verified public centerionware/Clockrove after owner publication instruction |
| M1.2 Resolve writable Git metadata | Implemented locally | `scripts/git-local` uses ignored `.local/git`; status and ignore checks succeed without modifying protected root `.git` |
| M1.3 Preserve handoff | Preserved | AGENTS.md, app_plan.md and ignore rules retained |
| M1.4 Draft source/contribution rights | Drafted; owner review required | docs/SOURCE_TERMS_DRAFT.md, CONTRIBUTING.md; repository public by owner instruction; no effective license adopted |
| M1.5 README, architecture, dependency inventory | Drafted | docs/ARCHITECTURE.md and docs/DEPENDENCIES.md written; setup and architecture documented; Actions debug/AAB and native tests verified |
| M1.6 Install/pin compatible tools | Pinned tools verified in Actions | Flutter 3.47.9/Dart 3.13.7, JDK 17.0.17+10, SDK 36, Ruby 3.3.6/Bundler 2.5.23/Fastlane 2.228.0 and lockfiles; successful Actions setup/build/tests |
| M1.7 Android Flutter scaffold and lockfiles | Build verified in Actions | Agreed application ID; Actions locked resolution, formatting and analysis pass; debug APK built in run 42980838050 |
| M1.8 Nonprivileged validation CI | Verified foundation run | .github/workflows/validate.yml; pinned actions/tools; no publishing secrets; source-only diagnostic/lockfile artifacts |
| M1.9 Create public repository | Public, owner-authorized | https://github.com/centerionware/Clockrove; verified PUBLIC after explicit owner instruction; source terms remain draft |
| M1 acceptance | Technical foundation verified; rights draft pending | debug/AAB/native validation succeeded; public by owner authorization; effective source rights still require adoption |
| M2.1 SQLite/manual timer/widget/notification prototype | Native source and CI checks verified; device proof pending | Serialized commands, typed bridge, manual controls, widget and independent notification stop are on main; full physical journey remains unproven |
| M2.2 Location/geofences/departure/recovery prototype | Native feasibility in progress | Sample storage, FGS/permission adapter, measured status, departure persistence and qualified membership passed Actions and merged; policy, alarms and Android registration/callbacks merged in PRs #12–14; 86 native tests passed; control UI validation and device evidence remain |
| M2.3 OSM map feasibility | OSM source/synthetic CI verified; owner-device proof pending | Actions 43006507700 passed; PR #10 merged bb2a9eb with six reviewed previews. Physical owner-device acceptance of OSM/custom HTTPS behavior remains unproven; Google Maps is deferred beyond launch by owner decision dated 2026-11-15 |
| M2.4 Physical screen-off/battery validation | Pending owner device results | Owner identified Pixel 7 / Android 17 and verified main Settings saves after updating; screen-off/GPS/battery journeys remain unverified; docs/DEVICE_VALIDATION.md records the required checks |
| M3.1 Schema, migrations and typed contracts | Business/history/job contracts verified; full contracts in progress | Schema 5 plus PRs #19–28/#30/#31 merged; history Actions 43105996492 and job-command Actions 43115650224 pass; final business integration passed #29; provider settings and backup contracts remain |
| M3.2 IDs/revisions/tombstones/serialized commands | Business/history/job foundation verified; full merge integrity pending | Native serialized writer, ancestry, opaque receipts, deleted-ID fences and versioned job creation checked in migrations/board/lifecycle/history/invoice/creation tests; backup merge integrity remains |
| M3.3 Jobs/sites/boards/session corrections/queries | Native corrections/queries and history UI verified; broader contracts remain | PRs #16/#19–28/#30 on main; fourteen storage and eight bridge history cases plus six actual framework calendar cases passed; manual correction forms/history navigation remain |
| M4.1 Manual tracker UI and history | Manual history UI merged and Actions verified; full M4 acceptance pending | Native start/stop/switch, board/settings/customer/report/archive/invoice views on main; history #32 exact-head Actions 43123810250 passed: 242 native plus 28 actual Android cases; eight final captures match reviewed views; launcher/configuration and physical manual journey remain |
| M4.2 Widgets/configuration/notification actions | Responsive providers/setup/native actions merged, main verified and internal upload accepted | #33 final head 6cadef0 passed full 43158333007 and merged 8c2f3fd; main 43159664866 has 274 native and 20 actual tests per API (24/35), zero failures/errors/skips; final captures reviewed; delivery 43160879953 accepted internal.22.1 / 216624221; physical launcher/recovery acceptance remains |
| M5.1 Permissions/site editor/capacity/entry/departure | Native implementation and CI verified; physical acceptance pending | PRs #12–16 cover policy, capacity, qualification, permission controls, maps and precise current-location setup; actual device journeys remain |
| M5.2 Grace/re-entry/late events/pruning/health/recovery | Native transition and recovery checks verified; physical proof pending | Departure/alarm/geofence tests on main; #17 recovery passed 43101725472 and merged; screen-off/OEM/reboot/GPS evidence remains |
| M6.1 Boards and accessible moves | Implemented, integrated and Actions verified | PRs #20/#23 native card/column CRUD/reorder/completion and templates, drag plus accessible moves, reviewed light/dark previews; #29 refreshed move-dialog regression passed 43117726325 |
| M6.2 Date/list/totals/map/timezones | Implemented, integrated and calendar checks verified | PRs #22/#28 selected date/range/list/map/totals and exact billing on main; six report/map previews reviewed; #29 ICU midnight overlap/gap/skipped-date checks passed 43117726325 |
| M6.3 Provider settings/secure credentials/tile policy | Custom HTTPS raster provider and keyless OSM fallback integrated; API-24/35 emulator evidence passes; physical owner-device acceptance remains open | OSM fallback, encrypted Android Keystore storage, validated attribution/HTTPS/header settings, redirect blocking and URL-redacted transport errors are implemented. The early runs [43616875394](https://github.com/centerionware/Clockrove/actions/runs/43616875394), [43617956145](https://github.com/centerionware/Clockrove/actions/runs/43617956145) and [43619088354](https://github.com/centerionware/Clockrove/actions/runs/43619088354) exposed map-test fixture timing issues; those failures are historical and are not the current status. Later main validation [43673229775](https://github.com/centerionware/Clockrove/actions/runs/43673229775) passed the complete API-24/35 suite, including provider-settings and report-map journeys; screenshots show loaded OpenStreetMap tiles. Exact rerun [43687899077](https://github.com/centerionware/Clockrove/actions/runs/43687899077) and PR #51 run [43688694748](https://github.com/centerionware/Clockrove/actions/runs/43688694748) also passed. These are emulator/CI results. Owner decision dated 2026-11-15 defers Google Maps beyond launch; its SDK runtime-key setup and restricted-key/billing verification are not launch gates. Physical owner-device acceptance of OSM/custom HTTPS behavior remains open; emulator results do not prove owner-device operation. |
| M6.4 CSV and document sharing | Implemented and combined document checks passed | PRs #22/#26–28 safe work/GPS CSV, immutable PDF snapshots/styles, private URI grants and email/save/share UI; final logo/pagination/decoded-viewer checks passed 43117726325 |
| Business iteration: pricing and customers | Implemented and merged | PRs #21/#24 global hourly default/currency, job hourly/task override and customer management; exact native calculations and customer reference tests pass |
| Business iteration: archive/restore/delete | Implemented and integrated settings checks passed | PRs #25/#28 enforce continuous 30-day native retention, stopped timer guard, restore qualification and retained templates/customers; #29 adds eligible deletion directly in job settings |
| Business iteration: invoices and email | Renderer/handoff checks passed; priority template-crash fix delivered internally; physical email/device checks remain | PRs #26–28 real PDF styles/numbered immutable snapshots, report action and private default-email/save/share handoff; actual email-app device compatibility remains unproven |
| Business iteration: logo and final document previews | Merged #29; Actions and actual-document visuals verified | Private bounded PNG/JPEG/WebP import, metadata removal, immutable logo retention, three-style viewer and all-style multipage real-device gates; all three multipage styles and six decoded theme/style views reviewed |
| Invoice foundation: source integrity, tax, quantity and navigation | Merged #35 and #38; main validated and internal upload accepted | #35 passed 383 native / 44 actual Android. #38 c1595c7 / 43195277559 passed 385 native / 44 actual, zero failures/errors/skips; three resize cycles/live edit/exact row/board checks and reviewed captures. Merged 1c358bc; main 43197481528 passed 385 native / 44 actual; delivery 43199258376 accepted internal.25.1 / 216642056 at 10:25:48 UTC; tester/physical availability separate |
| Exact money | Validated and merged #35 | decimal 3.2.6 Actions lock; native BigInteger/BigDecimal and canonical TEXT; migration/receipt/rollback/purge, wide totals and all-style 1000-digit actual PDFs verified. Main 43197481528 / delivery 43199258376 accepted internal.25.1 |
| Payments, receipts, CC and void/replacement | Merged and main validated; internal upload accepted; physical email/upgrade pending | #37 plus maintenance #40/#41 on main 93673fb; full 43254952365 has 479 native / 50 actual Android, zero failures/errors/skips. Play 43257957626 accepted internal.28.1 / 216663874 at 16:29:35 UTC; installation/availability and physical email are separate |
| Follow-on: quotes and invoice detail | Merged #39; main validation passed and internal delivery accepted | PR exact-head run 43381832106 and main run 43384059350 passed the actual quote journey and 30 API-24/35 device tests each with zero failures/errors/skips. Keyboard-closed assertion passed before recording the customer decision. Play run 43385810795 accepted internal.29.1 / version code 216718138; physical install and tester availability remain separate. |
| Owner additions: invoice/quote PDF hierarchy and Settings preview | Merged #39; exact-head and main validation passed; generated visuals reviewed | Customer details left and company details/date right, each wrapped/paginated within 47% width; subtotal/tax rows right aligned and only amount digits bold. Preview invoice sits directly below invoice style in Settings, uses selected style/currency and logo with synthetic data in the native viewer, and creates/sends/reserves nothing. PR run 43379382717 passed and its Modern/Minimal/Classic invoices, party columns, and preview pages/screens on API 24/35 were reviewed. The evidence-doc head run 43381832106 and main run 43384059350 also passed; all five PDF-copy checks passed on each API. |
| PR #42 merged-main follow-up | Merged, main validated and internal upload accepted | Main Validate run 43389226159 passed 563 native and 30 API-24/35 actual Android cases, zero failures/errors/skips; downloaded XML inspected. Play run 43391432210 accepted `0.1.0-internal.30.1` / version code `216720875` at 08:19:21 UTC. Review approval, tester availability and physical installation remain separate. |
| M6.5 quote usability, task comments/images, map and GPS controls | Quote customer add/save/select passed Flutter CI; full API-24/35 device suite and 49-sample map journey passed; owner-device checks pending | Core exact head `81d227f` / Actions run [43489413018](https://github.com/centerionware/Clockrove/actions/runs/43489413018) passed 32 Android tests per API and reviewed camera/task/comment image captures. Internal run [43500590080](https://github.com/centerionware/Clockrove/actions/runs/43500590080) accepted `0.1.0-internal.31.1` / `216764535`. PR #45 adds 50 MB/250 MP source imports, 5 MB normalized photos, multi-select review/atomic attach, ordered swipe viewing, quote customer management and the report-map repair. The refreshed customer-selector test passed at head `3ce3e29` / run [43590405687](https://github.com/centerionware/Clockrove/actions/runs/43590405687). At head `372c15a` and rerun `0a06901`, the full shared Customers screen add/save/return/select test passed and confirmed the quote draft captures the customer ID. Run [43595000940](https://github.com/centerionware/Clockrove/actions/runs/43595000940) passed formatting, analysis, Flutter behavior, Android build, all 594 native tests and the first 25 real Android PDF tests; then the API-24 billing journey could not return to **Billing basis** after invalid tax input. Later device journeys and API 35 did not run. The 49-point map journey passed API 24 in run [43581546961](https://github.com/centerionware/Clockrove/actions/runs/43581546961). API-35 map and physical map/GPS/camera behavior remain unproven. No VM build, test or formatter ran. Exact code head `c9d20cc` / Actions run [43610282595](https://github.com/centerionware/Clockrove/actions/runs/43610282595) passed 35 real Android tests each on API 24 and 35 with zero failures/errors/skips, including task media, GPS settings and the 49-sample map route; captures from both APIs were reviewed. The quote Manage customers button is directly below the selector and uses the shared Customers screen; the Flutter behavior test verifies add/save/return/select and the quote draft customer ID. Physical owner-device acceptance remains pending. |
| M6.6 remaining requested business features | Complete on exact head and merged main; physical owner-device acceptance pending | Hourly minimum/rounding, task minimum fee, Labor/Supplies classification, exact-money cost ledger, tax-preparation reports and secure map-provider settings are implemented. The history below records earlier exact-head failures and fixes. PR #49 merged as `aca488f`; exact-head run [43665273986](https://github.com/centerionware/Clockrove/actions/runs/43665273986) and merged-main run [43667760290](https://github.com/centerionware/Clockrove/actions/runs/43667760290) passed the full matrix. Main reports 614 native tests and 37 actual Android tests per API 24/35, zero failures/errors/skips. No VM build, test, formatter or analyzer ran. |
| Follow-on: invoice-only hourly billing modes/minimum hours and task-rate minimum fee | Merged #44; PR exact-head passed; main validation unresolved | Global defaults with independent per-job inherit/override: minimum hours then exact time, or round total time up to a full hour; task-rate minimum fee. Exact source times and reports remain unchanged. PR head `1a1a183` / Actions run [43571526697](https://github.com/centerionware/Clockrove/actions/runs/43571526697) passed all 32 actual Android tests on each API 24 and 35 and all 587 native tests. This run passes the location toggle off/on/save/reopen journey; API-24/35 switch-state captures were reviewed. Main run [43574603359](https://github.com/centerionware/Clockrove/actions/runs/43574603359) later failed at the invalid-tax-field scroll assertion, and Play delivery [43575491279](https://github.com/centerionware/Clockrove/actions/runs/43575491279) was skipped. The updated return-to-top gesture requires a new Actions result. No VM build/test/formatter. See app_plan.md §3.5a and docs/INVOICE_LIFECYCLE_PLAN.md. |
| Owner direction: first-use usability and widget appearance | Added to product contract; implementation/review pending | app_plan.md §3.0 and §3.2 plus docs/UI_DESIGN.md set the bar that common tasks need no training or guesswork, and every widget uses a transparent outer background with a visible border. Full-release review covers first-use journeys and Actions-rendered captures of all widget types/sizes against contrasting wallpapers. |
| Follow-on: task comments, supplies and costs | Comments/images merged #43; classification and cost ledger pass Actions source checks; actual Android journey pending | M6.5 implements private task/comment conversations and image attachments. M6.6 adds Labor/Supplies classification with legacy values retained as Unclassified, plus a native actual-cost ledger and UI for exact-money entries. Run [43599388270](https://github.com/centerionware/Clockrove/actions/runs/43599388270) passed all 602 native tests plus Flutter behavior and Android build checks. Supplies task labels never auto-book costs. Actual cost CRUD, migration, exact-money and paging cases passed; device coverage did not run because the earlier billing journey failed. Templates do not copy comments, attachments or costs. |
| Follow-on: company/tax-preparation reports | Source and tests underway; first exact-head run stopped in analysis | Main-menu Reports entry now opens a tax/financial screen with date/timezone, job/customer filters, separate currency summaries, issue/void sales, payment/correction cash, tax billed/collected, as-of outstanding, Labor/Supplies/Unclassified values, recorded direct/overhead costs, estimated profits/margins/cash surplus, historical drilldowns and CSV/PDF export. New-cost customer snapshots migrate without guessing historical attribution. CSV amounts are exact minor-unit integers as text; PDF has bounded page generation. Native reconciliation/date/currency/CSV-safety, Flutter filter/export, and on-device PDF rendering tests are added. Run [43604844157](https://github.com/centerionware/Clockrove/actions/runs/43604844157) passed formatting then Analyze found a missing-braces lint, so behavior/native/device tests did not run. The fix requires a new exact-head Actions pass. |
| M7.1 Crypto/KDF selection and device benchmark | 1 MiB benchmark and full-device PR #52 validation passed; maximum-size crypto-only peak memory is too high for enablement | PR #52 merged to `main` as `cc6870931920016e7ee9bc4f5fc4be6e96b26523`. Exact-head run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557) on `defb4e6` passed formatting, analysis, Flutter behavior tests, Android/instrumentation builds, 635 native tests, device tests, invoice-viewer checks and locked release tooling; JUnit reports 38 actual Android tests on each API 24 and 35 with zero failures/errors/skips. The 1 MiB benchmark measured 755/301 ms encrypt/decrypt on API 24 and 869/273 ms on API 35; envelopes were 1,048,648 bytes. PR #59 head `02931e5` / run [43700311041](https://github.com/centerionware/Clockrove/actions/runs/43700311041) exercised the 67,108,936-byte envelope: API 24 measured encrypt/decrypt 12,593/12,134 ms, baseline/peak VmHWM 274,336/823,572 KiB; API 35 measured 13,746/12,211 ms, baseline/peak 409,484/951,840 KiB. This is crypto-only memory, before the full exporter copies. Keep user-facing export withheld pending streaming or materially lower memory. |
| M7.2 Encrypted archive/schema/preview and user-directed export | Encrypted archive, schema boundary, codec, SAF bridge, Settings export and user-facing streamed restore are integrated; Settings export remains withheld pending full-path memory evidence; M7.3 conflict coverage and M7.6 remain incomplete | PR #71 exact head `def60fad` passed Validate run [43885189418, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43885189418/attempts/2): 732 native tests and 29 actual Android tests on each of API 24 and 35, all with zero failures/errors/skips. PR #74 added Settings restore/export and passed its exact-head source/device matrix. PR #79 exact head `04fb070` / [run 45081595207](https://github.com/centerionware/Clockrove/actions/runs/45081595207) passed the real system-picker data-changing restore on both APIs, as well as near-limit replay; merged as `4be7b83`. Merged-main validation [45084756566](https://github.com/centerionware/Clockrove/actions/runs/45084756566) passed. The maximum-size crypto-only RSS still blocks enabling Settings export. Full evidence: `docs/plan/status-history/validation-history-06.md`, `docs/plan/evidence/app-plan-evidence-04.md`, and [restore UI evidence](docs/plan/evidence/m73-restore-ui.md). |
| M7.3 Conflict/ancestry/tombstone/transactional merge | Incomplete; Settings/SAF route and Dart apply coordinator are integrated; a synthetic data-changing Android restore passed on API 24 and 35; deletion choices and review coverage remain incomplete | PR #75 adds causal local-preservation choices for eligible deletion conflicts and passed Actions run [44012769802](https://github.com/centerionware/Clockrove/actions/runs/44012769802), with 741 native tests. Opposite-side delete/restore adapters remain absent for job, site, column, card, session, template, automation, tax/money/cost, billing/location policy, quote draft, and logo kinds; one generic destructive adapter is unsafe across foreign-key/cascade, media, timer, and immutable-trigger behavior. PR #74 exact-head run [44027991208](https://github.com/centerionware/Clockrove/actions/runs/44027991208) passed its Settings restore route tests. PR #79 exact-head run [45081595207](https://github.com/centerionware/Clockrove/actions/runs/45081595207), on merged PRs #74/#75, passed an authenticated Documents picker restore with explicit conflict resolution and committed-state checks on API 24 and 35. Preview still cannot itemize all additions/changes/deletions, and imported open sessions lack an explicit close-time choice. Owner-device acceptance and M7.6 remain open. Evidence: [M7.3 native deletion-resolution follow-up](docs/plan/evidence/m73-native-deletion-resolution.md) and [restore UI evidence](docs/plan/evidence/m73-restore-ui.md). |
| M7 backup and safe merge | Integrated and data-changing restore is Actions-validated; milestone remains incomplete before closed-testing gate | PRs #71/#74/#75/#79 integrate the encrypted archive, Settings export/restore UI, native transactional merge, and device picker journey. The synthetic fixture test confirms authentication, conflict resolution, committed session/site state, stopped timer, and automation disabled after import on API 24 and 35. Remaining contract work includes itemized preview and open-session resolution, table-specific deletion choices, owner-device acceptance, and M7.6 whole-app UI/E2E coverage. Settings export remains withheld pending the full-path memory gate. |
| M7.6 full UI overhaul and exhaustive E2E/screenshots | Required after M6.5/M6.6/M7; inventory prepared, implementation not started | `docs/M7_6_UI_INVENTORY.md` records the current route/screen/option/state surface as preparation only. After remaining launch features are complete, overhaul first-use UX across the entire app and add actual-app journeys that assert native persistence/side effects and reviewed screenshots for every inventory item on API 24 and the current Play-supported stable API, including fresh install/upgrade, themes, large text, cancellation/retry and failure paths. This remains the mandatory gate before moving from internal to closed testing. Cloudflare Pages documentation remains a full public-release deliverable. |
| M8.1 Accessibility/privacy/support/store assets | Pending | Complete launch features first |
| M8.2 Device/dependency/release/upgrade validation | PR #58 release validation and PR #61 current-main validation/internal delivery passed; owner-device install, upgrade and full launch matrix remain pending | PR #58 exact head `a51fcd7` passed [43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294), including release-aware configuration and no-signing release Java compilation; it merged to main as `fefdcdf`. Merged-main Validate [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644) passed with 38 actual Android tests on each API 24/35 and zero failures/errors/skips. Main Play run [43701872698](https://github.com/centerionware/Clockrove/actions/runs/43701872698) failed release Java compilation because the generated registrant included dev-only `integration_test`; it did not produce or upload an AAB. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) accepted the internal AAB submission for `fefdcdf` at 11:30:34 UTC; tester installation, owner physical installation, upgrade and full launch-feature matrix remain unproven |
| Initial Play setup AAB (owner request) | Delivered | Signed artifact 13839295891 / successful run 42984008259; expires 2026-11-12 08:08 UTC; permanent signing secrets saved |
| M8.3 Protected workflows/Fastlane/internal delivery | PR #61 exact-main guarded internal upload/submission passed; review approval, tester availability and production remain pending | Main 93673fb / 43254952365 passed; Play 43257957626 accepted internal.28.1 / 216663874 at 16:29:35 UTC. On 2026-11-15, unchanged-head main run 43687899077 attempt 2 passed on a22fe28. Play run 43691044331 accepted internal.40.1 / versionCode 216891797 from a22fe28 after main had advanced to 8a0e340. Full current-main validation 43690672187 passed at 08:01:45 UTC; API 24 and 35 artifacts each report 29 base instrumentation plus 9 other device cases, zero failures/errors/skips. Play run 43692578396 then submitted exact main SHA 8a0e340 as internal.41.1 / versionCode 216893018, accepted at 08:07:56 UTC. Main Play run [43701872698](https://github.com/centerionware/Clockrove/actions/runs/43701872698) later failed before AAB upload at release Java compilation due to dev-only `integration_test` registration; no internal upload occurred. PR #58 exact head `a51fcd7` / run [43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294) passed and merged as `fefdcdf`; merged-main Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644) passed with 38 actual Android tests per API 24/35 and zero failures/errors/skips. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) completed successfully on exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; the internal AAB upload/submission was accepted at 11:30:34 UTC. This proves upload/submission only, not review approval or tester availability. Owner installation/availability and production remain separate |
| M8.4 Closed testing/production eligibility/paid release | Pending owner setup and M7.6 gate | Do not move from internal to closed testing until all launch features and M7.6 pass. Upload/submission/approval/live evidence remains separate. |
| M9 iOS | Deferred | After Android launch |
| Future service | Deferred | Not a launch task |

### 2026-11-15 — M7.1 first Actions static-check feedback

Branch `m7-encrypted-backup` / head `3af11b6` was pushed only to run the
repository's validation workflow; no publishing workflow or store secrets were
invoked. Actions run [43664789329](https://github.com/centerionware/Clockrove/actions/runs/43664789329)
resolved the pinned dependencies and exported the proposed `pubspec.lock` and
formatter patch. Formatting identified line wrapping in the crypto source and
tests; that exact CI patch is applied. Analyze then found two compile-time
issues: the Android benchmark used `SizedBox` without importing Flutter widgets,
and the decrypt worker passed a `List<int>` where `TransferableTypedData` needs
`TypedData`. Both are corrected. Since Analyze and the required clean-format
gate failed, no behavior tests, Android build, native tests, device journeys or
API 24/35 crypto benchmarks ran. Commit the generated lock and corrections,
then rerun the full workflow with enforced lockfile resolution. No build, test,
formatter or analyzer ran on the VM.

The locked rerun at head `8587722`, Actions run
[43665136368](https://github.com/centerionware/Clockrove/actions/runs/43665136368),
passed locked dependency resolution and formatting. Analyze stopped the run on
one informational lint treated as an error: the benchmark's `dart:typed_data`
import was unnecessary because `flutter/foundation.dart` already exported
`Uint8List`. The import is removed. No Flutter tests, Android build, native
tests, actual-device journeys or crypto timing benchmark ran. The shared local
branch also contains the separate API-35 device-input helper commit
`6fc1efe`; it is not part of remote PR #49 validation and must not be mistaken
for M7.1 validation. The next action is a locked Actions rerun from the updated
M7 branch. No build, test, formatter or analyzer ran on the VM.

### 2026-11-15 — M7.1 instrumentation dependency conflict

Locked Actions run [43665431324](https://github.com/centerionware/Clockrove/actions/runs/43665431324)
on `4886b48` passed locked dependencies, formatting, analysis, Flutter behavior
tests, Android debug build and native unit tests. It failed in
`:app:mergeDebugAndroidTestAssets`: the Android test graph requested
`androidx.test:runner:1.7.0` while Gradle consistent resolution constrained it
to 1.3.0 from the `integration_test` debug runtime graph. The API-24/35 device
journeys and crypto integration benchmark did not run. The source now aligns
the debug graph to runner 1.7.0 and adds `:app:assembleDebugAndroidTest` before
emulator setup so this boundary is checked earlier. Commit `f3596aa` is pushed
to `m7-encrypted-backup`; locked Actions run
[43667170760](https://github.com/centerionware/Clockrove/actions/runs/43667170760)
completed. It passed the dependency, clean-format, analyze, Flutter behavior,
Android build, native unit-test and `assembleDebugAndroidTest` gates. API 24
passed 28 actual Android tests and the 1 MiB crypto benchmark (878 ms encrypt,
284 ms decrypt). API 35's 28-case actual-app suite recorded five failures and
one error: MainActivity captured as a blank white screen during Settings, map
and widget journeys. Its logcat shows Flutter engine startup and viewport
metrics but no Dart exception or fatal crash; the API-35 crypto benchmark did
not run. Downloaded device artifacts and XML were inspected. M7.1 is partially
verified, not complete. No build, test, formatter or analyzer ran on the VM.

### 2026-11-15 — M7.1 API-35 device failure and M7.2 codec source

The API-24 suite and benchmark passed, but integration_test rebuilt the shared
`app-debug.apk` with its benchmark entrypoint. The API-35 loop then installed
that APK, explaining its blank Flutter screen and missing app assertions. The
CI script now copies the real debug and native instrumentation APKs to immutable
`RUNNER_TEMP` paths before either emulator starts, and installs those copies on
each API. This cause is diagnosed; the fix still needs a full Actions rerun.
The API-24 benchmark passed, while API 35 must rerun before M7.1 acceptance.

Started the deterministic binary M7.2 codec in
`lib/backup/backup_archive.dart` with typed SQLite scalars, raw media bytes,
SHA-256 media descriptors, exact 64 MiB final-plaintext accounting, preflight
size limits, canonical row/field/blob ordering, duplicate-key checks,
schema-versioned table allowlist and iterative revision-DAG validation. The
native snapshot currently reports schema 15; range tombstones are allowed only
for schema 16. Native blob IDs are HMAC-derived from snapshot ID and global row
ordinal, so they are unique across rows/tables. Non-null BLOB columns are
separate descriptors; null BLOB columns remain scalar nulls. Added adversarial
codec tests for round-trip/determinism, exact bounds, malformed versions,
duplicate fields/rows/media, corruption/truncation, revision cycles and deep
ancestry. Tests have not run yet.

Moved the existing clean-formatting failure gate in
`.github/workflows/validate.yml` to immediately after formatter correction
export/annotation and before Analyze/build/emulator work. Failing formatting
still exports the patch; clean heads still run the full required suite. This
workflow change has not run in Actions yet. The owner VM remains limited to
source editing and read-only review; no build, test, format or analysis ran
there.

## Active quote milestone task breakdown

| Task | Current evidence / status | Remaining gate |
| --- | --- | --- |
| Q1 Draft editor, exact inputs, Q-1000 sequence | Fresh head 5a71a9c full Actions passed; decimal inputs, quantity, tax, invalid draft and Q-1000 actual issue journey | None for this task |
| Q2 Immutable issue/revision/date/filter/history | Fresh native/Flutter/API checks and actual issue/history journey passed | None for this task |
| Q3 Explicit local acceptance/decline | Fresh native/Flutter and API-24/35 actual acceptance journey passed | None for this task |
| Q4 Three quote PDF styles, revisions, wide amounts, email/URI/preview | Verified on API 24/35; three-style party hierarchy visually reviewed | Run 43373569280 passed all five real PDF/copy cases on each API; reviewed the generated three-style party-layout and sample-preview pages, including wrapped long contacts and right-aligned financial totals. |
| Q5 Reviewed fixed/hourly mixed scope and durable task reuse | Fresh native and actual API-24/35 planning/task completion journey passed without creating work before completion | None for this task |
| Q6 Actual-work selection and accepted/current prices/tax/party | Fresh native and actual API-24/35 invoice journey passed; accepted/current values and customer/tax selection covered | None for this task |
| Q7 Exclusive claims, replay, paid exclusion, rollback | Fresh native race/claim checks and actual duplicate-exclusion journey passed on API 24/35 | None for this task |
| Q8 Retained quote/line provenance through replacement and purge | Fresh native replacement/purge/late-write checks, actual related-invoice navigation and source quote retention passed | None for this task |
| Q9 Released v8 → unreleased v9 migration | Frozen v8 fixture and late-install rollback passed in the 563-test native Actions gate 43379382717 | Freeze v9 before subsequent schema work after release |
| Q10 Real issue/accept/plan/complete/invoice UI journey | Merged #39; PR and main Actions runs passed 1/1 on API 24/35 | Runs 43381832106 and 43384059350 passed the full journey and post-review keyboard assertion |
| Q11 Actual accepted-price invoice PDFs/text/To/CC/bytes in three styles | Merged #39; actual-copy checks and generated pages passed on API 24/35 | Runs 43379382717, 43381832106 and 43384059350 passed PDF-copy and quote invoice checks; all three styles visually reviewed |
| Q12 Phone accessibility and responsive review | Artifact 13997334053 reviewed at 390×844 / 1.8 scale in light/dark; invoice previews and pages visually reviewed on API 24/35 | Actions visual gate passed; physical owner acceptance remains separate |
| Q13 Merge/main/internal delivery | PR #39 merged as 8da7113; main run 43384059350 passed; Play internal upload accepted | Delivery run 43385810795 submitted `0.1.0-internal.29.1` / code `216718138`; review approval, tester availability and physical installation remain separate |

## Current owner-reported bugs and final-release plan additions

| Item | Status | Evidence / next action |
| --- | --- | --- |
| Email invoice handoff always reports failure | Source fix implemented; full Android/device acceptance pending | Email used `ACTION_SEND` with an `ACTION_SENDTO` selector while attaching a PDF; changed it to attachment-capable `ACTION_SEND`. The validated source head f088d0e passed formatting, analysis, Flutter behavior, Android build and native test steps; the complete device stage has not passed. Owner device acceptance remains required. |
| Report map is blank with 49 recorded samples | Root cause reproduced in API-24 Actions; source fix and API-24/35 rerun pending | Run 43579662428 captured the actual red-screen exception: `Unsupported operation: Cannot modify unmodifiable map`. `NetworkTileProvider` receives a `const` headers map, then flutter_map mutates it to inject the package User-Agent. The provider now creates a fresh mutable headers map. The same run added a real job → report → map journey with 49 synthetic persisted samples and deterministic failed-tile widget coverage, but the new device journey stopped at the reproduced exception. A later WidgetHost assertion also failed; its relationship is unconfirmed. The map journey now closes its activities and removes its synthetic job/session in `finally`. Need exact-head Actions on API 24/35 and inspect captured maps before claiming repair. |
| Manual clock-in does not record GPS | Source fix implemented; full Android/device acceptance pending | TimerHostApi now requests the foreground location service after a committed manual start/switch when permission exists; service also requests one fresh real fix and keeps 10-minute sampling. The current full Actions device stage does not pass; native/device delivery evidence remains pending. |
| Small worksite radius | Source/schema change implemented; owner-device transition acceptance pending | Radius supports 1.8–10,000 m, new sites default to 150 m, and the v9 migration rebuilds old site constraints without changing site records. Android geofences under 100–150 m remain best-effort. The owner's 150 m home test eventually auto-started; their manual stop while still inside did not test departure. |
| Task and task-comment images | Moved into M6.5; increased-limit and multi-photo source/UI/device coverage pending Actions | Existing-image selection supports up to 10 items per batch; the complete batch is reviewed and committed atomically after explicit **Attach photos** acceptance. Source limit is 50 MB/250 MP; each normalized private PNG is at most 5 MB, with 30 MB per selection, 20 per task and 100 MB per job. Camera photos retain review/retake/cancel. Full-screen viewing starts at the chosen image and swipes chronologically across task and comment photos. Migration, count/size, batch atomicity, metadata stripping, removal and parent cleanup require exact-head Actions evidence. |
| Full app UI/E2E screenshots and Cloudflare Pages documentation | Split into M7.6 and final public-release docs stage | The complete end-to-end inventory and reviewed per-screen/option screenshots now gate closed testing. Static user guides on Cloudflare Pages remain part of the full public release. |

Actions run 43341316880 stopped in Analyze: formatting emitted a patch for three
files, and analysis found the unsupported `BaseRequest.abort()` call in the tile
timeout handler. Applied those CI-reported formatting changes and removed the
unsupported call. Follow-up run 43342043446 passed formatting and analysis, then
reported 155 Flutter tests passed, 3 failed and 14 skipped. Two failures were
site-radius assertions still expecting the old 150 m minimum/default; updated to
the new 1.8 m minimum and 30 m default. The third synthetic map-outage test did
not force its fake image through Flutter's decoder before asserting the tile
failure callback; added the deterministic decode step already used by the other
map-outage test. Android build, native and real-device steps did not run. A fresh
exact-head Actions run is required. No local build/test/formatter was run, per
owner instruction. Run 43343448664 then stopped at analyzer because the added
`dart:typed_data` import was redundant with `flutter/services.dart`; removed it.
Run 43343863070 passed formatting and analysis, then reported 156 Flutter tests
passed, 2 failed and 14 skipped. The invalid-radius fixture still entered 149 m,
which is now valid, so it now enters 1.79 m. The site-map outage test did not
accept the new explained current-location prompt before trying to locate the
map; it now explicitly confirms the prompt before inducing a tile decode error.
Android build/native/device steps did not run. Quote run 43337896079 passed at
d03992c before these fixes were added and therefore does not validate the current
worktree. Run 43344356175 then passed formatting, analysis, Flutter behavior and
the Android debug build, but native tests reported 563 completed with one failure:
the new `TimerHostApiTest` fake messenger inherited Flutter's unsupported default
background-task-queue method, and teardown then hit an uninitialized host. Added
an inline test task queue and null-safe cleanup. API-24/35/device and later gates
did not run. A new exact-head Actions run is required; no build/test/formatter ran
on this VM.

The full quoted milestone remains incomplete until its integration gates pass.
Physical owner GPS/widget/upgrade/email and launch/legal/store gates remain tracked
above; emulator and native fixtures do not establish them.

## Archived validation history

The chronological validation record is preserved in the following files, split at entry boundaries so each remains within the maintained-file limit.

- [Continuation — JDK and CI foundation through OSM prototype merged with complete code validation](docs/plan/status-history/validation-history-01.md)
- [Durable geofence policy and native event transaction source through Versioned job creation and strict timer transport — source prepared](docs/plan/status-history/validation-history-02.md)
- [Combined iteration exact-head acceptance and main merge through Preview checks pass; Classic pagination correction and native legacy review prepared](docs/plan/status-history/validation-history-03.md)
- [Native legacy reconciliation and tax PDFs pass; full review UI source prepared through Main settings-field reveal recovery — source awaiting Actions](docs/plan/status-history/validation-history-04.md)
- [Invoice navigation follow-up through 2026-11-14 — exact-head PR #45 validation passes](docs/plan/status-history/validation-history-05.md)
- [2026-11-14 — second settings visit and PR #45 merge through 2026-11-15 — owner decision: Google Maps deferred beyond launch](docs/plan/status-history/validation-history-06.md)

See [maintainability-audit.md](docs/plan/maintainability-audit.md) for oversized source/test files and their safe split sequence.
