# Historical validation record from plan_status.md — part 4

_Source: `plan_status.md validation record`. Entries: Native legacy reconciliation and tax PDFs pass; full review UI source prepared through Main settings-field reveal recovery — source awaiting Actions_

### Native legacy reconciliation and tax PDFs pass; full review UI source prepared

9c72416 / 43165815309 passed analysis, 107 Flutter cases, Android build, 353 native
cases, twenty actual Android cases per API (24/35), all seven actual-document
viewer cases and release-policy checks. Native/device XML has no failures/errors/
skips; the seven general-stage document skips passed in the dedicated stage.
Only the clean-format gate failed. Applied its exact CI patch, then restored the
two new draft-display fields without running a formatter locally. Reviewed the
actual one-page tax PDF for every style on both APIs: all six captures retain
subtotal/tax/grand total, priced rows, branding and footer, including Classic.

Prepared the complete legacy review form and covered-time dialog. Invoice lists
and blocked billing previews now open it. No sources are preselected; existing
reservations have read-only invoice links. Tasks can be searched/selected without
changing their work state. Exact times use calendar/time pickers or full input,
native IANA resolution, explicit repeated-time offsets, gap rejection and overlap
checks. Pending ranges can be edited/removed; stored reservations cannot.

A private note, original-document confirmation and separate empty-source
acknowledgment are required. The keyed form/command survives native completion
before reply or refresh failure, locks editing/navigation while outcome is unknown,
and replays one immutable intent. Confirmation dismisses the keyboard; save remains
available in a fixed footer. Separating invoice history from the PDF viewer keeps
navigation dependencies direct. Removed-job invoices still open read-only PDFs.

Prepared presentation cases for both refresh outcomes, missing-source validation,
repeated/gap/reserved times and CI light/dark captures with large text. These are
unvalidated source, not mocked native acceptance. The next required proof is the
actual Flutter legacy-selection/save -> unbilled-tax review -> new invoice ->
retained document journey, isolated from first-number regression fixtures, on
API 24 and 35 through Actions. No merge or invoice release is claimed yet.
No VM builds/tests/formatting/dependency resolution/rendering ran.

956f5da / 43169321012 stopped at analysis after exporting formatter output: twelve
multiline if statements needed explicit braces. Applied that CI patch and wrapped
the flagged blocks without changing their conditions or behavior. No new legacy
screen tests/native/device checks ran past the failed analyzer gate. Renewed
Actions evidence is required; the independent actual financial suite is next.


Owner quantity priority (current invoice patch): the existing `price_minor` is the
single unit price, renamed Unit price in the editor. No second price input is
introduced. Quantity defaults to one for released tasks and templates, supports
positive four-place exact decimals, and displays an extended total. Native reports
and invoice previews use per-line half-up totals before subtotal tax. New issued
lines freeze quantity and unit price; later edits keep the original source claim.
Template quantities remain editable defaults after source deletion. Source and
regressions are prepared; renewed GitHub Actions validation remains required.
No VM build, test, formatter or rendering ran.

94fd7c7 / 43169835589 passed Flutter behavior, Android build, native, actual
API-24/35 and document-viewer gates; only its final formatter gate failed. Its
exported patch was already included in the quantity commit. Quantity head
d52e35f / 43171494802 passed analysis and Flutter behavior; native/device gates
are still pending. Applied its CI-produced formatting correction; added strict
quantity transport, frozen-v6 default-one migration, save-payload and light/dark
editor capture coverage. Updated the old task-invoice basis assertion for unit
pricing. These additional changes require their own Actions run.

4477887 / 43172132517 stopped at analysis: the new capture used await on the
synchronous test keyboard hide call. Removed that await and applied the exported
CI formatter patch. No quantity native/device success is claimed from this run.

