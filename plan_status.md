# Clockrove task status

Updated 2026-11-11. Product contract: [app_plan.md](app_plan.md).
Statuses describe evidence, not intent. Android launch requires milestones 1–8;
iOS and future service remain deferred.

| Task | Status | Evidence / next action |
| --- | --- | --- |
| M1.1 Verify GitHub identity and remote | Verified | Escalated network check: user 1000 (`agent`) authenticates as `centerionware`; initially absent; private intended repository now created for CI |
| M1.2 Resolve writable Git metadata | Implemented locally | `scripts/git-local` uses ignored `.local/git`; status and ignore checks succeed without modifying protected root `.git` |
| M1.3 Preserve handoff | Preserved | AGENTS.md, app_plan.md and ignore rules retained |
| M1.4 Draft source/contribution rights | Drafted; owner review required | docs/SOURCE_TERMS_DRAFT.md, CONTRIBUTING.md; no effective license adopted, no publication |
| M1.5 README, architecture, dependency inventory | Drafted | docs/ARCHITECTURE.md and docs/DEPENDENCIES.md written; README setup commands recorded; Android compatibility pending |
| M1.6 Install/pin compatible tools | Installed in Actions; build compatibility pending | Flutter 3.47.9 archive checksum verified; ARM64 Dart 3.13.7 checksum verified; CLI runs; copied Temurin 17.0.20.1+1 into ignored tools; Actions passed JDK/Flutter/Android SDK installation; Ruby/Fastlane deferred until publishing work |
| M1.7 Android Flutter scaffold and lockfiles | Build verified in Actions | Agreed application ID; Actions locked resolution, formatting and analysis pass; debug APK built in run 42980838050 |
| M1.8 Nonprivileged validation CI | Verified foundation run | .github/workflows/validate.yml; pinned actions/Flutter/JDK/SDK; no publishing secrets or artifact uploads |
| M1.9 Create public repository | Public, owner-authorized | https://github.com/centerionware/Clockrove; verified PUBLIC after explicit owner instruction; source terms remain draft |
| M1 acceptance | Technical foundation verified; public rights gated | Actions 42980838050 succeeded; no effective license/public visibility until owner review |
| M2.1 SQLite/manual timer/widget/notification prototype | In progress | Native SQLite timer repository and five Robolectric tests added; Actions validation pending; UI/widget/notification integration remains |
| M2.2 Location/geofences/departure/recovery prototype | Pending | Native callbacks required |
| M2.3 OSM and runtime Google-key experiment | Pending | Google-key proof needs user key and Android runtime |
| M2.4 Physical screen-off/battery validation | Pending external device | No device identified |
| M3.1 Schema, migrations and typed contracts | Pending | Follow prototype findings |
| M3.2 IDs/revisions/tombstones/serialized commands | Pending | Transaction and concurrency tests required |
| M3.3 Jobs/sites/boards/session corrections/queries | Pending | Integrity and snapshot tests required |
| M4.1 Manual tracker UI and history | Pending | Native authoritative state only |
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
| M8.2 Device/dependency/release/upgrade validation | Pending | No build or device success claimed |
| Initial Play setup AAB (owner request) | Delivered | Signed artifact 13839295891 / successful run 42984008259; expires 2026-11-12 08:08 UTC; permanent signing secrets saved |
| M8.3 Protected workflows/Fastlane/internal delivery | Pending owner setup | Publishing not configured; no signing/store credentials |
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
