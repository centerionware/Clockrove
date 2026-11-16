# Historical validation record from plan_status.md — part 2

_Source: `plan_status.md validation record`. Entries: Durable geofence policy and native event transaction source through Versioned job creation and strict timer transport — source prepared_

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

Contract: [docs/NEXT_TEST_RELEASE.md](../../NEXT_TEST_RELEASE.md). The complete
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