Prepared the independent real Android financial journey in a separate test package
and instrumentation process after the standard 20 cases/API. The Actions script
retains and checks the original XML/PDF/widget evidence before clearing only its
ephemeral emulator's app data. A frozen v6 database and real generated legacy PDF
exercise production migration and the actual Flutter UI: explicit old-task review,
unit-price/quantity save, unbilled subtotal/tax, new immutable invoice, repeat-billing
exclusion, and reopening the retained PDF after a synthetic aged-archive purge.
Strict separate XML requires one complete financial case plus seven captures on
each API. The frozen DDL is packaged only in the test APK. This source is not yet
executed; it does not constitute device acceptance or merge evidence.

Goal-turn progress: pushed the actual financial journey as 50662d2 (queued run
43173354558). Quantity head b4e547d / 43172649874 has passed analysis, Flutter,
Android build and all 365 native tests (35 XML files; zero failures/errors/skips).
Reviewed both CI-generated quantity editor captures: exactly one Unit price input,
Quantity 26 and calculated USD 130.00 in light/dark themes. Actual device/PDF gates
remain live. Extended the template regression to preserve quantity/unit price after
aged job purge and copy them to a new job without completion history. No merge,
new internal delivery or physical financial acceptance is claimed.

Quantity b4e547d / 43172649874 completed successfully. Downloaded and inspected all
35 native XML files: 365 tests, zero failures/errors/skips; each actual Android XML
contains 20 cases with zero failures/errors/skips (40 total). Formatting, analysis,
Flutter behavior, debug build, actual-document viewer and release-policy gates all
passed. Light/dark single-price quantity editor captures reviewed above. This run
does not include the new actual financial journey or extended purge/template case.
Latest pushed head 6c0222d is live in 43174102042; superseded queued 50662d2 run
43173354558 was cancelled before execution. Keep this financial slice unmerged
until its dedicated journey is proven on both APIs.

Prepared additional actual tax-form coverage in the same financial journey: main
Settings enters/saves 7.5% through the real UI instead of seeding the native tax
value; job Settings rejects 101 without a write, saves explicit zero, restores
default inheritance, and verifies the issued invoice stays frozen. Added both
tax-form captures to the required artifact list. Source only until Actions runs.

Clarified the next payment milestone's precision contract: quantity-extended totals
can exceed 64-bit minor units, so payments/credits/balances must retain arbitrary
precision and canonical string storage/transport rather than narrowing issued
amounts. Required conservation cases include large partial payments and transferred
credits. No payment implementation or payment acceptance is claimed.

6c0222d / 43174102042 passed build/native and the original 20 API-24 cases, then
failed the new journey while locating the visible review-note field. Captured
XML/tree/PNG show the selected legacy checkbox and an EditText with no label/hint
on API 24. Inspection of the pinned Flutter engine confirms hints are only exposed
from API 28. Added stable semantic field identifiers and focus-before-entry in the
actual test, retaining all actual writes/assertions. API 35 was not reached.

Owner money priority: audit finds exact BigInt/BigInteger totals, but released
rate/unit-price inputs/storage still use Long limits. Added an explicit exact-money
contract before implementation: pinned Dart decimal plus native arbitrary precision,
canonical decimal text, lossless migration/receipts and deliberate currency rounding
only. Removing these limits is required before this financial patch merges. No
unbounded-money implementation or success is claimed yet.

Selected decimal 3.2.6 from its primary package/author references and adapted Dart
money parsing, formatting and quantity products to string-parsed Decimal, with
explicit half-up only at the task minor-unit boundary. Added very large totals,
half-cent neighbors and signed formatting cases. Native BigInteger calculations
remain exact. This is not yet resolved or validated: use owner-only Actions
prepare_dependencies to produce pubspec.lock, never resolve on this VM. Existing
64-bit rate/unit-price storage/input limits remain until the exact-money migration.

