# Implementation evidence from app_plan.md — part 2

_Source: `app_plan.md §12`. Entries: Owner-directed next iteration scope through Bounded cached-document storage review_

### Owner-directed next iteration scope

Owner reports the current main build generally works, but the widget occupies too
much home-screen space. GPS tracking has not yet been tested; permission prompting
worked, and background access required app settings. This is owner-reported
feedback without a device/OS log, not a completed physical acceptance matrix.

Deliver alongside current site-selection and archive work:
- A compact home-screen widget with the same native clock-out boundary.
- A real job detail display and the planned Kanban board: columns/cards, due dates,
  reorder/move controls and accessible actions, with committed native persistence.
- A main settings page for a default hourly rate and report currency, with an
  optional per-job hourly-rate override.
- Job reports showing completed time and amount owed using the effective current
  hourly rate. Exclude active/provisional work. Apply the rate to total finalized
  duration, round money once for the report using the currency's precision, and
  clearly state the calculation; payments/taxes are not added; the later owner request explicitly adds PDF invoicing and email drafts.
- Archive/unarchive and red, confirmed permanent deletion only after 30 continuous
  archived days, as specified above. Unarchive restores eligibility/preferences
  with fresh site detection and restarts the retention period if archived again.
- Clearer guidance for Android's separate background-permission settings step;
  do not pretend the foreground permission dialog includes an all-time option.

This explicitly replaces the original exclusion of hourly-rate calculations.
Implement native schema/commands and migrations before the corresponding screens;
maintain the single writer, revision/deletion integrity and real CI evidence.

Compact widget source now requests a 2×1 launcher footprint (110×56 dp legacy
minimum), uses one horizontal row and a 48 dp icon action, and retains native
versioned clock-out. Large text prioritizes job/action over the elapsed second
line. Added API-24/35 RemoteViews layout/accessibility cases and CI-rendered idle/
running previews. These are pending Actions and actual launcher/device review;
an installed widget's existing host size may require resizing or re-adding.


### Expanded combined-release request and temporary publishing hold

Owner added fixed task prices, per-job hourly/task billing mode, reusable task
content even from deleted jobs, full reports, customer management and job email,
PDF invoice/email handoff, global company/contact/logo/header/footer/email
settings and at least three invoice styles. These are fully recorded in the
linked next-test-release contract. No implementation of these additions is
claimed by documenting them.

At the owner's request that the next test release include the whole set, disabled
Play delivery workflow 414395901 temporarily; verified GitHub state
`disabled_manually`. Validate remains enabled. Re-enable normal automatic Play
delivery only when the complete combined iteration is ready, then merge/publish
from validated main. Production readiness remains disabled. Do not ship partial
intermediate test builds while this hold is in effect.

Compact-widget run 43071623833 built the Android app but native test compilation
failed because the new widget fixtures used Dart snapshot argument ordering for
the Kotlin type. Switched fixtures to named fields; all layout/state assertions
remain. Corrected Actions validation is pending; no VM checks ran.

### Native business persistence foundation — source awaiting Actions

Prepared schema 5 for the owner-requested combined iteration: job archive dates,
billing/customer fields, real three-column boards, cards, independent reusable
templates, customers, company/email/invoice settings, logo assets and prepared
invoice records. Currency/rates remain explicit and unset rates are not invented.
Money columns reject floating-point values; card placement has a composite
job/column foreign key. Legacy archived dates begin at migration, not a guessed past.

Added revision heads and ancestry rows, recording actual job/site/preferences and
manual/automatic session changes under the existing serialized native transaction.
Migration captures roots without operational geofence facts; new jobs seed boards
and revisions atomically. This is not a claim of tamper-proof history or a completed
backup merge implementation. Eight API-24 cases cover released-schema-4 migration,
tracking/receipt preservation, default boards, ancestry/dedup, retrospective ends,
site tombstones, FK/money guards and rollback. Source is unvalidated until Actions;
customer/board/pricing/invoice commands and screens remain unfinished. No VM
build, dependency resolution, formatter or test was run.


