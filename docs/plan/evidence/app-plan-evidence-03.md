# Implementation evidence from app_plan.md — part 3

_Source: `app_plan.md §12`. Entries: Quotes: native estimate foundation in progress through 2026-11-14 — M6.6 exact-head CI findings and corrective source_

### Quotes: native estimate foundation in progress

After merged payments #37, the next ordered milestone starts on `quotes`.
Schema 9 source adds editable quote drafts, independent issue-time Q-1000
numbering, immutable issued revisions and explicit local acceptance/decline
against a specific latest revision. Item and estimated-hour quantities use exact
scaled units and arbitrary-precision prices/totals with the existing documented
currency rounding. Quote commands never create sessions, completed tasks,
invoices, source reservations or payments. Issued customer/company/style/tax/logo
snapshots and revision ancestry survive edits and job purging. A frozen schema-8
fixture covers upgrade/rollback independently from the current installer.
Native tests are source only until Actions passes. No quote UI, PDF, email,
conversion or full milestone acceptance is claimed. Implement these dependent
parts next, preserving the same serialized command and source-coverage boundary.

### First quote storage validation evidence

1213cc5 / 43230233087 passed Android build and 495 native cases (44 downloaded
XML files; zero failures/errors/skips), including the quote storage and frozen-v8
upgrade/rollback cases. Later bridge and paged-history source 3962104 requires
43231015416; actual-device/full milestone gates remain pending. Main financial
validation failed at a UI-test transition; focused #40 / 43231269977 runs the
real-form wait correction. Automatic delivery was skipped, not published.

### Quote documents source awaiting Actions

Issued quote revisions now have private immutable PDF caches and explicit QUOTE
rendering across the three existing styles. Frozen unit quantities/prices/tax,
revision, calendar validity and printed terms/notes feed the renderer, with an
estimated total and no payment statement. Native preview/save/email/share and
private content URIs retain exact revision ownership and accounting CC. The new
actual quote PDF/text/attachment gate requires nine quote variants per API and
52 actual tests in total. No passing current document result or UI/conversion
completion is claimed; follow Actions and review genuine captured pages next.

### Quote editor and retained precision source

509 native tests passed for ef3ddcd in 43234622742 (46 downloaded XML, zero
failures/errors/skips). Actual test compilation failed at the file-private PDF
text helper, now shared internally. No real quote pages are accepted yet.
The quote draft editor source adds editable quantities/unit prices, estimated
hours, native/frozen currency precision, exact tax totals, customer/job links,
validity and printed terms/notes. Save remains separate from issue, and unknown
reply retries retain one immutable command. Pre-write invalid drafts return known
conflicts. Widget/native regressions await Actions; full quote navigation and
reviewed conversion remain unfinished.

### Quote navigation and review source

The Quotes main-menu entry now connects native status-filtered keyset pages to
creation/editing, exact issue review, explicit local acceptance/decline and
revision-specific PDF/history navigation. Native quote context returns coherent
currency precision, party/style metadata and calendar today/timezone for review.
Filters operate before pagination; calendar expiry remains separate from explicit
decisions. Schema 9 is still unreleased; date metadata is part of its new tables.
Pending issue/decision controls remain mounted across committed-version events so
lost replies reuse the original intent. Source widget tests cover issue review
cancellation/response loss and explicit decision review. These new screens and
filters need full Actions/device validation; accepted-scope conversion remains.

014f75f / 43237357264 passed analysis and 140 Flutter tests, then the zero-decimal
editor test failed its exact error assertion. The editor previously threw a
generic summary as soon as Form.validate returned false. It now also validates all
controller values before choosing that summary, preserving the exact money error;
the original assertion is retained. Applied the Actions formatting patch to the
unchanged editor/test/common command files. Current source also incorporates #40's
route wait and bounded startup diagnostics without weakening any device gate.

### Quote issue retry card retention

The c604021 Actions regression caught a real unknown-response UI bug: native
publication inserted an issued summary before the unkeyed issue card, recreating
its command child and losing the retry. Stable parent-card keys now preserve the
captured operation across list moves. The response-loss test stays unchanged;
validate this correction before acceptance. Navigation formatting came from
Actions artifact 13954368054, not a VM formatter.

### Main financial validation follow-up

Main 43229082487 failed at the initial API-24 payment field lookup. Reviewed the
actual failure screenshot/accessibility tree: the Record payment route is open,
but early forward-scroll actions passed the top field during its transition.
`payment-route-sync` adds real-route/action and editable-field waits before those
scrolls. Keep the full financial gates; validate and merge this focused test fix
before claiming a new internal delivery. No app crash or store upload is proven
by this failed run. See plan_status.md for artifact evidence.

### Focused payment-fix CI startup follow-up

43231269977 ended cancelled at the job time limit during emulator startup,
before instrumentation. The exact blocked probe is not proven by its log.
Bound all startup property probes and preserve emulator logs in device artifacts;
retain all financial/device gates. #40 still needs a successful current-head run
before merge, main validation and automatic internal delivery.

### Focused financial validation fix merged — 2026-11-12 14:59 UTC