Prepared native exact-money migration in this milestone: settings/job/task/template
prices are canonical TEXT with SQL guards; all typed money fields/reports use
BigInteger, and only checked small compatibility values reach the old INTEGER
columns. Explicit fingerprints preserve released small-number receipts and wide
values remain distinct from unset. Monetary revisions/templates survive source
purge correctly. Removed Dart Long input cap after completing the native path;
kept a 1024-digit input-size guard and explicit currency precision errors.

Applied the lockfile generated by Actions prepare 43176748417: decimal 3.2.6 and
its rational dependency are pinned. Applied its formatter patch (preserving newer
source edits). Added seven native money cases per API for wide rates/totals/tax,
immutability, purge/reuse, exact released fingerprints, opaque retries, rollback
and canonical DB guards; extended wide transport and frozen-v6 migration checks.
These native source changes remain unvalidated; no merge or delivery is claimed.
No VM tests/builds/formatting/dependency resolution/rendering ran.

1e51563 owner preparation 43176748417 passed the full actual financial journey on
both APIs: inspected four XML files, 20 original + 1 financial case per API, zero
failures/errors/skips (42 total). Main/default and job tax forms, explicit source
review, quantity save, taxed invoice, repeat exclusion and retained PDF all ran
through real Flutter/native storage. Only its final formatting gate failed; the
exported correction is applied in c5bf242. This proves the pre-money-migration
journey, not the newer exact-money storage. c5bf242 / 43178148571 is still live.

Added exact-money device acceptance to the next head: save/reopen a wide unit price
through the real task editor and require the exact extended board total before
returning to the normal invoice fixture. Each native PDF style must render a wide
subtotal/tax/grand total; API 35 extraction checks every digit. Original suite count
now requires 21 cases/API plus the separate financial case (44 total if successful),
with ten financial captures/API and all six new wide PDF/PNG artifacts required.
These additional checks are unvalidated source, not claimed device results.

Added a byte-size guard for expanded immutable invoice payloads before committing
a number or source claims. Wide exact prices and repeated amount tokens can grow
a single SQLite cursor row too large; preparation now returns INVOICE_TOO_LARGE
instead of truncating data or committing an unreadable invoice. A native regression
uses a valid repeated template and 1024-digit price to require no invoice, number
consumption or reservation. This new check remains unvalidated.

Exact-money c5bf242 / 43178148571 passed build/Flutter/native: inspected 381 native
cases with zero failures/errors/skips. Original API-24 suite passed 20 cases. The
financial case reached the issued/repeat-exclusion journey, then a test-only
waitFor queried a label twice while navigation refreshed the tree; its second
lookup returned null at line 231 (reopened board before job tax settings). Retain
the node found by the successful bounded poll rather than issuing another lookup.
No assertion is removed. API 35 was not reached. New wide UI/PDF and payload-size
cases still require fresh Actions evidence; formatting correction is being applied.

Source review found PDF visual overflow separate from arithmetic: amounts wider
than the price column could overlap descriptions, and the total stopped shrinking
at 9pt but could still extend off-page. Preserve normal rows/styles and route wide
line amounts and extreme totals through the existing paginated paragraph renderer.
Extended the actual all-style wide-money case with 1000-digit values, first/last
page captures and API-35 extraction of complete labelled row/subtotal/total values.
Only exact known page headers/footers are removed before joining text across pages;
monetary digits are never normalized or discarded. These changes need fresh CI.

7374335 / 43179838708 passed 383 native cases, zero failures/errors/skips, and the
21 original API-24 cases including wide-money PDF rendering. The financial case
entered the exact large price but lost the Quantity field during keyboard/inset
reflow between lookup and focus (tree/PNG confirm the focused price and hidden
quantity). Its test helper now dismisses only a visible IME with real Back, waits
for dismissal, confirms entered values and closes the keyboard between fields.
Interactive-window inspection supplies that visibility check; all native financial
assertions remain. Also fixes captures that could show the preceding invalid draft
before a queued input update painted. API 35 was not reached in this run.