### Native foundation and compact widget merged; Kanban command source

Widget exact head 1cfc12a passed Actions 43072986373; reviewed real RemoteViews
idle/running renders and merged PR #18 as 45670b2fac16b04929a8f7cb3ec564965a829b0d.
Business foundation exact head dec72f4 passed 43073787801: inspected JUnit XML,
127 native tests with zero failures/errors/skips, including all eight migration/
revision cases. PR #19 merged f94179e02f33a91e4631a9a273606a0b78abb220. These
are CI/resource proofs, not actual launcher or owner-data upgrade acceptance.

Prepared real native board commands for column/card CRUD/order/moves, explicit
completion roles/dates, fixed task prices and reusable templates. All operations
share the serialized versioned command ledger and record revision ancestry.
Column deletion requires a destination when populated and keeps at least one;
copying templates creates a new identity without old due/completion/timekeeping
state. Twelve API-24 cases cover integrity, concurrency, rollback, native
completion, currency/date validation and archived-source templates. These new
commands still need Actions and transport/UI integration. Job deletion, customers,
reports, billing settings and invoices remain in progress/pending; no partial
internal release is authorized while the publication hold is active.

### Monitoring retry and upgrade recovery continuation

Rebased the pending explicit monitoring retry/app-upgrade recovery source onto
current main without changing the combined next-release contract or publication
hold. Prior Actions 43065982993 stopped at formatting; applied its source artifact.
Fresh exact-head Actions validation is required. This remains source-level recovery
behavior, not physical GPS/process/upgrade proof. Play delivery remains disabled.
### Combined business iteration evidence (continuation)

PR #20 (7be840e) passed 43077477547: 139 native tests, twelve new board cases.
PR #21 (493814c) passed 43078381276: 150 native tests, eleven business command
cases. PR #22 (5972331) passed 43078930808: 160 native tests, ten DST/exact billing/
CSV cases. PR #23 (65d4734) passed 43080530052: 166 native tests; typed board UI
and interaction checks plus light/dark previews reviewed. PR #24 (4875670) passed
43085253088: customer/company/job billing journeys and six reviewed previews.
These increments merged; none implies the combined iteration or milestone 3 is
fully accepted. Native lifecycle source passed an earlier run; corrected exact
heads and UI remain under validation. Actual PDF checks moved from unsupported
Robolectric PdfDocument to pinned Android emulator instrumentation in Actions.
Logo import, document/provider/integrated report UI checks and release acceptance
remain open. Play workflow 414395901 stays disabled; no partial release publishes.

### Current combined-iteration validation evidence

Recovered actual Actions artifacts after the service interruption. #26 run
43093219562 contains two actual PDF instrumentation cases per API (24/35),
zero failures/errors/skips. #27/#28 runs 43093223910/43093226704 each contain
191 passing native cases. #29 earlier head 042216c / 43101008984 contains
198 passing native cases, including logo-store and DST-report checks; its actual
framework/logo/viewer sequence is still running. Six synthetic report/map views
were reviewed in light/dark themes. Latest #29 adds all-style multipage captures
and requires all ten expected device cases per API. Final exact-head checks,
actual document visual acceptance and main validation remain required; no partial
Play release is authorized. Task-level status is maintained in plan_status.md.
### Independent job-command correctness continuation

Prepared versioned/idempotent job creation through the native command boundary,
retained app response-loss intent and strict integral timer versions. Covers atomic
board/revision creation, stale/duplicate requests, rollback and deleted-ID fences.
Eight API-24/35 native cases plus retry/controller/codec journeys await Actions;
remaining milestone-3/4/7 implementation and physical acceptance remain open.
### Independent milestone-3 session history continuation

Prepared native closed-session manual CRUD and history/revision queries through
the serialized repository. Positive/half-open overlap and explicit timezone
validation, transactional GPS pruning/deletion fences, immutable invoices and
current-timer isolation have fourteen new API-24 cases awaiting Actions. No UI,
bridge or full manual correction journey is claimed yet; Play remains held for
the separately specified combined business iteration.