#40 exact head 658fce0 passed all Actions gates in 43239688776. Downloaded
artifacts 13954427580 and 13956665417: 42 native/eight device XML files prove
479 native / 50 actual Android cases with zero failures/errors/skips. Both APIs
produced all 16 financial UI captures; reviewed retained excess-precision input
and its inline error on API 24/35. No app behavior changed in this focused fix.
Merged with exact-head guard as 05ee6e76063172866ec8a1f48c9128c6eb069c0e at
2026-11-12 14:59:36 UTC. Main Validate 43242962261 is live; automatic internal
delivery remains pending. Last proven Play acceptance is still internal.25.1 /
216642056. Brought main into the quote branch, preserving both validation ledgers.
No VM builds/tests/formatting/rendering ran.

### Quote planning conversion source and first actual document proof

f442e97 / 43242100858 passed 517 native / 52 actual Android cases with zero
failures/errors/skips in downloaded XML. All nine real quote document variants
per API passed native render/text/private-attachment gates; three normal API-35
styles were visually reviewed. The final landscape viewer failed queued Save
cancellation feedback, now corrected without changing the assertion. Full current
validation and remaining visual review are pending.

Reviewed planning-conversion source now retains quote-line/revision/task identity,
creates uncompleted fixed-item plans or hourly nonbillable checklists, and can
explicitly link existing work without modifying it. Repeats/revisions reuse the
original binding, including after purge; template copies carry no quote history.
Compatible billing groups respect the existing one-mode-per-job contract. Native
source cases cover retry/concurrency/rollback/retention and schema failure. This
is not full conversion acceptance: UI and actual eligible-work invoicing with
retained agreement prices/tax, explicit estimate/actual differences and exclusive
coverage/provenance remain required. Never bill estimated hours automatically.

### Main validation readiness follow-up carried into quotes

#41 d92e617 corrects a test reveal gap found in main 43242962261. The reviewed
Settings capture shows the app open at its top, with AR below the viewport.
Wait for the actual body and keep bounded active scrolling until the exact field
is visible. Current quote source carries this same test correction; no assertion
or required financial/document gate is removed. New internal delivery is pending.

### Accepted-scope UI/context and bounded replay receipts (source)

Quote planning now has an issued-revision entry from current and historical local
acceptance. Mixed quotes show every fixed-price/hourly-rate group, compatible
new/existing jobs, and explicit create/link choices. Native context reads version,
issued agreement, retained mappings, job settings and board task ownership under
the same serialized boundary. Existing customer/tax/rate, quantity/unit price and
completion are shown before review; differences and historical revisions need
explicit acknowledgement. Plans create no time/completion/invoice/payment.
Read failures block review/submission while retaining entered names, existing-task
choices and note. Missing mapped task IDs are never recreated. Unknown write
outcomes lock edits/back navigation and retry the captured DTO and operation ID.

Scope commands hash their bounded receipt before persistence to avoid loading
thousands of task titles into a single SQLite cursor field on replay. New native
UTF-8-wide replay test checks compact receipt, identical replay, changed-payload
conflict and zero work claims. Source API/widget tests cover mixed exact-price
groups, immutable transport, review cancellation, response-loss identity and
failed-read preservation. Quote refresh/page callbacks also now return void from
setState. Fresh Actions compilation/formatting/tests/device evidence is required;
these source changes do not complete the quote milestone. No VM tests/builds or
formatters were used.

### Quote actual-work billing handoff refined

Added docs/QUOTE_ACTUAL_WORK_PLAN.md with concrete remaining entry/review, exact
actual-source selection, accepted/current price/tax/party policies, shared native
claims, retained original-issue provenance and replacement-chain semantics. Its
acceptance matrix includes real UI/storage/PDF evidence and failure/replay/rollback
cases. This closes a design ambiguity, not implementation or milestone acceptance.

### Scope first source check correction

43252111565 reached Actions analysis after exporting formatting corrections;
analyzer required a superclass constructor parameter in QuoteScopeTarget. Applied
the Actions-generated patch and constructor correction. Behavior/native/device
gates did not run on that head; the new scope source is not yet validated.

### Native actual-work quote review calculation (source)

QuoteInvoiceReviewStore reads accepted revision-linked mappings, compatible job
settings and the existing InvoiceCoverageStore preview. Its read-only review
selects exact completed task IDs or available finalized session slices, excludes
unmapped/incomplete/claimed work, and calculates actual quantities/duration using
accepted or explicitly chosen current prices/tax. Accepted-price billing remains
available when current prices are absent. Historical review, duplicate/forged
sources and cross-kind choices are rejected; no invoice, claim or work mutation
is exposed yet. Current preview totals are recomputed for eligible mapped work,
not copied from unrelated job tasks. Added native source tests for actual-versus-
estimated quantities, 1000-digit hourly rates, ordinary-invoice exclusions,
revision linkage, historical acknowledgement, archive and deletion behavior.

Actions 43252783507 passed formatting, analysis and Flutter behavior gates for
202ceb1 (including scope review/cancel/unknown-reply/failed-refresh tests) and is
building Android. The new native review source is newer and still needs Actions;
this is not completed quote invoice preparation/provenance or milestone acceptance.

### Quote invoice preparation and original-issue provenance (source)

Added the serialized native prepareQuoteInvoice command on top of authoritative
actual-work review. InvoiceStore now shares its existing immutable snapshot/number/
coverage write path with reviewed quote reports; ordinary invoices retain their
prior behavior. Accepted prices/tax and accepted party contact/email are defaults;
current choices are explicit. Customer documents carry quote number/revision, not
private review notes. A retained indexed quote_invoice_links table stores private
original-issue source/line/policy provenance; listing is metadata-only keyset
pagination. Replacement chains copy their original origin without interpreting
its old amounts as current charges. Job purge retains the links and revisions.
Schema 9 remains unreleased; frozen schema_v8.json was not modified.