Tightened the same input-helper correction: wait for the actual IME to open after
field focus before SET_TEXT, then confirm the exact value and dismiss it. Otherwise
a fast text-action acknowledgment could race the keyboard's later appearance and
skip dismissal. This is actual UI synchronization, not a replacement of native
saves or any financial assertion. No app-money behavior changed in this follow-up.

Goal-turn progress: final eb74c747cb8cbc6e343864ad9b471c910b131c26 passed complete
43182854787. Inspected 36 native XML files (383 tests) and four actual Android XML
files (21 original + 1 financial case per API, 44 total): zero failures/errors/skips.
Reviewed all six wide and twelve first/last 1000-digit PDF captures, exact wide-price
boards on both APIs, zero tax override and retained invoice. Ordinary layouts stay
clean; extreme values wrap and paginate; repeated full values are checked by actual
API-35 extraction. All other workflow gates passed, including actual-document viewer.

Merged #35 using the exact tested head guard; GitHub confirms f935485 at
2026-11-12 08:47:27 UTC. Main validation 43185844256 is live; new Play acceptance
and tester installation are not claimed. Created invoice-payments worktree/branch
from confirmed origin/main, preserving the prior worktree and unrelated work.
Updated the product handoff and next milestone status. Payments/receipts/CC/void,
quotes, comments/costs, reports and the remaining launch milestones stay unfinished.
No VM build, test, formatter, dependency resolution or rendering ran.

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

### Financial milestone accepted and merged — 2026-11-12 13:37 UTC

#37 final 4825869 passed Validate 43224532785 attempt 2. Downloaded current retry
artifact IDs 13947948379 (native), 13949201748 (device), 13949066888 (previews),
selecting by ID because attempt 1 retained duplicate artifact names. Inspected
42 native XML files / 479 cases and eight actual-device XML files / 50 cases;
zero failures/errors/skips. Both APIs produced the 16 required UI captures.
Reviewed current editor and confirmation captures on API 24/35, plus API-35
excess-precision input, balance, VOID and retained receipt screens and current
replacement/VOID/settled PDFs across the three styles. The bottom confirmation
control clears Android navigation. Actual storage case verified complete bounded
BLOB reads on API 24/35 after reproducing the old API-24 cursor failure. Opaque
storage bytes are separate from genuine PDF render/text/attachment checks.
Attempt 1 failed downloading the pinned emulator with curl HTTP/2 error 92,
before actual tests; only the failed job was retried on the same commit.

Merged exact tested head using match-head guard: main 1492081c9644e60020e06fde8b8401c5f4388759,
2026-11-12 13:37:28 UTC. Main Validate 43229082487 was queued at observation;
new internal publication remains pending. Last proven Play acceptance remains
internal.25.1 / 216642056. No VM builds/tests/formatting/rendering ran.

Owner reiterated exact money: confirmed pinned decimal 3.2.6 in Dart and native
BigInteger/BigDecimal string parsing. Excess currency precision rejects with the
draft kept; bounded input rejects rather than overflow/clamp/truncate. Exact
products round only at the documented currency boundary. Quotes remain the next
implementation milestone, using the same money contract.

### Quotes native foundation — source awaiting Actions

Schema 9 adds separate draft/revision/decision storage, issue-time Q-1000
numbering and frozen exact monetary/customer/company/style/logo snapshots.
Repository commands serialize save/issue/decision with global versions and durable
retry receipts. New API-24/35 native cases cover no financial/work side effects,
revisions/acceptance, half-cent and 1000-digit arithmetic, invalid input,
concurrent issue, late rollback, retained customer/logo/job provenance, frozen-v8
migration and failed migration rollback. The older schema fixtures stay unchanged.
Current source is not validated; UI, revision navigation, expiry display, actual
three-style PDF/private email handoff, accepted-scope mapping and invoice review
remain required before quote milestone completion. No VM build/test ran.

