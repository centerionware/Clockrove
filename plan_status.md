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
| M1.6 Install/pin compatible tools | In progress | Flutter 3.47.9 archive checksum verified; ARM64 Dart 3.13.7 checksum verified; CLI runs; copied Temurin 17.0.20.1+1 into ignored tools; SDK located; local builds stopped; CI JDK pin 17.0.17+10; Ruby/Fastlane deferred until publishing work |
| M1.7 Android Flutter scaffold and lockfiles | Scaffolded, build incomplete | Agreed application ID; pubspec.lock resolved; format and analysis pass; local retry cancelled; Actions build pending |
| M1.8 Nonprivileged validation CI | Implemented; GitHub run in progress | .github/workflows/validate.yml; pinned actions/Flutter/JDK/SDK; no publishing secrets or artifact uploads |
| M1.9 Create public repository | Private staging created; public gated | https://github.com/centerionware/Clockrove; verified private; terms review pending |
| M1 acceptance | Incomplete | No Android build or CI evidence yet |
| M2.1 SQLite/manual timer/widget/notification prototype | Pending | Depends on foundation |
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