Added source regressions for exact task billing, shared ordinary/quote exclusions,
replay identity, partial-payment replacement credit, paid refusal, job purge, late
provenance-write rollback (including number/revisions/claims/receipt), and late
schema-install rollback preserving released financial data. This native source
still needs Actions. Flutter preparation review/transport/history navigation and
actual-device/PDF acceptance remain required before quote milestone completion.

### Large planning-group write review

Source review found BoardStore.saveCard loading and snapshotting the entire board
on every new-card append, making a 2000-line quote group perform quadratic peer
work. Read the existing card by ID, count destination rows for append position,
and snapshot only that card when no peers changed. Cross-column moves retain
full peer snapshots after renumbering. The wide UTF-8 native scope test still
creates/replays all 2000 real tasks and now checks every card has a revision head;
it is not reduced to a mock or smaller limit. Fresh native/board/history Actions
are required; no local benchmark or device performance claim is made.

### Main API-35 settings reveal follow-up

43242962261 failed the real payment setup AR-field lookup on API 35 after API 24
passed. The reviewed capture shows Settings open at the top, not a crash. The
focused settings-route-sync test fix keeps polling/scrolling the real vertical
form through route readiness and waits for its Billing defaults body heading.
All financial assertions remain intact. Validate before merge; no new internal
publication is claimed from this failed main run.

### Invoice list return/refresh assertion repair (source)

Quote validation 43247145880 passed native build/tests (47 XML, 537 native
cases, zero failures/errors/skips; downloaded results inspected) but failed the
actual API-35 billing journey waiting for View original PDF. Its captured billing
log independently exposed Flutter rejecting a Future-returning setState callback
in InvoiceHistory._refresh after returning from invoice details. Correct the
callback to return void and extend navigation coverage to explicit refresh and
back navigation. This is a real source defect; it does not establish the sole
cause of the later emulator offline/segmentation failure. Maintenance #41 carries
the repair alongside the main Settings reveal recovery. Fresh Actions evidence
and internal publication remain required. No VM build/test/format was run.

### Maintenance merge and scope baseline evidence

#41 exact b0b84b8 passed full Actions 43251725288: 42 native XML / 479 tests
and 8 device XML / 50 actual Android tests across API 24/35, zero failures/errors/
skips. Real PDF/text/private-attachment and actual-page viewer gates passed.
Downloaded artifacts 13961465697 and 13961655711 inspected; reviewed AR Settings,
retained invoice viewer and unchanged 30.001 input with its two-decimal error.
Merged 93673fb2b05ac0adfef3a3ed94b5017d8558a6f9 at 16:05:59 UTC. Main
43254952365 is running; no new Play acceptance claimed. This merge includes the
same invoice-history repair already present in quotes. Both branch ledgers were
preserved while resolving documentation-only merge conflicts.

Scope 202ceb1 / 43252783507 passed formatting, analysis, Flutter behavior, build
and native checks: downloaded 47 XML / 539 native tests, zero failures/errors/skips.
Its Android device/PDF and viewer gates remain live. Native actual-work preparation
and board append optimization are newer source, requiring their own fresh Actions.

### Actual-work quote channel contracts (source)

Added strict quoteInvoiceContext, calculateQuoteInvoice, prepareQuoteInvoice and
paged quoteInvoices channel contracts. Context includes actual/estimated task
quantities, accepted/current prices/rates, current party/tax, exact eligible time
slices, unfinalized and coverage exclusions, and legacy review blockers. Native
calculation returns versioned canonical minor-unit totals and the chosen frozen
party; the write accepts source IDs/policies, never client-computed money. Related
invoice listing returns metadata without private provenance payloads/notes. Added
bridge source coverage for quantity/price differences, exact subtotal/tax/total,
strict boolean/integer types, same-operation replay and post-prepare exclusion.
Flutter transport and preparation/history UI remain next; fresh Actions required.

### Flutter actual-work invoice transport and document review

Typed immutable Flutter request/source/policy/calculation/history models now match
the native quote invoice contracts. Totals remain native canonical strings; write
commands send no client money. Added source channel tests for captured lists,
1000-digit total preservation, exact source/policy identity and paged metadata
history. Preparation errors now describe changed sources and missing revision
links. These additions still require Actions formatting/analysis/behavior checks;
the preparation UI and related-invoice navigation are not yet connected.

Reviewed older genuine Android artifacts from 43242100858: all three revision-2
quote styles show subtotal 411.00, tax 30.83, total 441.83; API-24 modern revision-1
shows 385.00 + 28.88 = 413.88. Reviewed first/last pages of the 1000-digit quote
variant in all three API-35 styles: amounts wrap through pagination without
visible clipping/overlap; terms/notes remain readable. These are that older
renderer's documents, not acceptance of newer source or of its previously failing
Flutter viewer stage. Current fresh document/viewer run remains required.

### Full scope/document baseline accepted in Actions