Quote foundation 1213cc5 is under Actions 43230233087 in draft #39.
The next source adds strict native channel commands for quote save/issue/decision
and context reads. Money and quantities must be strings; tax/revision/version
must be integers and confirmation a boolean. Native bridge cases reject numeric
money, numeric quantities, fractional tax/version and invalid confirmation types
before writes. This source still awaits Actions and does not expose a quote UI.

Quote source now includes bounded keyset pages, explicit historical revision reads,
revision-history pages, and calendar-date expiry projection labeled with native
today/timezone. Draft edits retain their issued number and prior acceptance;
expiry projections do not mutate history. Added pagination, retained historical
read, immutable number, changed-retry identity and exhausted-sequence cases.
Actions proof for this increment remains pending.

### Quote foundation native evidence and main delivery blocker

First quote storage head 1213cc5 / Validate 43230233087 passed formatting,
analysis, Flutter behavior, Android build and native tests. Downloaded artifact
13950276507: 44 XML files, 495 tests, zero failures/errors/skips. This includes
12 quote-store and four frozen-v8 migration executions across API 24/35.
Actual-device gates remain live, and the later strict bridge/paged-history head
3962104 still requires its own complete run 43231015416. Superseded queued
bridge-only run 43230575471 was cancelled after newer source queued validation;
the running first foundation run was retained for its evidence.

Main 43229082487 is terminal failure at initial API-24 payment amount lookup.
Actual failure.png/accessibility.txt show the form open but scrolled past the
amount during incoming-route semantics. Focused test-only #40 / 0c9175c waits for
the real payment form and amount before scrolling; Validate 43231269977 is live.
Play 43230903575 was skipped because main validation failed. New internal delivery
is not accepted; last proven acceptance remains internal.25.1 / 216642056.

### Quote PDF and private handoff source

First storage head 1213cc5 completed all gates in 43230233087; the later paged
bridge head remains under 43231015416. New unvalidated source adapts each frozen
issued revision to the shared three-style renderer with QUOTE/ESTIMATED TOTAL,
revision, expiry, exact item/estimated-hour lines and printed terms/notes. A
separate immutable first-write PDF cache uses bounded descriptor reads. Private
URI paths include quote/revision identity; preview/save/email/share route through
the existing native document boundary and current AR/company CC. Superseded
revision views/handoffs get a historical notice without rewriting prior PDFs.
Both global email-template guards remain mandatory at issue; default quote copy
uses estimate language and custom body expansion is retained with a QUOTE prefix.

Native source tests cover frozen adaptation, first-cache identity, exact private
provider bytes, read-only grants and retained revision attachment ownership.
Actual API-24/35 source adds all-three-style quote/revision/1000-digit PDF render,
text and private attachment checks. Copies gate now requires all three named
cases and 33 genuine PDF/PNG pairs per API (nine new quote variants), giving 52
actual cases overall. These are required pending gates, not completed evidence.
Quote UI, reviewed scope mapping and the complete milestone remain unfinished.

Quote UI adapter source now defines immutable line/draft models, frozen revision
reads, keyset pages, explicit issue/decision commands and revision-specific
preview/email/share/save calls. Money and quantities stay strings; the Dart live
total uses the pinned exact decimal task product and BigInt tax. Source tests
cover estimated hours, fractional units/half-up boundary, 1000-digit arithmetic,
invalid precision/range, immutable draft capture and exact channel transport.
Formatting/analysis/test evidence must come from the upcoming Actions run; quote
screens and reviewed scope conversion are still required.

### Quote first-page null binding corrected from Actions evidence

3962104 / 43231015416 passed formatting, analysis, Flutter behavior and Android
build, then ran 505 native cases: 503 passed, two API-24/35 executions of the new
first-page test failed. Downloaded artifact 13950813526; XML reports Android
SQLiteProgram rejects null string selection bindings. The optional cursor query
used null bindings for page one. Source now selects a fixed unfiltered first-page
query and a separate fixed keyset clause with entirely non-null bindings for later
pages. Pagination/order/expiry assertions remain unchanged. Real-device quote PDF
and new Dart adapter evidence still require the corrected current head in Actions.

