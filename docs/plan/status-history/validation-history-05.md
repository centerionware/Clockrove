# Historical validation record from plan_status.md — part 5

_Source: `plan_status.md validation record`. Entries: Invoice navigation follow-up through 2026-11-14 — exact-head PR #45 validation passes_

### Invoice navigation follow-up

Source repair pending Actions: invoice history refresh/back navigation must use
a synchronous setState callback. Actual quote run 43247145880 billing log caught
the Future-return assertion; strengthened widget navigation test checks refresh
and return without framework exceptions. #41 remains unmerged pending fresh full
validation. The emulator also went offline; no successful device result inferred.

#41 merged after exact b0b84b8 / 43251725288 full success (479 native / 50 actual
Android, zero failures/errors/skips; artifacts and AR/invalid-precision/retained-PDF
visuals inspected). Main 93673fb / 43254952365 validation running; new Play
publication is unproven. Quote scope 202ceb1 / 43252783507: 47 XML / 539 native
zero failures/errors/skips verified; actual-device gates running. Later native
quote billing and board optimization source still require fresh Actions.

Quote actual-work bridge: native context/calculation/preparation and metadata-only
paged history contracts implemented as source, with strict-type/exact-total/replay
coverage added. Not yet validated; Flutter preparation transport/UI remain open.

Flutter quote invoice API/models and exact-string/immutable-source/pagination
source tests added; Actions formatting/analyze/behavior pending. Preparation UI
and related-invoice navigation remain the next implementation work. Older real
43242100858 revision-2/all-style wide first/last pages and API-24 normal modern
quote visuals reviewed; this does not substitute for fresh current-head acceptance.

Scope/document baseline 202ceb1 / 43252783507 passed full Actions: 539 native
and 52 actual Android tests, zero failures/errors/skips; downloaded XML and all
six light/dark actual quote viewer captures inspected. Real PDF/text/private URI
and landscape/cancellation viewer gates passed. Newer actual-work backend and
Flutter invoice API require fresh checks; preparation UI/device journey remain.

Main 93673fb / 43254952365 passed full Actions (479 native / 50 actual Android,
zero failures/errors/skips; downloaded XML inspected). Play delivery 43257957626
is running; store/physical acceptance remain separate. Backend 56307eb /
43255636714 passed 553 native (47 XML, zero failures/errors/skips); actual-device
gates running. Preparation/history UI now implemented as source with explicit
selection/policy/native-version review, preserved failed reads and stable unknown
write identity. UI tests added; fresh Actions and real quote-to-invoice Android
journey/PDF/provenance acceptance remain required before merging #39.

Backend 56307eb / 43255636714 passed full Actions: 553 native / 52 actual
Android, zero failures/errors/skips; XML inspected. New UI 8351815 / 43258627074
stopped at null-aware-element lint/format; source patch/correction applied.
Actual quote UI/invoice pipeline gate now added as source (one fresh emulator
journey per API, three real accepted-price invoice PDFs/bytes/recipients); its
54-case combined gate still needs Actions evidence. Main delivery 43257957626
accepted internal.28.1 / 216663874 at 16:29:35 UTC; physical/store availability
is not inferred. Quotes remain draft #39 pending complete fresh UI/device proof.

Additional native quote billing source regressions added: missing current price,
frozen versus explicitly current customer/contact, and zero/three-decimal currency
invoice totals. Fresh Actions required; prior 553-case proof does not cover these
new cases.

6b32e7b / 43260662958: new invoice review/history UI behavior tests passed;
overall 152 passed / one quote-detail offscreen-finder failure / 14 document-only
skips. Existing command-identity assertions kept; test now scrolls to and checks
the enabled issued-document button after retry. Actions formatter patch applied;
Android build/native/device gates did not run on this failed head.

046fcf6 / 43261516779 passed formatting/analysis/full Flutter behavior and reached
Android build. Additional hourly allocation/estimate-versus-actual UI regression
added as source; fresh Actions still required. Native real-journey/PDF gate remains
unverified until actual emulator execution succeeds.

046fcf6 / 43261516779: Android/instrumentation build and 559 native tests
verified (47 downloaded XML, zero failures/errors/skips). Actual journey gate
running. Ordinary-versus-quote concurrent source race and large-text light/dark
phone review previews added as source; new checks require fresh Actions.

046fcf6 / 43261516779 ended at real API-24 quote entry: keyboard opened and
focused Unit price remained visually present, but its semantics node disappeared
outside the resized accessibility viewport. Reviewed capture/accessibility dump
from artifact 13966095163; no Flutter/native crash was logged. The entry helper
now actively re-reveals the field after IME opening before its unchanged SET_TEXT
and exact-value assertion. New journey/device acceptance remains unproven.