### Combined business iteration merged to main

PR #29 exact head 8e0e19e0f6bac377ed9acbb1a3aa70a6507054ce passed full
Actions 43117726325. Inspected artifacts contain 238 native cases and thirteen
actual Android cases on each API (24/35), with zero failures/errors/skips. All
six decoded light/dark viewer captures match the reviewed actual-document
images. Native multipage first/middle/final pages were reviewed for all three
styles; physical email-app and GPS/launcher compatibility remain unproven.

Merged #29 as cb2efc9d9aa3dd8fb2b183ebc2eb457e71c514ec after confirming no
older main validation remained active. Re-enabled Play workflow 414395901;
PRODUCTION_READY is unset. Main validation 43120365067 passed and automatically started queued Play
delivery 43121445107; upload/acceptance for this source is pending. No VM build/test was performed.

### Manual history UI milestone prepared

Added job work-history navigation, paginated entries, manual entry/correction
forms, exact millisecond and original-zone formatting, explicit repeated-time
offset selection and nonexistent-time rejection. Unknown command replies retain
the original intent and lock editing/navigation; stale drafts are kept and a
changed saved entry cannot silently advance its expected version. Delete requires
a warning confirmation. Job-wide paginated revision history includes deleted
entries, ordered by local insertion sequence despite clock rollback. Native
detail reads are scoped to the requested job; purged jobs remain inaccessible.

New native query/bridge, real-framework time-round-trip and UI/retry/accessibility
checks are prepared. They await GitHub Actions; no UI/device acceptance is claimed
from source alone. Widget configuration and full M3/M4 acceptance remain open.

### Complete business iteration accepted by Play internal track

Main cb2efc9 passed Actions 43120365067. Automatic Play delivery 43121445107
completed successfully using the permanent upload key; logs allocate versionCode
216603660, build versionName 0.1.0-internal.19.1, and confirm successful upload
at 2026-11-11 23:45:25 UTC. This satisfies the combined iteration's internal
upload gate. Tester availability/installation, physical tracking/email behavior
and production acceptance are not inferred. Production readiness stays unset.

### M4 widget configuration repository contract prepared

Prepared schema 6 device-local widget bindings through the serialized native
repository. Each widget keeps picker mode or an active default job and a fresh
configuration token. Archived/deleted defaults remain distinguishable from a
chosen picker without retaining job names or private work. Widget IDs/bindings
are device-local and must not be included in future portable backup/merge.

Versioned configuration commands retain original outcomes. A guarded native
widget-start command replays committed duplicate taps, fences replaced/deleted
bindings, rejects stale global versions and cannot switch another running job.
Widget removal is transactional and idempotent. Schema-5 migration preserves
existing work/settings/boards/receipts and has a rollback case. Twenty-two
API-24/35 native executions are prepared, pending Actions. The launcher activity,
RemoteViews integration and physical configuration journey remain unfinished;
this contract does not mark M4 widgets accepted.

Platform references: Android widget configuration lifecycle
https://developer.android.com/develop/ui/views/appwidgets/configuration and
foreground-service widget interaction exemptions
https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start.

### Manual history UI accepted in Actions and merged

Exact #32 head 6c0f4df passed 43123810250. Inspected XML confirms 242 native
cases plus fourteen actual Android cases per API (24/35), zero failures/errors/
skips. Eight final history captures match reviewed original-resolution views.
Pending BusinessCommand controls survive eviction from lazy lists and retry
their original captured intent; the unchanged lost-response/scroll regression
passes. Merged as 863c9a0; main validation and its internal upload remain pending.
M4 widget configuration and physical manual/recovery acceptance are still open.

### Priority interruption: main Settings Save crash

Owner reports an Android process crash saving main Settings, while job settings
save normally. Prioritize reproduction/fix ahead of widget UI. Actual Flutter +
native channel + SQLite save/reopen instrumentation and synthetic logcat capture
are prepared for Actions, without assuming a cause from mocked form tests.