### Quote Dart correction and actual-page viewer source

cb25103 / 43233491212 stopped at analysis: unnecessary typed_data import and one
missing brace block. Downloaded formatter artifact 13950784702 and applied its
API/test source patch; removed the import and wrapped the reported condition.
All formatting ran in Actions. This failed run provides no new Flutter/native
or Android PDF acceptance.

New quote document screen source previews a specific revision, displays the
estimate/historical notice, allows zoom/page navigation and private email/save/share.
The responsive toolbar scrolls independently for landscape/large text and reports
email-draft creation and save cancellation honestly. It is not yet linked from
quote navigation. Added six theme/style viewer captures and large-text handoff
checks using genuine API-35 quote-rendered PNGs in the post-device Actions gate;
no synthetic image is presented as PDF proof. New screen/test formatting and all
current gates remain pending. Quote list/editor, explicit issue/decision/revision
navigation and reviewed scope conversion still remain.

### Quote current-head validation and template preservation follow-up

ef3ddcd / 43234622742 passed analysis and the Flutter behavior gate, including
quote money/model/channel tests. Native/device/document viewer gates remain live.
Downloaded Actions formatter artifact 13950469584 and applied its two-file viewer
source correction; no VM formatter ran. Source review also corrected custom quote
subjects: default subjects use quote wording; custom subjects and bodies retain
template expansions with an explicit QUOTE prefix. Added native regression checks
that old revision email content remains unchanged. This later source still needs
its own complete Actions run. #40 / 43231269977 remains live at its actual Android
step; no successful main validation or new Play acceptance has been observed.

### Quote document native proof and draft-editor source

ef3ddcd / 43234622742 passed Android build and 509 native cases. Downloaded
artifact 13951494219: 46 XML files, zero failures/errors/skips, including quote
pagination/bridge/cache/private-provider cases. Android test compilation then
failed before emulators because CopyPdfTextApi35 was file-private. Shared that
test-only helper internally; no real quote PDF result is claimed yet. The earlier
formatter correction was already applied to source.

New editor source separates editable save from issue, with one unit-price field,
quantity/estimated hours, native currency precision, effective linked-job tax,
customer/job selection, explicit currency change with cleared prices, validity,
printed terms/notes and exact totals. It preserves field values through refresh
and captures one immutable command for unknown-reply retries. Native invalid
content is now a known pre-write command conflict so it cannot strand an editable
draft as an unknown result. UI capture validates controller values even when lazy
form fields are offscreen. Native historical precision remains frozen across
same-currency revisions; new precision and frozen-unit regressions are pending.
Two widget source cases cover unknown-reply lock/retry identity and retained
fractional input for native zero-decimal currency. These editor/native changes
still require formatting, analysis and full Actions proof. Quote list/detail,
issue/decision/revision navigation and accepted-scope conversion remain required.

### Quotes connected navigation and reviewed commands — source pending Actions

Added main-menu Quotes navigation, native status filters before keyset pagination,
coherent draft-party/currency/calendar context, new/edit draft flow, issue review,
local acceptance/decline with private notes and explicit revision confirmation,
revision history/older-page navigation and retained quote PDF access. Unknown
issue/decision controls remain mounted across committed version events; source
widget cases verify cancelled reviews write nothing and response-loss retries
keep the same captured issue intent. New native cases cover filtered older pages,
expiry and explicit decisions. Accepted-scope/job/task/invoice mapping still remains.

014f75f / 43237357264 passed analysis and ran 140 passing Flutter tests with one
failure: exact zero-decimal error text was not exposed by the generic invalid-form
summary. Controller capture now validates even after Form.validate reports invalid,
so the precise money error is returned; retain the original test assertion and
input-retention checks. Downloaded formatter artifact 13952887623 and applied its
unchanged editor/test/common-command source hunks. New API/navigation source still
requires its own formatting/analysis/full validation. No VM build/test/formatter ran.

