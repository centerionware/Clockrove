# Historical validation record from plan_status.md — part 6

_Source: `plan_status.md validation record`. Entries: 2026-11-14 — second settings visit and PR #45 merge through 2026-11-15 — owner decision: Google Maps deferred beyond launch_

### 2026-11-14 — second settings visit and PR #45 merge

Exact PR head `942659a` / Actions run
[43626467570](https://github.com/centerionware/Clockrove/actions/runs/43626467570)
passed formatting, Analyze, all Flutter behavior tests, Android debug build,
native unit tests, PDF generation/rendering and locked release-tooling/version
checks. The test now resets the form to **Billing basis** before both tax-rate
toggle visits. Downloaded device XML confirms 37 actual Android tests per API
24 and 35 with zero failures, errors or skips; the financial journey, quote,
invoice copies, payments, task media, 49-sample map, and map provider settings
passed. The quote Manage customers test created and saved a customer, returned
to the quote, selected it and verified that its ID persisted in the draft.

PR #45 was squash-merged to `main` as `69e1dab` after all exact-head checks
passed. Main validation run
[43628166015](https://github.com/centerionware/Clockrove/actions/runs/43628166015)
failed on API 35 while revealing **View original PDF** in the retained-invoice
journey. API 24 passed that journey. The API-35 accessibility dump placed the
button below the visible app viewport, while the app root extended beneath the
system navigation bar; the fallback swipe therefore started in the system bar
and did not scroll. This is test-harness evidence, not a production PDF failure.
The helper now keeps swipes above system bars and falls back to a real swipe if
an accessibility scroll action does not reveal the target. Exact-head CI for
this correction is pending. Play delivery run
[43630004858](https://github.com/centerionware/Clockrove/actions/runs/43630004858)
was skipped after merged-main validation failed; no Play upload is claimed.
Physical owner-device acceptance remains pending. No build, test, formatter or
analyzer ran on the VM.

The first exact-head run after this correction reached API 24 and passed its 28
base device cases, but the extended billing journey intermittently failed to
focus the visible **Default tax (%)** field through accessibility
`ACTION_CLICK`. The captured screen showed the field in the viewport without an
open keyboard. The test now retries with an actual touch at the field's
accessible screen bounds when that semantic click does not open the IME. The
next API-24 attempt passed the default-tax input and reached the task quantity
field, but the platform reported `ACTION_SET_TEXT` success while retaining
`12` instead of the requested `26`. The helper now selects the existing text and
types through the active IME if the field value does not change. This remains a
test-harness stabilization; exact-head Actions validation is pending.

At exact PR #46 head `ae4bc08`, Actions run
[43634037678](https://github.com/centerionware/Clockrove/actions/runs/43634037678)
passed formatting, analysis, Flutter behavior tests, Android debug build,
native tests and the full API-24 extended billing journey. API 35 reached the
invalid job-tax validation check but timed out without exposing the expected
message. Its screenshot shows Job settings still at the bottom with the Save
button and no validation message. Because the device helper returned after a
successful accessibility `ACTION_CLICK`, which can be acknowledged without a
real Flutter pointer event, the billing test now taps the visible clickable
screen bounds and waits for the UI to settle. This is a test-only correction;
the next exact-head Actions run is pending. No build or test ran on the VM.

The next exact head, `c9f8b6e` / Actions run
[43636374365](https://github.com/centerionware/Clockrove/actions/runs/43636374365),
passed formatting, analysis, Flutter behavior tests, the Android debug build and
614 native tests. The billing device journey stopped on API 24 at the final
**Save job settings** click: Android 7's accessibility node reported the visible
Flutter button as `clickable=false`, so the helper's new clickable-only tap
guard rejected it. The helper now taps the matched visible node's bounds even
when that accessibility flag is false. The API 24/35 billing journey did not
finish; validate this correction on Actions before claiming the billing journey
is green. No VM build or test ran.

Retry head `6e5b502` / Actions run
[43637427535](https://github.com/centerionware/Clockrove/actions/runs/43637427535)
passed formatting, analysis, Flutter behavior tests, Android build and all 614
native tests. The real-device step timed out with exit code 124 while installing
the app and instrumentation APKs on the API-24 emulator; the artifact contains
only emulator setup logs and no JUnit/device assertions. API 24 and API 35
journeys did not run, so this run provides no result for the direct-tap fix.
Retry the exact PR head in GitHub Actions. No VM build or test ran.

At exact head `413b206` / Actions run
[43638528694](https://github.com/centerionware/Clockrove/actions/runs/43638528694),
formatting, analysis, Flutter behavior tests, Android build and 614 native tests
passed. API 24 passed all 37 actual device cases, including the full billing
journey. API 35 passed its 28-case base suite, then the extended billing test
failed at Job tax (%) because the field stopped exposing `ACTION_SET_TEXT` after
focus. The source test now tries `ACTION_SET_TEXT` before opening the IME and
accepts the result only after reading back the exact value; the existing IME
typing fallback remains for API 24 cases that acknowledge but ignore the text
action. API 35 later journeys did not run. Fresh exact-head Actions validation
is pending. No VM build or test ran.

Exact PR #46 head `274ce49` / Actions run
[43640563667](https://github.com/centerionware/Clockrove/actions/runs/43640563667)
passed formatting, analysis, Flutter behavior tests, Android debug build,
locked release checks and all 614 native tests. Downloaded JUnit XML confirms
37 actual Android cases on each API 24 and 35, zero failures/errors/skips,
including both extended billing journeys, invoice copies, payments, quotes,
task media, map and widget journeys. The direct text action plus readback fix
resolved the API-35 billing failure; the API-24 IME fallback also remains
green. No VM build, test, formatter or analyzer ran. PR #46 is ready to merge;
merged-main validation and Play delivery are the next gates.

### 2026-11-14 — M6.6 merged-main API 35 system-navigation tap

PR #46 was squash-merged to `main` as `66e063a`. The exact updated PR head
`91bd9b6` passed GitHub Actions run
[43642895579](https://github.com/centerionware/Clockrove/actions/runs/43642895579):
formatting, analysis, Flutter behavior tests, Android build, locked release
checks and 614 native tests passed; downloaded JUnit XML confirms 37 device
cases on each of API 24 and API 35 with zero failures, errors or skips.

Post-merge main validation
[43644644853](https://github.com/centerionware/Clockrove/actions/runs/43644644853)
passed those same fast checks and all 37 API-24 device cases. API 35 passed its
28-case base suite, then the extended billing journey failed before exposing
**Job tax (%)**. The captured active accessibility window was Android Home,
and the emulator log records `LAUNCHER_TASKBAR_HOME_BUTTON_TAP`; no app fatal
exception was present. This is evidence of the device-test tap reaching system
navigation, rather than a confirmed product crash. The 614 native tests passed,
but the API-35 billing journey and following device journeys did not complete.

A CI-only follow-up now clips tap targets to the active app viewport after
excluding status/navigation bars and the IME, rejects targets from a different
active package, and keeps scrolling until the requested control has a safe
on-screen tap point. `click` uses this same safe reveal path. Exact-head Actions
validation is pending; no VM build or test was run. Play delivery
[43646438745](https://github.com/centerionware/Clockrove/actions/runs/43646438745)
was skipped because the merged-main Validate run failed, so no Play upload is
claimed. Retry the safe-tap follow-up through GitHub Actions, merge only when
its device matrix is green, then verify the new main validation and Play run.

The first PR #47 Actions run
[43647325052](https://github.com/centerionware/Clockrove/actions/runs/43647325052)
then exposed a restored-scroll-position case on API 24: the Settings form was
at **Company & contact**, below **Default tax (%)**, so a downward-only reveal
could not reach the field. Its screenshot shows Settings still open, with no
system-navigation tap. `scrollToStart` now accepts a screen-specific top anchor;
the global settings journey returns to **Billing defaults** before entering
tax. Revalidate this follow-up on Actions; no VM build or test ran.

The next exact head `a53727a` / run
[43648400067](https://github.com/centerionware/Clockrove/actions/runs/43648400067)
passed the 28-case API-24 base suite and the global-tax settings flow, then
reached the job-tax editor. The test had focused **Job tax (%)** and opened the
IME, but its delayed fallback pointer tap raced that transition and was
correctly refused because the keyboard covered the target; the screenshot shows
the active field and numeric keyboard. `enter` now treats that race as an
already-focused field, waits for the IME, then uses `ACTION_SET_TEXT` with exact
readback or select-all/IME typing. Only ordinary control clicks require their
safe pointer tap to succeed. Revalidate on GitHub Actions; no VM build or test
ran.

### 2026-11-14 — PR #47 passed; API-24 text replacement follow-up

PR #47 head `b9548a3` / Actions run
[43649614516](https://github.com/centerionware/Clockrove/actions/runs/43649614516)
passed formatting, analysis, Flutter behavior tests, Android build, 614 native
tests and 37 actual device cases on each API 24 and 35, with zero failures,
errors or skips. Squash-merged to main as `2f84790`.

Main validation
[43651548435](https://github.com/centerionware/Clockrove/actions/runs/43651548435)
passed the fast checks, Android build and all 614 native tests. API 24 passed
the 28-case base device suite, then its billing journey failed when the test
helper appended replacement text to a long unit-price field. `ACTION_SET_SELECTION`
reported success but moved the caret without selecting the text; the screenshot
shows the app remained open, so this was not a product crash. The helper now
verifies the selected range and sends Ctrl+A when necessary before typing. Exact
head Actions validation remains pending; API 35 was not reached. The gated Play
delivery did not run because main validation failed. No VM build, test or
formatter ran.

PR #48 exact head `91f0950` / Actions run
[43653156678](https://github.com/centerionware/Clockrove/actions/runs/43653156678)
passed formatting, analysis, Flutter behavior tests, Android build and native
tests. API 24 passed all 37 actual device cases, verifying that the selected
range / Ctrl+A fallback fixed the large unit-price replacement. API 35 passed
its 28 base device cases and the billing flow through invoice creation, then
the test failed to find the invalid-tax message. The screenshot shows Clockrove
still open near the bottom of Job settings. The lookup previously ignored
`AccessibilityNodeInfo.error`, which Flutter may use to expose field validation
messages; that property is now included. Validate this correction on the
updated PR head through GitHub Actions. No VM build or test ran; API-35 billing
and later journeys did not complete.

Updated PR #48 run
[43655152043](https://github.com/centerionware/Clockrove/actions/runs/43655152043)
passed all 37 API-24 device cases, including the long unit-price replacement.
API 35 passed its 28 base cases, then failed earlier in the extended billing
journey because the fallback sent Ctrl+A to an empty multiline review-note field
when Android did not expose selection endpoints. The failure screenshot shows
Clockrove open on the review form with the note still blank. Ctrl+A is now
restricted to non-empty values; this leaves empty fields on the ordinary IME
typing path. The invalid-tax lookup remains unverified on API 35. Exact-head
Actions rerun is required; no VM build or test ran.

PR #48 run
[43656883319](https://github.com/centerionware/Clockrove/actions/runs/43656883319)
passed the fast checks, Android build and native tests, then API 24's extended
billing journey failed while replacing quantity. The screenshot shows `126`
(seeded `1` plus typed `26`) although accessibility reported the old range as
selected. The device helper no longer trusts selection-range metadata: Ctrl+A
is sent for every non-empty field, while empty fields remain on ordinary IME
typing. API 35 was not reached. Validate the exact updated head in GitHub
Actions; no VM build or test ran.

PR #48 exact head `a49ed46` / Actions run
[43657992279](https://github.com/centerionware/Clockrove/actions/runs/43657992279)
passed formatting, analysis, Flutter behavior tests, Android build and all 614
native tests. API 24 passed its 28-case base suite and progressed through the
extended invoice/tax journey, then failed while restoring inherited job tax:
after enabling **Use default tax rate** and tapping Save, the native repository
still exposed the explicit zero override. The screenshot leaves Job settings
open; this is a device-test assertion, not an app crash. API 35 and later device
journeys were not reached. The next test revision reads the switch's real
checked state before and after each toggle, captures inherited state before
saving, and separately asserts that Save closes the form and persists a null
override. Validate on GitHub Actions; no VM build or test ran.

### 2026-11-15 — branch/main validation race audit

Main `00b11b6` / run [43661944356](https://github.com/centerionware/Clockrove/actions/runs/43661944356)
failed API-35 billing at the invalid-tax lookup, while PR head `a637b72` / run
[43659408202](https://github.com/centerionware/Clockrove/actions/runs/43659408202)
passed. The PR and merged-main commits have the same source tree
`1ddd58e325054ec607fb10d697dd25ee0eb061e9`, confirming run-to-run behavior
rather than merge drift. Main's retained screenshot and accessibility tree show
the bottom of Job settings with no exposed tax error; API 24 passed the same
journey. No product-crash evidence appears in this failure. Source review found
`reveal` and `scrollToStart` checking accessibility scrolls immediately, then
injecting a fallback gesture before asynchronous scroll completion. PR #49 adds
`waitForIdle` before rechecking targets or sending fallback gestures; all
invalid-tax, draft-retention and committed-state assertions remain. This is a
test-synchronization hypothesis, not yet proof of the full cause or a green
merged-main result. Exact-head and merged-main Actions validation remain
required. No VM tests/builds ran.

PR #49 run 43663670008 passed all 614 native cases and API-24 device checks.
API 35 advanced beyond invalid-tax validation, then failed replacing 101 with 0
at billing line 502. Retained screenshot shows 101 and validation error; the
accessibility dump shows the tax EditText unfocused. The input fallback accepted
keyboard visibility without proving target focus. Require the target editable
node's focused state, with bounded wait and safe pointer fallback, before typing.
Exact-head rerun pending; no VM tests/builds ran.
### 2026-11-15 — Google Maps runtime-key feasibility remains open

On the isolated provider branch based on `00b11b6`, reviewed the active map
contract, current OSM/custom-raster settings, and the stopped provider draft in
`.local/luna-providers`. The stopped draft's map privacy inventory was adapted
into `docs/MAP_PRIVACY_DATA_INVENTORY.md`; its claims were kept as release
checks rather than treated as validation evidence.

The official Flutter and Android setup guides configure the Android Maps SDK
key through `com.google.android.geo.API_KEY` application manifest metadata.
The documented `MapsInitializer` methods accept an Android `Context` and no
runtime key. No officially supported runtime user-key path for the standard
Flutter/Android Maps SDK integration was found. A native platform view still
uses the same SDK, so it does not solve key initialization. No Google Maps key
setting, SDK dependency, platform view, or map provider was added. Do not change
the product contract to a build-time key without an explicit owner decision.

The existing keyless OSM and protected custom HTTPS raster providers remain the
working options. Google Maps remains an open requirement; resolving it requires
a supported runtime-key path and owner-provided restricted-key proof, or an
explicit contract decision. The relevant official sources are linked in
`app_plan.md` and `docs/MAP_PRIVACY_DATA_INVENTORY.md`.

No build, test, formatter, or device check ran on the VM. No owner key or device
was available, so Google rendering, missing/invalid-key fallback and billing
restriction behavior remain unproven.

### 2026-11-15 — merged-main API-35 billing repair passed

PR #49 was merged as `aca488f`. Merged-main Actions run
[43667760290](https://github.com/centerionware/Clockrove/actions/runs/43667760290)
passed the 614 native cases and 37 actual Android device cases on each of API 24
and API 35, with zero failures, errors or skips. The earlier exact-head and
merged-main discrepancy is resolved for this billing journey. This validation
does not accept the remaining launch milestones or M7.6.

### 2026-11-15 — GPS-only range deletion implementation added

On an isolated branch based on `aca488f`, database schema 16 adds immutable
`location_deletion_ranges` coverage and migrates existing GPS identities into
coordinate-free revisions. Native preview and deletion use the serialized
versioned command boundary; deletion uses half-open measured-UTC bounds, bounded
keyset batches, per-known-point tombstones and one transaction while leaving
sessions/timesheets intact. New point revisions link to matching range revisions
without retaining coordinates. The history screen now offers an inclusive
local-date range, current-job/all-jobs scope, point-count preview, explicit
confirmation and preserved-export/backup disclosure. Committed state versions
refresh the history and report/map providers.

Native migration, boundary, scope, revision-payload, ancestry, duplicate-command,
stale-version, empty-range, rollback and bridge tests plus Flutter API/widget
tests were added for GitHub Actions. No build, test or formatter ran on the VM,
per owner instruction. Exact-head Actions validation and backup exporter/restore
integration remain pending; M7 and M7.6 are not accepted.

At exact head `cdbfecc`, Actions run
[43671703196](https://github.com/centerionware/Clockrove/actions/runs/43671703196)
passed formatting, analysis, Flutter behavior tests and the Android build, then
failed native tests: 621 cases ran, with nine failures. Eight SQLite migration
errors came from downgrade-style fixtures retaining the schema-16 range table;
those fixtures now drop the table before setting the historical version. One
session-history assertion counted live observation revisions when it intended
to check deletion tombstones; it now filters for deleted revisions. Both test
corrections require a fresh exact-head Actions run. The new actual Android
deletion journey was not part of this run. No VM tests/build/formatting ran.

### 2026-11-15 — observed editor clearing and tax-error navigation

PR #51 head `8c6054b`, run `43672803259`, passed formatting, analysis, Flutter
behavior tests, the Android build, and all 621 native tests. The API-24 base
device group passed all 28 cases, then the existing billing journey failed
while replacing tax `101` with `0`; the retained screenshot and accessibility
tree showed focused editor value `1010`, so Ctrl+A had not selected the original
text. API 35 and later journeys were not reached. PR #52 head `11baf85`, run
`43671872207`, passed its API-24 crypto benchmark but failed API-35 tax-error
navigation; its screenshot showed the bottom of Job settings.

Shared repair commit `36e27ba` clears the focused editor using ordinary delete
keys and observes an empty field before typing. The validation journey locates
the tax editor before checking its inline error, retaining exact error, draft
and committed-state assertions. The GPS Android journey source is now added: it
uses both picker modes, checks current-job/all-job scope, cancellation and
confirmation, reopens report/map views, and requires nine screenshots. It has
not run yet. Main's schema-15 snapshot code is reconciled into this branch;
commit `7f19d54` adds the schema-16 allowlist and synthetic range/tombstone/
causal-parent export test. The combined exact-head Actions run must validate
this adapter, the billing repair, and the Android GPS journey. No VM builds or
tests ran; M7 and M7.6 remain unaccepted.

### 2026-11-15 — GPS date-picker title matcher correction

On exact PR #51 head `c499310`, Actions run
[43674623832](https://github.com/centerionware/Clockrove/actions/runs/43674623832)
passed the preceding checks and reached the API-24 GPS deletion journey. It
stopped at `Select GPS dates`: the actual screenshot shows the title, while the
accessibility tree exposes `Select GPS dates Nov 9 to Nov 15` as one content
description. The matcher now supports an explicit prefix option for content
descriptions, enabled only for this date-picker title. Product labels and the
existing exact-match checks are unchanged. Exact-head run
[43677160634](https://github.com/centerionware/Clockrove/actions/runs/43677160634)
passed that title check but then stopped in input mode while waiting for exact
`Start Date`. The accessibility dump shows the actual start-date EditText value
and focus, without a `Start Date` text/contentDescription. No local build or
test ran.

### 2026-11-15 — GPS input-mode field matcher correction

The `43677160634` API-24 screenshot shows the input-mode picker open. Its
accessibility tree exposes the start date as a focused EditText containing
`11/09/2026`, without the expected `Start Date` label. The test now waits for
two visible editable date values and verifies that one field is focused, which
proves the input mode opened without relying on a missing label. The Review,
point-count, cancellation/confirmation, persisted tombstone, and report/map
assertions remain. A new exact-head Actions run is required; API 35 and later
journeys were not reached on `43677160634`. No VM build or test ran.

### 2026-11-15 — API 24/35 tax-error visibility repair

PR #54 commit `3ca1267` adds a short scroll when the tax editor clips its
inline validation message. Its earlier run `43674600623` passed API-24 billing
but the page-sized swipe moved past the error on API 35. Follow-up commit
`88d88b8` uses a 14%-viewport swipe; exact-head run
[43676781145](https://github.com/centerionware/Clockrove/actions/runs/43676781145)
completed successfully. Commits `44eea3d` and `b9c1147` integrate these changes
into PR #51 so its final gate exercises the billing repair and GPS deletion
journey together.
The preceding `36e27ba` run `43673656562` had failed API-24 error visibility:
the screenshot showed the focused tax editor clipped at the navigation bar.
No local build or test ran.

### 2026-11-15 — GPS deletion and billing exact-head validation passed

On PR #51 exact head `c247e01`, GitHub Actions run
[43678616256](https://github.com/centerionware/Clockrove/actions/runs/43678616256)
completed successfully. Formatting, analysis, Flutter behavior tests, Android
build, native unit tests, API-24/API-35 Android PDF generation and rendering,
actual-document invoice-viewer checks, artifact exports, and locked release-tool
validation all passed. The Android journey script validated the expected JUnit
counts on both APIs, including one `GpsRangeDeletionDeviceTest` case per API,
and required all nine GPS screenshots on each API. The run also includes the
14%-viewport tax-error visibility repair integrated from PR #54. This is PR-head
emulator evidence; physical GPS sampling/map acceptance, backup archive and
restore work, and M7.6 full-app UI/E2E acceptance remain outstanding. No VM
build, test, or formatter ran.

### 2026-11-15 — full-screen task-photo swipe direction

The full-screen photo viewer set `PageView.reverse: true`, so a leftward drag
decremented the attachment index and a rightward drag incremented it. Removed
that reversal and updated the existing viewer journey to assert left swipe
advances and right swipe returns across the chronological photo list. Source
change is in PR #55, head `32ac062`. Exact-head Actions run
[43675682397](https://github.com/centerionware/Clockrove/actions/runs/43675682397)
passed formatting, analysis, Flutter behavior tests, Android debug build and
native unit tests. Its real Android PDF/device stage failed in the API-24 invoice
journey because **View original PDF** was not exposed; the photo viewer device
journey was not reached, so Android swipe direction remains unverified.

PR #55 now also contains the shared Android billing-test harness corrections
from PR #54 commits `36e27ba`, `3ca1267`, `88d88b8`, `00531dc` and `32d6154`:
observe old editor text cleared before replacement, use short settled scroll
increments to avoid skipping fields, leave room to expose tax validation, and
reveal the covered-task row before reading its checkable state. These changes
affect test automation, not production billing behavior. Exact-head PR #55
Actions validation on API 24 and 35 is pending. No VM build, test, formatter or
analyzer ran.

PR #55 combined head `04cd124` / Actions run
[43680373221](https://github.com/centerionware/Clockrove/actions/runs/43680373221)
passed formatting, analysis, Flutter behavior tests, Android debug build and
native tests, then API 24 failed in
`actualTaskCommentAndReviewedCameraPhotosPersistInPrivateStorage`: after a
left swipe from the last photo (4 of 4), the viewer correctly remained on photo
4, while the device test expected photo 3. This expectation retained the old
reversed behavior. Updated the device journey to verify right swipe 4→3 and
left swipe 3→4, matching the requested left-advances/right-returns behavior.
Validate the corrected exact head in GitHub Actions; API 35 was not reached. No
VM build or test ran.

The corrected exact head `1f3662e` passed the full validation workflow in
[Actions run 43681914649](https://github.com/centerionware/Clockrove/actions/runs/43681914649),
including the Flutter behavior tests and API-24/35 Android device suites. The
photo journey verified right swipe 4→3 and left swipe 3→4. This establishes the
requested direction on emulators. No VM build, test, formatter or analyzer ran.

To prevent passing PR checks from becoming stale after main advances, active
ruleset `Protect Main` (ID `27171072`) now requires strict status checks for its
existing `android` check. PR #55's base matched current main at
`f0dc15f7a7e3f86d5c9fc387a847f425f8f1a7e4` when verified. The workflow does not
use a merge queue, so no `merge_group` trigger was added.
### 2026-11-15 — observed editor clearing and tax-error navigation

PR #51 head `8c6054b`, run `43672803259`, passed native tests but failed
API-24 billing while replacing tax `101` with `0`. The retained screenshot
and accessibility tree show focused editor value `1010`: Ctrl+A had not
selected the original text. PR #52 head `11baf85`, run `43671872207`, passed
API-24 crypto benchmark but failed API-35 tax-error navigation; its screenshot
shows the bottom of Job settings. The input fallback now clears via ordinary
editor delete keys and observes an empty focused field before typing. The
validation journey locates the tax editor before checking its inline error,
retaining exact error, draft and committed-state assertions. These corrections
require exact-head Actions validation. No VM builds or tests ran.

PR #54 head `36e27ba`, run `43673656562`, passed earlier checks then
failed API-24 tax-error visibility. Its screenshot shows the red `101` editor
clipped at the navigation bar; the inline error lies below the viewport.
The test now checks available space under the editor and scrolls once when
needed before asserting the unchanged exact error. Empty-before-replacement
behavior remains pending full API-24/35 acceptance. No VM execution ran.


### 2026-11-15 — shared M7.1 billing journey helper validated

PR #54 head `32d6154` / Actions run [43679591795](https://github.com/centerionware/Clockrove/actions/runs/43679591795) passed formatting, analysis, Flutter behavior, Android build, native tests, the full API-24/35 PDF and device matrix, and release tooling. This run validates the shared billing-test helper path but does not run the backup crypto benchmark. Applied the validated helper changes to the PR #52 reconciliation: short settled 14%-viewport scrolls, checked checkbox-node lookup, and the tax-error reveal step. The earlier exact PR #52 head `e87eebf48f4b0a513bd4c7eb874b14b129c6b1c6` / Actions run [43683558885](https://github.com/centerionware/Clockrove/actions/runs/43683558885) subsequently passed the full workflow and both benchmarks: API 24 encrypt/decrypt 874/337 ms, API 35 1,124/348 ms; each emitted a 1,048,648-byte envelope, and JUnit reported 37 actual Android tests per API with zero failures, errors or skips. That evidence predates the latest main base. PR #52 has now been rebased onto main commit `8a0e34052dc7e7fdc9bd1d6f6b0627e3eba175ea`; push the reconciled head and run full Actions, confirming both benchmarks against this base. No merge was performed. No VM build, test, formatter or analyzer ran.


### 2026-11-15 — refreshed PR #52 install failure and bounded recovery prepared

The refreshed PR #52 head `05d087b` / Actions run
[43691431696](https://github.com/centerionware/Clockrove/actions/runs/43691431696)
failed identically on attempts 1 and 2. API-24 product journeys completed, then
`flutter test integration_test/backup_crypto_benchmark_test.dart` built the
integration APK but hung during Flutter's own APK install for about 225 seconds.
The output said `No tests ran`, reported ADB exit `-15`, and the outer timeout
exited `124`. This was an emulator install failure; the crypto test did not run,
and API 35 was not reached. No manual Actions rerun was started.

The current PR #52 source adds a wrapper around that exact Flutter command. It
retries only exit `124` with the observed APK path, `No tests ran`, ADB `-15`,
no benchmark result, and no reporter progress except a `+0` loading line naming
`integration_test/backup_crypto_benchmark_test.dart` (relative or absolute
path). Before its
single retry it requires a changed kernel boot ID, completed Android boot, and
the expected API level after removing carriage returns from `getprop`. The
second command must exit successfully and emit the crypto result marker, which
the benchmark prints only after encryption, decryption and byte-for-byte
equality pass. A new synthetic Actions policy test covers successful first
run, the targeted timeout, unrelated timeout, repeated timeout, `+0` running,
`+0` skipped, `+0 -1` and other test-start failures, missing marker, wrong-API
reboot, CRLF API output and unconfirmed reboot, plus PackageManager errors that
contain misleading package/version text; it has not run yet.

Static review also tightened direct ADB recovery: expected package/version
text is accepted only when `dumpsys` exits zero, and installer output is
captured without a pipeline so log capture cannot be misclassified as an
install timeout. The maximum 64 MiB archive may still incur multiple
large in-memory copies; measure its peak on API 24 or stream it before exposing
user-facing export/restore. No VM build, test or formatter ran. Static review is
complete; no merge was performed. Next: push this reconciliation to PR #52 with
lease protection, then inspect one fresh full Actions run on the new exact head.

### 2026-11-15 — instrumentation APK version identity correction

PR #52 run [43695960415](https://github.com/centerionware/Clockrove/actions/runs/43695960415)
on `b374915` installed the app APK, then failed while preparing the Android
test APK. The exact AAPT output was not retained by that run. The failure was
identified as the test variant's empty `versionCode`; Android PackageManager
reports its default version as `0`. The main app version remains `0.1.0+1` in
`pubspec.yaml`, and `android/app/build.gradle.kts` wires
`versionCode = flutter.versionCode`.

The installer now skips AAPT on successful installs and queries APK identity
only after an ambiguous install timeout/kill. It parses named AAPT fields,
maps an empty code to `0` only for the instrumentation APK, and prints
sanitized package metadata if identity is still invalid; app APK code must
remain numeric. Synthetic Actions cases cover reordered named fields, normal
install without AAPT, instrumentation code `0` on a lost-reply verification,
and rejection/diagnostics for an app with an empty code. These cases have not
completed as a set yet. Obsolete run `43696665362` was canceled; the corrected
exact-head full Actions run is pending. Only `git diff --check` ran locally; no
VM build or tests ran. No merge was performed.

The first new exact-head run `43697421995` failed at the bounded-recovery
synthetic step because its normal-install setup left PackageManager state for
the next scenario. This is a test fixture isolation correction; the installer
correctly treated that existing package as already installed. The test now
clears install, PackageManager, query and reboot state before starting the next
scenario. No Android build/device journey ran in `43697421995`; a full exact
head Actions run is required.


### 2026-11-15 — PR #52 merged; M7.2 successor rebased

PR #52 merged to `main` as `cc6870931920016e7ee9bc4f5fc4be6e96b26523`. Exact PR head
`defb4e6` / Actions run [43697591557](https://github.com/centerionware/Clockrove/actions/runs/43697591557)
passed formatting, analysis, Flutter behavior tests, Android and instrumentation builds, 635 native tests,
invoice-viewer and release-tooling checks, and the API 24/35 device pipeline. Each API reported 38 actual
Android tests with zero failures, errors or skips. The 1 MiB benchmark measured encrypt/decrypt 755/301 ms
on API 24 and 869/273 ms on API 35; both envelopes were 1,048,648 bytes. Both APK install logs reported
Success. The run covers the instrumentation APK's default PackageManager version code 0 and the bounded
install-recovery synthetic cases. It is PR-head evidence; no post-merge main validation or Play delivery is
claimed here.

The local M7.2 successor was rebased from old PR #52 base `05d087b` onto the merge. Seven M7.2 commits
remain after the PR #52 merge; the obsolete docs-only status correction was dropped because the merged run
supersedes its pending status. The successor retains the PR #53 native import-preview source and PR #57 SAF
save bridge/exporter source; their reviewed source files are byte-identical to candidates `22239f1` and
`5978bfb`, respectively. PR #54's billing device-test file is also byte-identical to merged main (SHA-256
`283b6eb7ae7dc929736cb14d665522050c41fbf56332141c1fc1c5a05f8a4dad`), so its commits were not cherry-picked.
The PR #56 direct-install retry is superseded by PR #52's merged helper. The Settings export route remains
withheld pending maximum-size memory evidence or streaming. PR #60's first exact-head run
[43701264644](https://github.com/centerionware/Clockrove/actions/runs/43701264644) stopped at `Require clean
formatting`; all later validation stages were skipped. Workflow artifact `formatter-source-patch` (artifact
ID `14156920392`, SHA-256 `d0c703f05cc0fdadd192b856c3702326251f9652af17892c2d546ffe9cc266b5`) was downloaded
and applied as exported, changing only six Dart source/test files. No local formatter, build or test ran.
`git diff --check` passed after applying the patch. The fresh exact-head run [43701749563](https://github.com/centerionware/Clockrove/actions/runs/43701749563)
passed formatting but Analyze rejected an obsolete named-parameter default in the cancellation widget-test
fixture, along with five redundant typed-data imports and one missing-braces lint. The fixture now captures
the supplied `isCancelled` callback under a separate outer variable; the imports and braces were corrected.
The next run [43702265074](https://github.com/centerionware/Clockrove/actions/runs/43702265074) also passed
formatting but Analyze found one remaining redundant `dart:typed_data` import in
`test/backup_export_screen_test.dart`; it is now removed. No Flutter behavior, Android build, native or
device tests ran in either analysis-failed attempt. No local analyzer, formatter, build or test ran. A fresh
exact-head Actions run is pending. M7.3 restore apply and M7.6 remain incomplete. Do not merge before the
successor's exact-head Actions and the current-main/Play-delivery gates pass.


### 2026-11-15 — PR #60 behavior failures and preview hardening drafted

PR #60 run [43702626041](https://github.com/centerionware/Clockrove/actions/runs/43702626041) on `5124f26` passed Flutter formatting and analysis, then Flutter behavior tests reported 223 passed, 5 failed and 14 skipped. The failing cases were the MethodChannel mock's `PlatformException` replacing an expected `StateError`, and four backup-export screen tests that attempted to inspect or tap lazy-list children before scrolling them into view. The local changes correct the expected exception, scroll the success status and cancel controls into view, and make the polling helper reveal its target. These fixes have not yet run in Actions.

Static review also identified an unsafe expiry callback path: if an early expiry fires and scheduling its replacement fails, the private encrypted staging file and selected document could remain active indefinitely. The callback now terminalizes and cleans both; a synthetic early-callback/reschedule-failure test covers cleanup and idempotent terminal retries. The importer now withholds `applyReady` when a local timer is open, validates non-null GPS point revision payloads against the exact `{id, session_id}` schema while retaining null/redacted revisions and separate deletion-range revisions, and checks imported invoice claim job ownership and active interval overlaps against staged and local claims. Regression cases cover those checks and ensure location-payload content is absent from diagnostics. No apply implementation or transaction recheck is added.

The Flutter retry test now uses the actual expanded-reporter line `00:00 +0: backup crypto runs on a minimum-API Android emulator`; that progress must count as test start and refuse the single retry, while the exact `+0: loading .../integration_test/backup_crypto_benchmark_test.dart` line remains eligible. This distinction and the native safeguards have not yet completed Actions. PR #59's separate crypto-only maximum-envelope result is recorded above; its high measured peaks mean the Settings route stays withheld until streaming or a materially lower memory bound is validated. No local build, test, formatter or analyzer ran. Next: review the staged file list, push the fixes, and inspect one fresh exact-head Actions run. No merge was performed.


### 2026-11-15 — PR #60 scroll finder follow-up

Exact PR #60 head `4a4d346b9334b6e7f7b33e897f02418f0be39007` / Actions run [43705585212](https://github.com/centerionware/Clockrove/actions/runs/43705585212) passed the bounded ADB-recovery synthetic, formatting and Analyze. Flutter behavior tests reported 224 passed, 4 failed and 14 skipped. All four failures were in backup export widgets: `scrollUntilVisible` called `widget()` on a finder matching multiple `Scrollable` widgets (`Bad state: Too many elements`). The MethodChannel exception expectation and the actual `00:00 +0: backup crypto runs on a minimum-API Android emulator` retry refusal both passed. The helper now scopes its scrollable to the `ListView` in the backup screen. Run 437055 skipped Android build, native/device, invoice viewer and release tooling after the behavior-test failure. The subsequent run 43706188558 passed Flutter behavior and the Android build; its native failure is detailed below. No local build/test/analyzer/formatter ran. PR #60 is not merged.


### 2026-11-15 — PR #60 native preview validation correction

Rebased PR #60 head `a6ba266` / Actions run
[43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558)
passed formatting, analysis, Flutter behavior tests, Android build,
instrumentation-test assembly, and bounded install-recovery synthetics. Native
unit tests completed 675 cases with 16 failures; all failures were in
`BackupImportStoreTest`. The shared preview failure was a SQL query joining
staged `validation_time_claims` to `invoice_time_claims` while executing on the
private staging SQLite connection. `invoice_time_claims` belongs to the separate
local application database; SQLite raised a missing-table exception, and the
outer catch incorrectly returned `INVALID_REVISION_GRAPH`. This also masked the
expected `INVALID_REFERENCE` from overlapping imported claims.

Correction commit `f9b223f` keeps imported active-claim overlap checks on the
staging DB, then streams active incoming claims and checks each against the
local DB connection. This is bounded by incoming rows and does not copy local
claims into memory. An empty-preview regression covers the no-claim path; the
existing `rejectsImportedInvoiceTimeClaimOverlappingAnExistingLocalClaim` case
covers a real local overlap. The fix is rebased on current main
`fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`. PR #60 rebased head `ead1a8518fda0f3f4d65da0ab7f7ed4ce1f3b158` / Actions run [43708093424](https://github.com/centerionware/Clockrove/actions/runs/43708093424) passed formatting, analysis, Flutter behavior (228 passed, 14 skipped), Android build, release Java compilation, instrumentation assembly, install-recovery synthetics, and the complete API 24/35 workflow. Native JUnit totaled 676 tests with zero failures, errors or skips, including two `NativeVisualPreviewTest` cases. The earlier run [43706188558](https://github.com/centerionware/Clockrove/actions/runs/43706188558) also included those two cases in its 675-test JUnit total; the new aggregate is not a count that excludes them. API 24 and API 35 each passed 38 Android tests with zero failures, errors or skips. The 1 MiB crypto benchmark measured encrypt/decrypt 878/280 ms on API 24 and 877/287 ms on API 35; each envelope was 1,048,648 bytes. Invoice viewer, artifact export, and locked release tooling passed. Commit `9dc1542` makes expiry rescheduling fail closed: it detaches the active snapshot, wipes the key, attempts artifact deletion and retries failed deletions on the next store interaction. Regression coverage injects a later scheduler failure and a deferred deletion failure. Exact-head run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843) passed on SHA `9dc154288111af1219166d5eaeeea8661dfee1aa`: formatting, analysis, Flutter behavior, Android build/release compile, instrumentation assembly, bounded install-recovery synthetic, 677 native tests (zero failures/errors/skips, including two `NativeVisualPreviewTest` cases), 38 actual Android tests each on API 24 and 35 (zero failures/errors/skips), invoice-viewer validation, artifact exports and locked release tooling. API 24 benchmark attempt 1 timed out before test start during APK install; recovery confirmed a changed emulator boot ID and boot completion, then the single retry passed. The 1 MiB benchmark measured encrypt/decrypt 742/273 ms on API 24 and 951/292 ms on API 35; both envelopes were 1,048,648 bytes. Only `git diff --check` ran locally; no build, test, format or analyze ran on the VM.

PR #58 exact head `a51fcd7` / Actions run
[43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294)
passed its release Java compilation and complete device suite, then merged to
main as `fefdcdf`. Merged-main Validate run
[43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644)
passed with 38 actual Android tests on each API 24/35 and zero failures/errors/skips.
Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483)
completed successfully for exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`;
internal AAB upload/submission was accepted at 11:30:34 UTC. This does not prove
review approval or tester availability. Merged-main validation and guarded Play
submission are complete. PR #60 is not merged. Its expiry-reschedule fix passed
exact-head run [43710812843](https://github.com/centerionware/Clockrove/actions/runs/43710812843)
on `9dc154288111af1219166d5eaeeea8661dfee1aa`; see the M7.2 row for exact
device counts and benchmark/recovery evidence. The Settings export route remains
withheld pending streaming or materially lower complete-export memory evidence.


### 2026-11-15 — PR #61 streaming envelope benchmark and rebase

PR #61 pre-rebase head `a84dba0a2473e443be8c057a8c16b01219b141c4` / Actions run [43714608406](https://github.com/centerionware/Clockrove/actions/runs/43714608406) passed before rebasing onto PR #60. It passed formatting, analysis, Flutter behavior, Android/release builds, instrumentation assembly, 635 native tests, invoice-viewer/artifact/release tooling, and 38 actual Android tests each on API 24 and 35 with zero failures, errors or skips. The 64 MiB v2 codec gate passed: API 24 encrypt/decrypt 10,565/9,219 ms, codec peak RSS delta 18,564 KiB; API 35 9,473/8,777 ms, delta 21,948 KiB. Both produced a 67,108,864-byte archive and 67,134,601-byte envelope using 19 MiB / 2-iteration / parallelism-1 KDF settings. Independent post-codec verification raised RSS deltas to 37,916 KiB and 58,056 KiB respectively; this is logged separately from the codec gate. The rebased head `391984d962587b8ad275b824fc361286ff91f37d` includes current main `7a70658` and PR #60 export work but has not yet been validated; the old-head run is not evidence for the rebased tree.

The benchmark now checks the 65,536 KiB codec RSS budget immediately after encrypt/decrypt, before the independent restored-file hash; verification RSS remains reported separately. This avoids counting the test-only hash pass as codec memory while retaining archive digest checks. The worktree was rebased onto main `7a70658`; PR #60 export/validation files and evidence remain in the base, and only the overlapping plan documentation needed conflict resolution. No local build, test, formatter or analyzer ran. Only `git diff --check` is permitted locally. The documentation-only update was pushed as `5020972`, starting exact-head run [43717579441](https://github.com/centerionware/Clockrove/actions/runs/43717579441); that run was later canceled after review identified the cancellation race. Its completed checks and the corrective regression are recorded below.


### 2026-11-15 — PR #61 final-write cancellation race

Cross-review found that cancellation could arrive while the final authenticated manifest was being written to the transactional sink. The encrypt path checked cancellation before constructing/writing the manifest, but did not check again after the awaited sink writes and before `commit()`. `BackupCryptoV2.encrypt` now rechecks immediately before commit; cancellation follows the existing error path, discarding staged bytes. A deterministic test blocks the final manifest-tag write (the seventh sink write for a one-byte archive) with a `Completer`, requests cancellation while that write is pending, releases it, and asserts that the operation throws `BackupCryptoV2CancelledException`, does not commit, and discards the staged output. Digest verification and existing error/cleanup behavior are preserved.

Rebased exact-head run [43717579441](https://github.com/centerionware/Clockrove/actions/runs/43717579441) was canceled after this source review finding. Formatting, analysis, Flutter behavior, Android build, release Java compilation, instrumentation assembly and native unit tests had passed; the real-device stage did not complete, so this run is not validation of PR #61. The race fix and deterministic regression require a new exact-head Actions run. No VM build, test, formatter or analyzer ran; local review is limited to the source diff and `git diff --check`.


### 2026-11-15 — PR #61 cancellable stream reads

Review found that the legacy `isCancelled` poll could not wake a `StreamIterator.moveNext()` blocked on an open source stream. PR #61 now exposes an operation-scoped `BackupCryptoCancellationToken` to both encrypt and decrypt. It races each pending read, awaits one memoized upstream iterator cancellation before discarding the transactional sink, and consumes a losing read's late error. The bool callback remains a boundary poll and cannot interrupt a pending read.

Deterministic regressions stall encryption after a partial archive event and decryption while awaiting post-manifest EOF/trailing-byte validation. They request token cancellation and assert that upstream `onCancel` completes before sink discard, the operation reports cancellation, and staged bytes are never committed or visible. The separate cancellation-during-final-manifest-write test remains. These changes await a fresh exact-head Actions run; no local tests/builds were run. Intermediate exact-head run [43718937946](https://github.com/centerionware/Clockrove/actions/runs/43718937946) on `9cb3d9f` was canceled before tests because it was superseded; it is neither a passing nor failing validation result. M7.2 remains open, Settings export remains withheld, and M7.3/M7.6 remain pending.


### 2026-11-15 — PR #61 formatting gate before tests

Exact-head Actions run [43721230239](https://github.com/centerionware/Clockrove/actions/runs/43721230239) on `3eca0a95ac18fad25a2f57e2702b74d69498eac7` failed at `Require clean formatting`. Workflow artifact `formatter-source-patch` (artifact ID `14164302366`) contained the exact formatter correction applied to the formatted successor. Analyze, Flutter behavior, Android/release build, native, and device stages were skipped, so this run provides no test result. The corrected successor head is being prepared and requires a fresh exact-head Actions run. No local formatting, build, or test was run. M7.2/M7.3/M7.6 remain open, and Settings export remains withheld pending full-path memory evidence.


### 2026-11-15 — PR #61 analyzer gate before behavior tests

Exact-head Actions run [43721683574](https://github.com/centerionware/Clockrove/actions/runs/43721683574) on `a1683505ee519403f54953a8705bc1fd2fdfd9b5` passed formatting, then Analyze failed with nine `undefined_function` errors for `Completer` and `StreamController` in `test/backup_crypto_v2_test.dart` because `dart:async` was not imported. Flutter behavior, builds, native tests, and device stages were skipped, so this is not a behavior or device test result. The missing `dart:async` import is now added to the successor. It remains unvalidated until a fresh exact-head Actions run completes. M7.2/M7.3/M7.6 remain open, and Settings export remains withheld pending full-path memory evidence. No local tests, builds, formatting, or analysis ran.


### 2026-11-15 — PR #61 stalled encrypt cancellation test

Exact-head Actions run [43722191441](https://github.com/centerionware/Clockrove/actions/runs/43722191441) on `e1ec9c979a16354f3aa88b2564602b0a57cea513` passed formatting and Analyze, then Flutter behavior reported 240 passed, 1 failed and 14 skipped. The failure was `token cancels a stalled partial encrypt read before sink discard`: its `StreamController.onResume` handshake did not fire, so the test timed out before the cancellation assertions. The decrypt cancellation tests passed. Android build, native tests and API 24/35 device stages did not run. Exact-head Actions run [43723505222](https://github.com/centerionware/Clockrove/actions/runs/43723505222) on `7a856ba0ddfab8723008ad773f76cb0a1659f8eb` passed formatting and Analyze; its Flutter behavior stage was canceled before completion to replace an interim synchronous-controller/event-loop handshake. It provides no behavior result, and Android build, native tests and API 24/35 device stages did not run. Exact-head Actions run [43723864664](https://github.com/centerionware/Clockrove/actions/runs/43723864664) on `6eeae516bb22a01960d48f7c9883cd3b5295ce66` passed formatting and Analyze, then Flutter behavior reported 240 passed, 1 failed and 14 skipped. The same stalled-encrypt cancellation regression timed out after five seconds specifically waiting for the controller `onPause` callback after the partial chunk. The decrypt EOF cancellation test passed. Android build, native tests and API 24/35 device stages did not run. The successor adds phase-specific timeout messages and retains bounded cleanup so the next exact-head run will identify the stalled handshake. No local tests, builds, formatting or analysis ran. M7.2/M7.3/M7.6 remain open, and Settings export remains withheld pending full-path memory evidence.


Exact-head Actions run [43724540958](https://github.com/centerionware/Clockrove/actions/runs/43724540958) on `55cbd3093f2754c78d95fd8a756159650a0583dc` failed at `Require clean formatting`. Workflow artifact `formatter-source-patch` (`format.patch`) contained the exact correction, which is applied in the successor. Analyze, Flutter behavior, build, native and device stages were skipped, so the phase-labelled stream handshake has no test result yet. The current successor removes the unreliable pause/resume callbacks and cancels after `onListen` confirms the first source read is pending; it asserts the staged header exists and retains bounded cleanup. No local formatting or tests ran.


Exact-head Actions run [43725410495](https://github.com/centerionware/Clockrove/actions/runs/43725410495) on `b1384bb5443aab7386d783f57de131b0f12880d4` failed at `Require clean formatting`. The test did not run. Workflow artifact `formatter-source-patch` (`format.patch`) contained the exact formatting correction, applied in the successor; Analyze, behavior, build, native and device stages were skipped. No local formatting or tests ran.

Exact-head Actions run [43725692836](https://github.com/centerionware/Clockrove/actions/runs/43725692836) on `77d9bc4a56c278469a9517343f4b712e37a910c4` passed formatting, analysis, Flutter behavior (including the stalled-encrypt cancellation regression), Android/release builds, instrumentation assembly, and native tests. API 24 passed all 29 actual Android tests. API 35 reported 28 passed and one failed: `ReportMapDeviceTest.opensMapForFortyNineRecordedSamplesAndShowsTileState` found the visible `Map · 49 measured samples` label, then failed because the test helper could not complete `ACTION_CLICK` on that node or any parent. This is a device-test input-helper gap; the map journey was not reached, and no map screenshot was captured. The invoice-viewer and release-tooling gates were skipped after this failure, so M7.2 remains unaccepted. The successor adds a bounds-checked real touchscreen tap fallback and relies on the existing destination-screen assertions to verify the action. Fresh exact-head Actions validation is pending. No local build, test, formatter, or analyzer ran.
Successor `6e2673d7066fc7a4a88f4e3f78f35783cf3e5439` passed exact-head Validate run [43728554581](https://github.com/centerionware/Clockrove/actions/runs/43728554581). Both API 24 and API 35 passed 29/29 actual Android cases, zero failures/errors/skips, and their artifacts contain the 49-sample map capture. Review then identified that the convenience `MotionEvent.obtain` overload leaves the event source unspecified and that a generic `waitForIdle` can time out before destination-screen polling. Final code head `2017a33d15f1ba289037b8b913e75be370f200eb` explicitly sets `InputDevice.SOURCE_TOUCHSCREEN` and relies on destination polling. Exact-head Validate run [43730927411](https://github.com/centerionware/Clockrove/actions/runs/43730927411) succeeded. Inspected API 24/35 XML reports 29 tests each with zero failures/errors/skips, and both artifacts contain `map-captures/report-map-49-samples.png`. Final PR head `5d52b85da8d53711157cfd9c7f7f2cf2a2e602f3` also passed the full exact-head Validate run [43733065558](https://github.com/centerionware/Clockrove/actions/runs/43733065558). PR #61 merged as `770ba3506fc1c06244c20da99a9bb3bdc355fd75`; merged-main push Validate run [43735416556](https://github.com/centerionware/Clockrove/actions/runs/43735416556) passed on that exact SHA. Its API 24 and API 35 artifacts each report 29 actual Android tests with zero failures/errors/skips and include the map capture. Guarded Play delivery [43738238801](https://github.com/centerionware/Clockrove/actions/runs/43738238801) built and submitted internal.45.1 / versionCode `216925099`; Play accepted the submission at 17:04:46 UTC. This proves upload/submission only, not review approval or tester availability. M7.2 remains open because Settings export is withheld pending full-path memory evidence and transactional restore/merge remains outstanding. No local build, test, formatter, or analyzer ran.

### PR #62 Settings scroll fallback rebased onto validated main

PR #62 exact head `541bac9cdf443f2a3b2d19b6b4e204328d6e87d2` was based on stale main `fefdcdf`. Exact-head run [43713638659](https://github.com/centerionware/Clockrove/actions/runs/43713638659) passed formatting, analysis, Flutter behavior, Android/release compilation, instrumentation assembly, and native tests. API 24 passed 38/38 actual Android tests, including the changed Settings scroll helper. The subsequent API-24 backup benchmark APK installation timed out twice before the benchmark started (`No tests ran`, ADB exit `-15`, wrapper timeout `124`); recovery confirmed an emulator reboot. API 35 never started, so the flaky API-35 Settings path was not validated on that head. The branch is now rebased onto validated main `770ba350`. Review found its helper sampled viewport/first-child movement only after a fixed 150 ms, risking a race with an in-flight Flutter scroll animation. The successor polls for target visibility and requires it to stay visible with stable bounds for
150 ms before acting on it. If it remains hidden, the helper waits for observed scroll movement
to settle before issuing another accessibility scroll. It watches for up to 750 ms for movement,
then allows up to 1.5 seconds for an observed animation to settle. A source-tagged touchscreen swipe is used only when no movement is observed. Fresh exact-head Actions validation is pending. No VM build, test, formatter, or analyzer ran.


### 2026-11-15 — owner decision: Google Maps deferred beyond launch

Launch map support is limited to keyless OpenStreetMap tiles and user-configured custom HTTPS raster tiles. Google Maps, its SDK/key setup, billing and restriction verification, and Google-specific device acceptance are deferred beyond launch and are not launch gates. OSM/custom-provider privacy, attribution, outage behavior, and physical owner-device acceptance remain launch work. Earlier runtime-key feasibility research, CI results, and status entries are retained as historical evidence; this decision does not imply new implementation or validation.

### 2026-11-16 — PR #70 fixes invoice PDF action visibility

PR #45's merged-main Validate run [43628166015](https://github.com/centerionware/Clockrove/actions/runs/43628166015) failed the API-35 invoice journey because “View original PDF” was below the initial invoice detail viewport. The generated document existed; the action was not reliably discoverable. Guarded Play delivery run [43630004858](https://github.com/centerionware/Clockrove/actions/runs/43630004858) was skipped because main Validate had failed.

PR #70 moved the existing action directly below invoice totals and added a real Android assertion that it is tappable before scrolling. Exact head 58ca416dfe8c3b82fff8d58898db9ca7acc86a26 passed [run 43798809470, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43798809470/attempts/2), based on eb07857ed24344c4d7cbe4bd3e04d9ff5a33b4a7. API 24 and API 35 each passed 38 actual Android tests with zero failures, errors, or skips; the generated PDF opened in the Android viewer. All other Validate stages also passed. Attempt 1 had Android APK installation timeouts before the affected tests started; the bounded unchanged-head attempt 2 succeeded.

The owner merged PR #70 as main commit 025cc10f5aac80ce6f56005f694e448ee4473761. Merged-main Validate [43804094163](https://github.com/centerionware/Clockrove/actions/runs/43804094163) was triggered for that SHA and remains the gate before Play delivery. No local build or test ran.

### 2026-11-16 — PR #71 native streamed-restore failure windows validated

PR #71 head `def60fad2bc4aa8016ea141356e6063d5a979ef2` fixes two native failure windows. Apply now calculates the committed state version inside the SQL transaction before commit and does not perform a state-version read after durable commit; checked overflow fails before commit so SQLite rolls the transaction back. Import close and close-all still attempt private database/media artifact deletion when closing the staged database throws, report cleanup failure, and schedule a retry when deletion itself fails. Regression tests cover an injected outside-transaction version-reader failure and an injected database-close exception.

Exact-head Validate [43885189418, attempt 2](https://github.com/centerionware/Clockrove/actions/runs/43885189418/attempts/2) passed on `def60fad`. Attempt 1 on the same SHA failed during Gradle dependency resolution because Maven Central returned HTTP 429; no build or tests ran in that attempt. The retry passed formatting, Analyze, Flutter behavior, Android build, release Java compilation, instrumentation assembly, native JUnit, actual Android PDF generation/rendering on API 24 and 35, invoice viewer checks, artifact export, and locked release tooling. Native artifact `14233854254` reports 732 tests with zero failures, errors, or skips, including both new regressions. Device artifact `14235564848` reports 29/29 actual Android tests on each of API 24 and 35, with zero failures, errors, or skips.

PR #71 merged as main commit `d032f16da65b1a6a587bb99f8feddb2c30e9ac84`. This validates the native streamed-preview/apply implementation and its regressions, not a complete user-facing restore journey. M7.2 remains open because Settings export is withheld pending full-path memory evidence. M7.3 still needs the Settings/SAF entry, Dart apply coordinator, record-by-record addition/change/deletion review, complete conflict choices, and end-to-end transactional restore acceptance. No local build, test, formatter, or analyzer ran.
