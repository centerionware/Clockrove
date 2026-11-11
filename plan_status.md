# Clockrove task status

Updated 2026-11-11. Product contract: [app_plan.md](app_plan.md).
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
| M2.4 Physical screen-off/battery validation | Pending external device | No device identified; docs/DEVICE_VALIDATION.md records the prototype checks and honest capability boundaries |
| M3.1 Schema, migrations and typed contracts | Native schema/revision foundation verified; full contracts in progress | PR #19; Actions 43073787801 passed 127 native tests; business command/UI integration remains |
| M3.2 IDs/revisions/tombstones/serialized commands | Pending | Transaction and concurrency tests required |
| M3.3 Jobs/sites/boards/session corrections/queries | Pending | Integrity and snapshot tests required |
| M4.1 Manual tracker UI and history | Prototype controls merged; full history pending | Native-backed start/stop/switch, modern UI and map-outage journey pass Actions; corrections/history/full job management remain |
| M4.2 Widgets/configuration/notification actions | Pending | Offline/recovery/accessibility journey required |
| M5.1 Permissions/site editor/capacity/entry/departure | Pending | Both departure modes and suppression required |
| M5.2 Grace/re-entry/late events/pruning/health/recovery | Pending | State-machine matrix and physical tests required |
| M6.1 Boards and accessible moves | Pending | Preserve cards and calendar due dates |
| M6.2 Date/list/totals/map/timezones | Pending | DST and filter agreement tests required |
| M6.3 Provider settings/secure credentials/tile policy | Pending | Outage must preserve manual functionality |
| M6.4 CSV and document sharing | Pending | Escaping/formula/clipping tests required |
| M7.1 Crypto/KDF selection and device benchmark | Pending | Pinned maintained implementation and bounded envelope required |
| M7.2 Encrypted archive/schema/preview | Pending | Authentication before parse; explicit open-session choices |
| M7.3 Conflict/ancestry/tombstone/transactional merge | Pending | Full integrity and adversarial test matrix required |
| M8.1 Accessibility/privacy/support/store assets | Pending | Complete launch features first |
| M8.2 Device/dependency/release/upgrade validation | Partial CI evidence; launch validation pending | Debug/release bundles pass Actions; physical installation, upgrade and full launch-feature matrix remain unproven |
| Initial Play setup AAB (owner request) | Delivered | Signed artifact 13839295891 / successful run 42984008259; expires 2026-11-12 08:08 UTC; permanent signing secrets saved |
| M8.3 Protected workflows/Fastlane/internal delivery | Internal upload verified; broader release readiness incomplete | Actions 42988068348 attempt 2 succeeded; Play accepted versionCode 216549708; permanent key and automatic allocation verified; production/device/store gates remain |
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


### Binding combined next-test-release scope and publishing hold

Contract: [docs/NEXT_TEST_RELEASE.md](docs/NEXT_TEST_RELEASE.md). Owner requires the
next internal release to include all requested features; documentation is not
completion. **Play delivery workflow 414395901 is temporarily disabled_manually**
(verified through GitHub API). Validate is active. Do not publish partial builds;
re-enable automatic delivery only for the complete validated combined iteration.

| Addition | Status | Required evidence |
| --- | --- | --- |
| Hourly/task billing mode and task prices | Native/settings UI passed; report UI pending | PRs #20–24; exact integer, mode, zero/missing/currency and DST cases passed; combined report UI #28 awaits checks |
| Reusable task templates including deleted-job content | Board/native lifecycle passed; final cleanup pending | Independent catalog/copy/removal tested; #27 historical-provenance cleanup and final UI checks remain |
| Complete reporting and Kanban task management | Kanban passed; report core passed; integrated UI pending | #23 validated board and previews; #22 DST/pricing/CSV native tests; #28 full report/map/export UI unverified |
| Customers and per-job customer/email | Implemented and Actions passed | #21 native persistence/references and #24 typed forms/customer journeys; refresh-race fix in #28 pending |
| PDF invoice beside CSV | Pending | Valid multipage PDF with actual report line items/totals |
| Email draft with automatic fields and PDF attachment | Pending | Native Intent/URI grant tests; device email handoff evidence separate |
| Global company/contact and email subject/body templates | Implemented and Actions passed | #21 literal/injection/persistence tests and #24 UI journeys passed; document handoff pending |
| Optional invoice header/footer and company logo import | Header/footer stored; logo importer pending | Header/footer settings/native snapshot source exists; actual PDF/logo/device checks and importer remain |
| Minimal, Classic and Modern invoice styles | Pending | Three distinct real rendered PDFs with equal totals and logo/pagination checks |
| Combined internal release | Held | All scope implemented and checked before Play workflow is re-enabled |

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
