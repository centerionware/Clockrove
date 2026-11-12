# Clockrove task status

Updated 2026-11-12. Product contract: [app_plan.md](app_plan.md).
Statuses describe evidence, not intent. Android launch requires milestones 1–8;
iOS and future service remain deferred.

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
| M2.3 OSM and runtime Google-key experiment | OSM source/synthetic CI verified; runtime proof pending | Actions 43006507700 passed; PR #10 merged bb2a9eb with six reviewed previews. Live OSM/device rendering and Google runtime-key initialization remain unproven |
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
| M6.3 Provider settings/secure credentials/tile policy | Pending | Outage must preserve manual functionality |
| M6.4 CSV and document sharing | Implemented and combined document checks passed | PRs #22/#26–28 safe work/GPS CSV, immutable PDF snapshots/styles, private URI grants and email/save/share UI; final logo/pagination/decoded-viewer checks passed 43117726325 |
| Business iteration: pricing and customers | Implemented and merged | PRs #21/#24 global hourly default/currency, job hourly/task override and customer management; exact native calculations and customer reference tests pass |
| Business iteration: archive/restore/delete | Implemented and integrated settings checks passed | PRs #25/#28 enforce continuous 30-day native retention, stopped timer guard, restore qualification and retained templates/customers; #29 adds eligible deletion directly in job settings |
| Business iteration: invoices and email | Renderer/handoff checks passed; priority template-crash fix delivered internally; physical email/device checks remain | PRs #26–28 real PDF styles/numbered immutable snapshots, report action and private default-email/save/share handoff; actual email-app device compatibility remains unproven |
| Business iteration: logo and final document previews | Merged #29; Actions and actual-document visuals verified | Private bounded PNG/JPEG/WebP import, metadata removal, immutable logo retention, three-style viewer and all-style multipage real-device gates; all three multipage styles and six decoded theme/style views reviewed |
| Invoice foundation: source integrity, tax, quantity and navigation | Merged #35 and #38; main validated and internal upload accepted | #35 passed 383 native / 44 actual Android. #38 c1595c7 / 43195277559 passed 385 native / 44 actual, zero failures/errors/skips; three resize cycles/live edit/exact row/board checks and reviewed captures. Merged 1c358bc; main 43197481528 passed 385 native / 44 actual; delivery 43199258376 accepted internal.25.1 / 216642056 at 10:25:48 UTC; tester/physical availability separate |
| Exact money | Validated and merged #35 | decimal 3.2.6 Actions lock; native BigInteger/BigDecimal and canonical TEXT; migration/receipt/rollback/purge, wide totals and all-style 1000-digit actual PDFs verified. Main 43197481528 / delivery 43199258376 accepted internal.25.1 |
| Immediate next: payments, receipts, CC and void/replacement | Draft #37; baseline ledger/copies verified, new UI/layout and lifecycle source under validation | c21ef87 / 43201426802 passed 434 native / 46 actual Android. 7d414ee / 43207931777 passed Flutter/debug/440 native; API-24 copy one-page assertion failed before payment UI/API35. Layout corrected; native atomic cancellation/replacement/strict bridge prepared. 551a748 / 43210091600 found an Int/Float renderer compile mismatch, now corrected alongside late-rollback/purge regressions; new Actions evidence required. Lifecycle editor, actual VOID/replacement PDFs/UI and full increment acceptance remain open |
| Follow-on: quotes | Planned after invoice foundation | Independent Q-1000 numbering, issued revisions, acceptance, three PDF styles, reviewed job/task/invoice mapping without fabricated work or duplicate charges |
| Follow-on: task comments, supplies and costs | Planned | Private comments/history; simple Supplies toggle; charge classification separate from recorded purchase/labor/overhead costs; template copies do not duplicate history or expenses |
| Follow-on: company/tax-preparation reports | Planned after financial data foundation | Main-menu Reports icon; invoiced sales, cash, separate tax, outstanding, categories, actual costs, estimated profit/margins, dates/currencies and export/drilldown reconciliation |
| M7.1 Crypto/KDF selection and device benchmark | Pending | Pinned maintained implementation and bounded envelope required |
| M7.2 Encrypted archive/schema/preview | Pending | Authentication before parse; explicit open-session choices |
| M7.3 Conflict/ancestry/tombstone/transactional merge | Pending | Full integrity and adversarial test matrix required |
| M8.1 Accessibility/privacy/support/store assets | Pending | Complete launch features first |
| M8.2 Device/dependency/release/upgrade validation | Partial CI evidence; launch validation pending | Debug/release bundles pass Actions; physical installation, upgrade and full launch-feature matrix remain unproven |
| Initial Play setup AAB (owner request) | Delivered | Signed artifact 13839295891 / successful run 42984008259; expires 2026-11-12 08:08 UTC; permanent signing secrets saved |
| M8.3 Protected workflows/Fastlane/internal delivery | Invoice/quantity/tax/widget upload verified; launch gates remain | Main 43197481528 and delivery 43199258376 succeeded for 1c358bc; Play accepted 0.1.0-internal.25.1 / 216642056 at 10:25:48 UTC; permanent key and automatic allocation verified; production/device/store gates remain |
| M8.4 Closed testing/production eligibility/paid release | Pending owner setup | Separate upload/submission/approval/live evidence required |
| M9 iOS | Deferred | After Android launch |
| Future service | Deferred | Not a launch task |

## Validation record

2026-11-11: Previous state was a documentation-only handoff (no previous
implementation goal turn to classify). This turn makes concrete foundation progress.
`uname -m`: aarch64. `id`: uid 1000 agent. Initial sandbox network checks failed
DNS; network-enabled `gh auth status` succeeds. `gh repo view
centerionware/Clockrove` reports repository absent. No repository was created.
Flutter SHA-256 matches the official manifest. Bundled x64 Dart cannot run on
ARM64; matching ARM64 Dart archive SHA-256 is
`887cbb763bc4a1d6f3d7c34a6ea08f0f05211807abf98c06d6e4263907ccac1a`.
Root `.git` remains untouched. Alternate metadata status lists only public source;
private local skills/tools/metadata pass `check-ignore`. No Android tests, build,
physical checks, CI runs, uploads or publication have passed.

Flutter CLI rebuild succeeded: version 3.47.9 / Dart 3.13.7.
`flutter create --platforms=android --org io.github.centerionware --project-name
clockrove --android-language kotlin --no-pub .` succeeded. Generated counter demo
was replaced with a clearly unfinished welcome screen. OS backup disabled; release
debug-signing fallback removed. `flutter pub get` resolved 26 dependencies and wrote
pubspec.lock. `dart format lib`: 2 files, no changes. `flutter analyze`: no issues.
`flutter doctor -v` found existing SDK at /workspace/DreamPlayer/.toolchain-cache/android-sdk
(API/build-tools 36), but no JDK. Device discovery failed because adb could not
bind its listener in the sandbox; no device validation. `flutter build apk --debug`
failed: JAVA_HOME unset and java absent. Next action: install a pinned compatible
JDK locally, validate SDK tools/Gradle wrapper and Android build, then establish CI.

### Continuation — JDK and CI foundation

Previous goal turn: progress (scaffold, lockfile and analysis evidence).
Inspected live worktree before continuing. Found existing Temurin 17.0.20.1+1
ARM64 JDK outside PATH and copied it to ignored .tools/jdk without modifying the
other project. `java -version` succeeds. Upstream Adoptium metadata confirms the
exact release exists. Android compatibility notes select JDK 17 for AGP 9.1.
Explicit minimum API 24, compile/target 36, build tools 36.0.0 and NDK
28.2.13676358 now replace Flutter's implicit defaults.

Added validation-only CI for PR/main/manual dispatch: immutable checkout/setup-java
commits, verified Flutter 3.47.9 archive, locked dependency resolution, format,
analysis, implemented tests, debug APK and native unit task. No secrets, privileged
PR trigger, release signing, upload, caches or public binary artifacts. Workflow
has not run on GitHub. Scaffold has no substantive application tests yet.
Replaced Flutter template's older Gradle wrapper with exact upstream 9.3.1 jar and
scripts; official wrapper SHA-256 matches, distribution checksum pinned.
Private ignores remain intact; wrapper inputs are now allowed by Android ignores.
`dart format --output=none --set-exit-if-changed lib test`: 2 files unchanged.
`flutter pub get --offline --enforce-lockfile`: passed.
`flutter analyze --no-pub`: no issues (21.5s).

JDK-configured `flutter build apk --debug` is running in exec session 15110.
Verified live Gradle daemon PID 41373 and growing dependency cache; no success
claimed while it runs. Owner review of the concrete source terms requested;
local implementation continues while awaiting review. User was told the exact
application ID for Play setup: io.github.centerionware.clockrove.

The first JDK-configured build terminated with failure after 238.4s: Maven AAPT2
is x64 and cannot execute on ARM64 (`:app:processDebugResources`). Existing SDK
ARM64 AAPT2 runs (`aapt2 version`: 2.19). Added its absolute path only to ignored
.tools/gradle/gradle.properties; retry is live in exec session 56789. Session
15110 is terminal, not restarted merely because observation expired. Added
.kotlin/ to ignore generated compiler session state. CI remains standard x64 and
does not depend on this local override.

### Owner execution constraint

Owner instructed all builds/tests to run via GitHub Actions, never this VM.
Stopped the identified Clockrove Flutter/Gradle processes using TERM. Session
56789 closed without an APK success message; classify the retry as cancelled,
not passed. No further local build/test/analysis/format execution is authorized.
AGENTS.md now records this constraint. Continue with local edits/reviews only and
GitHub-hosted validation. A private intended repository will stage CI while the
source terms await owner review; public visibility remains gated.

Created centerionware/Clockrove privately and verified isPrivate=true. Reviewed all
40 staged paths: source, docs, workflow, lockfile and Gradle wrapper only; no local
skills, tools, credentials, runtime data or binaries. Pushed commit
be442a3c417b8dc163e3845c24e4998894679b98 to main. GitHub Actions run
https://github.com/centerionware/Clockrove/actions/runs/42980408630 is in_progress
on that commit (authoritative API observation). All further build/test evidence
must come from Actions. Private staging is not public source publication.

Actions run 42980408630 failed before any build/test: setup-java rejected
17.0.20.1+1 as invalid SemVer (four version components). Changed CI to exact
Temurin 17.0.17+10; upstream x64 Linux metadata confirms this release exists.
This is a CI configuration correction; no local build/test was run.

### Current continuation

Previous goal turn: progress (private repository, reviewed push, Actions workflow
and correction). Re-polled the same run 42980514022: in_progress, setup-java
succeeded, verified Flutter installation in progress. No restart. Updated stale
workspace facts in the plan to match committed source and private repository;
historical entries remain as a chronological record. No local validation executed.

Run 42980514022 is terminal failure: JDK and Flutter installation passed;
Android component step failed because sdkmanager was not on PATH (exit 127).
Resolved by installing command-line tools 19.0, archive 13114758, from Google's
fixed URL into a job-local SDK root. Official repository metadata provides SHA-1;
archive SHA-256 recorded as
7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee.
VM action was archive/metadata inspection only, no tool execution/build/test.
Workflow checks SHA-256, installs pinned packages, and exports SDK root explicitly.
Updated stale plan workspace facts. No build/test success claimed for failed run.

Active validation handle: GitHub run 42980838050, commit
d6dbe94af192450088f28da9a789387336f5c769, job 130390618681.
https://github.com/centerionware/Clockrove/actions/runs/42980838050
API/watch confirms in_progress; setup-java passed and Flutter setup is active.
Local gh watch session 97215 is monitoring that run; it performs no build/test.
Next action is to re-poll this exact run, inspect terminal logs if it fails, or
record successful checks and advance milestone 2 if the foundation passes.

### 2026-11-11 — live Actions foundation validation

Previous turn: progress (checksum-pinned SDK setup correction committed as d6dbe94).
Revalidated the same run 42980838050 / job 130390618681 rather than restarting.
Actions now reports success for JDK, verified Flutter archive, pinned Android
components, enforced dependency lockfile, formatting and analysis. Conditional
application test step completed with no behavior tests present; this is not
native test evidence. Debug APK compilation started at 07:39:33 UTC and remains
in_progress on the latest API observation. No VM build/test/format/analysis ran.
Native unit task and final job result remain pending. Do not mark milestone 1
complete until the CI result and remaining public-license gate are resolved.

### 2026-11-11 — foundation passed; native prototype started

Run 42980838050 concluded success on d6dbe94. Downloaded its logs for read-only
inspection: debug APK built; native unit task was NO-SOURCE (not behavior proof).
Verified lockfile/format/analysis and build tool compatibility on GitHub. Public
rights review remains the independent milestone 1 gate; continue native prototype
without public publication while awaiting that input.

Added native singleton SQLite command repository (prototype schema 1), serialized
transactions, global open-session uniqueness, persisted command fingerprints and
outcomes, stale-version rejection, atomic switch, UTC/timezone/origin storage and
boundary checks. Automation is explicitly unavailable until full policy exists.
Added five Robolectric tests for simultaneous surfaces, repeat IDs, clock reversal,
atomic switch, reopen recovery, overlap/adjacency and unavailable automation.
Pinned JUnit 4.13.2 and Robolectric 4.16.1 (artifact existence verified upstream).
No tests/build ran locally. New code is not validated yet. Full schema/revision
ancestry, migrations, typed Flutter bridge, widget, notification, locations/maps
and physical-device prototype evidence remain unfinished.

### 2026-11-11 — native widget clock-out slice

Previous turn: progress (native repository and tests committed in d45e286).
Revalidated run 42982039427: still in_progress, debug compilation active; not
terminal or failed. Added independent prototype widget code while it runs.

Native AppWidgetProvider renders authoritative running job and a timestamp-based
chronometer. Running action submits STOP without a Flutter engine or location
permission; idle opens the app (default-job/picker configuration is unfinished).
Widget actions carry displayed version and widget ID. Stale actions refresh rather
than stopping a newer session. Native surface command API captures wall-clock and
IANA zone once and returns persisted outcomes on retry without recapturing time.
Added sixth repository test for surface retry and stale widget safety.

No widget/device/receiver execution proven yet. Widget refresh after all future
app commits, default/picker configuration, typed Flutter adapter, native tracking
notification/service, and the rest of milestone 2 remain outstanding. New source
requires a fresh Actions run; earlier running validation may be superseded by the
new commit, because behavior changed, not because observation timed out.

### Owner-authorized public visibility

Owner explicitly instructed “make it public now.” Changed visibility with gh and
verified isPrivate=false / visibility=PUBLIC for
https://github.com/centerionware/Clockrove. This instruction authorizes publication
now, overriding the earlier wait for terms review; it does not adopt the draft
as an effective license. Source terms remain clearly labeled as a proposal.
Previous turn was progress (native widget slice, sixth test, reviewed push).
Run 42982039427 was cancelled by the new behavior commit, not by an observation
timeout. Run 42982453742 validates 8a95ed3 and was confirmed live this turn.

### 2026-11-11 — typed app/native timer adapter