317e1de / 43263186408: analysis and both enlarged-text phone-theme layout checks
passed; four synthetic UI previews reviewed. Hourly allocation reached USD 43.00
but asserted a duration without its combined source label; exact combined text
assertion corrected. No calculation/write guard removed. Clear input focus before
first-page preview. Its fresh formatter patch and API-24 IME visibility correction
are ready for the next complete Actions run; native race/device proof stays pending.

### Quote actual-work journey: API-35 bottom safe-area defect

Latest e4738aa / Actions run 43264537323 attempt 2: formatting, analysis, Flutter
behavior, Android build and all 561 native tests passed (47 XML; zero failures,
errors or skips). API 24 completed all 27 actual-device cases, including the full
Flutter quote issue/accept/scope/task completion/invoice journey and three actual
invoice PDFs. API 35 completed its preceding 26 cases, then the quote journey
failed at “View original PDF.” Inspected artifact 13966703563: accessibility
reported the button at y=1864–1920, under the system navigation bar beginning at
y=1794, while the invoice detail was at maximum scroll. The UI already attempted
to reveal it; the bottom inset was too small on edge-to-edge API 35. Changed the
detail ListView bottom padding from `MediaQuery.padding` to `viewPadding` to
reserve the physical navigation inset. This is a source fix only; fresh Actions
must prove full API-24/35 and generated-document gates before marking Q10/Q11
complete or merging #39. Existing assertions remain strict; no build/test ran on
the VM.

Run 43269368364 / 19bbac6 passed the Android build and 561 native tests. The new
API-24 quote run advanced beyond the prior PDF-viewer visibility failure but then
failed at “Prepare reviewed invoice.” Its failure screenshot shows the review note
focused and Android keyboard open after dismissing the invoice review dialog. This
is a real keyboard focus restoration issue that obscures the next action. The
screen now unfocuses the note before opening the confirmation dialog, and the
device test asserts the keyboard is closed before preparation. API 35 did not run
the quote journey in this attempt because the serial API-24 gate failed first.
Fresh CI must still prove both APIs, invoice PDF/text/viewer outputs, and all
remaining quote acceptance before these tasks are complete.

### Quote IME synchronization: API-35 field entry

Head c4ac90d / Actions run 43272288322 passed formatting, analysis, Flutter
behavior, Android build and 561 native tests (47 XML, zero failures/errors/skips).
Downloaded actual-device results show API 24 passed the full quote issue,
acceptance, scope planning, task completion, invoice and duplicate-exclusion
journey, including three generated invoice PDFs and the detail viewer. API 35
passed its preceding 26 device cases, then failed at the first unit-price entry.
Its capture shows the field focused with the numeric IME open; the accessibility
dump contains the surrounding fields but the test timed out checking
ACTION_SET_TEXT before waiting for the IME transition. Updated the helper to wait
for keyboard visibility, reveal the field again, then wait for its text action
before entering and asserting the exact value. This is a test timing fix awaiting
fresh Actions evidence, not a passing API-35 result. No build or test ran on the VM.

### Actual quote journey passes on API 24 and API 35; large-text collision fixed in source

Head 5a71a9c / Actions run 43276003231 succeeded. Formatting, analysis, all
Flutter behavior tests, Android build, all 561 native tests (47 XML, zero
failures/errors/skips), and the full Android PDF/device gate passed. Downloaded
the device artifact and inspected JUnit: API 24 and API 35 each report 27 actual
Android cases with zero failures/errors/skips; the quote issue/accept/plan/task
completion/actual-invoice/duplicate-exclusion case passed 1/1 on both APIs. Both
APIs generated the three accepted-price quote-to-invoice PDFs; visually reviewed
minimal, classic and modern outputs on each API and actual Flutter invoice viewer
captures. API-35 extracted text from all three shows quantity 30 × USD 12.50,
subtotal USD 375.00, tax 7.5% = USD 28.13 and total USD 403.13, retaining quote
Q-1000 revision 1. This validates the explicit currency-boundary rounding rule
with exact decimal inputs and output.

Downloaded and reviewed the 390×844 / 1.8× light/dark UI previews. The actual-work
setup screen's outlined floating labels overlapped neighboring controls, despite
the layout test passing. Replaced these with separate responsive labels, added
vertical spacing, and preserved the field identifiers and helper hints. The next
Actions artifact confirms the visual correction in both themes; the full actual
journey is pending because the scope review dialog reopened the keyboard. All
build and test work stays in GitHub Actions.

### Large-text layout preview passes; scope-review keyboard restoration found

Head 1f59374 / Actions run 43281033608 passed formatting, analysis, Flutter
behavior, Android build and all 561 native tests. Downloaded the UI preview
artifact and confirmed in both light and dark 390×844 / 1.8× screenshots that
separate labels and spacing remove the prior overlap. The actual API-24 journey
then failed after “Confirm review”: its note regained focus and the keyboard
covered “Apply reviewed plan.” Downloaded screenshot and JUnit show this exact
failure; API 35's quote journey did not run because the API-24 gate failed first.
Added pre-dialog unfocus in quote-scope review plus a device assertion that the
keyboard stays closed after confirmation. This fix and the full actual invoice
journey need fresh Actions and artifact review before Q10–Q12 can be accepted.
No VM build, test or formatter was run.

