# Historical validation record from plan_status.md — part 3

_Source: `plan_status.md validation record`. Entries: Combined iteration exact-head acceptance and main merge through Preview checks pass; Classic pagination correction and native legacy review prepared_

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