Email subject and body templates must both be validated safely, with field-level
feedback and correction guidance. Invalid placeholders/lengths/control characters
never bypass the native transaction guard. Use a bounded literal scanner rather
than platform-sensitive regex initialization, and refuse malformed stored templates
before invoice-number allocation. Validation/reproduction evidence is pending
in the priority settings-crash branch; see plan_status.md.

Owner refinement: new invoice numbering starts at 1000 (INV-1000) and advances
automatically. Existing prepared invoices keep their numbers/content. Older
unconsumed counters below 1000 use a floor at allocation; higher counters never
reset. Invalid template validation must not consume or advance the sequence.

### Main Settings crash reproduced and guarded source checked

Unfixed diagnostic head 2cdf329 / Actions 43128896688 reached Save settings on
actual API 24 and killed the app process. Captured AndroidRuntime trace shows
BusinessStore.saveSettings -> InvoiceEmailTemplate.<clinit> -> PatternSyntaxException
for the unescaped closing brace, wrapped in ExceptionInInitializerError. This
confirms the user's process-crash report and the source cause.

Guarded head a30d0b7 / 43128476006 passed 244 native host cases and sixteen
actual cases on each API (24/35), zero native/device failures/errors/skips,
including Settings save/reopen and both-template invalid/correction cases. The
whole run failed formatting only; final formatted source plus the requested
INV-1000 allocator and actual invoice preparation awaits 43129744209.

### Priority settings fix merged

Full Actions 43129744209 passed exact head 145870a. Final run-specific XML
contains 245 native cases and sixteen actual cases on each API (24/35), zero
failures/errors/skips. Actual Settings save/reopen and native invoice creation
with INV-1000 and correct expanded subject/body pass. Merged #34 as
c5e19d15a06ccd31e85455e5ab4782bcdf36a88d. Its main validation and Play upload
are separate and pending; production readiness stays unset.

### Priority hotfix internal delivery accepted

Main c5e19d1 passed 43131240701; run-specific XML confirms 245 native cases and
32 actual Android cases, zero failures/errors/skips. Play delivery 43132531984
succeeded with the permanent key and accepted versionName 0.1.0-internal.21.1 /
versionCode 216609104 at 2026-11-12 01:17:19 UTC. Settings crash correction, both
template guards and INV-1000 numbering are delivered to the internal track.
This does not establish tester installation or physical device acceptance.
Resume widget implementation; production readiness remains unset.

### Owner-expanded M4 widget scope

Owner requests responsive combined widgets plus separate timer-only and task-list
widgets. Small combined widgets retain only timer controls; larger widgets add a
scrollable native due-task collection. Task taps open the correct board. Implement
all three alongside default/picker configuration, engine-independent timer actions
and stale/deleted-job handling. Initial due window is today plus six subsequent
calendar days and all overdue tasks, excluding completed/archived work. Native
collections and size-aware layouts must be verified, not represented by mock
success. Physical launcher behavior remains a separate acceptance gate.

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

### Owner handoff: paid invoices and accounting copies next

Owner explicitly schedules paid marking and invoice-derived PAID receipts as the
next task after the current widget merge. All invoice/receipt email drafts CC the
optional accounts-receivable email when set, otherwise the company primary email.
This scope is planned, not implemented or validated. Current widget work continues.

### Widget-host test lifecycle correction

Actions 43138373244 passed Flutter startup regression, Android build and 272 native
cases. Actual Settings save/reopen passed on API 24. The host test then detached
a live Flutter view by replacing MainActivity content, provoking a Flutter JNI
accessibility null-parent abort. Move the real AppWidgetHost into a native debug-only
activity so Flutter lifecycle is preserved; this test activity is absent from
release builds. Keep the actual service/collection/board/setup checks and strict
zero-failure gates. This failed emulator run is not widget acceptance.

### Owner clarification: invoice coverage is exclusive