Exact 202ceb158dfbbd20ef94b4739a6fbfe9ff231588 passed full 43252783507.
Downloaded 13961252326: 47 XML / 539 native tests, zero failures/errors/skips.
Downloaded 13960723873: 8 XML / 52 actual Android tests across API 24/35, zero
failures/errors/skips. Genuine quote/invoice PDF generation, API-35 exact text,
private attachment identity and actual-page Flutter viewer gates passed, including
the previously failing landscape/cancellation snackbar case. Reviewed all six
light/dark quote viewer captures in 13960648664: three styles, historical revision
notice, toolbar and retained pages are clear. This validates the scope baseline
and document behavior; newer native actual-work billing/optimization and Flutter
invoice transport source remain under separate validation. No quote UI emulator
issue/accept/plan/work/invoice journey or complete milestone is claimed yet.

### Actual-work preparation/history UI (source)

Added accepted-revision entry to actual-work invoicing and related invoice history.
The form chooses a mapped job and real dates/timezone, displays accepted/current
customer/contact/tax/prices plus estimated/actual quantities or duration, and
requires selected eligible work and a private review note. Hourly time allocation
and historical acceptance require explicit acknowledgement. Native calculation
version/totals are reviewed before a separate write. No source is selected by
default. Failed refreshes preserve drafts and block review; unavailable selected
IDs require an explicit clear. Unknown outcomes retain the same operation/invoice
ID/request/choices and lock edits/back navigation until a known result.

Related invoice history pages metadata, keeps old rows on failed reads, retries
the same failed cursor/refresh and opens original/replacement invoice detail.
Creation labels come from native formatting instead of parsing arbitrary stored
epochs in Dart. Private provenance notes/payloads are not in the history list.
Added UI source tests for review cancellation, identical response-loss retry,
failed-refresh selection/note preservation, version-change rejection and history
page/refresh retries. Fresh Actions and native real-device journey remain required.

Main 93673fb / 43254952365 passed full validation: 42 XML / 479 native and 8 XML /
50 actual Android, zero failures/errors/skips; downloaded results inspected. Play
43257957626 is running. Backend 56307eb / 43255636714 has 47 XML / 553 native
passed with no failures/errors/skips; current device/document gates remain live.

### Backend acceptance and next actual quote UI gate

56307eb / 43255636714 passed full Actions: 47 native XML / 553 tests and eight
device XML / 52 actual Android, zero failures/errors/skips. Downloaded results
inspected; real PDF/text/URI and actual-page viewer gates passed. This backend
proof is separate from the newer preparation/history screens. UI head 8351815 /
43258627074 stopped at a null-aware-element analyzer lint and formatting. Applied
its exported source patch and lint correction; behavior/device gates did not run.

Added a separate fresh-emulator QuoteBillingDeviceTest and Actions-only script,
after retaining the prior financial evidence and resetting only the ephemeral
app. Source journey creates/issues a real quote in Flutter, explicitly accepts
it, creates uncompleted scope, edits actual quantity/current price on the board,
moves the task to Done, reviews/cancels/prepares at accepted price, opens the
actual PDF/history and checks duplicate exclusion. Also prepares independent
performed work for the other two styles through real native commands, renders
all three Android PDFs, checks API-35 exact text and private attachment bytes/
To/CC. The script requires the named real XML case, nine UI captures and all
three PDF/PNG/text outputs; expected total becomes 54 actual cases across APIs.
Stable semantic field identifiers support real keyboard entry. This new gate is
source pending Actions, not a claim that the journey or new screens passed.

Main Play 43257957626 accepted 0.1.0-internal.28.1 / 216663874 at
2026-11-12 16:29:35 UTC, built with the permanent key from main 93673fb.
Owner installation/physical/email/availability acceptance remains separate.

### Additional accepted-agreement financial regressions (source)

Added native cases for accepted-price billing when the current task price is
unset (current-price choice rejects, accepted choice preserves work), frozen
accepted customer/contact/email despite later customer and job edits versus
explicit current-party choice, and exact zero/three-decimal JPY/KWD invoice
subtotal/tax/total boundaries. All use real repository/storage commands and shared
claims; no local tests were run. These tests are newer than the 553-case backend
proof and need fresh Actions alongside the preparation UI journey.

### New invoice UI behavior gate and quote-detail test readiness

6b32e7b / 43260662958 passed analysis and all new quote invoice review/history
behavior tests (cancelled review, identical lost-reply operation/invoice/source
retry, failed-read draft/selection preservation, changed-version refusal and
history cursor retry). Overall Flutter suite stopped with 152 passed / one failed
/ 14 document-only skips: the existing quote-detail test looked for an offscreen
lazy-list document button after its successful retry. Preserve its command count/
identity assertions, scroll back to the document button and assert it is enabled.
Applied Actions-generated semantic-wrapper formatting. No Android build/device
result is inferred from this behavior-stage failure. Fresh validation required.

### Hourly UI allocation regression and current behavior proof

046fcf6 / 43261516779 passed clean formatting, analysis and the full Flutter
behavior gate, including the corrected issued-document readiness check; Android
build is running. Added a further hourly UI case with coherent estimate/mapping/
actual-time fixtures: selected finalized time cannot reach calculation without
explicit quote allocation; reviewed total reflects one actual hour, not the
1.5-hour estimate, and cancelling creates no command. Estimated-hour display trims
unused fractional zeros. This additional source needs Actions; no claim of actual
Android quote journey acceptance is made before its named device case passes.

### Current native proof and final quote acceptance checks