Previous turn was progress: explicit owner publication and verified public state.
Revalidated run 42982453742: still live; no restart based on elapsed observation.
Added TimerApi typed Dart adapter and Kotlin TimerHostApi, attached/detached with
Flutter engine lifecycle. Native requests use a background task queue and existing
serialized repository. Snapshots include eligible jobs; timestamps remain native.
App commands refresh widgets; raw maps are confined to codec adapters. Three Dart
transport tests cover UTC decoding, immutable job lists, response-loss retries,
native-only timestamps/origin, stale conflicts and missing responses. These mocks
prove codec semantics only. No VM validation ran. docs/TIMER_CONTRACT.md records
wire/lifecycle semantics and outstanding event delivery. App controls/Riverpod,
notification/location/geofence/map prototypes and physical proof remain unfinished.

### Owner-requested one-time initial Play bundle

Owner added SERVICE_ACCOUNT_JSON and explicitly requested a signed setup AAB
artifact via Actions, allowing public plaintext AAB downloads and the minimum
one-day retention after the access/retention limitations were explained. No
private repository is permitted. Owner authorizes generation and persistence of
signing keys as repository secrets, with exactly the same key for future CI.

Prepared dispatch-only initial-play-bundle.yml. It builds validated foundation
commit d6dbe94 (run 42980838050), versionCode 1, without claiming full app readiness.
Creates the upload key in Actions, verifies AAB signing and exports a public
certificate; encrypts private recovery material to a temporary recipient whose
private key is ignored and never published. Operator will recover/store signing
secrets and delete encrypted recovery artifact. Bootstrap refuses replacement
when an upload key is already configured. No Play upload or production submission.
SERVICE_ACCOUNT_JSON is present but not used for building the manual-upload file.
Current native adapter run 42982856409 failed formatting in test/timer_api_test.dart;
this does not invalidate the previously validated foundation commit. Unpushed
prototype event changes remain separate from this one-time delivery workflow.

### Initial signed AAB delivered — 2026-11-11

Actions run 42984008259 completed SUCCESS. Built validated foundation d6dbe94,
version 0.1.0+1, signed with a newly generated RSA-4096 upload key; jarsigner
strict verification passed in Actions. Owner-download artifact:
https://github.com/centerionware/Clockrove/actions/runs/42984008259/artifacts/13839295891
Name clockrove-initial-play-aab, expires 2026-11-12T08:08:17Z (one day).

Recovered the CI-generated keystore through CMS-encrypted transport and stored
UPLOAD_KEYSTORE_BASE64, UPLOAD_STORE_PASSWORD, UPLOAD_KEY_ALIAS and
UPLOAD_KEY_PASSWORD as repository secrets; verified all four names. No values
printed or committed. Removed encrypted recovery artifact 13839570276 and
BOOTSTRAP_RECIPIENT_CERT; API confirms only AAB/public certificate artifact remains.
Permanent key reuse is configured via release Gradle environment inputs; bootstrap
refuses replacement if key secret exists. Owner explicitly authorized secret
creation and public plaintext AAB with one-day retention. SERVICE_ACCOUNT_JSON
was not used. No Play upload, acceptance, store release or production readiness
claimed. App compilation/signing occurred only in Actions; local activity was
credential recovery/secret administration, not a VM build/test.

### 2026-11-11 — resume native prototype after initial Play setup

Previous turn: progress (successful signed setup AAB, key persistence and evidence).
Current prototype validation still fails Dart test formatting in the last terminal
run. Pending correction shortens test descriptions and makes Actions display the
formatter diff when a check fails; no formatter runs on VM.

Added committed-version subscriptions to native repository and event channel.
Subscriptions replay current version; commits publish after transaction completion;
retries/conflicts do not emit false changes. Native listener exceptions cannot
change a durable command outcome. Engine detach/cancellation closes subscription
and invalidates queued events. Dart receives typed version hints and re-queries
snapshots. Added seventh native test for replay, commit-only delivery, unsubscribe
and reconnect. Code/test validation is pending Actions; no device claim.

Active prototype validation: run 42985050236 on fa360ff, confirmed live in Actions.
Formatting, analysis and Dart test steps passed; Android debug compilation started
08:13:23 UTC and remains in_progress. Do not cancel this run merely to push
prepared edits. Re-poll it for terminal results and inspect native unit evidence.

Source review found java.time requires API 26 without desugaring while minimum
is 24. Prepared an uncommitted correction using System.currentTimeMillis and
java.util.TimeZone for first-tap UTC/IANA-zone/offset capture, plus an API-24
Robolectric retry-boundary test. No VM checks executed. Commit this compatibility
fix after the current validation run finishes, then validate it in Actions.

### Owner internal-delivery activation and automatic versioning

Owner reports initial AAB uploaded to Play (owner evidence, not API verification)
and authorizes automatic internal testing after every validated main push, plus
production delivery from pinned version tags once testing/readiness permits it.
No manual version increments: tags set production versionName; internal names and
versionCodes are assigned by CI. Same upload key is mandatory for both tracks.

Current native run 42985050236 is terminal FAILURE: Kotlin 2.4 rejects inferred
intersection types in three mixed SQL bind arrays. Prepared explicit Any? array
types along with the API-24 capture correction and eighth native test. Formatting,
analysis and Dart tests passed before the compiler failure; native tests did not run.
Selected pinned release tools Ruby 3.3.6 / Bundler 2.5.23 / Fastlane 2.228.0;
prepare-release-tools workflow resolves the committed Gemfile lock on Actions.
No VM builds/tests/dependency resolution executed. Internal/production uploads are
not yet proven; publication must wait for successful native validation and setup.

### Automatic internal/tagged publishing implementation

Actions prepare-release-tools run 42986036667 succeeded; retrieved and committed
its Gemfile.lock (Ruby 3.3.6/Bundler 2.5.23/Fastlane 2.228.0). Prepared Play delivery
triggered by successful main validation and v* tags, exact validated checkout,
main ancestry, tag semantic checks and production readiness gate. Tags set build
versionName; internal names use CI run/attempt; codes read all Play bundles/APKs
and track releases, then choose max(history+1, seconds since 2020). Shared FIFO
publishing queue covers allocation through upload; retries re-read history.

Validation and publishing use queue:max, cancel-in-progress:false so pushes queue
instead of superseding each other (GitHub supports up to 100 pending). Added pure
Ruby version/tag/capacity/rerun tests to Actions validation. Upload uses the saved
key and service account; no release binaries/credentials in public artifacts.
Production remains blocked until one-time PRODUCTION_READY gate reflects feature,
device and store eligibility. No manual version bumps or per-release approvals.
Workflows and Fastlane lanes are implemented but not yet executed/verified end to
end; no internal upload acceptance or tester availability claimed. Existing native
fix run 42986013201 is live in Android build stage; do not cancel it for this push.

Native fix run 42986013201 completed: debug APK compiled successfully; native test
execution blocked before tests by AGP 9 packageDebugUnitTestForUnitTest consuming
copyFlutterAssetsDebug outputs without a declared dependency. Added the explicit
variant-matched dependency in Gradle; this preserves host-test asset packaging and
checks rather than bypassing them. New validation queues behind 42986580821 and
will be eligible for automatic internal delivery only if all checks pass.

### Prototype controls dependency preparation

Previous turn: progress (publishing workflow, pinned lock and native task fix).
Revalidated runs 42986580821 (live) and 42986727241 (pending), preserving both.
Prepared Riverpod 3.0.3 on branch prototype-controls; official pub metadata requires
Dart ^3.7.0 / Flutter >=3.0.0, compatible with selected tools. Validate manual
owner-only prepare_dependencies mode resolves/exports proposed pubspec.lock as a
source artifact; normal pushes/PRs still enforce the committed lockfile. No secret
access or publication is enabled by this preparation mode. Main source and its
active build remain unchanged. No VM resolution/build/test ran.

Feature dependency preparation run: 42987027150 on prototype-controls / e52b1fd.
Dispatched successfully with prepare_dependencies=true; this is source lockfile
preparation, not publication. Main fixed run remains 42986727241. Preserve these
handles and inspect results rather than starting replacements.

Prepared uncommitted Riverpod timer providers/controller: snapshots depend on
native committed-version hints; commands retain an immutable intent ID/version
across uncertain response retries and block competing UI intents while unresolved.
Native outcomes remain authoritative. No optimistic timer or second database.
Controller tests and prototype controls UI remain next, after Actions lockfile
resolution; this code is unvalidated and not on main. No VM checks executed.

### Prototype manual controls and controller tests

Actions run 42987027150 exported proposed-pubspec-lock artifact 13840027259;
retrieved its source lockfile and applied it without local dependency resolution.
Added Riverpod prototype controls on isolated feature branch: authoritative timer,
start/stop/switch, New job last, job creation, refresh/resume re-query and honest
unavailable-location state. Immutable retry intents prevent lost-response retries
from changing IDs or emitting competing actions. Added three presentation tests
for response loss/dedupe, simultaneous taps and stale-state refresh; mocks prove
presentation semantics, not native/physical execution. No fake running timer or
second writer. All new source is pending Actions validation, not on main yet.

Validation now exports source-only formatter patches on format failure so edits
can be applied from CI output without ever running a formatter on the VM. Earlier
main run 42986580821 is terminal failure; corrected run 42986727241 is live.
Feature dependency run remains live on its previous source; new controls require
a new feature validation run rather than pretending that old run covered them.

Dependency preparation run 42987027150 on e52b1fd completed SUCCESS. Retrieved logs:
Android build passed; native testDebugUnitTest ran (not NO-SOURCE) and Gradle
reported BUILD SUCCESSFUL (23s); release version allocation/tag checks passed and
Fastfile syntax was OK. This source predates the new screen/controller and cannot
validate those changes. It provides native repository/Robolectric and compilation
evidence only, not real widget delivery or physical-location evidence.

New controls are committed as 787916b in draft PR #1:
https://github.com/centerionware/Clockrove/pull/1
PR validation run 42987698700 is pending/in progress. Main run 42986727241 is still
live in Android compilation; no automatic Play upload success yet. Do not merge
PR until new UI/presentation checks pass. No VM checks executed.

### Prototype review and first automatic publishing run

Previous turn: progress (native prototype controls, tests and draft PR).
Main corrected validation 42986727241 completed SUCCESS, triggering automatic
Play delivery run 42988068348. Its source eligibility and Ruby tool install passed;
JDK setup is active. No Play upload acceptance claimed yet.

PR run 42987698700 completed FAILURE at formatting. Downloaded/reviewed its
formatter-source-patch artifact and applied that Actions-generated source patch;
no VM formatter ran. Added braces to keep the formatted conditional lint-safe.
Added a screen journey test for start/create/switch/stop and New job ordering,
using a controlled adapter (UI semantics only; native tests remain separate).
Feature changes require fresh PR validation. No main merge or VM tests performed.

First automatic Play delivery run 42988068348 is terminal FAILURE at begin_edit:
Google Api Error: Invalid request - The caller does not have permission. No code
allocated, release bundle built, upload or submission occurred in that run. JDK,
SDK and locked tools installed; credential setup ran, then API authorization
failed. Owner asked to grant service-account client_email Clockrove visibility
and testing-release access in Play Console Users and permissions. No key/JSON
requested in chat. Do not repeat unchanged permission failures; await owner access
change before another Play attempt. Continue independent prototype work.
PR correction validation handle: 42988271415. Main validation 42986727241 passed.

### Play permission state changed — retry authorized

Owner reports the service-account user has been invited and explicitly asks to
retry. Re-ran failed job in delivery run 42988068348 after that external change;
this is not an unchanged-credential retry. Same validated source/upload key are
preserved; allocator will inspect Play history again. No success claimed yet.
PR run 42988271415 is still being observed independently; no replacement started.

### Prototype controls validated and merged

PR validation 42988271415 concluded SUCCESS on exact head 7e3dd12: formatting,
analysis, adapter/controller/screen tests, Android debug build, native unit task,
locked release-tool/version-policy checks all passed. Marked PR ready and merged
that tested head as 6713ddf0e61bfdde48be6c3f09273d78d3db1895. No local checks ran.
Main merge validation handle 42989132915 is live; its subsequent internal delivery
must use that exact validated SHA and the permanent saved upload key.

Play permission retry 42988068348 attempt 2 passed allocation after owner's account
invitation, proving that the earlier permission barrier changed. It is now in
signed release AAB build (started 08:41:48 UTC). Upload/Play acceptance is still
unproven. Preserve this live run and observe its result, not another restart.

### First automatic internal upload accepted — 2026-11-11

Delivery run 42988068348 attempt 2 completed SUCCESS. Logs report allocated
Android versionCode 216549708, signed build/strict verification passed, and
"Successfully finished the upload to Google Play" at 08:45:37 UTC, followed by
internal upload/submission accepted. This is successful internal upload evidence,
not proof of approval, tester installation or production readiness. The delivered
source is the earlier validated native prototype; merged manual controls at
6713ddf will follow through main validation 42989132915 and automatic delivery.
No signing key was replaced; saved key and store-history allocator were reused.
Owner invitation resolved the initial API permission failure. No VM builds/tests.

### Native location persistence prototype — 2026-11-11

Previous turn: progress (Play retry succeeded, tested controls merged, status table
reconciled). Created native-location-prototype branch from current main. Added
schema 2 location point persistence with actual measurement/reception timestamps,
coordinates, accuracy and session association. Only a requesting active session
can accept a new sample; idle/late/wrong-session samples rejected. Immutable IDs
deduplicate repeated samples and reject divergent reuse; invalid coordinates,
accuracy and future measurements rejected. Stop/switch prune points after end
atomically, including wall-clock rewind. No synthetic GPS generation or recording
callbacks are implemented by this slice.

Preserved actual schema-1 DDL in synthetic test fixture and added four Robolectric
checks: validation/dedup, boundary pruning/late callbacks, upgrade preserving open
session and dedup outcomes, and failed-upgrade rollback without record/schema loss.
No VM tests/builds ran. New code needs Actions validation before main integration.
FGS/fused callback/notification/geofence/map feasibility remain incomplete.

### Foreground fused-location service implementation