Work already assigned to any invoice for the same job must be excluded from new
invoices, especially after payment. Current preparation snapshots report data
without a source-coverage ledger, so this remains explicit next-task work rather
than an existing guarantee. Implement native transactional task/interval claims,
legacy reconciliation, transparent exclusions and tests alongside paid receipts.
The original invoice is reused for resends and receipt generation.

### Owner roadmap expanded while widget checks continue

Recorded partial payments and unchanged-original balance copies; Q-1000 quotes
and reviewed conversion; unpaid/partially-paid void/replacement with conserved
credit; task comments and supplies classification with separate actual costs;
main-menu company reporting; default tax percent with explicit per-job override.
The detailed plan in docs/INVOICE_LIFECYCLE_PLAN.md defines order, states, exact
money/tax arithmetic, native coverage/payment invariants, correction examples,
PDF/email behavior, migrations, backup dependencies and acceptance checks. These
features are planned, not implemented. Current widgets remain the active task.

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

### 2026-11-12 payment source checkpoint

Draft #37 arithmetic/native ledger head c00eb1a ran Actions 43190055836: 407
native cases, two failures in the old widget v5 migration simulation; all seven
payment arithmetic and 16 API-24/35 ledger cases passed. This is not full branch
acceptance. The simulation lowered a current database's version and left new
payment tables behind. It is replaced with an independent frozen-v5 DDL seed.
Actions also exported released v7 DDL/seed statements, frozen as schema_v7.json
with only platform-owned android_metadata omitted. New 7→8 preservation/rollback
cases are prepared; the one-time generator/export step is removed. AR/CC and
ordered ledger additions also require the next Actions run. Main/internal delivery
remains held by the separately investigated widget binding failure.

PR #37 had documentation merge conflicts after #36 landed, so its b9869b2 push
did not schedule pull-request Actions. Resolved those conflicts by retaining the
newer payment checkpoint and consolidating duplicate status rows; validation must
run on the merged branch head. No validation is inferred from that missing run.

Payment draft 6177d71 / 43192830791 stopped in Flutter behavior: the AR invalid
address and preserved-draft assertions passed, then the corrected email's selection
overlay intercepted scrolling to Save. Native checks did not run. Applied the
Actions formatter patch, configured company/AR fields for email input without
autocorrect/suggestions, and dismiss text editing before the regression scroll.
Next Actions evidence must verify these changes and the frozen-fixture migrations.

### 2026-11-12 payment baseline and immutable copy increment

811fb7d / Actions 43193679201 passed formatting, analysis, Flutter behavior, debug
build and all 414 native cases (downloaded XML: zero failures/errors/skips). This
verifies the frozen 5/7→8 migrations, ledger, AR validation and native CC baseline.
Its actual Android stage failed the known old widget initial-binding case; that is
being corrected separately in #38. This is not full payment milestone acceptance.

Next source increment retains an immutable receipt intent in the same transaction
as each payment, and a correction copy with each reversal. Explicit balance copies
freeze a command version, paid/due and historical event boundary without consuming
invoice numbers or creating cash. Original issued payload/PDF remains unchanged.
Payment events freeze received/effective labels and UTC offset as well as UTC and
IANA timezone. Customer documents exclude internal notes. Retained copy metadata
and revisions survive job purge; derived PDFs use separate private content URIs,
read-only grants and the same three native styles. Save captures the selected
bytes; email keeps current AR/company CC and guards oversized/failed handoffs.

Seven native copy cases per API, two additional handoff cases per API and one
comprehensive actual Android copy/PDF case per API are prepared. The latter covers
partial/paid/balance/correction documents in all three styles, real attachments,
and 1000-digit receipt balances, with required image/PDF artifacts and exact API-35
text checks. New source still needs Actions evidence and image review. Payment UI,
bridge wiring and credit-preserving void/replacement remain outstanding.

### 2026-11-12 widget release hold resolved at PR level