### Quote scope keyboard fix passed full Actions validation

Head d03992c / Actions run 43337896079 succeeded. Formatting, analysis, Flutter
behavior tests, Android build and all 561 native tests passed (47 XML, zero
failures/errors/skips). Downloaded Android device XML reports 27 actual cases on
API 24 and 27 on API 35, zero failures/errors/skips; the real quote issue,
acceptance, scope planning, task completion, invoice and duplicate-exclusion
journey passed 1/1 on each API. All three actual quote-to-invoice styles and the
Flutter invoice viewer were reviewed on both APIs. Extracted API-35 text confirms
30 × USD 12.50, USD 375.00 subtotal, 7.5% / USD 28.13 tax and USD 403.13 total.
Reviewed Actions preview artifact 13997334053 at 390×844 / 1.8 scale in light
and dark; the fields and confirmation action are readable, and the keyboard
remains closed after review. This confirms the quote fix at d03992c only; all
later bugfix source changes remain pending Actions. No VM build/test/formatter
was run.

### 2026-11-13 — M6.5 attachment focus fix and API-35 test-helper follow-up

On `395151b` / Actions run `43440475983`, API-24 instrumentation confirmed the
comment photo was stored but the app returned to the board before exposing the
attachment. Moved focus cleanup and refresh until after the camera-review dialog
finishes closing and removed the test's post-acceptance Back action. On exact head
`a177984` / run `43443440401`, the full API-24 quote and task-media journeys
passed; the task-media result reports one test with zero failures/errors/skips,
including comment ownership and both gallery/camera persistence. API 35 failed
earlier in the quote journey: the accessibility helper could not reveal
Description, and its screenshot shows the quote form at the top while the
keyboard is open. The API-35 task-media journey therefore did not run. Added a
touch-swipe fallback to the quote and task-media device helpers and am sending it
through a new exact-head Actions run. No VM build, test or formatter ran. Physical
map/GPS/camera acceptance remains unproven.

### 2026-11-13 — API-35 test journey needs touch scrolling in payment helper

Head `6bf3238` / Actions run `43470341913` passed formatting, analysis, Flutter
behavior tests, Android build and native tests. API 24 completed 23 actual
native-device cases, financial lifecycle, immutable copies, payment, quote
conversion and task/comment gallery-camera journeys, all with zero
failures/errors/skips. API 35 passed the same 23 actual native cases, financial
lifecycle and immutable-copy journey. The payment journey then failed to expose
`Unit price (USD)` while correcting a partially paid invoice; its screenshot and
accessibility dump show the replacement editor at the tax section, with the
price row outside the viewport. This is an incomplete device-helper scroll path,
not evidence of missing persisted replacement data. Added touch-swipe fallback
to `InvoicePaymentDeviceTest` forward/backward scrolling and record/retry from a
new exact-head Actions run. API-35 payment completion, quote, task-media, and the
physical map/GPS/camera checks remain pending. No VM build/test/formatter ran.

### 2026-11-13 — API-35 deterministic camera fixture follow-up

Exact head `baa38a4` / Actions run `43473888142` passed formatting, analysis,
Flutter tests, Android build and native tests. Downloaded and parsed device XML:
API 24 passed 23 base cases plus 1 billing, 5 immutable-copy, 1 payment, 1
quote and 1 task-media case, all with zero failures/errors/skips. API 35 passed
the same first five suites; task-media failed when the Android app correctly
handled `ActivityNotFoundException` and showed “No camera app is available”
because the test APK's synthetic camera activity did not resolve on that
platform. The API-35 screenshot and accessibility tree confirm this fallback;
gallery selection and all earlier journeys passed. Moved the synthetic handler
into the debug APK, leaving release camera behavior unchanged, so Actions can
exercise the production capture intent and output-URI flow on both APIs. Fresh
exact-head device evidence is pending. All build and test work remains in
Actions; no VM build/test/formatter ran.

The camera test remained incomplete until head `8be7c17` / run `43485231969`.
That exact run passed all workflow steps and its downloaded XML reports 32 actual
Android tests on each API, all zero failures/errors/skips, including task-media.
Reviewed camera-review and final comment-owned attachment screenshots on API 24
and 35. The task-media test sets a private preference in the debuggable app,
verifies the non-exported fixture activity is installed, and clears the
preference afterward. It confirms review-before-attach and persisted comment
ownership. This fixture is absent from release builds. Physical map/GPS/real-
camera acceptance remains separate.

Head `81d227f` / run `43489413018` passed every Actions step after the
destination-specific snackbar fix. The test now asserts the task-photo and
comment-photo messages separately. Downloaded and parsed XML reports 32 actual
Android tests on each API, zero failures/errors/skips, including the quote and
task-media journeys. Reviewed API-24 and API-35 camera-review, task attachment,
and comment-owned attachment screenshots; the latter visibly says “Photo
attached to this comment.” Physical map/GPS/real-camera acceptance remains
separate. No VM build/test/formatter ran.