046fcf6 / 43261516779 passed Android build/instrumentation compilation and native
tests: downloaded 47 XML / 559 tests, zero failures/errors/skips. This includes
missing current price, frozen/current party and JPY/KWD exact invoice boundaries.
Its actual device/document gate is live. Added a further real repository race
between ordinary and accepted-quote preparation of the same task: one invoice,
one source claim/number, correct winner price, and identical retries of both known
outcomes. Added light/dark 390×844 phone layout tests at 1.8 text scale with
Actions-generated setup/confirmation previews, explicitly synthetic UI fixtures
that do not establish native financial success. Both new checks need fresh Actions.

### Actual quote entry keyboard readiness correction

046fcf6 / 43261516779 ended in the new API-24 quote journey waiting for the
focused Unit price after the keyboard opened. Downloaded 13966095163 and reviewed
failure.png/accessibility.txt: the field remains visibly focused near the IME,
while its full semantics bounds no longer appear in the resized app tree. No
app crash was logged. Keep real UI keyboard entry and exact-value assertions;
actively reveal the field again after IME opening before acquiring SET_TEXT node.
The unchanged predecessor financial/device gates passed on API 24, but this is not
full two-API/54-case success and the final actual-page viewer stage did not run.
Applied the Actions formatter artifact from live 43263186408. Added a per-task
quote acceptance table to plan_status.md so baseline, source and pending real
UI/document/release evidence remain distinct.

### Phone preview and hourly assertion follow-up

317e1de / 43263186408 passed analysis and the light/dark 390×844/1.8-scale
preparation/review layout tests. Downloaded ui-previews and reviewed all four
synthetic UI captures: readable totals, deliberate actual-versus-estimate wording
and reachable wrapped confirmation controls. Clear input focus before the setup
capture so it shows the actual first form page rather than returning to the note.
The hourly allocation test correctly reached USD 43.00 after acknowledgement;
its final duration finder omitted the source label printed in the same Text.
Assert the complete exact source label + newline + 1h 0m duration instead, keeping
no-calculation-before-allocation and no-write-on-cancel assertions. Applied its
exported formatting patch. Android/native/race/device gates did not run on that
head because the behavior assertion failed.

### 2026-11-13 — quote scope-review fix accepted; owner bug fixes in progress

Head d03992c / Actions run 43337896079 passed the full quote milestone. Downloaded
and reviewed native test XML: 47 suites, 561 tests, zero failures/errors/skips.
Downloaded Android test results: 27 actual cases on each of API 24 and API 35,
zero failures/errors/skips, including the quote journey 1/1 on each API. Reviewed
actual generated quote-to-invoice PDFs in minimal/classic/modern styles on both
APIs; API-35 text confirms Q-1001 revision 1 and exact 30 × USD 12.50 subtotal,
7.5% tax and USD 403.13 total. Reviewed 390×844, 1.8-scale setup and confirmation
screenshots in both themes; scope review no longer restores the keyboard or hides
the next action. This closes the Actions quote gate at d03992c. Subsequent email,
map, GPS, small-radius and map-centering source edits have not yet run in Actions;
no build, test or formatter has been run on the VM.

### 2026-11-13 — invoice hierarchy and Settings preview acceptance

The owner requested that invoice, receipt, correction, replacement and quote PDFs
show customer billing details on the left and company details on the right, each
limited to 47% of printable width with wrapped and paginated overflow. The issue
date belongs in the company column. Subtotal and tax rows align to the right edge;
labels and tax rates remain regular weight, while only the subtotal/tax amount
digits are bold. Main Settings places **Preview invoice** immediately below the
style selector and renders synthetic sample data through the production PDF
renderer/viewer using the selected style/currency and saved logo, without saving
an invoice, reserving work, using a number or opening email. These requirements
are recorded in §3.5a, `docs/INVOICE_LIFECYCLE_PLAN.md` and `docs/UI_DESIGN.md`.