#38 c1595c7 / 43195277559 passed 385 native and 44 actual Android cases with no
failures/errors/skips. The prior factory-only change reproduced the empty third
resize even while the factory held 30 rows and the host adapter remained null.
AOSP Android-7 pre-connection notify path omitted the attachment callback; replacing
immediate notifications with revision/token/day adapter identity passed all three
resize cycles, a committed live edit and final-row/real-board checks on both APIs.
Merged 1c358bc; main and internal upload are separate gates, not yet proven here.
The payment draft now includes the tested source through a main merge.

### 2026-11-12 receipt validation and isolation correction

9562f08 / 43197625440 passed all 434 native cases (downloaded XML, zero failures,
errors/skips), plus Flutter/formatting/analysis/debug gates. The new all-style
actual receipt/correction/balance/wide-PDF case passed API 24, but the shared suite
then failed its unchanged first-invoice settings regression: six earlier receipt
fixtures had consumed INV-1000, so it observed INV-1006. API 35 was not reached.
Keep both first-number assertions and move copy lifecycle instrumentation to a
fresh process/app-data run after prior XML/captures have been retained, following
the existing financial-journey isolation. New copy run also asserts INV-1000; no
counter reset or weakened expected number is used. Revised full Actions evidence
and all-style image/API-35 text review are still required before acceptance.

### 2026-11-12 immutable-copy acceptance and payment UI increment

c21ef87 / 43201426802 passed full Actions: 434 native cases and 46 actual Android
cases across API 24/35, zero failures/errors/skips (40 native XML files and six
actual XML files inspected). Both settings and isolated copy processes preserve
INV-1000 first-number checks. Partial/paid receipt captures in all three API-35
styles were reviewed; short-copy footer pagination exposed on API 24 and balance
copies needs improvement. Source now removes redundant status/section spacing,
keeps a prominent header state, shortens the fixed copy note and requires short
financial fixtures to fit one page. Wide values still paginate without truncation.
All revised visuals require fresh Actions review; not every prior capture was
reviewed yet.

Native payment/receipt metadata and strict command bridge, invoice-list balances,
Dart 4096-digit payment parsing and the invoice detail/payment/correction screens
are prepared. One invoice journey exposes original PDFs, source review, payments,
saved historical copies, current AR/company CC and Send latest copy. Mark paid
proposes the remaining amount; payment/correction confirmation opens its saved
receipt. Native copies retain original invoice/cash identity. Stable keyed submit
controls preserve unknown-response retries when a live paid balance changes form
layout. Regressions cover exact input, response loss, concurrent refresh, transport,
retained deleted-job invoices and audited correction. A new isolated actual Flutter
journey covers AR Save, rejected precision, partial/full payments, corrections,
real receipt views/To/CC/attachment and settlement after job purge. Existing legacy
review navigation is updated for the unified detail screen. All new UI/bridge and
layout source needs Actions evidence. Void/credit-preserving replacement remains
unimplemented and blocks merging the complete payment milestone.

Payment UI cea30dc / 43205517843 stopped at analysis with five missing-brace lint
findings; behavior/build/native/device stages did not run. Applied the Actions
formatter patch and fixed all five reported control-flow blocks. Revalidate the
same prepared UI/bridge/layout journey in Actions; no VM formatter or tests ran.

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

Actual Android document test source now extends every invoice style through
partial-payment replacement: immutable VOID copy, new invoice with credited
payments at issue, final settlement receipt, frozen old PDF/receipt bytes, exact
API-35 extraction and actual To/CC/private attachment reads. The Actions copy gate
requires all 24 PDF/PNG pairs per API, including nine new lifecycle captures, while
retaining the short-document one-page and 1000-digit completeness assertions.
These are prepared cases, not acceptance; current 40259d7 / 43211073661 is live
on the preceding source and the extended document cases need their own run.

Native balance review removed a second competing due accessor: `balance.due`
itself is now zero for VOID records, while the original total and immutable
credited/cancelled split remain retained. This prevents a future native consumer
from accidentally collecting a cancelled predecessor by reading the ordinary due
field. Added a direct pure arithmetic regression and kept the native/index/bridge
zero-due assertions. This follow-up requires Actions validation too.