### 2026-11-13 — M6.5 merged to main

PR #43 (`milestone-quote-media-gps`, exact head `4a58bb2dcbc4d28941ba22074b230c94f3a0f58d`)
passed Actions run `43493099149` and was squash-merged at `81079883f97d4ebbbbbc403a4309654435fa9ab2`.
The workflow completed successfully. Downloaded device artifacts report 32
instrumentation tests on API 24 and 32 on API 35, with zero failures, errors or
skips. Reviewed the API-35 comment-photo screenshot; the success message names
the comment as the attachment destination. Main-commit validation run
`43497031589` also completed successfully. Internal Play delivery run
`43500590080` is still in progress. Maps, GPS sampling and real-camera behavior
still require the owner's physical-device acceptance; emulator evidence does
not establish those results. No VM build/test/formatter ran.

### 2026-11-13 — M6.6 invoice billing policies in progress

Adopted the documented per-job/per-invoice scope for hourly rounding and
minimums, with task minimum fees applied once per job per invoice. A vertical
slice now contains the schema-v11 policy store, exact hourly billing arithmetic,
global and per-job settings, invoice preview/line rendering, frozen invoice and
replacement policy metadata, and native/Flutter tests. This is source work only;
the GitHub Actions check is still required before claiming it works or merging.
Supplies classification, the actual-cost ledger and tax-preparation reports,
and secure map-provider settings remain in M6.6. No VM build/test/formatter ran.

### 2026-11-14 — PR #44 API 35 billing journey follow-up

Exact head `02e89685c7296f8fb579724da8d8469d86bea70b`, Actions run
`43564540421`: formatting, analysis, implemented-behavior tests, Android build
and native unit tests passed. API 24 completed 32 actual device tests across the
base, billing, invoice-copy, payment, quote and task-media suites with zero
failures/errors/skips. API 35's 23-test base suite passed, including settings
save/reopen and location-recording controls. Its billing journey failed while
the test tried to scroll back to the inline message for an invalid tax value;
the failure screenshot shows the real Job settings screen and no app crash, but
does not establish that the validation message was visible. The device helper
is being updated to use the proven vertical-scroll and real-swipe fallback.
Fresh exact-head Actions evidence is still required; PR #44 is not ready to
merge. No VM build/test/formatter ran.

### 2026-11-14 — merged-main billing check and Supplies classification start