Focused #40 run 43231269977 ended cancelled at the 45-minute job limit. Its log
stopped during API-24 startup after adb daemon creation; no instrumentation began.
Current head 658fce0 / 43239688776 bounds startup/wait/property/shutdown probes and
retains emulator logs; it is live. The quote branch incorporates the same startup
script and initial payment-form wait. Required device cases and financial assertions
are unchanged. No new main validation or Play acceptance is claimed.

### Quote issue-response regression caught a moved command card

c604021 / 43241323307 passed analysis. The zero-decimal editor case and explicit
acceptance case passed; the lost issue-reply case failed because Retry same
operation disappeared after native version publication. The issued summary inserts
new list children ahead of the issue card; the unkeyed parent card was recreated,
disposing its keyed child command state. Source now gives the issue and decision
cards stable list keys, preserving captured commands when those cards move.
Retain the full response-loss/version-event assertion; this is an app UI fix,
not a relaxed test. Applied formatter artifact 13954368054 to all four unchanged
navigation/API/test files. Full corrected-head Actions validation remains pending.

### Main payment journey synchronization failure — 2026-11-12

Main Validate 43229082487 on merged 1492081 failed its API-24 payment UI journey
at initial amount entry. Downloaded artifact 13949183965 and reviewed failure.png
and accessibility.txt: the app is on Record payment, scrolled to its bottom,
without a crash. The helper began forward scrolling while the incoming route's
amount semantics was not yet exposed, moving past the top field. Add explicit
waits for the real payment refresh action and editable amount before entry starts;
retain all exact amount, state, PDF and capture assertions. The prior PR pass does
not prove main validation or publication. Internal delivery remains blocked until
main passes. This test-only correction awaits Actions; no VM build/test ran.

### Payment-fix workflow startup timeout — 2026-11-12

#40 head 0c9175c / 43231269977 is terminal cancelled at the 45-minute job limit.
Its app build/native gate passed; no actual instrumentation started. Full log
shows the API-24 image verified, AVD created and adb daemon started at 14:00:21,
then cancellation at 14:36:27. Available artifacts contain no emulator log, so
which startup probe stalled is unproven. Source bounds wait-for-device with a
kill-after grace, every boot property call, an overall boot deadline and shutdown;
it also captures emulator diagnostics on success/failure. Financial assertions,
required cases and UI captures are unchanged. Validate the corrected script in
Actions before merging or claiming publication. No VM build/test ran.

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

### Quote document/navigation evidence and reviewed planning conversion source

f442e97 / 43242100858 passed formatting, analysis, Flutter behavior, build,
517 native and 52 actual API-24/35 cases. Downloaded native artifact 13954384676
(46 XML) and device artifact 13956368052 (eight XML): zero failures/errors/skips.
All nine real quote PDF variants per API rendered with exact text/attachment checks.
Reviewed all three genuine API-35 normal quote pages from 13956667591: descriptions,
26 units at 12.50, 1.5 estimated hours at 40.00, subtotal 385.00, tax 28.88 and
estimated total 413.88 are readable and correct. Full viewer gate failed only its
landscape Save-cancel message assertion: the older email snackbar was still
visible while the new feedback queued. Current source replaces prior feedback;
the assertion is unchanged. Full corrected-head gate and remaining visual review
are required; no completed quote milestone or release is claimed.

Native planning conversion source adds immutable quote-line/task bindings and
accepted-revision links in unreleased schema 9. Only explicit accepted revisions
can create or link scope. Fixed items copy exact quantities/unit prices into open
planning tasks; estimated-hour scope creates nonbillable checklists and a frozen
exact job rate, never sessions/completion/invoices/claims/payments. Each reviewed
group targets one compatible hourly/task job; mixed kinds/different hourly rates
require explicit compatible groups. Existing-task linking leaves source work
unchanged. Prior mappings are reused across commands/revisions, retained after
job purge and cannot resurrect deleted identities. Old revisions/job-setting
differences require explicit review; task templates contain no quote history.