### Verified native lifecycle checkpoint

40259d7 / 43211073661 passed formatting, analysis, Flutter behavior, debug build
and 476 native tests. Downloaded/inspected 42 native XML files: zero failures,
errors or skips; InvoiceLifecycleTest has 32 executions and InvoicePaymentBridgeTest
has 10 across API 24/35. This validates native cancellation/replacement, ordered
zero-cash credit/tax changes, command replay/concurrency, early/late rollback,
coverage/legacy/retained history and bridge baseline. Real Android stage remains
live. Subsequent 7d84a5b adds the single ordinary due-field guard and all-style
actual lifecycle PDFs; those additions do not inherit acceptance from this older
head. Lifecycle editor/relationships and complete physical acceptance remain open.

### Lifecycle editor and actual UI checkpoint

Previous goal turn made concrete progress: native cancellation/replacement and
strict bridging were committed, 476 native XML results inspected, and current
source/remaining acceptance recorded. No milestone was marked complete.
40259d7 / 43211073661 completed 48 actual Android executions: API 24 all 24
passed; API 35 original/billing/copies passed but the payment UI missed Partially
paid after Back (47 pass / 1 fail total, no errors/skips). Actual screenshot shows
correct 30.00 paid / 77.50 due, with the status above the viewport. The test now
waits for the actual invoice-detail route before scrolling, preserving all balance/
status assertions. Short-copy one-page gates passed on both APIs. New baseline
ad3f06b / 43212272285 passed 477 native cases (42 XML files inspected, zero
failures/errors/skips); extended actual document/UI validation remains separate.

Prepared lifecycle UI now edits frozen descriptions/quantity/unit price or flat
amounts, previews exact subtotal/tax/credit/due, adds actual unbilled report work,
explicitly reviews source releases and requires a red confirmation before native
commit. Cancellation/Back writes nothing. Live version changes keep the draft but
require reviewed refresh; unknown replies retain the same immutable command across
a VOID transition. Invoice details link predecessor/successor, route receipts to
the original invoice and corrections to their current allocation, and expose VOID
credit/cancelled amounts without collection actions. Historical view/email/share
adds a current VOID warning without rewriting old PDF bytes or printing private
reasons. Added presentation/math/immutable-retry and native handoff regressions;
actual PDF checks also verify historical attachment retention. This new UI/handoff
source requires fresh Actions formatting/analysis/tests/build/device proof and
visual review. Complete payment milestone remains draft #37.

Actual UI source now extends the isolated financial journey through reviewed
partial-payment replacement, cancelled review with no writes, preserved payment
ID/credit/tax, original-owner receipt with historical notice, predecessor VOID
details/copy, and confirmed unpaid cancellation with released unbilled work.
Fifteen actual UI captures per API are required alongside the 24 all-style PDF
pairs. Cancellation view has its own title and zero-due explanation. New money/
tax fields disable Flutter's default max-length truncation; oversized pasted
values remain intact and fail exact parsers. Added a regression for a valid-looking
prefix followed by over-limit content, preventing a truncated valid prefix from
being saved. All new UI/format/handoff/interaction evidence remains pending.
The intermediate ad3f06b run's live Android stage is superseded by this concrete
Back-synchronization/editor/actual-flow source, not treated as a timeout failure;
its inspected 477 native results remain the accepted baseline.

Editor source 3f1265f / 43215477299 stopped at analysis with five required-brace
lint findings; behavior/build/native/device gates did not run. Applied only the
Actions-exported formatter patch and corrected all five reported branches. The
actual Back-status helper also now polls the incoming detail route and scrolls
until the real status is visible, rather than treating one transient false scroll
action as proof of reaching the top during a route transition. All financial and
status assertions remain. Revalidate this source; no formatter or tests ran on VM.