Main Validate run [43574603359](https://github.com/centerionware/Clockrove/actions/runs/43574603359)
failed in API-24 billing instrumentation because the test could not rediscover
`Job tax (%)` after its return-to-top attempt. The screenshot shows the real Job
settings page near its lower fields without an application crash. Play delivery
[43575491279](https://github.com/centerionware/Clockrove/actions/runs/43575491279)
was skipped. The test helper now uses repeated real swipes; a new Actions run is
required before the merged-main baseline is green.

M6.6 Supplies classification source work is underway on
`milestone-supplies-costs`: schema v12; new task selection and visible category;
legacy migration to `UNCLASSIFIED`; propagation through reusable templates,
reports and immutable invoice lines; command identity; and old-snapshot
compatibility. Tests were added for those native behaviors and the actual task
editor/invoice journey. Invoice/task changes remain under Actions validation.
`git diff --check` is the only local check; no build/test/formatter ran on this
VM. Actual cost ledger, tax-preparation reports, secure map-provider settings,
M7 backup, and the mandatory M7.6 UI-overhaul/E2E/screenshots gate remain. Closed
testing stays blocked on all feature work and full M7.6 acceptance.

### 2026-11-14 — report-map white screen reproduced and fixed

PR #45 head `232a722` / GitHub Actions run
[43581546961](https://github.com/centerionware/Clockrove/actions/runs/43581546961)
reproduced the white screen on API 24 as
`Unsupported operation: Cannot modify unmodifiable map`. `NetworkTileProvider`
adds its package User-Agent to headers while initializing the tile layer; both
map screens now give it a fresh mutable map. The new actual-Android journey
persisted 49 synthetic GPS samples, navigated through job → report → map, and
passed. Its exported XML reports 24 API-24 tests with zero failures, errors or
skips. The captured screen was visually inspected: OpenStreetMap tiles loaded,
the coordinate and attribution are visible, and **Open map** is available. The
map-error widget fallback also has deterministic test coverage. The earlier full
run failed at the separate invoice-billing journey while finding `Job tax (%)`,
so API 35 was not reached. The helper now scrolls until the visible `Billing
basis` anchor confirms the top section instead of using a fixed swipe count. The
exact-head rerun [43583501631](https://github.com/centerionware/Clockrove/actions/runs/43583501631)
failed during Android-test compilation: `waitFor` was called with an integer in
its Boolean parameter position. The argument has been removed. No device tests
ran in that attempt; a new run is required to verify the scroll correction and
API 35. The map change remains in PR #45 and is not merged yet. No VM build,
test, or formatter ran.

### 2026-11-14 — expanded task-photo selection and viewer source

Raised logo and task-photo source limits from 5 MB/16 MP to 50 MB/250 MP,
while retaining bounds on each dimension, downsampling to 1,024 px and a 5 MB
normalized-image limit. Task-photo storage now allows 20 images per task and
100 MB per job; schema v13 rebuilds the existing attachment table without
discarding prior rows. The gallery picker accepts up to 10 existing images,
shows a swipeable review, and adds the batch atomically. The full-screen viewer
starts at the selected item and swipes chronologically across task and comment
photos. Source tests cover dimensions and selection order, database migration
and larger normalized storage; Flutter and actual Android gallery journeys were
updated for multi-photo selection and viewer swipes. The previous exact-head
Actions run [43584358180](https://github.com/centerionware/Clockrove/actions/runs/43584358180)
passed the 49-sample map journey on API 24, then stopped at a separate billing
harness scroll assertion before API 35 or task-media ran. The helper swipe now
starts in the left gutter. None of this image source has yet been built or
tested by Actions. Only source review and `git diff --check` ran on the VM; no
build/test/formatter ran locally.

### 2026-11-14 — leading-decimal payment entry source fix

The owner reproduced a payment form rejection for `.01` while `0.01` succeeded.
The shared parser required digits before the decimal point. It now normalizes
leading-dot values such as `.01` to `0.01` before exact Decimal parsing, leaving
currency precision checks and minor-unit conversion unchanged. Parser and full
payment-form regression tests assert `.01` records exactly one cent (`1` minor
unit). The API-24/35 payment journey also enters `.01`, captures the form and
asserts that the stored payment is one minor unit. The parser and full payment
form cases passed in runs 43587087757, 43587596151, 43588048998, 43588642154
and 43588980204. The actual API-24/35 payment journey still has not run on these
heads because unrelated Flutter-test failures stop CI before Android validation.
No VM tests or builds ran.

### 2026-11-14 — quote customer management and exact-head rerun

Added **Manage customers** directly below the quote customer selector using the
same `CustomersScreen` as Job settings. The quote form watches the shared
customer provider, so additions and edits appear in the selector after return.
Widget coverage creates a customer from the quote flow and checks that it is
available in the selector. Runs [43588980204](https://github.com/centerionware/Clockrove/actions/runs/43588980204),
[43589349628](https://github.com/centerionware/Clockrove/actions/runs/43589349628)
and [43589666718](https://github.com/centerionware/Clockrove/actions/runs/43589666718)
reached this test but failed to expose the lazy-list Save customer action; the
latest still reported 177 other behavior tests passing. These runs also passed
the `.01` payment and photo viewer cases. Run [43590405687](https://github.com/centerionware/Clockrove/actions/runs/43590405687)
passed the refreshed selector test, all Flutter behavior tests, formatting,
analysis and Android build, but native failures stopped device journeys. The
M6.6 image/migration/replay fixes are now recorded in the next entry. The
follow-up replaces test-only customer injection with the full shared-manager
flow and verifies the saved quote draft retains the selected customer's ID. The
customer editor keeps Save visible while scrolling. The exact flow passed in
Actions run [43595000940](https://github.com/centerionware/Clockrove/actions/runs/43595000940).
Formatting, analysis, Flutter behavior, Android build and 594 native tests also
passed there. The later API-24 billing journey failed to expose **Billing
basis** after invalid tax input; later emulator journeys and API 35 did not run.
No VM build, test or formatter was run.

### 2026-11-14 — PR #45 native CI failure reviewed and corrected in source

Exact head `3ce3e29` / Actions run `43590405687` passed Flutter formatting,
analysis, behavior tests and Android debug build. Native unit tests completed
594 cases with 15 failures. Reviewed the exported JUnit XML and Gradle logs:
schema expectations still said v12 after the v13 photo migration; a batch-image
retry test read a fresh expected state version instead of reusing the command's
original version; and `CursorWindow` could not read a normalized image BLOB over
2 MB through one `getBlob` call. Updated schema assertions to v13, corrected
retry version reuse, and added bounded 512 KiB BLOB reads with a full-byte
regression above 2 MB. Applied the formatter-only patch exported by Actions.
These changes await fresh exact-head Actions validation. Device journeys did
not run because native tests failed. No VM build, test or formatter ran.

### 2026-11-14 — native image reads pass; Android fixture compile blocker

At PR #45 head `1e568f1` / Actions run `43592371710`, Flutter format/analyze/
behavior tests, Android build and 594 native tests passed. Native results include
the v13 migration, batch retry and exact retrieval of the expanded 2.1 MB photo.
The real-Android instrumentation task did not execute tests: Java compilation
failed because the synthetic multi-image fixture chained `Intent.setClipData`,
which returns `void`. The fixture now assigns `ClipData` separately and adds
the grant flag as a separate call. Fresh Actions validation is pending. No Android
device journeys ran on that head; no VM build, test or formatter ran.

### 2026-11-14 — actual-cost ledger native fixture corrections

At exact head `b355e6f` / Actions run
[43598420647](https://github.com/centerionware/Clockrove/actions/runs/43598420647),
formatting, analysis, Flutter behavior tests, UI preview export and Android debug
build passed. Native tests ran 602 cases with four failures in two existing
older-schema fixtures. Both fixtures reduced `user_version` while retaining the
new v14 `actual_costs` table, so migration correctly rejected the duplicate
table creation. The fixtures now drop `actual_costs` before downgrading. The new
ledger tests passed; the fixture corrections need an exact-head Actions rerun.
Device journeys did not run. No VM build, test or formatter ran.

### 2026-11-14 — API-24 billing return-to-top harness correction

At PR #45 head `9724065`, Actions run
[43599388270](https://github.com/centerionware/Clockrove/actions/runs/43599388270)
passed Flutter formatting, analysis, implemented-behavior tests, Android debug
build, and all 602 native unit tests. The API-24 billing journey failed after
invalid-tax validation because its return-to-top swipe did not expose
**Billing basis**; API 35 and subsequent device journeys did not run. Reviewed
the exported failure screenshot and accessibility dump: the Job settings form
remained open, the scroll container spanned x=0..1080, its visible contents
spanned x=63..1017, and the helper began the swipe at x=32. The helper now finds
the visible `ScrollView`, bounds the vertical gesture to that container, and
swipes at its center. This is a test-harness correction, not evidence that the
actual Android journeys pass. Run `git diff --check` only on the VM; no build,
test or formatter was run locally. Exact-head Actions validation is required
before continuing through API 35 and the remaining M6.6 device journeys. Tax
preparation reports and secure map-provider settings remain unimplemented.

### 2026-11-14 — M6.6 report source and repeat API-24 scroll failure

PR #45 head `53e906a` / Actions run
[43601098250](https://github.com/centerionware/Clockrove/actions/runs/43601098250)
passed formatting, analysis, Flutter behavior tests, Android debug build and all
602 native tests. The API-24 billing journey failed the same **Billing basis**
rediscovery after invalid-tax validation, despite the helper moving its swipe
inside the visible scroll container. The screenshot still shows Job settings;
the accessibility dump places the first visible Flutter child at x=63..1017.
The helper now starts at that child's left edge, marks injected motion as
touchscreen and checks whether a backward accessibility action actually moved
to the target before using the gesture fallback. A fresh exact-head run is
required; API 35 and later device journeys did not run on this head.

M6.6 report source adds schema-v15 customer snapshots for actual costs while
leaving historical costs unattributed; exact currency-grouped report summaries,
issue/void and payment/correction date drilldowns, as-of balances, customer/job
filters, and supporting CSV/PDF exports; plus native, Flutter and PDF-render
tests. The exact-head Actions workflow has not validated these additions yet.
The report source must pass all Actions stages before it is marked implemented.
Secure map-provider settings also remain. No VM build, test or formatter ran.

### 2026-11-14 — report analyzer correction

PR #45 head `974a7a2` / Actions run
[43604844157](https://github.com/centerionware/Clockrove/actions/runs/43604844157)
passed Flutter formatting. Analyze stopped on one
`curly_braces_in_flow_control_structures` lint in the report screen's filter-load
error handler. Behavior tests, Android build, native tests and device journeys
did not run. Added braces around the reported `if (mounted)` body; push and
validate a fresh exact head. No VM build, test or formatter ran.

### 2026-11-14 — report widget test and formatter correction

Exact head `1abdcfd` / Actions run
[43605155898](https://github.com/centerionware/Clockrove/actions/runs/43605155898)
passed Analyze. Actions supplied a formatter patch for the report screen, API
and widget test. Flutter behavior tests reported 180 passed, one failed and 14
skipped: the new test searched for a CSV exact-amount note while its lazy-list
row was still off screen. Android build, native tests and device journeys were
skipped. Applied the generated patch and moved the assertion after scrolling to
the export controls; the correction needs a new exact-head Actions run. The
API-24 billing-settings scroll journey remains unvalidated on this helper. No
VM build, test or formatter ran.

### 2026-11-14 — report customer-filter test correction

Exact head `0905c50` / Actions run
[43605752509](https://github.com/centerionware/Clockrove/actions/runs/43605752509)
passed formatting and Analyze, but Flutter behavior again reported 180 passed,
one failed and 14 skipped. The customer-filter test's mock API last saw an
empty customer selection after the row tap sequence; the CSV-note visibility
failure was fixed. Changed the test to tap the checkbox tile directly, assert
that it became selected, then apply. The report test still needs exact-head CI
validation; Android build, native tests and device journeys were skipped. No VM
build, test or formatter ran.

### 2026-11-14 — business-report native test fixture correction

Exact head `ccae99f` / Actions run
[43606286116](https://github.com/centerionware/Clockrove/actions/runs/43606286116)
passed formatting, Analyze, all Flutter behavior tests and Android debug build.
Native unit tests reported 614 cases with four failures across SDK variants.
The failures were two repeated fixture assumptions: the formula note was
concatenated after safe text in a detail cell, so it was not a leading formula;
and the test attempted to change an existing job's currency, which is
intentionally immutable. The fixture now exports the formula note as the whole
detail cell and changes the default currency before creating a new JPY job.
PDF and device journeys were skipped. A fresh exact-head Actions run is needed.
No VM build, test or formatter ran.


### 2026-11-14 — quote task classification in device journey

Exact head `afdf6d8` / Actions run
[43608664165](https://github.com/centerionware/Clockrove/actions/runs/43608664165)
passed formatting, Analyze, all Flutter behavior tests, Android debug build and
614 native tests. The API-24 device suite passed the oversized-logo test using
the current 5 MB bound, then exposed a gap in the quote billing fixture: the
quote-generated task is Unclassified, so the task editor correctly refused to
save until the user chose Labor or Supplies. The captured emulator screen
confirmed that validation. Updated the journey to assert the message, choose
Labor, and retry before verifying the exact quantity and price. API 35 and later
journeys were not reached. A fresh Actions run is required. No VM build, test or
formatter ran.


### 2026-11-14 — exact-head API 24/35 verification passed

Exact code head `c9d20cc` / Actions run
[43610282595](https://github.com/centerionware/Clockrove/actions/runs/43610282595)
passed formatting, Analyze, all Flutter behavior tests, Android debug build, all
614 native tests, PDF generation/rendering, invoice-viewer checks, and the full
actual Android journeys. Downloaded device XML shows 35 tests per API 24 and 35,
with zero failures, errors or skips. The 49-sample report map, main Settings
location-recording toggle, billing, invoice copies, payments, quote billing and
task-media journeys passed on both APIs. Reviewed quote board captures show the
updated task as `30 × USD 9.99 each`; its invoice journey retains the accepted
quote unit price and verifies exact subtotal, tax and total. The customer-manager
Flutter test also passed in the full behavior suite. Secure map-provider settings
and owner-device acceptance remain open. No VM build, test or formatter ran.

### 2026-11-14 — secure custom map-provider source and tests

Added Android Settings support for an optional HTTPS raster-tile server while
keeping keyless OpenStreetMap as the default. Custom templates, attribution,
authentication headers and credentials are bounded and validated. Public
settings snapshots expose only whether a credential exists; the secret is
fetched through a separate channel for tile requests and released when the map
client closes. Android
encrypts provider settings with AES-GCM under an Android Keystore key in
app-private preferences; reset removes the encrypted data and key. Tile
credentials are headers only, redirects are disabled, and transport exceptions
do not expose URLs. A Keystore read failure falls back to OSM so map use does
not depend on saved provider settings.

Added Dart validation/settings-form/map-selection/credential-redaction tests,
tile redirect and transport-redaction tests, and Android emulator tests for
Keystore round-trip, ciphertext-at-rest, invalid URL rejection and reset. These
are source-only until exact-head GitHub Actions runs; no VM build, test,
formatter or analyzer was run. The Google Maps Flutter SDK remains a separate
open requirement: Google's current setup configures the Android key in
`AndroidManifest.xml` and recommends package/signing-certificate restrictions.
The standard Flutter SDK does not document runtime initialization from a
user-entered Keystore value. See the official [Flutter setup guide](https://developers.google.com/maps/flutter-package/config)
and [Android key restrictions](https://developers.google.com/maps/documentation/android-sdk/get-api-key).
M6.3/M6.6 remain incomplete until the key strategy is decided and verified.

Exact head `027a616` / Actions run
[43616592751](https://github.com/centerionware/Clockrove/actions/runs/43616592751)
passed dependency resolution but failed formatting and Analyze. Applied the
formatter patch exported by Actions and changed the tile-client callback to an
initializing formal for the reported lint. Behavior, build, native and emulator
tests did not run. A fresh exact-head Actions run is required; no VM build,
test, formatter or analyzer was run.

### 2026-11-14 — map provider test harness correction

At exact head `040961a` / Actions run
[43616875394](https://github.com/centerionware/Clockrove/actions/runs/43616875394),
formatting and Analyze passed. Flutter behavior tests reported 174 passed, 19
failed and 14 skipped. The quote customer-management test passed. The failures
included map widgets left waiting on the unmocked asynchronous provider-settings
channel and map-settings form tests asking `ensureVisible` for children that had
not yet been materialized by the lazy list. Added a deterministic OSM settings
API to the map widget fixtures and changed the provider form tests to scroll
until their target rows are built. These are test-fixture corrections only;
exact-head Actions must confirm them. Android build, native tests and device
journeys did not run. No VM build, test, formatter or analyzer ran.

### 2026-11-14 — map CI follow-up and remaining synthetic-tile errors

Exact head `309996e` / Actions run
[43617956145](https://github.com/centerionware/Clockrove/actions/runs/43617956145)
passed format and Analyze. Flutter behavior tests reported 187 passed, 6 failed
and 14 skipped. Adding a deterministic provider-settings fake resolved 13 map
test failures. The remaining map tests reported `StateError: Bad state: No
element` while exercising synthetic raster tiles and zoom interactions; the run
also exposed an incorrect expected HTTP-validation message and one settings
test still using `ensureVisible` on a lazy child. Updated the expected text,
scrolled to the lazy save button, and added temporary framework-error stack
capture to the remaining map widget tests to identify the underlying source.
This source correction is unverified. Android build, native tests and device
journeys did not run. No VM build, test, formatter or analyzer ran.

### 2026-11-14 — wait for configured map before tile precache

Exact head `c9d22c7` / Actions run
[43619088354](https://github.com/centerionware/Clockrove/actions/runs/43619088354)
passed format and Analyze and reported 189 Flutter behavior tests passed, 4
failed and 14 skipped. Exported framework stacks show every remaining failure
comes from test code calling `tester.element(find.byType(FlutterMap))` before
`mapProviderSettingsProvider` finishes its initial asynchronous read; the map
screen is still showing its loading state at that point. Updated report and
work-location map tests to settle the settings read before tile precaching. This
sequencing change awaits exact-head Actions validation. Android build, native
tests and device journeys did not run. No VM build, test, formatter or analyzer
ran.

### 2026-11-14 — quote customer manager and API-24 billing scroll correction

At exact PR #45 head `d0eeae8`, Actions run
[43619518818](https://github.com/centerionware/Clockrove/actions/runs/43619518818)
passed formatting, Analyze, all Flutter behavior tests, Android debug build and
native unit tests. The quote form's **Manage customers** button is below its
customer selector and uses the same `CustomersScreen` as Job settings; the
quote add/save/return/select test passed.

The real API-24 PDF/device stage generated and rendered the invoice PDFs and
passed its 28-case base Android suite. The subsequent billing journey timed out
looking for **Job tax (%)** after toggling off **Use default tax rate**. The
retained screenshot and accessibility dump show Job settings still open, with
the scroll position below where the newly inserted field appears. Updated the
device journey to return to Billing basis before revealing and editing the
field. This test-only correction is unverified and pushed for a fresh exact-head
Actions run; API 35 and later device journeys were not reached on run
`43619518818`. No build, test, formatter or analyzer ran on the VM.

At exact head `39b30dc`, Actions run
[43620954797](https://github.com/centerionware/Clockrove/actions/runs/43620954797)
again passed formatting, Analyze, all Flutter behavior tests, Android debug
build and native unit tests. Before any Android device test ran, the API-24
emulator stopped responding during APK installation and the workflow's 120
second install timeout expired (exit code 124). Its captured emulator log ends
at first boot; the tax journey did not execute and API 35 was not reached. This
is an environment timeout, not evidence for or against the UI-test correction.
Retry the same exact-head run. No build or test ran on the VM.

### 2026-11-14 — exact-head PR #45 validation passes

Exact source head `2f9e961` / Actions run
[43622167264](https://github.com/centerionware/Clockrove/actions/runs/43622167264)
passed formatting, Analyze, all Flutter behavior tests, Android debug build,
native unit tests, PDF generation/rendering and locked release-tooling/version
checks. Downloaded XML confirms 37 actual Android tests on each API 24 and 35,
with zero failures, errors or skips. This includes the billing journey after
returning to the top of Job settings, editing and saving the job-tax override,
quote billing, task media, invoice copies and payments. The quote form's
Manage customers test passed its full add/save/return/select flow and verified
the selected customer ID persists in the draft. The 49-sample map and provider
settings were exercised in the same API matrix. This completes M6.6
implementation and CI evidence; physical owner-device acceptance remains
separate, and M7 backup plus the M7.6 full-app E2E/screenshots gate are still
required before closed testing. No VM build, test, formatter or analyzer ran.

At plan-evidence head `1b2f949`, Actions run
[43624729471](https://github.com/centerionware/Clockrove/actions/runs/43624729471)
passed formatting, Analyze, Flutter behavior tests, Android debug build, native
tests and the 28-case base device suite, but the extended API-24 billing
journey failed when it reopened Job settings and could not reveal **Use default
tax rate** from the retained lower scroll position. The prior override entry,
validation, save and task-board return had succeeded. Updated the device test to
reset to Billing basis before the second inheritance toggle. This is a test
harness correction; it requires a fresh exact-head Actions run. API 35 and later
journeys did not run on `43624729471`. No VM build, test, formatter or analyzer
ran.
