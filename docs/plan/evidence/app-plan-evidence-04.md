# Implementation evidence from app_plan.md — part 4

_Source: `app_plan.md §12`. Entries: 2026-11-14 — chunked task-photo retrieval follow-up through 2026-11-15 — owner decision: Google Maps deferred beyond launch_

### 2026-11-14 — chunked task-photo retrieval follow-up

At PR #45 head `5752499` / Actions run
[43591679777](https://github.com/centerionware/Clockrove/actions/runs/43591679777),
formatting, analysis, Flutter behavior tests and the Android debug build passed.
The native suite improved from 15 failures to four of 594; all four were
`TaskConversationTest` reads returning no image for both the small-image and
expanded-image cases. Schema-v13 assertions and idempotent batch retry now pass.
Simplified the chunk SQL to query by immutable attachment ID and explicitly cast
SQLite `substr` offsets/lengths, while checking the stored byte bound in Kotlin.
A new exact-head Actions run is required; device journeys were skipped after
native tests failed. No VM build, test or formatter ran.


### 2026-11-14 — photo-read native pass; gallery fixture compile correction

PR #45 head `1e568f1` / Actions run
[43592371710](https://github.com/centerionware/Clockrove/actions/runs/43592371710)
passed Flutter formatting, analysis, implemented-behavior tests, Android debug
build and all 594 native unit tests. The native report confirms schema-v13
migrations, batch-command replay and byte-for-byte reads of a 2.1 MB photo pass.
The workflow stopped before device execution because the synthetic gallery
activity chained `Intent.setClipData`, a Java `void` method, in a fluent
expression. Updated the fixture to set `ClipData` and read permission flags
separately. The exact-head Actions rerun remains required; no device journey ran
on this head. No VM build, test or formatter ran.

### 2026-11-14 — M6.6 actual-cost ledger editor and paging source

Extended the native cost ledger from schema/CRUD source into a paged end-to-end
source slice. The platform channel now accepts an `(occurred,id)` cursor and
returns the next cursor, keeping ordering deterministic when multiple costs
share a timestamp. The Flutter ledger screen lists costs in recorded timezone
and currency, loads older pages, and opens an add/edit form with exact decimal
validation, date/time, category, optional job/task and receipt details. Deletes
are confirmed and soft-delete into the revision history. Cost commands track
the latest committed native version so a user can retry after another app
change without losing the form. Added native pagination and Flutter create,
currency-precision and positive-amount tests; the ledger is reachable from the
main Reports screen. This is source-only and awaits Actions formatting,
analysis, widget/native tests, Android build and device coverage. No local
build/test/formatter ran. Tax-preparation reports and secure map-provider
settings remain in M6.6.

Exact-head follow-up: run `43597344579` on `d29c347` generated a formatter
source patch and stopped at analysis. It identified an accidentally placed
`digits` constructor initializer on `TaskCard`, plus unused/deprecated/lint
findings in the new screen. Applied the exported formatter patch and corrected
the constructor and analyzer findings. The corrected source still needs an
exact-head Actions rerun. No VM build, test or formatter ran.

At corrected head `d5983c9` / run `43597717786`, formatting, analysis, Flutter
behavior tests and UI preview export passed. Android compilation stopped on a
Kotlin reified-array type-inference warning promoted to an error for a mixed
`Long`/`String` SQL bind array. Added an explicit `Any` type and applied the
workflow's two-line formatter correction. Native tests and Android journeys did
not run on this head; exact-head Actions rerun remains required. No VM build,
test or formatter ran.

At head `b355e6f` / Actions run `43598420647`, formatting, analysis, Flutter
behavior tests, UI preview export and Android debug build passed. Native tests
ran 602 cases and reported four failures, all in older-schema fixtures for
location recording and task-image migration; these fixtures downgraded the
version number but retained the newly installed v14 actual-cost table, so the
upgrade attempted to create it twice. The two fixtures now drop the v14 table
before setting their simulated older version. The cost-ledger CRUD, migration
and paging cases passed in this run; rerun is required to validate the fixture
correction. Device journeys did not run. No VM build, test or formatter ran.

### 2026-11-14 — M6.6 report source and repeat API-24 scroll failure

PR #45 head `53e906a` / Actions run `43601098250` passed formatting, analysis,
Flutter behavior tests, Android debug build and all 602 native tests. The API-24
billing journey failed the same **Billing basis** rediscovery after invalid-tax
validation, despite the helper moving its gesture inside the visible scroll
container. The screenshot still shows Job settings open, and the accessibility
dump shows the first visible Flutter child at x=63..1017. The current harness
starts at that child's left edge, marks injected motion as touchscreen and
checks whether an accessibility backward action moved to the target before
using the touch fallback. Another exact-head run is required; API 35 and later
device journeys did not run on `43601098250`.

In parallel, M6.6 report source adds schema-v15 customer snapshots for actual
costs, preserving old entries as unattributed; native aggregation for exact
currency groups, invoice/void issue-date movements, cash/credit effective-date
movements, as-of balances and recorded costs; a Reports entry with job/customer
filters and drilldowns; exact minor-unit CSV and a paginated summary/detail PDF.
Native reconciliation, date/currency/filter, CSV-injection, Flutter filter/
export and Android PDF-render tests are added. None has run in Actions yet and
no local build/test/formatter was run. Secure map-provider settings remain.

At commit `974a7a2` / Actions run `43604844157`, the formatting check passed;
Analyze reported one `curly_braces_in_flow_control_structures` lint in the new
report filter error handler. Behavior tests, Android compilation/native tests
and device journeys were skipped. Added braces to the reported conditional;
push the correction and require a fresh exact-head run. No VM build, test or
formatter ran.

At commit `1abdcfd` / Actions run `43605155898`, Analyze passed. The workflow
provided a formatting patch for the report screen, API and widget test. Flutter
behavior tests reported 180 passed, one failed and 14 skipped: the new filter/
export widget test tried to find the exact-amount CSV note before its lazy-list
row had been built. Android build, native tests and device journeys were
skipped. Applied the workflow's patch and moved that expectation after scrolling
to the export controls. This corrective source needs a new exact-head Actions
run. The API-24 billing-settings scroll journey remains unvalidated on the
current test helper. No VM build, test or formatter ran.

At commit `0905c50` / Actions run `43605752509`, formatting and Analyze passed,
but Flutter behavior again reported 180 passed, one failed and 14 skipped. The
customer-filter test's mock report API still saw an empty selection after the
tap sequence; the CSV-note visibility issue was fixed. The test now taps the
checkbox tile and asserts its selected state before applying. Android build,
native tests and device journeys were skipped. A fresh exact-head Actions run
is required. No VM build, test or formatter ran.

At commit `ccae99f` / Actions run `43606286116`, formatting, Analyze, all
Flutter behavior tests and the Android debug build passed. Native unit tests
reported 614 cases with four failures across SDK variants. The report CSV test
put a formula note after safe detail text, so the cell did not need formula
protection; the JPY grouping test attempted to modify an existing job's fixed
currency. Updated the fixtures to put the formula in the entire detail cell and
change the default currency before creating a new JPY job. PDF generation and
device journeys were skipped. Validate the correction in a fresh Actions run.
No VM build, test or formatter ran.


### 2026-11-14 — API-24 device fixture correction

At PR #45 head `d6405b5`, Actions run
[43607466254](https://github.com/centerionware/Clockrove/actions/runs/43607466254)
passed formatting, Analyze, all Flutter behavior tests, Android debug build and
614 native tests. The API-24 real Android PDF stage ran five tests and found one
fixture failure: its oversized-logo payload was 2,000,001 bytes, below the newer
5,000,000-byte normalized-image limit. Updated the device test to use
`ImageLimits.NORMALIZED_BYTES + 1`, so it stays aligned with the product limit.
API 35 and later device journeys were not reached. The correction requires a
fresh exact-head Actions run. No VM build, test or formatter ran.


### 2026-11-14 — quote task classification in device journey

Exact head `afdf6d8` / Actions run
[43608664165](https://github.com/centerionware/Clockrove/actions/runs/43608664165)
passed formatting, Analyze, all Flutter behavior tests, Android debug build and
614 native tests. API 24 passed the corrected 5 MB image-limit test, then its
quote billing journey showed the expected task-classification guard for a new
Unclassified task. Updated the device test to assert that message, choose Labor
and retry before checking the exact quantity and price. API 35 and subsequent
journeys were not reached. A fresh exact-head Actions run is required. No VM
build, test or formatter ran.


### 2026-11-14 — exact-head API 24/35 verification passed

Exact code head `c9d20cc` / Actions run
[43610282595](https://github.com/centerionware/Clockrove/actions/runs/43610282595)
passed formatting, Analyze, all Flutter behavior tests, Android debug build, 614
native tests, PDF generation/rendering, invoice-viewer checks and all actual
Android device journeys. Downloaded XML reports 35 tests per API 24 and 35, with
zero failures, errors or skips. The quote task guard and classification retry
passed, and reviewed board captures show quantity 30 with a USD 9.99 unit price.
The actual-work invoice journey passed on both APIs. The quote form's Manage
customers button remains immediately below the customer selector and uses the
same shared management screen as Job settings; its add/save/return/select Flutter
test passed. Secure map-provider settings and owner-device acceptance remain
open. No VM build, test or formatter ran.

### 2026-11-14 — merged CI navigation guard and API-24 text replacement follow-up

PR #47 head `b9548a3` / Actions run
[43649614516](https://github.com/centerionware/Clockrove/actions/runs/43649614516)
passed formatting, analysis, Flutter behavior tests, Android build, 614 native
tests and 37 actual device cases each on API 24 and API 35, with zero failures,
errors or skips. The CI-only safe viewport, scroll reset and IME-wait changes
were squash-merged as `2f84790`.

Post-merge main run
[43651548435](https://github.com/centerionware/Clockrove/actions/runs/43651548435)
passed formatting, analysis, Flutter behavior tests, Android build and 614
native tests. API 24 passed its 28-case base journey; the extended billing
journey found that the device helper could append replacement text to a long
unit-price value after Android reported `ACTION_SET_SELECTION` success without
selecting the range. The failure screenshot shows Clockrove still open with the
field focused, not an app crash. The test helper now checks the selected range
and uses Ctrl+A before typing if the range was not actually selected. Validate
this correction on an exact Actions head; API 35 and subsequent device journeys
did not run on `43651548435`. No VM build, test or formatter ran.

At PR #48 head `91f0950` / Actions run
[43653156678](https://github.com/centerionware/Clockrove/actions/runs/43653156678),
the corrected text-selection helper passed all 37 API-24 device cases,
including the wide unit-price edit and invoice journey. API 35 passed its 28
base cases and the billing flow through invoice creation, then the test could
not discover the invalid-tax message. Its screenshot shows the app still open
near the bottom of Job settings. The lookup previously ignored
`AccessibilityNodeInfo.error`, which Flutter may use to expose field validation
messages; that property is now included alongside visible text and descriptions.
Rerun the exact PR head through Actions; no local build or test ran.

The next PR #48 run `43655152043` again passed all 37 API-24 device cases, but
API 35 stopped earlier when the fallback sent Ctrl+A to an empty multiline
review-note field whose selection endpoints were unavailable. Its screenshot
shows the review form open and the note still blank. Ctrl+A is now limited to
non-empty values, preserving ordinary typing into empty fields. The API-35
invalid-tax accessibility check is not yet verified on the updated head. No
VM build or test ran.

The next PR #48 run `43656883319` passed formatting, analysis, Flutter tests,
Android build and native tests, then API 24 failed while replacing the task
quantity: the screenshot showed `126`, with the seeded `1` followed by typed
`26`, even though accessibility reported the range selected. This confirms the
reported range is not reliable on API 24. The helper now sends Ctrl+A for every
non-empty field and keeps the empty-field IME path unchanged. API 35 did not run
on this head; validate the correction through Actions. No VM build or test ran.

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

### 2026-11-15 — main validation and Play delivery freshness

PR #55 exact head `3d29145` / Actions run
[43683416096](https://github.com/centerionware/Clockrove/actions/runs/43683416096)
passed and merged to `main` as `a22fe28`. Its first merged-main validation
attempt reported 28 successful API-35 instrumentation cases, but the required
API-35 XML/artifact collection was incomplete and the workflow failed. The
available artifact does not prove a host emulator segfault. Unchanged-head
attempt 2 of [run 43687899077](https://github.com/centerionware/Clockrove/actions/runs/43687899077)
passed. PR #51 exact head `e898035` / run
[43688694748](https://github.com/centerionware/Clockrove/actions/runs/43688694748)
passed 29 base instrumentation cases on each API with zero failures, errors or
skips; it merged as `8a0e340`. Full merged-main validation
[43690672187](https://github.com/centerionware/Clockrove/actions/runs/43690672187)
passed at 08:01:45 UTC. Its API 24/35 artifacts show 29 base cases plus nine
other device cases per API, all with zero failures, errors or skips. Play run
[43692578396](https://github.com/centerionware/Clockrove/actions/runs/43692578396)
then checked out that exact `8a0e340` source and accepted internal.41.1 /
versionCode `216893018` at 08:07:56 UTC. This does not prove tester availability.

The successful retry for the older `a22fe28` commit triggered Play run
[43691044331](https://github.com/centerionware/Clockrove/actions/runs/43691044331),
which built and submitted internal.40.1 / versionCode `216891797` from
`a22fe28` after `main` had advanced to `8a0e340`. This is a verified stale
internal-delivery gap: the prior workflow accepted any successful main ancestor.
The current branch adds an exact freshly fetched `main` check before build and
again before internal submission; this workflow change has not yet been
validated in Actions. No local build or test ran.

### 2026-11-15 — release Play bundle blocked by dev-plugin registrant

Main Play delivery run
[43701872698](https://github.com/centerionware/Clockrove/actions/runs/43701872698)
on source `cc687093` failed at `:app:compileReleaseJavaWithJavac`: the generated
`GeneratedPluginRegistrant.java` referenced
`dev.flutter.plugins.integration_test`, which is correctly declared only under
`dev_dependencies` and is absent from the release compile classpath. The bundle
did not build, and the later Play upload/submission step was skipped; this run
did not upload internally.

PR #58 prepares a Flutter release-aware configuration refresh
(`flutter build apk --release --config-only`) after the enforced locked
dependency resolution, then retains the AAB build's `--no-pub` behavior. Its
non-secret Validate addition runs that release configuration step and
`:app:compileReleaseJavaWithJavac`; Flutter 3.47.9 documents `--config-only` as
generating build files without artifacts, and the compile task avoids the
repository's signing gate. PR #58 exact head `a51fcd7` / Actions run
[43703761294](https://github.com/centerionware/Clockrove/actions/runs/43703761294)
passed the release-aware configuration, non-secret release Java compilation and
full device suite, then merged to main as `fefdcdf`. Validate run [43706417644](https://github.com/centerionware/Clockrove/actions/runs/43706417644)
passed on `fefdcdf` with 38 actual Android tests per API 24/35 and zero
failures/errors/skips. Guarded Play delivery run [43708670483](https://github.com/centerionware/Clockrove/actions/runs/43708670483) completed successfully on exact main SHA `fefdcdf9f0bcfbdf451ab3fad9eaeaf7f4bccf24`; the internal AAB upload/submission was accepted at 11:30:34 UTC. This proves upload/submission only, not review approval or tester availability.
The current-main validation and guarded Play upload/submission gates are complete
for `fefdcdf`. M8.2 still needs owner-device installation/upgrade evidence, while
M8.3 still needs ongoing protected-release and availability checks. The earlier
Play runs 43692578396 and 43691044331 establish prior deliveries. No local build
or test ran.

Before these changes were committed, PR #58 exact head `004c6de` / Actions run
[43700127667](https://github.com/centerionware/Clockrove/actions/runs/43700127667)
passed formatting, analysis, Flutter behavior tests, the Android build and
native unit tests, then failed its API-24/35 PDF/device stage at
`locationRecordingDefaultCanBeTurnedOffAndBackOnInActualSettings`. This is
separate from the release-plugin issue; that head did not contain the release
configuration check. A fresh exact-head run must cover both.

### 2026-11-15 — PR #61 API-35 actual-map test input

Exact-head Actions run
[43725692836](https://github.com/centerionware/Clockrove/actions/runs/43725692836)
on `77d9bc4a56c278469a9517343f4b712e37a910c4` passed through native tests. The
API-24 actual-device suite passed 29/29. API 35 passed 28/29; the remaining
failure was the report-map instrumentation helper, which found the visible
`Map · 49 measured samples` control but could not invoke it through
`ACTION_CLICK` on that node or its ancestors, so the test ended before
validating the map screen. Add a tap at the
visible, screen-clipped label bounds and retain the destination assertions. The
fix still requires a fresh exact-head Actions run; M7.2 acceptance remains
pending. No local build, test, formatter or analyzer ran.

The touchscreen fallback successor `6e2673d7066fc7a4a88f4e3f78f35783cf3e5439`
passed exact-head Validate run
[43728554581](https://github.com/centerionware/Clockrove/actions/runs/43728554581).
API 24 and API 35 each passed 29/29 actual Android tests with zero failures,
errors or skips; both result artifacts include the real 49-sample map capture.
Before merging, review found that the convenience `MotionEvent.obtain` event
source was unspecified and the generic idle wait could be an unnecessary timeout
before the destination checks. The successor sets `SOURCE_TOUCHSCREEN` and
relies on the existing destination-screen polling. This refinement needs its
own exact-head Actions validation. Final exact head `2017a33d15f1ba289037b8b913e75be370f200eb`
passed Validate run
[43730927411](https://github.com/centerionware/Clockrove/actions/runs/43730927411).
Inspected API 24 and API 35 XML confirms 29/29 actual Android tests each, zero
failures/errors/skips, and the 49-sample map journey passed on both. Each API
artifact contains `map-captures/report-map-49-samples.png`. This validates the
test-helper correction at PR head. PR #61 subsequently merged as
`770ba3506fc1c06244c20da99a9bb3bdc355fd75`; merged-main Validate run
[43735416556](https://github.com/centerionware/Clockrove/actions/runs/43735416556)
passed on that exact push SHA with 29/29 API-24 and 29/29 API-35 actual Android
cases, zero failures/errors/skips, and both map captures. Guarded Play delivery
run [43738238801](https://github.com/centerionware/Clockrove/actions/runs/43738238801)
submitted internal.45.1 / versionCode `216925099`; Play accepted it at
17:04:46 UTC. This confirms upload/submission only, not review approval or
tester availability. M7.2 remains open because Settings export is withheld
pending full-path memory evidence and transactional restore/merge remains
outstanding. No local build, test, formatter or analyzer ran.

### 2026-11-15 — PR #62 Settings scroll fallback rebased

PR #62 exact head `541bac9` was based on stale main `fefdcdf`. Its latest run
[43713638659](https://github.com/centerionware/Clockrove/actions/runs/43713638659)
passed formatting, analysis, Flutter behavior, Android/release compilation,
instrumentation assembly and native tests. API 24 passed all 38 actual Android
tests, including the changed Settings scroll helper. Before API 35 started, the
API-24 backup benchmark APK install timed out twice (`No tests ran`, ADB exit
`-15`, wrapper timeout `124`); recovery confirmed an emulator reboot. API 35
and the flaky API-35 Settings path were not validated on that head. The branch
is rebased onto validated main `770ba350`. Review found that the prior helper
compared viewport and first-child bounds but sampled only after a fixed 150 ms,
which could race an in-flight Flutter scroll animation. The successor polls for target visibility and requires it to stay visible with stable bounds for
150 ms before acting on it. If it remains hidden, the helper waits for observed scroll movement
to settle before issuing another accessibility scroll. It watches for up to 750 ms for movement,
then allows up to 1.5 seconds for an observed animation to settle. A source-tagged
touchscreen swipe is used only when no movement is observed. A fresh exact-head
Actions run is required. No VM
build, test, formatter or analyzer ran.


### 2026-11-15 — owner decision: Google Maps deferred beyond launch

Owner decision narrows launch map support to keyless OpenStreetMap tiles and user-configured custom HTTPS raster tiles. Google Maps, its SDK/key setup, billing and restriction verification, and Google-specific device acceptance are deferred beyond launch and are not launch gates. OSM/custom-provider privacy, attribution, outage behavior, and physical owner-device acceptance remain launch work. Earlier feasibility research and validation notes are preserved as historical evidence; this decision does not imply additional implementation or validation.