99f57ac / 43215957788 passed formatting, analysis, Flutter behavior, debug build
and 479 native cases (42 XML files inspected, zero failures/errors/skips; 34
lifecycle executions). The Android gate then stopped compiling its test APK: the
new fixture referenced InvoicePaymentStore without importing it. Added the missing
import. No emulator cases or new actual-document acceptance ran on that head.
Prior visual review covered actual 40259d7 short Classic paid-balance copies on
both APIs, modern balance/correction and minimal correction; they remain readable
and one page. New lifecycle editor/VOID/replacement captures still need fresh CI.

60fe554 / 43218156299 passed formatting/analysis/Flutter/debug and 479 native
cases (42 XML files inspected, zero failures/errors/skips). API 24 then passed
original/billing/all-style copies; the payment journey completed its earlier
partial/full/correction/deleted-job steps before the new correction expansion
lookup failed. Its actual PNG/tree show the control is visible with Android 7's
spoken label "Correct this invoice, Collapsed". The helper now accepts only the
exact label or exact known ", Collapsed"/", Expanded" accessibility suffixes; all
value, status, source, credit and document assertions remain. API 35 was not reached.
Added physical acceptance procedures for quantities, pasted-value rejection,
payments, company/AR CC in the real email app, corrected credit and source releases.
No physical/device success is inferred from those instructions. Fresh CI required.

### Bounded cached-document storage review

Source audit found that the accepted 20 MB cached-PDF limit exceeded Android 7's
stock 2048 KiB CursorWindow, while original/copy readers selected PDF and metadata
(and original logo) in one cursor row. AOSP config/implementation and the public
SQLiteStatement blob-descriptor API were checked. New source reads metadata
separately, checks each BLOB length/type/limit first, uses a bounded small-row path
and a read-only descriptor for larger bytes, closes resources and verifies exact
length/EOF. Copy reads do not load the original PDF unnecessarily. No money,
numbering, payload or source-claim write behavior changes.

A new actual Android storage case uses an isolated temporary database, reproduces
the oversized cursor constraint on stock API 24, and checks original/copy/logo
byte identity, nullable/small reads and oversized rejection. Its patterned bytes
are explicitly NOT native PDF-rendering evidence; the existing all-style genuine
PdfDocument/PdfRenderer/text/attachment cases remain separate and required. The
copy gate now requires exactly those two named actual cases per API (50 combined
actual cases expected for the complete workflow). This follow-up needs Actions
proof; 29fbe8b's existing UI validation continues separately.

29fbe8b / 43220345571 passed the actual Android stage: downloaded/inspected 42
native XML files (479 cases) and eight device XML files (48 cases), zero failures/
errors/skips. All 15 UI captures per API were produced. The complete workflow then succeeded, including actual-document viewer and
locked release tooling/version policy. Reviewed all nine API-24 VOID/replacement/settled
replacement PDF captures from 60fe554, plus 29fbe8b API-35 correction confirmation,
credited balance and original-owner historical receipt: exact values, warnings
and retained identities are legible. Capture review found a bottom correction CTA
partly behind Android's navigation bar; source now adds the system bottom inset
to all three financial scrolling screens. Added a required actual editor-fields
capture (16 UI captures per API now). Fresh reader/inset current-head acceptance
remains required; the native-only BLOB boundary case remains separate from genuine
PDF rendering. No new merge, Play upload or physical acceptance is claimed.

The bounded-reader source e3a29e7 / 43221972322 passed app/native gates, then its
actual storage test reproduced the legacy cursor failure on API 24 at the explicit
old-query probe: IllegalStateException "Couldn't read row 0, col 0 from CursorWindow".
The probe had only expected SQLiteException; fixed-reader assertions were not
reached. Accept that exact additional platform exception/message at the old probe
only, preserving mandatory byte-identity, bounds, null/small and real PDF checks.
No positive large-reader acceptance is claimed yet. The queued inset increment
contains the same probe and is superseded by this specific correction. Complete
financial baseline remains 29fbe8b / 43220345571, all gates green (479 native /
48 actual); the final reader/inset head still needs 50 actual cases and 16 UI
captures per API. No VM build/test ran.