The source implementation is in PR #39. Actions run 43364550534 on
`925045b77c23e44d1ba5c5e5c4debf070e2bc7b2` failed its final formatting and PDF
gates. Check-run annotations from 131685765272 supplied the exact diagnostics:
the preview PDF contained “Kitchen tile installation,” but the expected-value
comparison stripped separators only from extracted text; the full customer
sentinel appeared in concatenated PDF text, but the bounds check searched each
fragment for the entire marker and therefore returned no bounds. The formatter
annotation identified three Dart line-wrap adjustments. The source normalizes
both sides of the sample comparison, maps marker offsets across normalized PDF
text fragments to their corresponding bounds, and applies those changes. Run
43367050519 on `26b434dd580e2ccee405951dd1a539055becb951` passed the invoice
preview sample-text case but failed the layout case before it reached the bounds
checks: the joined text was normalized to remove spaces, while the marker assertion
still expected spaces. Its formatter annotation also showed the preview test
callback body needed dedenting. Both exact corrections are now in the worktree,
pending a fresh Actions run. Run 43368987614 on `0b3d9f4b03eb9d380e1f36a3834b77bfa9d8f590`
passed formatting, Flutter behavior, Android build and native tests but the PDF
layout assertion still received empty bounds. Android's [`PdfPageTextContent`
reference](https://developer.android.com/reference/android/graphics/pdf/content/PdfPageTextContent)
says bounds can be empty for unbounded text; API 35 also provides
[`PdfRenderer.Page.searchText`](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#searchText(java.lang.String)),
which returns match bounds. The test now requests bounds for each sentinel through
that API instead of inferring them from text content fragments. This correction
awaits Actions. The `native-ui-previews` artifact from 43368987614 was retrieved
and visually reviewed: API-24 party-layout images for all styles, API-35 Minimal
party layout, all three API-35 Settings preview styles, and the Settings preview
screen capture. These show customer-left/company-right placement, wrapped
overflow, right-aligned subtotal/tax rows, bold amount digits and a clear
sample-only preview with page controls. Visual review looks correct, while the
automated API-35 geometry assertion remains unverified until the `searchText`
revision passes. No local build, test or formatter was run. Keep #39 draft until
corrected formatting/PDF/quote acceptance passes and generated PDF images from
the passing run have been reviewed.

### 2026-11-13 — PDF geometry passes; API-35 Settings test navigation

Actions run 43371370101 on `cc5843a9945675e02f293a40412d89241b470af0` passed the
invoice preview and wrapped-party/right-edge geometry cases on both API 24 and
API 35 (five immutable-copy cases, zero failures on each). Formatting, Flutter
behavior, Android build and native tests also passed. The overall run failed later
in the separate payment UI journey on API 35 while trying to reach the
accounts-receivable field. The field exists in the accessibility tree below the
viewport with `visible=false`; API 24 completed the same payment journey. This is
recorded as an API-35 test-scroll issue, not a PDF failure. The payment journey's
`reveal` helper now searches child nodes before scrolling the container, matching
the route helper used elsewhere. The correction was included in the subsequent
full Actions run 43373569280, which passed; see the final acceptance record below.

### 2026-11-13 — invoice hierarchy and preview passed Actions

Run 43373569280 on source head `751f13b` passed the full Validate workflow:
formatting, analysis, Flutter behavior, Android debug build, 563 native tests,
and 30 actual Android cases each on API 24 and API 35, with zero failures,
errors or skips. This includes all five invoice/quote PDF-copy cases on both
APIs and the real quote issue/accept/plan/work/invoice journey and payment UI
journey, each 1/1 per API. The customer-left/company-right layout, wrapped 47%
columns, right-aligned subtotal/tax rows, bold amount digits and Settings preview
were checked in the actual generated documents. Downloaded and visually reviewed
the successful run's generated party-layout images, Settings preview images for
Minimal/Classic/Modern on both APIs, and preview screen captures. The three styles
remain distinct, party details wrap within their columns, and the preview is
clearly marked sample-only with page navigation. No local build, test or
formatter was run.

Requirements and evidence are reflected in `docs/INVOICE_LIFECYCLE_PLAN.md`,
`docs/UI_DESIGN.md` and `plan_status.md`. The documentation update triggered run
43375591543: API-24 invoice-copy checks passed 5/5, then the quote journey failed
before API 35 while locating its first title field. Its failure screenshot shows
the quote draft at the bottom of its form with only backward scrolling available.
The UI test now resets to the top before entering that title. This exact-head
correction awaits Actions; do not mark #39 ready or merge until the complete
API-24/35 run passes.

The scroll correction is included in source head `69dc8b6`; Actions run
43377293028 passed formatting, analysis, Flutter behavior, Android build, native
tests, and the five actual invoice-copy checks on both API 24 and API 35. API 24
also passed the full quote journey. API 35 passed its 29 preceding device cases,
then the quote journey failed after confirming the customer decision: Android
restored focus to the private note and reopened the keyboard over “Record reviewed
decision.” The failure screenshot and accessibility tree showed the note focused
with the IME visible. The quote detail now unfocuses the note around confirmation,
and the real-device journey asserts that the keyboard remains closed before it
records the decision.

Source head `e3c5532` / Actions run 43379382717 passed the full Validate workflow:
formatting, analysis, Flutter behavior, Android debug build, all 563 native tests,
and all 30 actual Android tests on each of API 24 and API 35, with zero failures,
errors or skips. The quote issue/accept/plan/work/invoice journey, payment journey,
and all five real PDF-copy checks passed 1/1, 1/1 and 5/5 per API respectively.
Downloaded and visually reviewed the exact-head generated invoice PDFs in all
three styles and party-layout samples for all styles on both APIs: customer
details are left; company details and issue date are right. Long content wraps
within the 47% columns, and subtotal/tax labels are regular while only the amount
digits are bold at the right edge. Reviewed the selected-style Settings preview
pages for all three styles and preview screen on both APIs; it is clearly
sample-only and provides page navigation. The
preview and quote invoice figures/text were verified in the Android artifacts.
No local build, test, or formatter was run; `gh` queries and artifact retrieval
ran outside the sandbox as instructed.

### 2026-11-13 — quote iteration merged and internal delivery accepted

PR #39 was squash-merged to `main` as `8da7113a749adf39addb45f7b9005773493793a4`.
The exact PR head `e4e730c` passed Actions run 43381832106; main commit
`8da7113` then passed run 43384059350 with all 563 native tests and 30 actual
Android cases each on API 24 and API 35, zero failures/errors/skips. The main
run's device XML confirms the real quote acceptance, planned-scope, quantity,
invoice, PDF-copy and payment journeys all passed. The invoice layout and
Settings preview visuals were reviewed from the successful PR-head artifacts;
the merge commit has the same source tree.

Automatic main-push delivery run 43385810795 completed successfully and Google
Play accepted the signed internal-testing upload: `0.1.0-internal.29.1`, version
code `216718138`, on the internal track. This confirms upload/submission
acceptance only; it does not establish Google review approval, tester
availability, physical installation or a public release. Build, validation and
Play delivery all ran in GitHub Actions; nothing was built or tested on the VM.

### 2026-11-14 — invoice billing policy checks passed on API 24 and 35

PR #44 source head `3aaa48753ea377f8a887fdbe3df400f3de9fe4ad` passed the
complete Validate workflow in Actions run [43566399956](https://github.com/centerionware/Clockrove/actions/runs/43566399956),
attempt 2. Formatting, analysis, Flutter behavior tests, Android build, native
tests, real PDF generation and viewer checks on API 24 and API 35, device-result
exports, and locked release-tooling/version policy all passed. Exported device
XML reports 32 actual Android cases on each API, with zero failures, errors or
skips. This includes the real billing-policy journey that checks invalid-tax
feedback, main Settings save/reopen, invoice quantities, prior-invoice
exclusion, and retained invoice data. The API-35 unbilled invoice review capture
was checked and shows the exact synthetic subtotal, 7.5% tax, and total.

Attempt 1 of the same run lost the API-24 emulator to a process segfault after
its 23-case base suite; the emulator went offline before billing tests. It did
not report an application assertion failure. Attempt 2 used the same source
head and passed both API suites. The workflow test-scroll helper now uses
accessibility scrolling with a touchscreen swipe fallback to reveal the actual
invalid-tax message. At this point PR #44 was still open; M6.6 remained in
progress because Supplies classification, actual costs, tax-preparation reports,
and secure map-provider settings were outstanding. The later exact-head pass and
squash merge are recorded below. No build, test, or formatter ran on the VM.

The plan-evidence commit `f8d0587ba2343e0c6f747676520e6066af700f64` received
two independent full Actions runs. Dispatched run
[43569496084](https://github.com/centerionware/Clockrove/actions/runs/43569496084)
passed and its exported XML confirms 651 total native/device tests, zero
failures/errors/skips, including all 32 actual Android cases on each API. The
automatic PR run
[43569578326](https://github.com/centerionware/Clockrove/actions/runs/43569578326)
failed during the API-35 `locationRecordingDefaultCanBeTurnedOffAndBackOnInActualSettings`
journey: the accessibility action returned successfully but the setting still
read on. The emulator then went offline and segfaulted. The API-24 suite passed
in that run. This disagrees with the dispatched run on the same commit and does
not reproduce the owner's app crash, but it is a real acceptance-test failure.
PR #44 remained unmerged until the discrepancy was resolved. The settings-device helper now adds
a touchscreen tap at the visible switch bounds if the accessibility action does
not change its state. A new exact-head API-24/35 run is required to establish
whether this is an interaction-harness issue or an app defect.

### 2026-11-14 — location toggle journey passes on the revised harness

The helper correction is in exact PR head
`1a1a183bf592309c16bedb2006c22f7129ad332d`. Actions run
[43571526697](https://github.com/centerionware/Clockrove/actions/runs/43571526697)
passed formatting, analysis, Flutter behavior tests, Android build, all 587
native tests, and the API-24/35 Android/PDF stage. Downloaded XML confirms 32
actual Android cases per API, zero failures/errors/skips. This includes the real
global location-recording toggle saved off and on, main Settings save/reopen,
both billing journeys, immutable invoice copies, payments, quotes and task
media. The invoice billing test still asserts the exact invalid-tax message.
The API-24/35 location-toggle captures show the switch in both states and were
reviewed. Locked release-tooling/version checks also passed. The UI test keeps
the accessibility click and supports a real touchscreen tap at the switch
bounds if the state does not change. The previous automatic PR run on the same
documentation head had failed that toggle journey before the emulator
segfaulted; this later exact-head PR run passed. PR #44 was then squash-merged to
`main` as `48fe0f89727bc017c4d8b30f8fd60f4915b16cb6`. Main validation run
`43574603359` and automatic internal delivery were still pending when this entry
was written. M6.6 remains in progress because Supplies classification, actual
costs, tax-preparation reports, and secure map-provider settings remain. All
validation ran in GitHub Actions; no build, test, or formatter ran on the VM.

### 2026-11-14 — merged-main billing check and Supplies classification start

The merged-main Validate run
[43574603359](https://github.com/centerionware/Clockrove/actions/runs/43574603359)
failed in API-24 billing instrumentation: the harness could not rediscover
`Job tax (%)` after attempting to return to the top of Job settings. The saved
failure screenshot shows the actual settings page near its lower fields and no
application crash; this does not prove whether the navigation issue is solely in
the harness. Play delivery
[43575491279](https://github.com/centerionware/Clockrove/actions/runs/43575491279)
was skipped. The current feature branch replaces the acknowledged accessibility
scroll with repeated real return-to-top swipes. A fresh Actions run must verify
the correction before main is considered green.

The M6.6 Supplies-classification slice is source-only: schema v12, explicit
Labor/Supplies selection with legacy rows requiring classification, reusable
template propagation, immutable invoice-line snapshots, migration/command tests,
and an actual-app UI assertion have been added. Historical rows and old invoice
snapshots default to `UNCLASSIFIED`; classification is never guessed from price.
Actions run `43581546961` passed formatting, analysis, Flutter behavior tests,
Android build, and native unit tests. The API-24 actual-map journey passed, but
the later billing suite failed before the API-35 device stage; the separate
Supplies task-editor journey also remains pending because it runs after billing.
Only `git diff --check` ran locally; no VM build/test/formatter ran. Resolve the
`Job tax (%)` harness assertion, rerun both APIs, then continue actual costs,
tax-preparation reports and secure map-provider settings before M7 backup and
the required M7.6 full-app UI/E2E gate.

### 2026-11-14 — report-map white screen reproduced and fixed

PR #45 head `232a722` / GitHub Actions run
[43581546961](https://github.com/centerionware/Clockrove/actions/runs/43581546961)
reproduced the reported white screen on API 24 as
`Unsupported operation: Cannot modify unmodifiable map`. `NetworkTileProvider`
adds its package User-Agent to the supplied headers during `TileLayer`
initialization; the report and single-location map screens had supplied a
constant map. Each screen now creates a mutable header map. The new Android
instrumentation journey writes 49 synthetic persisted GPS samples, opens the
actual job → report → map flow, waits for a loaded tile or visible unavailable
state, and captures the screen. API-24 XML reports 24 tests, zero failures,
errors or skips, including this journey. The captured screen was visually
reviewed and shows OpenStreetMap tiles, the selected coordinate, attribution,
and the external **Open map** action. A separate widget test covers tile-error
fallback rendering. API 35 has not yet run: workflow `43584358180` passed the
API-24 map journey, then the invoice journey failed to return to `Job tax (%)`.
The helper scrolls until the visible `Billing basis` anchor confirms the top
section and now starts swipes in the blank left gutter to avoid controls. The
earlier exact-head run `43583501631` failed Android-test compilation because a
`waitFor` call passed an integer where its second parameter is Boolean; that
argument was removed. A fresh exact-head run is pending. The map fix is on the
PR branch and not yet merged. No build, test, or formatter ran on the VM.

### 2026-11-14 — larger task-photo imports and swipeable photo review

Expanded company-logo and task-photo imports to 50 MB/250 MP, with source
dimensions bounded at 32,768 px and decoding sampled before large bitmaps are
allocated. Normalized private PNGs may be up to 5 MB. Task photos now permit 20
per task, 30 MB per selected batch and 100 MB per job; schema v13 widens the old
attachment constraint while preserving saved images. The existing-image picker
allows up to 10 images, reviews the entire selection in a horizontal carousel,
and attaches the set in one transaction. Full-screen viewing starts at the
chosen photo and swipes left to the previous image and right to the next across
task-level and comment-level images in timestamp order. Native migration/batch
tests, a Flutter multi-select/review/swipe test, and an actual Android
multi-selection/viewer journey are added but have not run yet. Latest Actions
run [43584358180](https://github.com/centerionware/Clockrove/actions/runs/43584358180)
passed the 49-sample map journey on API 24, then failed in the separate billing
test because its return-to-top swipe could not expose `Billing basis`; the
updated helper swipes in the blank left gutter. API 35 was not reached. The new
image and helper changes await a fresh exact-head Actions run; no VM build,
test or formatter ran.

### 2026-11-14 — leading-decimal money input correction

The owner reported `.01` being rejected when recording an invoice payment even
though `0.01` succeeded. The shared currency parser required an integer part;
accepting `.01` as `0.01` fixes that input validation bug while preserving exact
minor-unit conversion and existing currency-precision checks. Regression tests
cover the parser and full payment form, asserting one cent persists as minor-unit
string `1`. Actions runs `43587087757`, `43587596151`, `43588048998`,
`43588642154`, `43588980204`, `43589349628` and `43589666718` each passed both
penny-related Flutter tests. Runs `43588642154`, `43588980204`, `43589349628` and
`43589666718` also passed the task-photo selected-index and left/right swipe
test. The quote form has **Manage customers** directly below its customer
selector and opens the same `CustomersScreen` as Job settings. The shared
customer editor now keeps its save action visible while scrolling. The expanded
widget journey adds and saves a customer through that screen, returns to the
quote, selects the customer, then verifies the draft captures its ID. This
follow-up awaits exact-head Actions. No VM build, test or formatter ran.

The actual API-24/35 Android payment journey also enters `.01`, captures the
form, and verifies the retained payment is exactly one minor unit. These device
assertions await GitHub Actions.

### 2026-11-14 — M6.6 exact-head CI findings and corrective source

At PR #45 head `3ce3e29` / Actions run
[43590405687](https://github.com/centerionware/Clockrove/actions/runs/43590405687),
Flutter formatting, analysis, implemented-behavior tests and Android debug
build passed. Native unit tests completed 594 cases with 15 failures; the
exported XML artifacts and Gradle log were reviewed. Current-schema assertions
expected v12 after the v13 expanded-photo migration. The batch-image retry test
supplied a new state version instead of the original command version. Reading a
stored image as one SQLite BLOB exceeded Android's CursorWindow. Updated the
current-schema assertions to v13, fixed the retry test to reuse its original
version and changed photo retrieval to bounded 512 KiB BLOB slices; the
regression now checks every byte of an image larger than the previous storage
limit. Applied the formatter-source patch exported by Actions. Exact-head rerun
is pending; device journeys did not run because native tests failed. No VM
build, test or formatter ran.