Previous turn: progress (native storage/migration slice and PR #2). Re-polled its
existing validation 42990150091; still active, not restarted due to elapsed time.
Added pinned Google Play services location 21.3.0 (artifact existence verified)
and native location foreground service with explicit manifest permissions/type,
ten-minute target, measured/reception metadata and per-request session binding.
Ongoing notification reflects committed repository state; clock-out uses a private
broadcast receiver sharing native commands, independent of Flutter/GPS permission.
Observer/provider cleanup rejects work after destruction; process loss is not
claimed as continuous tracking. Sample mapper test covers actual timestamp,
accuracy and stable duplicate identity; repeated delivery retains first reception.

Current Android foreground-service requirements and fused API docs were read.
Service code is not wired to a visible permission/onboarding flow or enabled in
Flutter yet; tracking remains unavailable to users until that adapter is added.
No real callback/foreground notification/device cadence proof exists. No local
build/test ran. New source requires its own Actions validation after the storage
slice run finishes; it must not be mistaken for already-validated code.

Storage PR #2 passed Actions run 42990150091 and merged exact tested head a970b71
as d6e4d9c177323032d921b32a5350fa356340487a. Schema-2 storage/migration checks
are verified in CI, not real callback delivery. Service follow-up was preserved
on separate foreground-location-prototype branch; nothing unvalidated was merged.
Manual-controls Play delivery 42989964414 succeeded: allocated versionCode
216550241 and Google upload accepted at 08:54:33 UTC. Tester installation remains
unverified. New service additions require their own PR/Actions run.

### Visible location permission/start flow — 2026-11-11

Previous turn: progress (service source/mapper tests and draft PR #3). Service-only
validation 42991221956 concluded SUCCESS; merged exact tested head 4db4b34 as
 a99faa954b9a74c9fb1c971510c63520395cd5ca. This proves compilation/tests, not actual
fused delivery or FGS lifecycle on a physical device.

Prepared location-permission-flow branch: native activity visibility checks,
foreground fine/coarse permission callback and separately requested notification
permission, typed tracking status/enable/disable contracts and native health
invalidation events. Activity detach cancels pending permission callbacks. Service
startup failures remain explicit; no background location permission is requested
or geofence automation enabled. Added Flutter purpose dialog and independent
location controls, honest denied/approximate/provider status and resume refresh.
Tests cover denial/restriction codec responses and that purpose confirmation
precedes native permission calls while denied location preserves manual clock-out.

These UI/bridge changes require new Actions validation. No VM formatting/build or
tests ran. Physical screen-off sampling, recovery, geofences, map experiment and
clock-out sampling target are still incomplete; milestone 2 is not complete.

### Permission flow validation correction and native lifecycle tests

Previous turn: progress (tracking permission bridge/UI and PR #4). Existing PR run
42992333516 concluded FAILURE at Dart formatting. Downloaded/reviewed its source
patch and applied the exact Actions formatter corrections; no VM formatter ran.

Source review found native enable requests were not reserved before asynchronous
storage checks and could overwrite a permission result. Reserved them immediately,
ignored cancelled continuations, marshalled replies back to main, and sanitized
storage exceptions so callers do not hang. Added native Robolectric codec-driven
bridge tests: competing enables preserve original callback; cancelling before a
permission result prevents late service startup; approximate grant records a
foreground-service intent while retaining timer; background entry cannot prompt
or mutate timer. Pinned Robolectric permission APIs verified from exact source.
These tests are pending CI and do not prove physical FGS delivery. No VM tests ran.

### Owner UI direction and modern timer design

Owner explicitly requests extremely clean, modern UI from the start. Applied that
direction to the prototype instead of deferring visual work to release polish:
consistent Material 3 light/dark themes, forest-green palette, soft neutral canvas,
restrained cards, strong timer hierarchy, readable tabular elapsed display,
maximum reading width, grouped jobs and quieter state-dependent location controls.
No fake navigation or unsupported feature buttons added.

Elapsed display derives from committed native UTC start timestamps; a foreground
refresh timer only redraws and is cancelled in background/disposal. Backward clock
shows a correction prompt instead of negative elapsed time. Added UTC DST/long
session display checks, large-text layout check, and Actions-rendered light/dark
synthetic PNG previews with fonts from the pinned Flutter SDK. No previews have
yet been rendered or inspected; new UI is pending its own CI/visual review.

Permission-flow PR #4 passed Actions 42993338165, including native lifecycle tests,
and merged exact tested d490aa0 as 0262d861a553e45ed87f4e6552132dd6949230cf.
Visual changes isolated on modern-timer-ui; no unvalidated UI merged. No VM tests,
formatter or build ran. Geofence/map/recovery/device feasibility remains open.

### Modern UI validation correction

Previous turn: progress (clean light/dark UI, display/accessibility/preview source
and PR #5). Revalidated existing run 42994742872; it concluded FAILURE at formatting,
so no previews were rendered. Downloaded and reviewed Actions formatter-source
patch (artifact 13843147570), applied source-only corrections without running a
VM formatter. Added a lifecycle guard so elapsed display refresh does not start
when its widget is initially created while backgrounded. Display derives from
native timestamps and never writes timer state. New CI run must prove formatting,
analysis, layout/tests and generated previews; visual review remains outstanding.

Corrected UI run 42995204970 passed formatting but failed analysis on exactly two
unnecessary imports (dart:ui in elapsed_display.dart and dart:typed_data in the
screen preview test). Removed only those imports; no check was disabled. Rendering
still has not run, so there is no visual artifact to approve yet. New validation
will cover this source correction. No VM analyzer/formatter/build/test executed.

### UI test-harness failures diagnosed

Previous turn: progress (formatter/analyzer corrections). Existing run 42995708218
concluded FAILURE in tests: ten passed, including large-text layout; manual journey
tap happened before scroll layout repainted, and both previews failed because
Flutter's test isolate does not support resolvePackageUri. No preview artifact
was generated. Added frame settling after scroll operations (not tap suppression)
and explicit pinned FLUTTER_ROOT export/font lookup for CI rendering. Removed
unsupported isolate lookup; preview assertions remain enabled. New source needs
fresh Actions validation and image review. No VM tests/formatter/build ran.

Current UI handle: run 42996201315 on dadb445, confirmed in_progress at pinned
Android component installation. Preserve this run; no preview artifact yet.
Main internal delivery 42995143199 has concluded SUCCESS independently. No visual
approval or physical-device evidence inferred from that unrelated delivery.

### First rendered modern UI review and measured-status refinement

Run 42996201315 passed analysis and all Dart/UI checks, exported ui-previews
artifact 13843514422, and is now completing native/release-tool checks. Downloaded
and visually inspected both CI-rendered PNGs: clear timer/jobs hierarchy, readable
forest/light and mint/dark contrast, restrained cards and no layout overflow.
Debug ribbon remained in preview; removed it explicitly from app/preview wrappers
and added scrolled location-panel captures for complete visual review. No VM
rendering or tests were run; images came directly from Actions.

Prepared real latest-sample measurement/accuracy in tracking status, with one
serialized repository observation snapshot. Latest is selected by measured time,
not callback arrival time. UI labels actual stored sample time and reported
accuracy only when available. Added native out-of-order delivery metadata test
and Dart UTC/accuracy decoding checks. Synthetic previews now include approximate
location state with synthetic sample metadata; no real location data involved.
New source remains pending CI and renewed preview inspection, not merged.

Modern UI run 42996201315 completed SUCCESS with rendered visual review. Merged
exact tested head dadb445 as 9e32c90bd8f4d604fdc6dee31e5d75c1f50e4cea. Both
CI-only previews were inspected locally as images (not rendered/tested on VM).
Baseline release builds do not show the debug ribbon seen in the initial test
preview. Observation-status/debug-preview refinements remain isolated on
measured-tracking-status for new CI review. docs/UI_DESIGN.md records the owner
style requirement and reusable design standards for subsequent features.

### Measured-status validation correction

Previous turn: progress (modern UI merge/visual review and measured-status PR #6).
Existing PR run 42997495311 concluded FAILURE at formatting. Downloaded/reviewed
and applied its Actions-generated patch to three Dart source/test files. No VM
formatter/build/test ran. New validation will cover actual sample metadata and
clean/scrolled previews. Native departure-state follow-up is prepared locally,
not included in this correction and not enabled at runtime.

### Persisted departure primitives (dependent feature branch)

Prepared departure-state-persistence based on measured-tracking-status. Prototype
schema 3 adds one durable departure candidate with detected boundary and deadline;
v1/v2 forward migrations preserve prior work/location records. Duplicate exits
keep the first boundary/grace. Re-entry strictly before expiry cancels; delayed
finalization closes at detected exit, prunes points after that boundary and never
closes a different/new manual session. Manual stop/switch clears candidates in its
own transaction. Missing GPS is not an input to these primitives.

Added four Robolectric tests for recovered deadlines/retrospective pruning,
re-entry/zero grace/duplicate callbacks, manual precedence, max/invalid grace and
v2 upgrade preserving points. Current schema-1 rollback tests also remain active.
These native internals are not wired to geofence callbacks, alarms or UI yet;
no automation is enabled by this slice. Validation is pending; all tests run on
Actions, never VM. This PR depends on measured-status PR #6, then targets main.

### Clean UI and qualified membership progress

PR #6 complete validation 42998378446 succeeded and merged as db7c24b. Inspected
all four CI-rendered images from artifact 13844847336: light/dark first-screen
and scrolled location states; debug ribbon removed and measured sample metadata
readable. UI quality is established for this prototype, not launch readiness.
Main validation 42999610367 is live; later Play delivery is independently tracked.

PR #7 complete validation 42998556045 succeeded. Squash ancestry caused a merge
conflict after retargeting; rebased onto main as 36b7239 with identical source tree
verified using git diff. New exact-head validation pending before merge.

Prepared isolated native geofence membership reducer plus six JUnit tests. Any
inside same-job site prevents departure; unknown is not an exit; stale/equal-time
contradictions are surfaced. Capacity >100 is rejected. Only qualified new entries
can select a sole eligible/armed/unsuppressed job, multiple matches require choice,
and active timers never switch. Caller must still persist membership, arming and
suppression and use the native repository for commands. No registration, runtime
automation or device success is claimed. GitHub Actions validation pending.

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

### OSM prototype merged with complete code validation

Previous goal turn: progress. This continuation completed native visual merge #9
and the stored-point OSM prototype. Exact head 94451c2 passed full Actions
43006507700: formatting/analysis, 23 Dart/UI tests, debug APK, native tests/rendering
and locked release-version checks. Inspected all six PNGs in artifact 13849240735
across both themes. Merged #10 at bb2a9eb1a5a78074434472cb601d8755168f95e5.
The map receives actual matching measured coordinates/accuracy, has visible
attribution/cache/backoff and permits manual clock-out after tile failure.

Runtime Google-key and live OSM/device proof remain pending. Geofence platform
registration/callbacks, durable arming/suppression, scheduler/recovery and physical
acceptance remain M2 work; no full milestone or launch completion is claimed.
Main visual build f9207b5 separately passed validation 43003375715 and internal
Play delivery 43004256117 (upload only). Merged-map main validation 43007569229
is live; its subsequent internal delivery is not yet confirmed.

Formatter negative-path proof is live in disposable branch ci-format-gate-proof,
commit fa4437e / owner manual run 43007665309. Only source whitespace differs;
expected result is failed final gate after the independent checks complete.
Do not merge the probe branch. Observe its terminal result, record it and delete
the temporary branch. No VM build/test/format/render was executed.

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

### M2 departure alarm integration — source prepared, unverified

Native departure alarms now observe committed candidate state on the singleton
repository, request one inexact wake, and use SQL-guarded candidate/session expiry.
Private boot/clock-change receivers hold uncertain work. Seven API-24/API-35 test
cases are prepared for CI; no VM tests/builds ran. Runtime geofence registration,
permission/site/review UI and physical wake/recovery proof remain incomplete.

Durable policy exact head 87f566c passed full Actions run 43020856586. Inspected
JUnit XML: 42 native tests, zero failures/errors/skips, including fourteen new
policy cases. PR #12 merged as 87539048b9c5f468f8f52ebec88aac38de981b8b.
Dart/UI, Android build and release-version checks passed in that run. Alarm
adapter additions remain separate and need their own CI; milestone 2 is incomplete.

Alarm adapter run 43021499336 compiled the Android app, but native test
compilation rejected a nullable PackageInfo permission array in the new manifest
assertion. Added an explicit empty-array fallback without changing the permission
assertions. Runtime tests remain unverified until the corrected Actions run.

### M2 Android geofence platform adapters — source prepared, unverified

Native registration now removes old inventory before replacement and distinguishes
requested sites from platform task success. Private callbacks use shared serialized
work, precise/background permission guards, replay-safe location-service handoff
and a generic notification for ambiguous manual selection. Startup protects pending
work before uncertain recovery; default idle state does not initialize Play Services.
Fifteen API-24/API-35 cases are prepared with synthetic task completions/HMAC.
Actions/device validation and site/permission/review/health UI remain outstanding.

### M2 verified alarm merge; automation control UI prepared

PR #13 merged f845937 after exact head c76e806 passed Actions 43022813646:
56 native tests, zero failures/errors/skips; Dart/UI, build and version gates passed.
Policy main run 43021843305 and Play internal delivery 43022739286 succeeded.
PR #14 head e42b37e passed 43023461269 with 86 native tests (all clean). The
rebased exact head d0ef67b remains under final Actions validation.

Work-site editing, versioned settings, permission education/separate Android flows,
provisional departure and explicit history-preserving review now have source.
Retained command identity supports response-loss retries. Six native API cases on
API 29/35 and nine Dart/UI cases are prepared, including six light/dark preview
images. No VM validation ran. Source/UI acceptance and physical device evidence
remain pending; M2/M5 are incomplete.

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

### M2 controls merged; site selection source awaiting Actions

PR #15 merged 897e120 after exact head 8a266c4 passed run 43028074243:
98 native tests, no failures/errors/skips, all Dart/UI and Android build checks.
Reviewed final departure light/dark images and earlier site/editor/timer images.
Main adapter validation 43026215698 and Play internal 43027191550 succeeded.

Map selection now has source for tap/radius/zoom/attribution and a coordinate
fallback on failure or polar locations. Current location uses an explicit,
foreground-only, cancellable fresh-fix request; setup fixes stay out of history.
Seven API-24/35 controller cases, one API-29/35 bridge case, eight UI/map cases and
one codec case are prepared but unrun. Two map previews plus updated site-editor
previews will render in Actions. The form retains off-screen validators. No VM
builds/tests/rendering ran. Native/device and final visual evidence remain pending.

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

### Owner request — archive/unarchive and delayed permanent job deletion

In progress: archive jobs while retaining history/preferences; unarchive restores
clock-in and automation eligibility with fresh site facts. A continuous 30-day
archive period is required before red, confirmed, non-undoable deletion. Native
version/active-session/age/capacity guards and transactional child-data cleanup,
opaque deletion metadata and privacy-preserving deduplication are required.
Migration must not invent a historic archive date. No implementation or validation
is claimed yet; current-location button delivery remains in PR #16.


### Next iteration requested by owner

| Task | Status | Evidence / next action |
| --- | --- | --- |
| Current-location site button and precise-on-demand permission | Implemented; final CI pending | PR #16, 114 native checks and UI flow passed before formatter-only correction; await exact final head |
| Compact home widget | In progress | Owner reports existing widget too large; reduce footprint while retaining native stop |
| Archive/unarchive and 30-day red confirmed deletion | In progress | Owner requirements recorded; native schema/commands and UI still required |
| Job detail display | Pending | Real overview/settings/board/report navigation required |
| Persistent Kanban | Pending | Follow full board contract, including accessible moves and safe column deletion |
| Global hourly rate/currency and per-job override | Pending | Explicit owner scope update replaces original no-rate calculation decision |
| Job report amount owed | Pending | Current effective rate × finalized duration; exclude active/provisional work; currency rounding required |
| Background-permission guidance | Pending | Owner encountered expected separate Android settings flow; clarify the in-app instructions |

Owner says main generally works; GPS is untested and device/OS metadata is absent.
This does not establish native screen-off, recovery or location acceptance.

Compact widget: source prepared for a 2×1 default and one-row 48 dp action. Native
API-24/35 cases cover real RemoteViews inflation, touch bounds, state/labels and
large text. Native visual harness renders idle/running images in Actions. No VM
build/test/render ran; source is not yet validated on CI or the owner's launcher.


### Binding combined business scope — current internal acceptance

Contract: [docs/NEXT_TEST_RELEASE.md](docs/NEXT_TEST_RELEASE.md). The complete
combined implementation passed exact-head checks 43117726325 and merged in #29.
Main checks 43120365067 and Play delivery 43121445107 passed for cb2efc9;
0.1.0-internal.19.1 / 216603660 was accepted. Play delivery is active and
production readiness remains unset. Earlier held/pending ledger notes below
record the development history, not the current release state.

| Addition | Status | Evidence / remaining gap |
| --- | --- | --- |
| Hourly/task billing mode and task prices | Merged and Actions verified | Exact integer pricing, missing/zero values, currencies, completion dates and DST report cases passed; report UI integrated |
| Reusable templates including deleted-job content | Merged and Actions verified | Independent catalog/copy/removal, deletion retention and provenance cleanup passed |
| Reporting and full Kanban management | Merged and Actions verified | Board CRUD/reorder/drag/accessibility, selected date/job list/map/totals/CSV, reviewed theme previews |
| Customers and per-job customer/email | Merged and Actions verified | Native references and typed customer/job forms, refresh-race checks passed |
| PDF invoice beside CSV | Merged and actual-framework verified | Immutable numbered snapshots and all-style multipage row/amount/total checks passed |
| Email draft with automatic fields and PDF attachment | Merged; native handoff checks verified | Real Intent/content-URI/read-grant tests pass; installed email-app compatibility remains a physical-device gap |
| Company/contact and email templates | Merged and Actions verified | Persistence, placeholder handling, immutable snapshots and UI journeys passed |
| Optional header/footer and company logo | Merged and actual-framework verified | Bounded PNG/JPEG/WebP import, metadata removal, immutable retention and actual rendered logo/pagination checks passed |
| Minimal, Classic and Modern invoice styles | Merged; actual visuals reviewed | Three distinct native PDFs, multipage first/middle/final pages and decoded light/dark viewer captures reviewed |
| Combined internal release | Upload accepted | Delivery 43121445107 / versionCode 216603660; tester installation is separate |

No new login/server/SMTP credential system, task timer, tax calculation or recurring
billing is authorized by these features. Existing application scope remains active.

Compact-widget run 43071623833 built the Android app but native test compilation
failed because the new widget fixtures used Dart snapshot argument ordering for
the Kotlin type. Switched fixtures to named fields; all layout/state assertions
remain. Corrected Actions validation is pending; no VM checks ran.

### M3/native business foundation — source prepared, unverified

Schema 5 and transactional revision metadata now have source for the complete
combined iteration. Existing tracking data/command receipts are preserved; new
jobs seed real default boards, and core job/site/preferences/session changes
record ancestry. Added a frozen released schema-4 fixture and eight API-24 tests
for migration, invariants, dedup, retrospective revisions and rollback. No VM
validation ran. New business commands/UI and backup merge remain pending; do not
mark the combined feature set or schema migration verified before Actions passes.
Play publication remains held for the entire requested iteration.


### Foundation merge evidence and native Kanban commands

- PR #18 merged 45670b2 after 1cfc12a passed 43072986373. Reviewed compact idle/
  running RemoteViews previews; actual launcher sizing remains to be checked.
- PR #19 merged f94179e after dec72f4 passed 43073787801. JUnit XML confirms
  127 tests, no failures/errors/skips, including eight real migration/revision cases.
- Native Kanban command source now covers columns/cards, ordering/moves, explicit
  completion and prices, independent template copy/removal, transaction guards
  and revision ancestry. Twelve API-24 cases are prepared. No VM validation ran;
  Actions, typed transport and real board UI integration are still required.

Job lifecycle/customer/settings/report/invoice features are unfinished. Play
workflow 414395901 remains disabled_manually; do not publish an incomplete test
iteration. Existing owner GPS/device feedback is still not full device acceptance.

### Customer, company and billing command source

Prepared serialized native settings/customer/job commands with revision ancestry,
exact integer rates, explicit hourly/task mode, effective customer email overrides,
customer archive/reference guards and safe literal email template expansion.
Existing jobs retain their currency when the default changes; incompatible default
rates do not apply. Eleven API-24 cases cover response-loss dedup/stale writes,
transaction rollback, exact large/zero rates, references and template injection.
Actions validation is pending. UI, reports, invoices/logo and job lifecycle remain
unfinished; Play delivery remains held. No VM builds/tests ran.

### Kanban validation and shared reporting source

PR #20 head 7be840eb88c128261f24dba55ee385247e472157 passed Actions
43077477547. Downloaded native XML confirms 139 tests, zero failures/errors/skips,
including all twelve new board cases. Merged the validated source; typed bridge
and usable board screens are still pending, so Kanban is not feature-complete.

Shared native report source now clips finalized intervals at current report-zone
calendar boundaries, separates active/review-held work, selects completed billable
tasks and measured GPS, calculates exact alternative hourly/task totals, and
produces quoted/formula-protected work and separate GPS CSV. Missing rate/price
stays explicit; hourly rounding is once per selected job total, half up to the
currency minor unit. Ten prepared API-24 cases cover DST (23/25 hours), skipped
calendar days, clipping, exact rounding/large values, task mode and CSV consistency.
This source awaits Actions; report UI, map wiring, document save/share and actual
PDFs remain unfinished. Play publication remains held; no VM checks ran.

### Native bridge and job board UI source

Added a strict business channel with string-encoded minor units and typed Dart
board/template adapters, connected job taps to the actual board, and prepared
column/card editors, due dates, fixed prices, completion roles, accessible moves,
long-press drag destinations and independent template reuse/removal. Unknown
mutation outcomes retain the original request closure for retries; editor drafts
survive conflicts. Added bridge rejection cases on API 24/35, Dart exact-money/
board/editor/retry cases and synthetic light/dark preview generation in Actions.
Validation and visual review are pending. Customer/settings/report/invoice screens
and lifecycle remain unfinished, and Play delivery is still held.

Customer/settings head 493814c passed Actions 43078381276; inspected XML confirms
150 native tests, zero failures/errors/skips, including eleven business command
cases. Reporting head 5972331 passed 43078930808; XML confirms 160 native tests,
zero failures/errors/skips, including all ten DST/pricing/CSV report cases. Both
validated source increments merged. Neither result proves complete screen/PDF/
email/device behavior. First board UI validation 43079870604 stopped at formatting
and multiline-if lint; applied the runner's formatting artifact and added explicit
blocks. Corrected UI validation is pending. Play delivery remains disabled.

### Company/customer/job settings screens — source prepared

Connected main Settings/Customers navigation and job settings to persisted native
records. Prepared global hourly/currency and company/contact/header/footer/style/
email-template forms, customer CRUD/archive/manage view, per-job hourly/task basis,
rate override, color and customer/email override controls. Currency changes clear
the editable default rate before saving, preserving explicit monetary intent.
Prepared source requires Actions formatting/analyzer/tests and synthetic visual
review. Logo import, PDF/style previews, report screens and lifecycle still remain;
no combined release or complete invoice claim is made.

### Monitoring retry and upgrade recovery continuation

Rebased the pending explicit monitoring retry/app-upgrade recovery source onto
current main without changing the combined next-release contract or publication
hold. Prior Actions 43065982993 stopped at formatting; applied its source artifact.
Fresh exact-head Actions validation is required. This remains source-level recovery
behavior, not physical GPS/process/upgrade proof. Play delivery remains disabled.
### Native job lifecycle — source prepared, unverified

Prepared serialized archive/restore/delete commands. Archive requires clock-out;
restore preserves preferences but rotates registrations and checks site capacity.
Native deletion enforces thirty continuous elapsed UTC days, resets after restore/
rearchive, rejects rollback/unknown archive dates and purges job-specific entities
transactionally. Independent customers/templates remain, with deleted-job template
provenance and historical private revision payloads cleared. Opaque command hashes
preserve original outcome replay after personal fingerprints are purged; retained
surface headers contain only version/action/opaque job ID/origin. This is logical
data removal, not a forensic secure-erasure guarantee. Ten API-24 cases cover age,
reset, running guard, preferences, retained templates/customer, response-loss replay,
capacity, rollback and duplicate concurrent delete. Actions/UI integration remain
pending. Play publication is held; no VM validation ran.

Lifecycle fdd33a8 passed the completed android check in 43085255507 (176 native
tests, zero failures/errors/skips). Merged #25. This monitoring branch now includes
that main source; exact combined validation is pending. Play delivery remains held.
### Immutable invoice/PDF source prepared

Prepared stable numbered invoice snapshots using actual finalized hourly or
completed-task report selections. Missing rates/prices prevent allocation; explicit
free tasks remain valid. Snapshots preserve contacts, company/templates/style,
exact totals and optional immutable logo references. Rendering is outside the
repository writer; cached PDF cannot overwrite a prepared document. Added native
Minimal/Classic/Modern layouts with wrapped multipage content, aspect-preserving
logo, optional header/footer and shared exact totals. Five API-24 snapshot cases
and two native-graphics PDF cases are prepared: generate bytes, reopen actual PDF
pages and export synthetic previews. These have not yet run. Logo picker, document
provider/share/email, preview UI and report UI still remain; no release claim.

### Kanban UI acceptance and document handoff source

Board head 65d4734 passed Actions 43080530052; downloaded XML confirms 166 native
tests with zero failures/errors/skips. Reviewed actual light/dark board previews:
readable task pricing/due dates, clear hierarchy, no clipped card content. Merged
PR #23. This is not full combined iteration completion or physical-device proof.

Prepared a nonexported invoice content provider with temporary read grants,
actual saved PDF attachment/email recipient/subject/body, email-app routing,
share fallback, document save picker and native preview of real PDF pages.
Source adds provider/Intent cases on API 24/35; no external email has been sent
by the agent and composer opening is never recorded as delivery. Added archive/
report/invoice-index transport and corrected deletion to scrub provenance from
older revisions of explicitly removed templates, plus customer command payload
purging. Invoice build 43082392102 exposed that PdfDocument has no Kotlin use
receiver; changed to explicit close in finally. Corrected Actions checks and
all new document/provider/UI journeys remain pending. Play delivery is held.
PDF run 43083368119 built Android and passed snapshot/domain cases but the two
real-PDF host cases failed: pinned Robolectric has no PdfDocument implementation
and returns a closed native document handle. Moved those assertions to Android
instrumentation (not mocks or relaxed assertions), with API 24/35 emulator checks
in Actions, pinned vendor archives and AndroidX runner 1.7.0 / JUnit extension
1.3.0. Actual PDF page/text/render/pagination checks and previews remain pending.
No emulator/SDK/build/test was run on this VM. Settings journeys in 43082585871
passed except formatting; applied its runner-generated patch to the source.

### Combined release UI integration — source prepared, unverified

Prepared shared date-range/calendar/job-filter reporting screens with exact
alternative hourly/task amounts, finalized interval list, completed task lines,
excluded active/review work, measured GPS map with accuracy/timestamps and trace
breaks at session/gap/antimeridian boundaries. CSV/GPS save through the document
picker rechecks the displayed native version. PDF invoice is beside CSV, with
actual saved-document preview, page navigation, email attachment/save/share and
prepared-invoice history. Added archive/restore UI and red, irreversible native-
eligibility-only deletion confirmation. Added settings archive action.

Provider reloads now retain editor data instead of destroying pending commands
when native version notifications precede responses. Prepared a response-loss/
refresh regression test proving the original invoice ID/version/intent survives,
plus CSV selection, elapsed precision, trace and deletion-confirmation cases.
Source awaits Actions formatting/analyzer/build/tests and real visual review;
logo import, final integration fixes and combined release acceptance remain open.
No VM build/test/render ran; Play delivery remains disabled.

Settings head 4875670 passed 43085253088; inspected XML confirms 166 native tests
with zero failures/errors/skips and the new Flutter customer/settings/job billing
journeys passed. Reviewed six light/dark company/customer/billing previews. Merged
#24; real logo import and document/render/combined acceptance remain unfinished.
Removed the customer action's heavy elevation in the next UI source revision to
match existing flat surfaces. Integrated UI formatter artifact from 43086427014
applied; remaining analyzer/test results are still being inspected.

### Company logo import and immutable branding — source prepared

Prepared system image/document picking with a bounded private copy, accepted PNG/
JPEG/WebP validation, pixel/byte limits, EXIF orientation, downscaling with preserved
aspect and metadata-free PNG re-encoding. Settings can preview/apply/change/remove
the logo; immutable assets remain while referenced by prepared invoices. Unused
assets are removed without rewriting earlier PDFs. Cached draft tokens are opaque
content hashes; replay checks the original committed result before needing draft
bytes. Input URIs/filenames and source EXIF/GPS are not retained as settings.

Added five API-24 SQLite/receipt/rollback/retained-branding cases, four real Android
image cases to the API 24/35 instrumentation matrix, and three logo UI retry/cancel
regressions. Also locks report filter changes while invoice intent is pending.
All checks remain pending; no VM builds/tests/rendering ran. Real PDF run
43085897954 passed host checks but emulator setup stopped at an unzip overwrite
prompt; corrected CI uses explicit replacement of ephemeral runner SDK copies
with the pinned vendor archives. This is not proof the PDFs rendered successfully.
Play delivery stays disabled until the combined feature set passes acceptance.

Branding run 43089105094 passed Flutter presentation/retry checks and the Android
app build, but unit-test compilation rejected desktop-only ImageIO fixture APIs
on the Android test classpath. Replaced that fixture with a tiny valid PNG built
using standard CRC/zlib streams; production image decoding stays native and is
instrumented separately. Added native-eligible red deletion in job settings as
well as the archive view and avoided creating a trailing page for a blank invoice
footer. Exact updated checks remain pending. Emulator setup now also records the
pinned package descriptor required by avdmanager; no device rendering claim yet.

Integration review also keeps date/job controls available after a failed report
query (so a selection can be narrowed), refreshes a move dialog's actual native
snapshot before a fresh confirmation, and adds corresponding presentation
regressions. Pending-command retries still retain the original captured operation.
These source fixes await Actions; no VM checks ran.

Lifecycle head fdd33a8 has a successful completed `android` check in run
43085255507 (GitHub's overall run status lagged). Downloaded XML confirms 176
native tests, zero failures/errors/skips, including all ten lifecycle cases;
merged #25 with the exact checked head. PDF instrumentation now boots API 24 and
reaches the actual runner. Run 43090913550 found eager reflection of a generated
API-35-only text lambda on API 24; isolated those types in a guarded helper,
retaining real PDF text/render assertions. Fresh checks are pending. Applied the
completed branding run's formatting artifact for settings deletion controls.

Invoice core head 8c43978 reports a successful Actions run 43093219562, including
the real API-24/35 instrumentation step after isolating API-35-only text types.
Artifact downloads currently return GitHub HTTP 502; do not claim inspected XML
counts or document visual acceptance until actual artifacts can be read. Added a
mandatory post-device Flutter check that reads the actual Android-generated page
files, exercises the viewer at large landscape text, and exports light/dark views
for all three styles. Also prepared hourly/task report and measured-map previews
using synthetic data/tiles. No mocks substitute for actual PDF generation here;
the viewer routing stub receives real device-produced page images. All new checks
and visual acceptance remain pending. Play delivery remains disabled.

Report boundary review found that the default repeated-midnight interpretation can
omit an hour in zones such as Havana. Prepared API-24 ICU first-occurrence/next-
valid day boundaries, retaining UTC duration math, with host and actual framework
checks for midnight overlap, midnight gap, skipped dates and the final supported
year. The maximum calendar date now terminates before lexicographic year rollover.
Using the historical Sao Paulo 2015 transition keeps the fixture compatible with
both old device tzdata and current rules. New checks await Actions. Reference:
[Android ICU wall-time options](https://developer.android.com/reference/android/icu/util/Calendar#setRepeatedWallTimeOption(int)).

Device-capture review found that connected-test teardown can uninstall the app
before its app-specific external previews are pulled. Prepared direct invocation
of the same AndroidJUnitRunner after building/installing the test APK, a test-only
JUnit XML listener, nonzero/no-failure/no-skip assertions and mandatory PDF/PNG
capture for each style on each API. No broad storage permission is introduced;
test data remains app-specific and is captured before emulator cleanup. Actual
runner support: [AndroidJUnitRunner listener](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner).
This strengthens artifact/coverage evidence; updated Actions validation is pending.

### Owner-requested main merges

Merged the exact validated heads as requested:
- #26 head 8c43978 / Actions 43093219562 -> main merge 036233f.
- #27 head 5142c2f / Actions 43093223910 -> main merge e495231.
- #28 head a06b13c / Actions 43093226704 -> main merge 834a7e4.

These include invoice snapshots/PDF styles, private document/email handoff and
integrated report/archive UI. Their checks passed; artifact downloads were still
returning HTTP 502, so actual document visual acceptance remains unproven. #29
now targets main for logo import, final integration/calendar fixes and strengthened
real-device artifact/visual gates. Its latest validation remains queued. Main
validation and the final combined acceptance are still required. Play delivery
remains disabled; no partial internal release has been published.

Branding run 43101008984 passed analysis and Flutter checks and exported readable
artifacts again. Applied its exact formatter correction. Reviewed six synthetic
hourly/task report and measured-map light/dark captures: amounts, billing basis,
CSV/PDF actions and sample labels are legible without overflow. Synthetic blank
tiles verify layout only, not live map availability. Actual PDF/logo device checks
are still running; invoice visual acceptance and combined release remain pending.

Extended actual Android pagination acceptance to all three styles: each long
invoice must retain all 130 item titles and its total in API-35 extracted text;
API 24/35 export the real multipage PDFs and first/middle/last rendered pages.
Device XML now requires the expected PDF (3), logo (4) and report-boundary (3)
cases individually, preventing missing test classes from passing the gate.
These strengthened checks remain pending in Actions.

### Recovered artifact evidence and current task status

Artifact downloads recovered. Inspected exact CI XML (no local test execution):
- #26 / 43093219562: two actual PDF instrumentation cases on each of API 24
  and API 35, four total, zero failures/errors/skips. This proves real framework
  execution; that earlier capture still lacks retained page previews.
- #27 / 43093223910 and #28 / 43093226704: 191 native cases each, zero
  failures/errors/skips, including ten attachment/provider handoff and five
  immutable invoice snapshot cases.
- #29 earlier head 042216c / 43101008984: 198 native cases, zero
  failures/errors/skips, including five logo-store and twelve reporting cases.
  Actual framework/logo/viewer steps remain running; full run is not yet passed.

Updated the task table to distinguish merged behavior from missing implementation
and physical acceptance. Latest heads still requiring complete checks are #17
4d3db27 (43101725472) and #29 ba22cb6 (43101862377). Play remains held.

Run 43101008984 completed actual Android instrumentation: ten cases on API 24
and ten on API 35, zero failures/errors/skips. Inspected the retained actual
single-page Minimal/Classic/Modern renders: distinct layouts, aspect-preserved
logo, readable contacts/items and matching USD 1250.00 totals. Full run failed
its viewer regression and the already-applied formatting correction. Viewer
fixture review found it replaced MediaQuery with a new size-zero instance when
changing text scale, collapsing the size-dependent controls area. Preserve the
real viewport/insets via copyWith, then scroll and hit-test the whole button
before tapping. New viewer captures and all-style long-document checks still
require Actions; no local rendering or test execution ran.
### Session correction/history storage — source prepared

Prepared a separate milestone-3 native slice: closed-session manual entries,
correction/deletion, stable paginated history and revision ancestry queries.
Commands use the existing serialized writer and durable response-loss receipts.
Global half-open overlap checks include open sessions; positive durations avoid
overflow and timezone/offset context must match the supplied start instant.
Correction records manual origin, prunes points outside both corrected bounds,
and records point deletion revisions; extension never manufactures observations.
Deletion retains session tombstones/history. Job identity remains stable.
Historical edits clear only that session's automation references, preserving a
current same-job timer/departure and registration. Prepared invoices stay immutable.

Added fourteen API-24 native cases for boundaries, DST offset ambiguity, overlap,
concurrency/deduplication, rollback, GPS pruning, current automation isolation,
deleted-ID fences, archived queries/pagination and immutable invoices. All await
GitHub Actions; no VM tests/builds ran. Typed Flutter bridge/forms remain future
work, so M3/M4 and the complete manual-history journey are not accepted. This
branch is independent of the held combined internal iteration.
Monitoring recovery run 43094466938 completed with successful formatting, analysis,
Flutter tests, Android build and native tests, but failed while exporting native
results/previews during the GitHub Actions incident. Release tooling did not run;
this is not a passed validation. Combined current main (834a7e4) into #17 for a
fresh complete validation including actual PDF checks. No VM build/test ran.

Monitoring recovery #17 head 4d3db27 passed full Actions 43101725472; inspected
193 native cases, zero failures/errors/skips. Merged exact head to main as
315bce1, then combined that source into #29 for final integration validation.
Physical app-upgrade/recovery evidence remains separate. Play is still held.

Combined head 12ea2b9 / 43103492055 passed formatting, analysis, Flutter tests,
Android build and 200 native tests (inspected XML, zero failures/errors/skips).
API 24 instrumentation passed all ten cases. API 35 failed the strengthened
all-row PDF extraction assertion: "Missing row 8 in MINIMAL"; nine other device
cases passed. The viewer step did not run, so combined acceptance remains open.

Current failure gating stopped before retaining API-35 preview files. Capture
documents before XML enforcement, preserve actual extracted text and render every
page of a failing document for diagnosis, retaining all failure gates. Reviewed
the existing API-24 first/middle/last minimal captures; this does not explain the
API-35 text mismatch or prove all styles/pages accepted. No production rendering
change or weakened assertion is being substituted for the missing evidence.
Play delivery remains disabled.

Diagnostic head 1cc258e / 43106859741 retained the failing API-35 PDF, extracted
text and all fifteen Minimal pages. Original-resolution review of page 4 shows
rows 8–20 intact with their amounts. The actual extracted text splits "section 8"
(and many subsequent glyphs) across individual lines; this is an extraction
whitespace mismatch, not evidence of a missing rendered row. Normalize whitespace
while still requiring every complete title together with its exact amount, plus
total/footer. Including amounts also avoids a shorter row number accidentally
matching a longer numbered title. Retain real PDF/render/coverage and failure
capture gates. All three styles and corrected viewer still await fresh Actions.

The independent session resolver's actual API-24/35 XML from 43105393744 confirms
three calendar cases on each API passed, alongside the earlier two PDF cases per
API. That run failed only formatting; the exact formatter patch is applied in
#30 and its corrected run remains active. No VM tests or rendering ran.
### Versioned job creation and strict timer transport — source prepared

The production create-job call still allocated a new ID on every invocation and
could not retain a response-loss intent. Prepared a versioned native command with
a proposed stable job ID, one atomic job/board/revision commit, deleted-ID fencing
and durable original outcome replay. New jobs capture the current default currency
once; a retry does not relabel an existing job. The old one-argument repository
helper is internal fixture convenience and is no longer used by production UI.

App creation retains command ID/job ID/name/version while uncertain, exposes the
existing retry action and blocks another app timer/create command until resolved.
The dialog permits the same 240-character name bound as job settings. Native timer
transport now rejects fractional versions instead of truncating them and still
captures its own timestamps/manual origin. Existing tracking remains independent.

Eight API-24/35 native cases (sixteen executions), three controller retry/concurrency
checks and one codec case are prepared, alongside the existing create/switch/stop
widget journey. All validation remains pending in GitHub Actions; no VM builds,
tests or formatting ran. This is an independent milestone-3 contract fix, not proof
that remaining history/widgets/backup work or full launch acceptance is complete.

Initial job-command run 43108121797 stopped at formatting and one analyzer lint:
the screen fixture's wrapped if statement needed braces. Applied the exact Actions
formatter patch and added that block. No behavior/build/native result is claimed;
corrected exact-head validation remains required.
PR #30 initially had no Actions run because its appended evidence conflicted with
the newly merged recovery notes. Combined main 315bce1 and retained both histories
to restore PR mergeability; fresh validation is required, not assumed passed.

Added strict native transport for session commands, cursor history and revisions.
Four API-24/35 bridge cases (eight executions) cover real dispatch round-trips,
forged-origin isolation, malformed integer/cursor rejection, response-loss replay
and stale-draft protection. Native bridge code is prepared; typed Flutter API/forms
and successful CI evidence remain pending.

Prepared the typed Flutter history API for immutable drafts, native cursor pages,
revision ancestry/purged payloads and explicit command IDs/expected versions.
Three codec checks cover preserved milliseconds/timezone context, cursor identity,
immutable revision parents and response-loss propagation without automatic duplicate
submission. These verify transport only; the actual native storage cases remain
separate. API source and new checks await Actions; history forms are unfinished.

Earlier history head b2af063 / run 43103934067 passed its Android build and native
unit step. Inspected XML confirms 215 native cases with zero failures/errors/skips,
including all fourteen history and eight bridge executions. That full run subsequently completed successfully; newer
Flutter API and wall-time resolver validation remain required.

Prepared native strict wall-time resolution for the correction form: return both
UTC/offset choices for a repeated local time, one for ordinary time and no choices
for a clock gap/skipped date. Keep milliseconds and reject invalid dates/zones.
Three actual-framework cases per API (24/35) are added to the instrumentation
class list; these remain pending, without mocked ICU success. Flutter transport
exposes the choices; forms and full milestone acceptance remain unfinished.
Reference: https://developer.android.com/reference/android/icu/util/Calendar#setRepeatedWallTimeOption(int).

Applied the exact formatter artifact from current wall-time run 43105393744 to
the Flutter API and codec checks. Analysis/behavior/device results for this new
source remain under validation. No VM formatter, test, build or rendering ran.

History #30 exact head 026824c passed full Actions 43105996492. Inspected XML
confirms 215 native tests and ten actual Android cases across API 24/35, all with
zero failures/errors/skips, including three calendar cases per API. Merged to
main as 8bb533d. This branch now includes that source; fresh combined validation
is required. History UI and full M3/M4 acceptance remain incomplete. Play is held.

PDF assertion head d79957d passed full Actions 43108568856, including ten actual
cases on each Android API and the viewer/landscape checks. Downloaded actual XML
confirms zero device failures/errors/skips. The whitespace-aware priced-row check
passed for all 130 items in all three styles. Current #29 also includes validated
main history contracts and requires thirteen actual device cases per API.

Visual review of the passing viewer artifacts found a remaining capture defect:
all three styles had identical hashes within each theme and the document area
was blank. The test captured an Image widget before asynchronous frame decoding.
Wait for the actual memory image to decode and assert a non-null painted image
before capturing. This is an artifact/verification correction; actual decoded
viewer visuals still need new Actions review. Play remains held.
Job-command run 43108685907 passed Flutter checks and the Android build but
reported four native failures (two fixture issues on each API): direct SQL
inspection preceded the lazy helper's first open, and the timestamp assertion
incorrectly expected the business-fixture clock to control native surface taps.
Open storage via a normal snapshot before inspection and verify the tap's captured
time against the actual native call window, still rejecting the caller's supplied
Long.MAX_VALUE timestamp/automation origin. Production timestamp behavior is
unchanged. Corrected combined-main validation remains pending.

Job commands #31 exact head 0f0c7c4 passed full Actions 43115650224; inspected
231 native cases including sixteen new job-creation executions, zero failures/
errors/skips. Merged to main as 4c253b7. Final business integration now includes
that validated source as well as #30 history contracts; fresh combined checks
remain required. Physical acceptance and manual-history UI are unfinished.

Reviewed the nine original-resolution API-35 multipage captures from successful
run 43108568856: first, middle and final pages for Minimal/Classic/Modern.
Long headers/footers wrap within margins; repeated headings/page numbers and
aligned task amounts remain legible. Together with the priced-row extraction
checks and earlier single-page logo/total review, native PDF layout acceptance is
supported. Decoded Flutter viewer captures and the current combined exact-head
checks remain required before lifting the Play hold.

Viewer head 65b0f44 / 43115429141 passed build/native checks but the device step
stopped before instrumentation: the pinned emulator archive transfer failed with
curl 92 (HTTP/2 stream INTERNAL_ERROR). No device XML/viewer result was produced.
Rerunning the failed job for the same commit is an infrastructure retry; the full
combined/device/decoded-viewer gate remains required. Play stays disabled.

Job-command head 0f0c7c4 / 43115650224 passed its native step: inspected XML
confirms 231 cases, including all sixteen job-creation executions, with zero
failures/errors/skips. Full workflow/device completion is still required before
merging #31. Source has not been built or tested on this VM.

Viewer retry 43115429141 attempt 2 / head 65b0f44 completed successfully.
Downloaded six decoded light/dark captures; hashes differ across all style/theme
pairs and original-resolution review shows the actual PDF pages, logo, totals,
email/save/share controls and page navigation. The earlier blank-capture issue is
resolved in the verification path. Native multipage review and viewer acceptance
are now supported; final combined head 8e0e19e still requires its own full check.

### Combined iteration exact-head acceptance and main merge

Full Actions 43117726325 passed exact head 8e0e19e. Inspected XML: 238 native
cases, thirteen actual Android cases on API 24 and thirteen on API 35, zero
failures/errors/skips. Six final decoded viewer captures match the reviewed
light/dark images. Actual PDF all-style multipage review and all 130 priced-row
checks passed. This supersedes the earlier pending/held iteration statuses.

PR #29 merged as cb2efc9d9aa3dd8fb2b183ebc2eb457e71c514ec. No open PRs
remain. Play workflow 414395901 is active; production readiness remains unset.
Main Actions 43120365067 passed; automatic delivery 43121445107 is queued.
This iteration's signed upload and Play acceptance are not yet claimed. All execution remains in GitHub Actions.
Full manual-history UI, widget configuration, providers, backup/merge and launch
acceptance remain unfinished; physical GPS/launcher/email compatibility remains
a separate device-validation gap.

### Manual history UI and retained changes — prepared for Actions

Implemented a job work-history route with native pagination, manual add/correct
forms, original timezone/millisecond preservation, explicit offset choices and
gap rejection. Commands capture immutable intent for unknown-response retries;
forms/navigation are locked until resolved. Refresh keeps drafts and only adopts
a newer version when the saved entry still matches its baseline. Changed source
requires explicit reload/review. Running sessions remain read-only until stopped.

Added red confirmed deletion and job-wide paginated change history, including
deleted entries. Native insertion ordering survives wall-clock rollback, and
job-scoped detail/audit reads honor job purge. Added query/transport tests, a real
API-24/35 time-format/resolve round trip and UI loss/stale/gap/deletion/history/
large-text cases plus six synthetic preview captures. All validation is pending
in GitHub Actions; no local execution or completed M4 acceptance is claimed.

History PR #32 is open as a draft. Exact head 71d23b6 is being validated in
Actions 43121754750. Earlier run 43121577416 was cancelled only after its source
was superseded. Additional local review bounds calendar-picker seed years and
makes UI test scrolling independent of the previous scroll position; these
changes must be included in the next validation head. Main delivery 43121445107
is currently building with the permanent key; acceptance remains pending.

History run 43121754750 stopped at analysis: ten unbraced conditional lints in
the screen, one in the codec fixture, and a missing closing parenthesis in the
widget harness. No behavior/native/device check ran. Corrected those source
issues; formatter output was unavailable because the syntax error stopped the
format step before patch generation. Fresh Actions evidence remains required.

### Combined business internal upload verified

Main validation 43120365067 and automatic Play delivery 43121445107 succeeded
for cb2efc9. Inspected delivery logs confirm permanent-key signing, automatic
versionCode 216603660 and versionName 0.1.0-internal.19.1. Play accepted the
internal upload at 2026-11-11 23:45:25 UTC. This supersedes the queued/building
notes above. Tester availability/installation and physical GPS/launcher/email
compatibility are not inferred, and production readiness remains unset.

Applied the exact Actions formatter patch from 43122136231; analysis passed
on that source. Its behavior step remained live during review. The new preview
harness awaited real font/file work inside the fake widget-test clock, unlike
the accepted preview harnesses. Move font loading and image/file capture into
tester.runAsync and retain real capture assertions. Also queue a fresh read
when native versions arrive during a history request, with a held-response UI
regression. New source needs fresh Actions checks; no device/native result from
that old run is inferred.

Corrected history head ebfda2a is being validated in 43122948408. Applied its
exact Actions formatter patch for the queued-refresh regression and real-I/O
capture calls. Behavior/native/device results are still pending; the formatting
correction requires an exact-head validation before merge. No VM execution ran.

Run 43122948408 completed FAILURE: 90 Flutter cases passed, one failed; seven
existing device-preview-only cases were deferred to the post-device invocation.
The failed history retry case scrolled a pending save button outside the lazy
list cache, discarding its captured command. Other history cases and eight
preview captures passed. Preserve BusinessCommand state with native Flutter
keep-alive while its outcome is unknown, releasing it after a known result. Keep
the scroll-away/retry test unchanged to verify the actual defect is fixed.
Android build/native/device stages did not run after this UI failure.

Reviewed all eight original-resolution history captures from ebfda2a /
43122948408: list, editor, offset/save section and deleted-change history in
light/dark themes. Text, timestamp precision, selected offsets, scrolling save
actions and red deletion are legible. This is synthetic UI visual evidence, not
a native history/device acceptance substitute. Current retry fix 386ce0d is
being validated in 43123476933; exact-head native/device checks remain pending.

### M4 per-widget configuration contract — source prepared

Prepared native schema 6 device-local picker/default bindings and unique tokens,
versioned/idempotent configuration commands and a guarded widget-start boundary.
Replaced/removed bindings cannot start work; duplicate committed taps cannot
restart after clock-out, and another running job is never silently switched.
Archived/deleted defaults remain explicitly unavailable instead of changing to a
picker silently. Bindings retain only an opaque job ID, with no job name/history,
and are excluded from the future portable backup contract.

Eleven API-24/35 native cases (twenty-two executions) cover independent persistent
widgets, response replay/stale state/invalid drafts, native clock and widget
origin without Flutter/GPS, archive/restore/purge, duplicate callbacks, competing
widgets, ID reuse, schema-5 preservation and migration rollback. New-schema
expectations are updated; all checks await Actions. Launcher configuration,
RemoteViews/start integration, reconfiguration/removal wiring and actual launcher
evidence remain unfinished. No local builds/tests ran or M4 acceptance claimed.

### Manual history exact-head acceptance and merge

PR #32 head 6c0f4df passed full Actions 43123810250. Inspected run-specific XML
contains 242 native cases and fourteen actual cases on each Android API (24/35),
zero failures/errors/skips. Eight exact-head history PNGs match the reviewed
light/dark list/editor/offsets/change views. The unchanged scroll-away retry
regression passes with pending-command keep-alive. No VM tests/builds ran.

Merged #32 as 863c9a0ce8f508886df7b3b2955d52904b0e8b85. Its main checks and
subsequent automatic internal delivery are separate and still pending. Full M4
widget configuration, physical launcher/history/recovery acceptance and the
remaining provider/backup/launch gates are not complete.

### Priority: owner-reported main Settings save process crash

Owner confirms main Settings Save exits to the Android home screen with an
"app stopped working" message; job-settings Save does not crash. Paused widget
implementation. Investigation branch is based on current main, excluding the
unreleased widget schema. Added an actual Flutter/native-channel/SQLite Android
settings-save/reopen journey and synthetic-emulator logcat capture to reproduce
the process failure. No fix or reproduction is claimed before Actions evidence;
no local builds/tests are permitted or performed.

Source audit found InvoiceEmailTemplate's singleton initializer compiled a regex
with an unescaped literal right brace. Android libcore explicitly documents that
its ICU regex backend requires escaping it; JVM/Robolectric accepted the old
pattern. Main Settings invokes this initializer; job settings do not. Escape the
brace and add actual Android validation/expansion/native-save coverage, retaining
the full actual Flutter save/reopen test. The baseline diagnostic run remains
live to capture the original failure; do not claim device reproduction/fix yet.
Reference: https://android.googlesource.com/platform/libcore/+/refs/heads/master/ojluni/src/main/java/java/util/regex/Pattern.java

Owner requires guarded, actionable validation for both email subject and body.
Replace the platform-sensitive regex entirely with a bounded, nonrecursive
placeholder scanner. Both fields reject unknown, unfinished, nested or unmatched
braces, lengths/control characters as appropriate. UI validates both controllers
even when a field is offscreen, shows inline errors with supported-placeholder
guidance and blocks save until corrected. Native save returns field-specific
conflicts without changing settings/version; invoice preparation also rejects
malformed stored templates before allocating a number. Regression cases cover
both fields, correction, literal replacement values and native transaction safety.

Baseline 43126720643 did not reach Save: Flutter accessibility descriptions had
a leading newline and the exact-label harness missed the menu. The retained
actual tree identified that harness issue; trim the labels and retain the full
save/reopen gate. Its result is not evidence that the original crash was
reproduced. Add a safely caught legacy-pattern compilation check on real Android
to establish the platform mismatch directly. New checks await Actions.

Owner requests the first invoice number 1000. Updated new-database defaults and
the atomic allocator's legacy floor to produce INV-1000, INV-1001, etc. Higher
existing sequences continue, and stored invoice records are never renumbered.
Number/file-name/rollback assertions and a low/high legacy counter regression
are prepared; validation remains pending alongside the priority crash guard.

Dispatched corrected UI lookup against unfixed app source on branch
settings-crash-baseline (2cdf329), run 43128896688, to verify the original Android
process failure independently of the proposed fix. This diagnostic branch is
not a release candidate and must not be merged. No failure result is assumed.

Extended the actual Android template case through native manual-work creation
and invoice preparation: require INV-1000, the expanded subject and the real
hourly total in the email body on both APIs. This closes the prior gap where
actual PDF renderer cases used prebuilt snapshots without exercising native
template expansion/allocation. The final source still needs its exact-head run.

### Actual process crash reproduced; guarded Android path passes

Unfixed source 2cdf329 / run 43128896688 reached Save on actual API 24 and
terminated the app process. Inspected AndroidRuntime logs identify
InvoiceEmailTemplate.<clinit>, PatternSyntaxException for the unescaped literal
right brace, wrapped in ExceptionInInitializerError and followed by process
death. This is a reproduced process failure, not a mocked/assumed cause.

Guarded source a30d0b7 / 43128476006 passed 244 native host cases and sixteen
actual Android cases on each API (24/35), zero native/device failures/errors/
skips. Both Settings save/reopen and native subject/body validation/correction
cases passed. The whole workflow failed only the clean-format gate. Applied
that exact formatter patch; final source 145870a additionally includes INV-1000
allocation and actual native invoice preparation. Run 43129744209 is live; no
final-head acceptance/upload is claimed. Widget #33 remains paused and must
combine the fixed main source before its eventual merge.

History main 863c9a0 passed 43125332760 and Play delivery 43126724653; inspected
logs confirm accepted 0.1.0-internal.20.1 / versionCode 216606241 at 00:29:37 UTC.
That build predates the settings fix. Production readiness remains unset.

### Priority fix final acceptance and merge

Full Actions 43129744209 passed exact head 145870a. Inspected final XML confirms
245 native cases and sixteen actual Android cases per API (24/35), zero failures/
errors/skips. Actual Flutter Settings save/reopen, native invalid subject/body
rejection/correction and real invoice preparation/expansion with INV-1000 pass.
Merged #34 as c5e19d15a06ccd31e85455e5ab4782bcdf36a88d. Main validation and
automatic internal delivery for this new merge must pass before claiming Play
acceptance of the fix. The original crash was independently reproduced against
unfixed source in 43128896688. No builds/tests ran on the VM.

Prepared explicit physical-device regression steps for the delivered #34 settings
fix, both template fields, INV-1000 progression and manual-history/timezone
behavior in docs/DEVICE_VALIDATION.md. These are procedures, not device results.
Local widget branch now includes fixed main c5e19d1 and all priority evidence;
its former successful run predates this integration, so fresh combined checks
are required before any widget merge. Priority main run 43131240701 is live;
widget UI implementation/publication remains paused until internal delivery.

### Priority hotfix delivery accepted; M4 work resumes

Main validation 43131240701 succeeded for c5e19d1. Inspected main artifacts
contain 245 native tests and 32 actual Android tests, zero failures/errors/skips.
Signed delivery 43132531984 succeeded; inspected logs allocate versionCode
216609104, build 0.1.0-internal.21.1 and confirm Play internal acceptance at
2026-11-12 01:17:19 UTC. The priority settings crash, both-template guards and
INV-1000 numbering are delivered internally. Actual tester installation and
physical phone retest remain separate, and production readiness is unset.

M4 widget UI/integration work can resume. Local widget source incorporates fixed
main plus retained milestone evidence; new combined checks are required. Full
application acceptance is still incomplete (widgets, physical tracking/recovery,
providers, encrypted backup/merge and launch/store gates).

### Expanded responsive widget iteration — in progress

Owner added Timer, Tasks and Combined widget types. Combined must show only
timers when compact, add a scrollable due list when space permits, and open a
task's actual job board when tapped. Task-only lists support all active jobs or
one selected job; overdue and next-seven-calendar-day incomplete tasks are
included, archived/completed work excluded. Configuration, native start/stop,
reconfiguration/removal/restore and stale choices remain part of this iteration.
The source contract is prepared, but launcher UI/collections/responsive behavior
and physical acceptance are not yet implemented or claimed.

### Responsive widgets source prepared for Actions

Implemented three native providers, compact/expanded Combined layout, scrollable
due-task collection, per-widget active-job/picker filtering and Flutter setup with
immutable command retries. Task taps enter the corresponding board through a
separate activity; native clock-in/out preserves serialized timer ownership.
Restored launcher IDs deliberately reset to picker mode without starting work.
Added native date/filter/large-collection and size checks plus Flutter lost-reply
and unavailable-default checks. Prior combined foundation run 43133758021 passed;
this new source still requires its own Actions and launcher evidence. No local
builds or tests were run. Physical launcher acceptance remains open.

### Widget emulator iteration: real startup regression caught

Actions 43136781343 passed format/analyze/Flutter, Android build and 272 native
cases (zero failures/errors/skips). API 24 real host scrolling, all three providers
and task PendingIntent handoff passed. Existing Settings UI and new setup UI
correctly failed: custom initial routes were accidentally combined with `home`,
which Flutter rejects at startup. Replaced redundant `home` with an explicit `/`
route and added full-app route construction plus actual board-render assertions.
This failed run is not acceptance and no new widget build has been published.

### Next task ordered by owner

After current widgets are validated and merged, implement paid status and PAID
invoice receipts. Add optional accounts-receivable email in main Settings; use it
as CC for every invoice/receipt email when present, otherwise CC company email.
Draft creation must never imply payment or email delivery. Section 3.5a contains
the contract and acceptance checks. No payment/receipt implementation is claimed.

Widget source ed791a3 passed Flutter/build/272 native cases in 43138373244.
API-24 Settings save passed; the test host incorrectly replaced a live Flutter
view and caused a native accessibility abort. Replace the test host with a native
debug-only Activity and retain the actual collection/setup/board gates. No local
build/test and no new widget publication occurred.

Owner clarification: exclude previously invoiced work from subsequent invoices
for that job, whether the first invoice is unpaid or paid. Current InvoiceStore
does not yet keep source claims; this is planned work after the widget merge.
Cover overlapping dates/timezones, concurrent preparation, task IDs, partial UTC
intervals, edits, response-loss retries, empty billable remainder and safe legacy
invoice reconciliation. Payment/receipt/resend must retain original coverage.

### Detailed owner roadmap after current widgets

Added docs/INVOICE_LIFECYCLE_PLAN.md and linked it from the product contract. It
plans partial/full payments and balance copies, exclusive billing claims, void and
replacement with preserved partial credit, quotes, Kanban comments/supplies and
actual expense records, company reports, and main/job tax percentages. Tax on a
100.00 subtotal at 7.5% is 7.50; issued totals remain immutable and tax is separated
from revenue/profit. No planned financial feature is marked implemented.
Current widget source is still under Actions; finish/merge it before starting
the invoice iteration. No local builds/tests were run.

### Real widget setup passed; board lookup and diagnostic retrieval corrected

Partial API-24 evidence from 43140436576 confirms actual main Settings save, real
widget configuration save/cancel and all provider renders passed. The remaining
assertion expected a standalone `To do` label, while Flutter merges column title
and task-count subtitle. Match the exact visible semantic line and always finish
the monitored board activity, including failed assertions. The run then stalled
attaching post-test logcat; retain the continuous stream and bound fixture pulls.
No passing full emulator acceptance or widget publication is claimed yet.

### Actual widget choice verification strengthened

43143381176 passed Flutter/build/native checks and API-24 Settings, actual task
scrolling/board rendering and all providers. Setup intermittently left the picker
selected: pinned Flutter AccessibilityBridge acknowledges ACTION_CLICK even when
the addressed node has no TAP semantic action. Require a clickable target, search
children before merged ancestors, and verify the actual checked choice before
Save/Back. Keep the native binding and cancel assertions; do not count a transport
acknowledgement as user selection or acceptance. Full Actions rerun is required.

### First complete API-24 widget journey verified; API-35 board-body observation open

6033cbe / 43144846081 passed 272 native cases and all 19 actual API-24 cases,
zero failures/errors/skips. API 35 passed 18/19, including actual setup checked
choice/save/cancel and Settings. Its board header and 0-of-30 loaded task summary
were visible, but the column subtree was absent from cached accessibility nodes.
Refresh virtual nodes and retain a separate actual board screenshot before the
unchanged column assertion; later setup screenshots must not overwrite failure
evidence. Full API-35 board acceptance remains open, not assumed from its header.

### Collection publication and native timer-surface checks

43146802062 retained all assertions and caught an intermittent first-bind task
collection failure on API 24 before board capture. Source review identified
concurrent widget refresh callers that could publish stale size/state after a
newer refresh. Serialize refresh publication; load the remote factory on creation
as well as dataset change; key adapters by per-widget configuration token. Return
task filtering metadata in the same native read as its rows. These are source
corrections awaiting Actions, not an assertion that this failed run passed.

Also retain the configured task job's visible/accessible label when a different
job is running. Added an actual denied-location, no-Flutter-screen clock-in/out
journey through real PendingIntents and two widgets, asserting native WIDGET
origin, positive duration, no location history, and separate task-filter context.
The required actual widget class count becomes four per API (20 total per API).
All builds/tests remain Actions-only; full acceptance is still pending.

### Actual board capture and native clock-in observation

43148476659 passed 272 native cases; API-24 task binding, scrolling, actual board
rendering/column lookup, provider layouts, setup save/cancel and Settings passed.
Reviewed the separate actual board image: populated To do cards and Doing column
render correctly. The new native action case reached WidgetStartActivity and
committed its configured job, but incorrectly expected ActivityMonitor.getHits()
to count a PendingIntent launch. Observe the matched activity directly (as the
board test already does), assert no resumed Flutter screen, then continue through
the actual clock-out broadcast. Also publish final bindings before creating host
views so the test never clicks a cached pre-configuration version. Full 20-case
checks on both APIs remain pending. Launcher descriptions now distinguish all
three widget choices. No test/build ran on the VM and no widget release is claimed.

### Actual API-24 surface journey passed; API-35 reapply and pending-form bugs fixed in source

bea20be / 43150196358 passed all 20 actual API-24 cases, including real native
clock-in/out with denied location, WIDGET origin, no samples, and separate task
job context. API 35 caught a real RemoteViews reapply bug: addView appended timer
rows instead of replacing them. Clear the container before adding its one timer;
repeat real updates and assert exactly one row.

API-35 setup also exposed a commit-version-before-response race: timer snapshot
reload replaced the form, disposing its pending BusinessCommand before the save
reply and leaving controls locked. Keep prior data during reload/read failure,
key the pending command, and add deterministic delayed-read/reply Flutter cases.
Move platform widget-availability checks inside the native save command after
receipt replay so widget removal resolves an original save without resurrecting
bindings; fresh unavailable writes return a known conflict. Added native retry/
removal checks. Preserve per-test screenshots and require actual board window
focus; the column assertion stays required. New source awaits Actions.

### Board-body failure identified as a real framework assertion

4b1951a / 43153617082 passed the new Flutter save-race regressions, native checks
and all 20 actual API-24 cases. API 35 passed native clock-in/out, repeated-update
row count, separate task scope and setup save/cancel; only the board-body check
failed. Its preserved per-test screenshot shows a real `_InactiveElements.remove`
assertion in the board, not merely missing accessibility labels. Keep that test.

The board's width-only LayoutBuilder was building tooltip/overlay-bearing cards
during layout. Replace it with a normal build-phase Builder and window-relative
MediaQuery width; give vertical columns independent scroll controllers. Similar
upstream OverlayPortal/layout failures are documented at
https://github.com/flutter/flutter/issues/180569 and
https://github.com/flutter/flutter/issues/183168. This is an evidence-informed
workaround, not a claim that the app's first stack has already proven that cause.
Debug builds now forward bounded Flutter error type/stack only to local Android
logcat for CI diagnosis, preserving normal presentation and excluding field/widget
dumps; release error handling is unchanged. Apply Actions-generated formatting,
finish removed-widget setup as cancelled, and revalidate the full source.

### Board viewport fixture corrected before renewed device checks

19781aa / 43156318971 passed analysis and the new zero-to-ready window-metrics
regression, but two existing board journeys failed because their shared fixture
overrode MediaQuery with an implicit zero size. Preserve the real test view's
metrics while overriding text scale; keep the pricing/editor assertions and the
separate intentional zero-size regression. Applied the Actions formatter patch.
No actual Android validation ran for that head after the Flutter test gate failed;
the board workaround still needs full Actions and actual API-24/35 evidence.

### Complete actual widget journeys pass on both Android versions

3332465 / 43157030521 passed 101 Flutter cases (seven device-document previews
intentionally deferred), Android build, 274 native cases and twenty actual cases
on each API (24/35), with zero native/device failures/errors/skips. The separate
actual-document viewer stage passed its seven cases. Actual framework tests prove
scrolling through all thirty tasks, correct board entry and populated columns,
all three providers, real setup save/cancel, repeated updates without duplicate
rows and native clock-in/out without a Flutter screen or location permission.

Reviewed run-specific board, scrolled Combined list, compact running timer and
configuration captures from both APIs. The prior API-35 board red screen is absent.
Only the required clean-format gate failed: applied its exact Actions-generated
test-expression patch. Final exact-head validation and subsequent main/internal
delivery remain required; physical launcher/GPS/email acceptance remains separate.
No build, test or formatter ran on the VM.

### Owner physical Settings regression result

2026-11-12: owner reports a Pixel 7 running Android 17 now saves main Settings
without crashing and appears to work. Owner confirms updating to the latest
available app build immediately before testing. Latest verified Play delivery at
this point is 0.1.0-internal.21.1 / 216609104; Android App info was not transcribed.
This is physical-device confirmation of the reported Save crash correction, not
the invalid-template correction, widget, GPS, email, upgrade or recovery matrix.

### Final widget acceptance in Actions and merge

Final head 6cadef0 passed full Actions 43158333007, including formatting, analysis,
101 Flutter cases, Android build, 274 native cases, twenty actual Android cases
on each API (24/35), seven actual-document viewer cases and locked release-policy
checks. Native/device XML has no failures/errors/skips; the seven preview cases
intentionally skipped in the general Flutter stage passed in the dedicated stage.
Reviewed all eight final board/list/timer/setup captures from the two API versions.

Reviewed the public file list, confirmed unchanged tested main base and no active
competing main runs, then merged #33 as 8c2f3fdfb3853c6a567f0a5f050f003fa27c39a0
at 2026-11-12 05:11:06 UTC. Main validation 43159664866 is active; no new Play
upload is claimed yet. Production readiness remains unset and physical widget/GPS/
email/recovery acceptance remains open.

Started the next owner-ordered invoice slice in an isolated invoice-integrity
worktree based on the actual merge commit. Read-only review confirms preparation
currently reuses full report contents, without durable billed-source claims, and
job purge currently removes invoices. Coverage, tax, safe legacy reconciliation
and retained financial records therefore require native/schema changes before the
payment and receipt features. No new financial implementation or validation is
claimed by this review. Owner's successful latest-build Settings retest is recorded
above. No build, test, dependency resolution, formatter or rendering ran on the VM.

### Invoice source coverage and retention foundation prepared

Prepared schema 7 and native coverage filtering/reservation inside the same
serialized transaction as invoice allocation. Active task IDs are unique; UTC
claims use half-open job intervals with database overlap/job guards. Repeated or
partially overlapping selections bill only uncovered work. No remainder consumes
no invoice number. Claim failures roll back documents, numbers, revisions and
command receipts. Billed session boundaries cannot be moved out of their reserved
span; expansion can add unbilled work. Work deletion preserves the claims.

Issued invoices, cached PDF/logo references and financial revisions survive job
purge; both deletion warnings explain that retained history. Existing invoices
without source identities are explicitly LEGACY and block further billing for
that job pending a reviewed reconciliation journey, rather than guessing claims.
A frozen released-schema-6 fixture covers migration preservation and rollback.
Prepared twelve API-24/35 coverage cases, three migration cases on both APIs, and
five exact tax parsing/rounding/overflow cases. Existing snapshot/sequence tests
now add genuinely new work before expecting a second invoice. None has run yet.

This is incomplete source awaiting Actions, not a ready business release. Billing
preview/exclusion links, legacy reconciliation, global/job tax settings and totals,
retained-invoice navigation and integrated real-device/PDF journeys remain before
this slice can merge. Payment/receipt/CC/void work follows its foundation. No VM
build, test, formatter, dependency resolution or rendering was run.

Widget main 8c2f3fd passed full 43159664866. Downloaded run-specific XML confirms
274 native and forty actual Android cases, zero failures/errors/skips. Automatic
Play internal delivery 43160879953 is active; do not claim accepted upload/version
yet. Invoice foundation beec0cf is draft #35 with Actions 43160987076 active; no
financial tests/builds are claimed passing until that run supplies results.

### Widget internal delivery accepted; tax integration source prepared

Play delivery 43160879953 successfully built and verified the signed bundle with
the permanent upload key, allocated 0.1.0-internal.22.1 / 216624221, and accepted
its internal upload at 2026-11-12 05:27:54 UTC. This is widget merge 8c2f3fd, not
the unfinished invoice branch. Tester installation/availability remains separate.

First invoice run 43160987076 stopped in Flutter tests: a deletion dialog assertion
still expected the old reusable-template wording. Update it to assert retained
invoices/billing history and templates/customers. Applied the CI-exported formatting
for the existing command messages. Native coverage/migration checks did not run
past that failed Flutter gate; no passing financial validation is claimed.

Added source for global default tax and nullable per-job inheritance/override,
including explicit 0%. SQLite stores checked integer percentage units in revisioned
billing preference rows; save validation is authoritative and transactional. Native
transport rejects floats/strings/booleans. Compatible zero/inherit command values
retain released receipt fingerprints. Tax-bearing invoices freeze subtotal, rate,
tax and grand total; email amount uses that total, and native PDF styles show the
breakdown. Legacy invoices retain recorded totals and unknown tax, never today's
rate. Both forms preserve invalid drafts with inline correction guidance.

Prepared native tax persistence/calculation/inheritance/atomic rejection/immutability
cases, strict bridge cases, decimal parsing and real form correction cases. Actual
Android single/multipage PDFs for all three styles now assert 7.5% tax, subtotal
1250.00, tax 93.75 and total 1343.75. Actions must build/test/render this source;
preview, legacy reconciliation and retained invoice navigation still block merge.
No VM build, test, formatter, dependency resolution or rendering was run.

### Tax/coverage checks ran; review and retained-invoice navigation prepared

a9c17bd / 43162233917 passed analysis, 105 Flutter cases (seven actual-document
previews deferred), and Android build. Native XML contains 325 cases, three
failures and no errors/skips. All new coverage/migration/tax/bridge cases passed.
Two JobCreation rollback cases assumed settings were the only initial revisions;
capture the complete initial revision count instead and require rollback to it.
LogoStore's purge case expected a retained invoice logo to disappear; require the
original bytes/reference to remain. Actual Android PDF/widget stages did not run
past those failures. Applied the run's exported formatter patch to its source.

Added native billing preview using the same unbilled lines, exact subtotal/tax/
total and validation as preparation. It allocates no numbers or claims, exposes
existing-invoice links for excluded work and hides unproven totals while legacy
coverage is unresolved. The separate Review invoice route requires Create invoice
before allocation and keeps the pending command/key across version-before-reply
reloads or read failures. Reports/CSV still describe all selected work.

Added main-menu Invoices and richer per-job/all-job lists, including frozen job/
customer/issued date, removed-job marking and retained PDF navigation. Native
preview equivalence/no-allocation and removed-job index checks plus UI response-
loss and navigation cases are prepared. Legacy coverage reconciliation is still
missing; this slice must remain a draft until that reviewed journey and final
native/device/PDF/visual checks pass. No new source is claimed validated by writing
it. All builds/tests/formatting/rendering remain Actions-only.

### Preview checks pass; Classic pagination correction and native legacy review prepared

647f941 / 43163572642 passed 106 Flutter cases and 329 native cases, zero native
failures/errors/skips, including the preview, retention and corrected rollback/
logo cases. Actual API 24 ran twenty cases: nineteen passed, while the three-style
short PDF case produced two pages for Classic. Minimal's one-page tax PDF was
captured and reviewed; Classic was the next style in the preserved loop. API 35
and actual-document viewer stages did not run after that failed device gate.
Applied the run's formatter patch. Adjust Classic text/row gaps while preserving
font sizes/content and the one-page assertion; preserve failing PDF/first/last
captures before future assertions. This adjustment awaits actual Android proof.

Prepared native explicit legacy coverage reconciliation and typed transport. It
requires a review note, confirmation and selected task identities/exact UTC ranges
with IANA timezone context; empty mappings need a separate acknowledgment. It does
not infer sources from titles/prices or change original documents, amounts, tax,
payments, numbering or work completion. Task ownership, overlapping ranges and
existing reservations are guarded before writes; review/claims/revisions/receipts
commit together. Original outcome replay survives review completion and job purge.

Legacy ranges can reserve removed historical work. A live entry overlapping any
active job reservation cannot be shifted out of its already-billed portion, even
when the reservation lacks the original session ID. Native context shows current
incomplete/completed tasks and existing invoice ownership links. Prepared twelve
API-24/35 legacy validation/rollback/concurrency/replay/transport cases plus a Dart
immutable-intent/codec case. The user-facing source/time editor and its integrated
journey remain unfinished; no reconciliation acceptance or full-slice completion
is claimed. All new source awaits Actions; no VM validation/rendering ran.

### Native legacy reconciliation and tax PDFs pass; full review UI source prepared

9c72416 / 43165815309 passed analysis, 107 Flutter cases, Android build, 353 native
cases, twenty actual Android cases per API (24/35), all seven actual-document
viewer cases and release-policy checks. Native/device XML has no failures/errors/
skips; the seven general-stage document skips passed in the dedicated stage.
Only the clean-format gate failed. Applied its exact CI patch, then restored the
two new draft-display fields without running a formatter locally. Reviewed the
actual one-page tax PDF for every style on both APIs: all six captures retain
subtotal/tax/grand total, priced rows, branding and footer, including Classic.

Prepared the complete legacy review form and covered-time dialog. Invoice lists
and blocked billing previews now open it. No sources are preselected; existing
reservations have read-only invoice links. Tasks can be searched/selected without
changing their work state. Exact times use calendar/time pickers or full input,
native IANA resolution, explicit repeated-time offsets, gap rejection and overlap
checks. Pending ranges can be edited/removed; stored reservations cannot.

A private note, original-document confirmation and separate empty-source
acknowledgment are required. The keyed form/command survives native completion
before reply or refresh failure, locks editing/navigation while outcome is unknown,
and replays one immutable intent. Confirmation dismisses the keyboard; save remains
available in a fixed footer. Separating invoice history from the PDF viewer keeps
navigation dependencies direct. Removed-job invoices still open read-only PDFs.

Prepared presentation cases for both refresh outcomes, missing-source validation,
repeated/gap/reserved times and CI light/dark captures with large text. These are
unvalidated source, not mocked native acceptance. The next required proof is the
actual Flutter legacy-selection/save -> unbilled-tax review -> new invoice ->
retained document journey, isolated from first-number regression fixtures, on
API 24 and 35 through Actions. No merge or invoice release is claimed yet.
No VM builds/tests/formatting/dependency resolution/rendering ran.

956f5da / 43169321012 stopped at analysis after exporting formatter output: twelve
multiline if statements needed explicit braces. Applied that CI patch and wrapped
the flagged blocks without changing their conditions or behavior. No new legacy
screen tests/native/device checks ran past the failed analyzer gate. Renewed
Actions evidence is required; the independent actual financial suite is next.


Owner quantity priority (current invoice patch): the existing `price_minor` is the
single unit price, renamed Unit price in the editor. No second price input is
introduced. Quantity defaults to one for released tasks and templates, supports
positive four-place exact decimals, and displays an extended total. Native reports
and invoice previews use per-line half-up totals before subtotal tax. New issued
lines freeze quantity and unit price; later edits keep the original source claim.
Template quantities remain editable defaults after source deletion. Source and
regressions are prepared; renewed GitHub Actions validation remains required.
No VM build, test, formatter or rendering ran.

94fd7c7 / 43169835589 passed Flutter behavior, Android build, native, actual
API-24/35 and document-viewer gates; only its final formatter gate failed. Its
exported patch was already included in the quantity commit. Quantity head
d52e35f / 43171494802 passed analysis and Flutter behavior; native/device gates
are still pending. Applied its CI-produced formatting correction; added strict
quantity transport, frozen-v6 default-one migration, save-payload and light/dark
editor capture coverage. Updated the old task-invoice basis assertion for unit
pricing. These additional changes require their own Actions run.

4477887 / 43172132517 stopped at analysis: the new capture used await on the
synchronous test keyboard hide call. Removed that await and applied the exported
CI formatter patch. No quantity native/device success is claimed from this run.

Prepared the independent real Android financial journey in a separate test package
and instrumentation process after the standard 20 cases/API. The Actions script
retains and checks the original XML/PDF/widget evidence before clearing only its
ephemeral emulator's app data. A frozen v6 database and real generated legacy PDF
exercise production migration and the actual Flutter UI: explicit old-task review,
unit-price/quantity save, unbilled subtotal/tax, new immutable invoice, repeat-billing
exclusion, and reopening the retained PDF after a synthetic aged-archive purge.
Strict separate XML requires one complete financial case plus seven captures on
each API. The frozen DDL is packaged only in the test APK. This source is not yet
executed; it does not constitute device acceptance or merge evidence.

Goal-turn progress: pushed the actual financial journey as 50662d2 (queued run
43173354558). Quantity head b4e547d / 43172649874 has passed analysis, Flutter,
Android build and all 365 native tests (35 XML files; zero failures/errors/skips).
Reviewed both CI-generated quantity editor captures: exactly one Unit price input,
Quantity 26 and calculated USD 130.00 in light/dark themes. Actual device/PDF gates
remain live. Extended the template regression to preserve quantity/unit price after
aged job purge and copy them to a new job without completion history. No merge,
new internal delivery or physical financial acceptance is claimed.

Quantity b4e547d / 43172649874 completed successfully. Downloaded and inspected all
35 native XML files: 365 tests, zero failures/errors/skips; each actual Android XML
contains 20 cases with zero failures/errors/skips (40 total). Formatting, analysis,
Flutter behavior, debug build, actual-document viewer and release-policy gates all
passed. Light/dark single-price quantity editor captures reviewed above. This run
does not include the new actual financial journey or extended purge/template case.
Latest pushed head 6c0222d is live in 43174102042; superseded queued 50662d2 run
43173354558 was cancelled before execution. Keep this financial slice unmerged
until its dedicated journey is proven on both APIs.

Prepared additional actual tax-form coverage in the same financial journey: main
Settings enters/saves 7.5% through the real UI instead of seeding the native tax
value; job Settings rejects 101 without a write, saves explicit zero, restores
default inheritance, and verifies the issued invoice stays frozen. Added both
tax-form captures to the required artifact list. Source only until Actions runs.

Clarified the next payment milestone's precision contract: quantity-extended totals
can exceed 64-bit minor units, so payments/credits/balances must retain arbitrary
precision and canonical string storage/transport rather than narrowing issued
amounts. Required conservation cases include large partial payments and transferred
credits. No payment implementation or payment acceptance is claimed.

6c0222d / 43174102042 passed build/native and the original 20 API-24 cases, then
failed the new journey while locating the visible review-note field. Captured
XML/tree/PNG show the selected legacy checkbox and an EditText with no label/hint
on API 24. Inspection of the pinned Flutter engine confirms hints are only exposed
from API 28. Added stable semantic field identifiers and focus-before-entry in the
actual test, retaining all actual writes/assertions. API 35 was not reached.

Owner money priority: audit finds exact BigInt/BigInteger totals, but released
rate/unit-price inputs/storage still use Long limits. Added an explicit exact-money
contract before implementation: pinned Dart decimal plus native arbitrary precision,
canonical decimal text, lossless migration/receipts and deliberate currency rounding
only. Removing these limits is required before this financial patch merges. No
unbounded-money implementation or success is claimed yet.

Selected decimal 3.2.6 from its primary package/author references and adapted Dart
money parsing, formatting and quantity products to string-parsed Decimal, with
explicit half-up only at the task minor-unit boundary. Added very large totals,
half-cent neighbors and signed formatting cases. Native BigInteger calculations
remain exact. This is not yet resolved or validated: use owner-only Actions
prepare_dependencies to produce pubspec.lock, never resolve on this VM. Existing
64-bit rate/unit-price storage/input limits remain until the exact-money migration.

Prepared native exact-money migration in this milestone: settings/job/task/template
prices are canonical TEXT with SQL guards; all typed money fields/reports use
BigInteger, and only checked small compatibility values reach the old INTEGER
columns. Explicit fingerprints preserve released small-number receipts and wide
values remain distinct from unset. Monetary revisions/templates survive source
purge correctly. Removed Dart Long input cap after completing the native path;
kept a 1024-digit input-size guard and explicit currency precision errors.

Applied the lockfile generated by Actions prepare 43176748417: decimal 3.2.6 and
its rational dependency are pinned. Applied its formatter patch (preserving newer
source edits). Added seven native money cases per API for wide rates/totals/tax,
immutability, purge/reuse, exact released fingerprints, opaque retries, rollback
and canonical DB guards; extended wide transport and frozen-v6 migration checks.
These native source changes remain unvalidated; no merge or delivery is claimed.
No VM tests/builds/formatting/dependency resolution/rendering ran.

1e51563 owner preparation 43176748417 passed the full actual financial journey on
both APIs: inspected four XML files, 20 original + 1 financial case per API, zero
failures/errors/skips (42 total). Main/default and job tax forms, explicit source
review, quantity save, taxed invoice, repeat exclusion and retained PDF all ran
through real Flutter/native storage. Only its final formatting gate failed; the
exported correction is applied in c5bf242. This proves the pre-money-migration
journey, not the newer exact-money storage. c5bf242 / 43178148571 is still live.

Added exact-money device acceptance to the next head: save/reopen a wide unit price
through the real task editor and require the exact extended board total before
returning to the normal invoice fixture. Each native PDF style must render a wide
subtotal/tax/grand total; API 35 extraction checks every digit. Original suite count
now requires 21 cases/API plus the separate financial case (44 total if successful),
with ten financial captures/API and all six new wide PDF/PNG artifacts required.
These additional checks are unvalidated source, not claimed device results.

Added a byte-size guard for expanded immutable invoice payloads before committing
a number or source claims. Wide exact prices and repeated amount tokens can grow
a single SQLite cursor row too large; preparation now returns INVOICE_TOO_LARGE
instead of truncating data or committing an unreadable invoice. A native regression
uses a valid repeated template and 1024-digit price to require no invoice, number
consumption or reservation. This new check remains unvalidated.

Exact-money c5bf242 / 43178148571 passed build/Flutter/native: inspected 381 native
cases with zero failures/errors/skips. Original API-24 suite passed 20 cases. The
financial case reached the issued/repeat-exclusion journey, then a test-only
waitFor queried a label twice while navigation refreshed the tree; its second
lookup returned null at line 231 (reopened board before job tax settings). Retain
the node found by the successful bounded poll rather than issuing another lookup.
No assertion is removed. API 35 was not reached. New wide UI/PDF and payload-size
cases still require fresh Actions evidence; formatting correction is being applied.

Source review found PDF visual overflow separate from arithmetic: amounts wider
than the price column could overlap descriptions, and the total stopped shrinking
at 9pt but could still extend off-page. Preserve normal rows/styles and route wide
line amounts and extreme totals through the existing paginated paragraph renderer.
Extended the actual all-style wide-money case with 1000-digit values, first/last
page captures and API-35 extraction of complete labelled row/subtotal/total values.
Only exact known page headers/footers are removed before joining text across pages;
monetary digits are never normalized or discarded. These changes need fresh CI.

7374335 / 43179838708 passed 383 native cases, zero failures/errors/skips, and the
21 original API-24 cases including wide-money PDF rendering. The financial case
entered the exact large price but lost the Quantity field during keyboard/inset
reflow between lookup and focus (tree/PNG confirm the focused price and hidden
quantity). Its test helper now dismisses only a visible IME with real Back, waits
for dismissal, confirms entered values and closes the keyboard between fields.
Interactive-window inspection supplies that visibility check; all native financial
assertions remain. Also fixes captures that could show the preceding invalid draft
before a queued input update painted. API 35 was not reached in this run.

Tightened the same input-helper correction: wait for the actual IME to open after
field focus before SET_TEXT, then confirm the exact value and dismiss it. Otherwise
a fast text-action acknowledgment could race the keyboard's later appearance and
skip dismissal. This is actual UI synchronization, not a replacement of native
saves or any financial assertion. No app-money behavior changed in this follow-up.

Goal-turn progress: final eb74c747cb8cbc6e343864ad9b471c910b131c26 passed complete
43182854787. Inspected 36 native XML files (383 tests) and four actual Android XML
files (21 original + 1 financial case per API, 44 total): zero failures/errors/skips.
Reviewed all six wide and twelve first/last 1000-digit PDF captures, exact wide-price
boards on both APIs, zero tax override and retained invoice. Ordinary layouts stay
clean; extreme values wrap and paginate; repeated full values are checked by actual
API-35 extraction. All other workflow gates passed, including actual-document viewer.

Merged #35 using the exact tested head guard; GitHub confirms f935485 at
2026-11-12 08:47:27 UTC. Main validation 43185844256 is live; new Play acceptance
and tester installation are not claimed. Created invoice-payments worktree/branch
from confirmed origin/main, preserving the prior worktree and unrelated work.
Updated the product handoff and next milestone status. Payments/receipts/CC/void,
quotes, comments/costs, reports and the remaining launch milestones stay unfinished.
No VM build, test, formatter, dependency resolution or rendering ran.

Payment UI 3731b80 / 43206450476 passed formatting and analysis but remained in
Flutter behavior tests during observation. Source review identified two test
cleanup paths closing a broadcast stream while offstage Riverpod subscribers can
remain paused. Dispose the ProviderScope first, then close the stream; preserve
all exact-money, command identity, retry and navigation assertions. This is a
test-lifecycle correction, not evidence of a passed behavior/native/device gate.
Fresh Actions validation is required; no local builds or tests ran.

### Native void/replacement source checkpoint (2026-11-12)

Prior turn made progress: pushed 7d414ee to correct paused-stream test cleanup and
updated draft #37 with accurate scope. Actions 43207931777 now passed formatting,
analysis, Flutter behavior, debug build and native unit gates; actual Android
document/payment journey remains live. Do not treat the whole run as passed.

New native source prepares confirmed/audited unpaid cancellation and atomic
replacement with edited existing rows, explicit owned-claim release and additions
from actual unbilled work. The default keeps the entire old claim bundle; old line
titles/prices are never used to guess source identity. Original documents remain
unchanged, cancelled predecessors have no collectible balance, and unresolved
legacy coverage stays unresolved on successors. Replacement freezes existing
credit at issue. Positive fully paid invoices reject void/replacement; a zero-total
Nothing due invoice may be cancelled to release reservations. Below-credit totals
are rejected. Transfers preserve payment IDs/dates and emit zero-cash, ordered
credit/tax/sales adjustments; corrections follow the current allocation. Cancellation
copies are separate immutable intents. Native regressions cover concurrency, replay,
rollback, exact wide/split money, source ownership, hourly intervals, legacy and
retained deleted-job history. Source only: new Actions validation, lifecycle bridge/
editor UI, actual replacement/VOID PDFs and physical email acceptance remain open.
This is still draft #37, not a completed or published payment milestone.

7d414ee / 43207931777 passed formatting, analysis, Flutter behavior, debug and
440 native cases (41 XML files, zero failures/errors/skips). Actual API 24 passed
21 original and one billing journey; copy generation then failed the explicit
one-page assertion on the short Classic paid-balance copy (two payments). The
new payment UI journey and API 35 were not reached. Reviewed the actual Classic
paid receipt PNG: values are correct and readable, but duplicated event detail
layout needs less vertical space. Copy-only renderer source now uses a full-width
compact event-detail paragraph and less header spacing; original invoice layout
and immutable copy payloads are unchanged. Preserve the one-page assertion and
all complete-value checks. New validation is required.

Lifecycle bridge source now exposes exact editable frozen rows, active claim IDs,
predecessor/successor links, immutable cancellation details and each payment's
current allocation. Strict void/replace commands preserve string amounts, integer
versions/tax and explicit confirmation; two additional native bridge cases cover
transport rejection and credit/relationship/cancellation-copy round trips. Native
replacement build 43209333407 remains live; no new native result is claimed yet.
Lifecycle editor UI and actual VOID/replacement PDF/interaction acceptance remain
next. Financial UI must route original receipts to their original invoice and
corrections to the current invoice, and must not offer collection on VOID records.

Replacement ed94bfc / 43209333407 stopped at native compilation: a refactored
ledger read still referenced the removed local `total` for tax allocation. Source
now uses `balance.total`; no arithmetic policy changed. Native lifecycle tests and
actual replacement/VOID documents have not passed. Superseded queued/intermediate
commits share this identified compile error; validate the corrected head in Actions.

551a748 / 43210091600 also stopped at native compilation: the full-width copy
paragraph passed a Float width to the existing Int layout API. Corrected the
width conversion; preserve exact content and the one-page check. Added late
VOID-copy failure injection to prove rollback after credit has already moved,
and cancellation immutability/retention across a subsequent job purge. These new
cases still require Actions evidence; no native lifecycle success is claimed.