Repository/channel source plus native cases cover retry, concurrency, partial
failure rollback, existing completed work, revised agreement links, hours with
1000-digit rates, retained purge ancestry and late schema-install rollback.
This new source has not passed Actions. Conversion UI, native actual-work quote
invoice preview/preparation, accepted-price/tax retention, estimate/actual difference
review and invoice provenance still remain. Ordinary/quoted billing must share
exclusive coverage; estimated hours never become worked time. All build/test and
render work remains in Actions.

### Focused main follow-up #41 carried into quote validation

Main 43242962261 is terminal failure at API-35 Accounts receivable field lookup.
Downloaded 13954879850; reviewed failure.png/accessibility.txt showing Settings
open at the top with the target below the viewport and a real vertical ScrollView.
The helper previously stopped issuing scrolls and then waited passively. #41
head d92e617 keeps active bounded reveal polling, prefers the vertical form and
waits for Billing defaults. Carried the same test-only correction into the quote
branch. All exact money/state/PDF/capture gates remain intact. Validate #41 before
merge; main/internal delivery remains unaccepted. This is not a device crash fix.

### Current quotes increment

43247145880: 47 native XML / 537 tests, zero failures/errors/skips. Android
API-35 billing original-PDF lookup failed and emulator went offline; actual
viewer stage did not run. Do not treat source or native-only checks as completion.
Current planning UI/context/bounded receipt and retry tests are implemented as
source, pending fresh Actions. Invoice-list refresh assertion is being repaired
in maintenance #41 (b0b84b8), with matching quote callback fixes here.
Actual-work invoice preparation with accepted-price/tax review and durable quote
provenance is the next dependency; physical acceptance remains separate.

Quote actual-work preparation remains open. Its detailed policy and required
evidence now live in docs/QUOTE_ACTUAL_WORK_PLAN.md, linked from the lifecycle
plan. Never infer worked hours/completion or a new invoice from planning alone.

Scope 2e10834 / 43252111565 stopped at an analyzer constructor-style lint and
formatting. Actions source patch and super-parameter correction applied; fresh
validation required. No behavior/native success inferred from this run.

Actual-work review core: read-only native source and tests added; no preparation
command/UI/provenance write yet. Exact task/session selection and accepted/current
price/tax calculations must pass fresh Actions before integration. Scope head
202ceb1 / 43252783507 passed Flutter formatting/analysis/behavior and reached
Android build; native/device outcomes remain pending.

Quote preparation/provenance: native source now includes one serialized command,
shared invoice/coverage writer, accepted/current prices/tax/party selection and
retained paginated quote links copied through replacements. Source tests cover
credit/claim conservation, purge and late-write/schema rollback. Fresh Actions,
Flutter transport/review/history and real-device/document acceptance remain open.

Large scope source review: remove repeated whole-board reads/snapshots from
new-card append while preserving all changed-peer revisions on moves. The full
2000-task replay/head-count regression remains; performance/behavior awaits Actions.

### Main settings-field reveal recovery — source awaiting Actions

Main 05ee6e7 / 43242962261 passed build/native checks and API-24 device cases,
then failed API-35 payment setup locating Accounts receivable email. Downloaded
13954879850 and reviewed failure.png/accessibility.txt: Settings is open at its
top, with a vertical ScrollView and the target below the viewport; no app crash
is shown. The old helper stopped scrolling after 35 attempts and then waited
passively for a potentially offscreen field. Keep bounded active search/scrolling
until the exact target appears, prefer the vertical form over editable scroll
nodes, and wait for Billing defaults before entry. Preserve strict field/state,
PDF/attachment and all required capture assertions. This focused test-only fix
still requires full Actions proof before merge and new internal delivery.
