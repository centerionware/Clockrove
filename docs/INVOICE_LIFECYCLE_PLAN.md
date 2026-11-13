# Billing, quotes, task notes and business reporting

Owner-requested implementation queue, 2026-11-12. This is a contract for future
work, not blanket feature or launch acceptance evidence. Widgets and the invoice
foundation have merged; see app_plan.md and plan_status.md for exact validation and
delivery evidence. Follow the Actions-only working agreement and keep each remaining
slice independently reviewable.

## Ordered delivery

Owner addition to this patch: task quantities and unit pricing are high priority
within the first slice. Rename the existing price field to Unit price, without
adding a second price input. Quantity defaults to 1 for existing/new tasks and
supports positive decimal values with up to four fractional digits. Store exact
scaled integer quantities; compute quantity × unit price with wide arithmetic and
round once per task to currency precision before subtotal/tax. Reusable templates
retain quantity as an editable default. Boards/reports show the resulting task
total, and issued invoice lines freeze both quantity and unit price. Editing
either later never re-bills a task already reserved by an invoice; additional work
needs a new task or the explicit invoice correction policy.

1. Invoice integrity foundation: source coverage, safe legacy migration, global/job
   tax, quantity/unit pricing, exact money, immutable totals and invoice navigation.
   Merged in #35 as f935485 after 383 native / 44 actual Android cases passed.
2. Payment history, partial/full payment, balance copies, receipts, accounting CC,
   and unpaid/partially-paid void-and-replace.
3. Quotes and their reviewed connection to actual jobs and invoices.
4. Invoice-only hourly rounding/minimum-hours policies and task-rate minimum fees.
5. Kanban comments, labor/supplies classification and actual expense records.
6. Main-menu business Reports icon, financial summaries and exports using the
   validated invoice/payment/cost data.

Carry the owner-requested payment/receipt/CC work as the immediate next iteration
after the merged invoice foundation. These dependent slices are one business roadmap; scaffolding or
mocked PDF/email/storage behavior does not complete a slice.

## One clear invoice journey

A job report links to its invoices; a main-menu Invoices entry provides the same
records across jobs. List number, customer/job, date, total, outstanding balance
and status. Invoice detail shows the original document, payment history and
replacement links, with primary actions Record payment and Send latest copy.
Mark paid is a shortcut that proposes the remaining balance for confirmation.

Keep financial records accessible when a job is archived. Job deletion must not
silently erase issued documents, payments or billing coverage; explain retained
financial history in the deletion flow and preserve stable source/tombstone IDs.

| State | Meaning | Available correction |
| --- | --- | --- |
| Unpaid | No effective payment allocated | Record payment; void; void and replace |
| Partially paid | Payment greater than zero and below total | Record remaining/partial payment; void and replace |
| Paid | Balance is zero | Receipt/resend/history; no void |
| Voided | Explicitly cancelled original, retained in history | Open replacement/history; cannot accept new payment |

Derive payment status from the ledger, never from an unchecked status toggle or
opening an email draft. Handle a zero-total invoice explicitly as nothing due.
Do not present a voided invoice as still collectible.

## Work is billed once

Invoice preparation atomically reserves the exact task IDs and half-open UTC
portions of hourly work it bills. Previously invoiced work is excluded even when
the original invoice is unpaid. Date/timezone changes, repeated selections,
concurrent commands, retries, changed prices and edited/deleted work must not
create another charge for the same covered work. Task templates copied into new
work have new identities; do not deduplicate unrelated work by matching titles.

Show exclusions and their existing invoice links. A partly overlapping time
selection bills only its uncovered portions. No billable remainder means no new
invoice and no invoice-number consumption. Prepared invoices retain coverage even
if PDF rendering/email handoff fails: retry the same document instead of preparing
another invoice. Receipts and balance copies share its number and coverage.

Only an explicit audited void/replacement transaction may release or transfer
coverage. Payment, job archiving, resending, or hiding an invoice never releases it.
Migrate existing invoice coverage conservatively. Older snapshots without stable
source identity require review/reconciliation; never infer task identity from
amount/title or silently allow ambiguous work to be billed again.

## Invoice-only rounding and minimum-charge policies

These settings affect only newly generated invoices. Keep actual session bounds,
durations, location history, reports of actual hours and time exports unchanged.
Quotes remain unrounded and have no minimum charge unless the owner separately
requests that behavior.

Main Settings exposes an hourly billing mode, a minimum billable-hours value and
a minimum task-invoice fee. Each has a global default and an independent per-job
choice to inherit the default or override it. An explicit zero/disabled override
suppresses a nonzero global default for that job:

- **Hourly billing mode**: default to **Minimum hours, then exact time**. Bill at
  least the configured minimum, then charge any additional work at its exact
  recorded duration; with a 1-hour minimum, 5 minutes bills as 1 hour and 1 hour
  1 minute bills as 1 hour 1 minute. The alternative **Round total time up to the
  next hour** bills 5 minutes as 1 hour and 1 hour 1 minute as 2 hours. Recommend
  aggregating exact, selected, finalized and still-unbilled seconds per job and
  effective rate on each invoice, so splitting work into separate sessions does
  not cause repeated round-ups. For exact mode use
  `max(minimumHours, exactSeconds / 3600)`; for round-up mode use
  `max(minimumHours, ceil(exactSeconds / 3600))`. Do not alter source intervals.
- **Minimum billable hours**: 0 disables the minimum. Parse/store values as
  bounded canonical decimal strings and use exact decimal/integer arithmetic.
  In exact mode any duration above the minimum stays exact to its recorded
  second; only the final hourly money amount uses the established currency-boundary
  rounding rule.
- **Minimum task-invoice fee**: 0 disables the minimum. For a task-rate invoice
  with at least one billable task line, if the exact task subtotal is below the
  configured amount, add a visible minimum-fee adjustment equal to the difference.
  Never create an invoice or fee when there is no billable task work.
  Do not invent a task, change unit prices or claim new source work. Tax applies
  according to the invoice's explicit job/global tax snapshot. Store the global
  amount in the configured default currency; if a job uses another currency,
  require an explicit job-currency override instead of implying an exchange rate.

Invoice review and PDFs must show exact worked time separately from rounded/minimum
billed hours, and show any minimum-fee adjustment as its own labeled line. Keep the
original covered sessions/tasks exclusive across invoices; a rounding increment or
minimum fee is not a new time interval or task source. Freeze the effective policy,
inputs and resulting invoice lines/totals when issuing; resends, balance copies and
receipts reuse that issued snapshot. A void/replacement preview shows the inherited
or overridden policy, recalculated billable hours/fee and paid credit before
confirmation, and retains the original voided invoice unchanged. Open product
decision before coding: confirm the recommended per-job/per-rate/per-invoice scope
for hourly rounding/minimums and per-job/per-invoice scope for task minimum fee.

Legacy review opens the original PDF and shows the job's current tasks and existing
invoice reservations, without automatically selecting tasks by title or price.
The user can link a task even if its current completion state changed; linking
must not alter completion, price or timekeeping. Covered time is entered as exact
bounds with an IANA timezone and explicit repeated-time choice; invalid/gap times
are rejected. These manual reservations may cover work whose source entry was
removed. Existing reservations cannot be released or reassigned by reconciliation.

Require a review note and confirmation against the original document. An empty
mapping requires a separate explicit acknowledgment that no source records can
be linked and a reason, retained in audit history. Uncertainty stays unresolved
until the user reviews it; never choose this acknowledgment automatically. Commit
source claims, review state and ancestry atomically with a replayable command.
Do not change the original invoice/PDF/number/total/tax, fabricate work, record a
payment or allocate a new number. Reconciliation is separate from void/replacement.

## Tax percentages and exact totals

Main Settings has a default tax percentage, initially 0%. Job Settings has an
inherit-default choice or explicit override; an explicit 0% overrides a nonzero
default. A value of 7.5 means 7.5%, not 0.075%. Apply it to the entire subtotal as
requested; do not infer location-specific rates or item exemptions.

Parse decimal percentages exactly in Flutter and Kotlin. Support 0–100% with up
to four fractional percentage digits, using integer units of 0.0001 percentage
point. For 7.5%, rateUnits=75000. With integer currency minor units:

    tax = roundHalfUp(subtotal * rateUnits / 1000000)
    total = subtotal + tax

Round tax once on the invoice subtotal, use checked/big-integer arithmetic and
preserve currency precision. For a subtotal of 100.00 at 7.5%, tax is 7.50 and total
107.50. Reject invalid, negative, excessive-precision, nonfinite and out-of-range
input with field errors and preserved drafts; native validation is authoritative.

Freeze subtotal, effective rate, tax, grand total and currency in each issued
invoice/quote. Settings changes never retroactively change prepared documents or
existing balances. Legacy invoices retain their recorded totals; do not invent
historic tax or apply today's default. A replacement previews its own explicit
rate/amount. Quote-to-invoice review preserves or explicitly revises agreed tax.
PDFs/balance copies/receipts show subtotal, tax rate/amount, total, paid and due.

Collected tax is tracked separately from tax-exclusive sales/revenue and is not
included in profit/margin calculations. Track billed tax and collected tax
separately. Partial-payment allocations between net sales/tax must be exact,
deterministic and conserved to the last minor unit. Transfers to replacement
invoices are not a second cash receipt; retain the allocation/adjustment trail.

## Payments and updated copies

Record a payment amount in the invoice's frozen currency, received date/timezone,
and optional method/reference/note. Default date to today. Allow several partial
payments and a final payment. Show original total, each payment, total paid and
remaining balance; currency changes in main Settings must not reinterpret them.

Quantity × unit price can produce an invoice total larger than a signed 64-bit
minor-unit integer. Keep payment, allocation, credit and remaining-balance math in
arbitrary-precision integers too. Transport/store these amounts as canonical
minor-unit decimal strings with native validation and the document's frozen
currency precision; never narrow an issued total to SQLite INTEGER, Long, double
or a JavaScript number. Bound user input to supported document amounts and reject
malformed values without changing the ledger. Include large-total partial-payment,
rounding, replacement-credit and tax-allocation conservation regressions.

Use native serialized/versioned commands, durable IDs and idempotent receipts.
Prevent duplicate recording and stale/concurrent overpayment. For the first
version, payment cannot exceed the remaining balance. Unknown responses retain
one immutable retry intent; stale forms retain the draft and request review.
Correct mistakes with an explicit audited reversal/correction, not silent deletion.
A correction updates the current allocation even after invoice replacement.

Send latest copy generates an immutable statement of that invoice's current
balance: original number and line items, original total, payments/credits, amount
paid, balance due and UNPAID/PARTIALLY PAID/PAID marking. Keep the original invoice
available unchanged. A receipt records the payment/document snapshot at that time;
a fully settled copy is prominently PAID with zero due. Reopening/resending any
copy neither records another payment nor allocates another invoice number.
Keep old receipts as historical records when later corrections occur.

### Native payment arithmetic and event allocation

The next implementation uses canonical minor-unit strings up to 4096 digits for
issued totals/payments, independent of the 1024-digit source-price limit. A task's
quantity and a multi-line subtotal can grow beyond its source price. This is a
bounded-text policy, not fixed-width arithmetic. Plain major-unit invoice amounts
are parsed exactly against their frozen currency digits (0–4); excess fractional
digits, exponents, signs, grouping, leading zeros and malformed input are rejected.
A zero-total invoice has an explicit Nothing due state and cannot accept payment.

For known tax T, grand total G and cumulative effective paid P, allocate collected
tax as roundHalfUp(P × T / G), with zero when G is zero. Each payment or reversal
books the difference between allocations before and after that event. Net-sales
change is cash change minus tax change. Consequently splitting a payment cannot
lose tax cents and full settlement collects exactly the frozen tax. Unknown legacy
tax remains unknown. Do not independently round tax on every payment.

A replacement does not create cash. Recompute cumulative tax against the new
frozen total/tax and book the difference as a dated accounting reclassification,
with an equal opposite net-sales change. Preserve prior payment receipt dates and
historical allocation events; never rewrite earlier reports using today's ratio.
A later reversal applies to the current allocation destination and books its
current cumulative tax difference. Retain the original payment ID through chains.

Payment persistence is being implemented as schema 8 with immutable payment/reversal/transfer and
allocation-event identities, original invoice ownership, current destination, and
revision ancestry. Financial rows reference retained invoice identities rather
than mutable job rows; job purging must retain their revision payloads too.
Commands must atomically write payment, allocation, event, receipt intent and
version outcome. Unknown-response retries reuse all IDs and the unchanged draft.
Install tables without decoding every historical invoice payload; validate a
frozen document when it is actually opened or changed. This section and the
PaymentMath source/tests do not complete the user-facing payment milestone.
The native ledger source currently prepares payments/reversals/ordered allocation
events and retained financial revisions; transfer/copy/UI behavior remains pending.
Event order uses command version plus ordinal, not wall-clock timestamp or random
ID. Received/effective dates are stored separately from recorded dates. A backdated
entry never rewrites previously stored allocation events; dated adjustment rows
must explain corrections in later reports.

For replacements, v7 invoice lines lack stable source IDs even though coverage
claims do have IDs. Never infer a line-to-task association from its title, price or
array order. Keep the full old claim bundle by default; partial source release
requires an explicit reviewed mapping. New issued lines may carry stable IDs and
source links, while old/deleted-job documents retain conservative coverage.

## Void and replace without losing money

Fully paid invoices cannot be voided. Unpaid invoices may be explicitly voided
with confirmation, releasing their active billing reservations transactionally.
For partially paid invoices, require Void & replace so payments are not stranded.

Void & replace opens a draft prefilled with the old lines and all source links.
The user reviews description/price/line changes and sees credited payments and
new balance before committing. Keep the original active/reserved until commit;
Back/cancel changes nothing. In one native transaction:

1. Recheck eligibility, current version, source coverage and replacement totals.
2. Create a new numbered immutable invoice and mark/link the original VOID.
3. Transfer retained source reservations; claim added unbilled work and explicitly
   release removed lines with history. Reject claims owned by another active invoice.
4. Reallocate the original effective paid amount to the replacement without
   creating another cash payment. Preserve original payment IDs/dates and a
   traceable credit-transfer history.

The original remains visible with its replacement link and no collectible balance.
The replacement starts partially paid when credited amount is below its total,
or paid if it exactly equals that total. Initially reject a replacement total below
existing credit with a clear message; never discard credit, invent a refund, or
silently create a negative balance. A future explicit refund/customer-credit policy
would be separate work. Concurrent/double replacement must produce one outcome.

Example: INV-1000 totals 1000.00, paid 300.00. Replacement INV-1001 totals 1200.00:
original is VOID, replacement credits 300.00 and owes 900.00. Cash received remains
300.00, not 600.00. No source line can be charged by both active invoices. Subsequent
replacement/reversal chains retain this identity and conservation.

## Email and accounting copies

Main Settings adds an optional accounts-receivable email. At email-draft creation,
CC it when set, otherwise CC the company primary email. Apply to invoices,
receipts, balance/replacement copies and quotes, including old prepared documents.
Do not CC both primary and receivables addresses by default. Deduplicate recipients;
if both are empty, show that no accounting copy address is configured.

Keep the customer To address, document-kind-appropriate subject/body, and PDF
attachment populated through the default email app with private URI grants.
The user presses Send. Draft creation is neither delivery evidence nor payment.
Subject/body must distinguish original total from outstanding balance. Preserve
both-template validation and custom templates; introduce clear additional tokens
only as needed, without silently changing the existing amount token's meaning.
Validate the new address in Flutter/native settings; invalid templates/addresses
must show actionable errors, retain drafts and never crash.

## Quotes

Quotes reuse customers, company/contact/logo, three PDF styles, header/footer,
privacy-safe document sharing and the accounting CC rule. Clearly mark them QUOTE.
Use an independent Q-1000 sequence; quote creation/revisions never consume invoice
numbers, record payments or reserve billable work.

Draft quotes contain customer, optional linked job, descriptions, fixed-price
items and/or estimated hours/rates, explicit currency, subtotal/tax/total, optional
valid-until date and terms/notes. Use exact quantities/money, not binary floats.
Allocate the quote number when first issued. Issued revisions are immutable;
editing creates a numbered revision under the same quote number and preserves
prior copies. Acceptance/decline is an explicit local record against a specific
revision; opening an email or exporting the PDF never proves customer acceptance.
Expiry is a clearly labeled calendar-date state, not deletion or automatic billing.

An accepted quote can prefill a reviewed job/task scope and agreed prices. Never
create worked hours, clock sessions or task completion from an estimate. For the
first version, Prepare invoice from quote reviews actual eligible billable tasks
or work through the same invoice coverage boundary; incomplete/unperformed work
is not silently converted to completed work. Preserve quote/line references on
mapped tasks and invoices, exclude already-billed work, show differences between
estimate and actuals, and retain accepted price/tax snapshots unless explicitly
revised. Repeating conversion must reuse the existing mapping rather than create
duplicate tasks or invoices. Direct deposits/advance billing would need an explicit
additional coverage/credit policy before being introduced.

Quote navigation supports draft/issued/accepted/declined/expired filters, revision
history and linked job/invoices. Issued financial documents remain available when
jobs are archived; deletion must preserve clear provenance and retained history.

The concrete remaining conversion policy and acceptance matrix are in
[QUOTE_ACTUAL_WORK_PLAN.md](QUOTE_ACTUAL_WORK_PLAN.md). It specifies explicit
actual-source selection, accepted/current price/tax/party review, shared claims,
retained provenance and correction-chain behavior; these remain implementation
and validation requirements.

## Kanban comments, supplies and actual costs

Add chronological comments to task cards, with created/edited timestamps and
plain-text input. Support visible edit/delete history and native versioned/retry
semantics. This is a local app: do not invent team identities/accounts. Comments
stay private to task detail and are not automatically inserted into customer PDFs.
Archived jobs retain readable comments; templates copy task definition/category,
not old conversation, incurred costs, payments or completion history.

Keep a simple Supplies toggle on task entry/edit; off means Labor for new tasks.
Persist a charge classification separately from customer price and incurred cost.
Historical/unclassified financial lines need an explicit review bucket rather than
silently asserting every old charge was labor. Retain issued line classification
and audited accounting reclassification without rewriting the original PDF.

A supplies charge is customer revenue, not automatically an expense of the same
amount. Record actual costs explicitly with amount/currency, date, category,
optional job/task, vendor/reference and note. Task supplies classification can
prefill a cost form, but must not automatically book income/cost or duplicate a
purchase when a task/template is copied. Keep unknown cost distinct from zero.
Support supplies/material purchases, subcontract labor, other direct job costs
and overhead. Own tracked hours remain time, not an invented wage expense.

## Business reports from the main menu

Add a visible Reports icon/entry to the main menu. It opens work reports and a
business/tax-preparation view with date range, clearly labeled timezone, optional
job/customer filters, currency grouping, summary cards and drilldown rows.

| Figure | Meaning |
| --- | --- |
| Invoiced sales | Tax-exclusive posted invoice amounts, with void/replacement adjustments; quotes are estimates, not revenue |
| Cash received | Unique real payment records minus corrections, by received date; credit transfers are not new cash |
| Tax billed/collected | Separate tax components and their dates, never profit |
| Outstanding | Current/as-of unpaid balances, excluding voided predecessors and transferred credits |
| Labor / supplies / unclassified charges | Customer-charge classification, kept distinct from cost categories |
| Recorded direct costs / overhead | Explicit cost records; unknown costs are not assumed zero |
| Estimated gross profit | Clearly labeled revenue basis less recorded direct costs |
| Estimated net profit | Same basis less direct costs and overhead |
| Margin | Profit divided by tax-exclusive revenue; undefined/zero revenue shown honestly |
| Cash surplus | Cash received less recorded cash costs, distinct from invoiced profit |

Distinguish invoice/issue-date views from cash/payment-date views and explain
recognition dates. Define exact integer allocation/rounding for partial payments,
tax and replacement adjustments before implementation. A historical/as-of report
must respect event dates, not rewrite prior cash receipts as new revenue on a
replacement date. Reconcile drilldowns to displayed totals, payment IDs and active
invoice coverage. Do not add different currencies together. Show cost/data
completeness and unclassified amounts beside profit estimates.

Export useful CSV/PDF summaries and supporting transaction rows with existing
CSV formula safety and private sharing. Tax-preparation reports organize local
records; tax collected, deductibility, inventory/COGS and filing treatment depend
on jurisdiction and accounting policy, which the app must not guess. These are
not automatically complete tax returns. Income and expense/supporting-document
separation is also reflected in the IRS recordkeeping guidance (a US reference,
not an assumption about this owner's jurisdiction):
https://www.irs.gov/businesses/small-businesses-self-employed/what-kind-of-records-should-i-keep

## Validation and recovery contract

All writes remain native SQLite serialized transactions with global versions,
durable command outcomes, migrations, revision history and safe error messages.
All builds/tests/rendering run in GitHub Actions, with pinned tools/dependencies.

Require tests for overlapping/partial interval claims, task identity, legacy
ambiguity, concurrent invoice/payment/replacement commands, response loss,
payment conservation, reversal/transfer chains, stale drafts, exact percentage
parsing/rounding and overflow, inherited/explicit-zero tax, immutable documents,
CC precedence/fallback, comments and template-copy isolation, cost completeness,
report dates/currencies and CSV safety. Billing-policy tests must prove that with
a 1-hour minimum exact mode bills 5 minutes as 1 hour and 1 hour 1 minute as
1 hour 1 minute; round-up mode bills 5 minutes as 1 hour and 1 hour 1 minute as
2 hours; larger minimums raise but never lower billable time; inherited and
per-job overrides (including explicit disable); task minimum-fee adjustment and
no-work behavior; exact actual-time preservation; tax application; immutable
policy snapshots; and source-claim exclusivity. Actual
Android PDFs in all three styles
must show correct tax/paid/due/VOID/QUOTE states and paginate cleanly. Verify native
email To/CC/body/attachment intents; physical mail-app behavior is separate.

Future encrypted backup/merge must include invoice claims, payments/allocations,
replacements, quotes/revisions, tax snapshots, comments and expenses by stable
identity. Import may not duplicate payment/credit or release already-billed work;
ambiguous histories need previewed conflicts and transactional resolution. No
email sending, timers or automation are triggered by restore.

## Current copy implementation checkpoint (not milestone acceptance)

Baseline 811fb7d / 43193679201 passed 414 native cases plus Flutter/analysis/build;
its device stage was held by the separate widget binding issue. Native copy source
now atomically retains a receipt intent per payment and correction intent per
reversal. Balance copies are explicit versioned commands. Stable kind-prefixed
identities prevent collisions, and retries reuse the same intent. Metadata is
immutable; derived PDF caching does not mutate the ledger. Original documents,
numbers, coverage and earlier receipts remain unchanged.

A copy freezes its command-version boundary and exact paid/due minor strings.
Receipt/correction documents refer to one immutable event; balance documents list
events through that boundary. Received/effective labels and offsets are captured
at native write time, so later timezone changes do not rewrite a printed copy.
Internal payment/correction notes stay off customer PDFs. Copies retain financial
revision history on job purge and use separate read-only private content URIs.
All three layouts label original invoice totals separately from paid and due.

Rendering a balance history is bounded to 5000 events and 200 pages; exceeding a
limit fails explicitly without truncation or deleting payments. Individual saved
receipts remain available. Rendering and email failures never undo a committed
payment; retries open the same retained document. Email expansions over 100 KB are
rejected before activity handoff and unexpected handoff failures are caught; Save
PDF/Share remain available. Actual Android PDFs/attachments and wide receipt money
need the new Actions/visual evidence. UI, bridge and void/replacement remain open.

### UI integration checkpoint

c21ef87 / 43201426802 passed 434 native / 46 actual Android cases, including
all-style native copies and wide receipt text. Visual review found short-copy
footer overflow; revised spacing and a one-page short-fixture gate are prepared.
The invoice detail/entry/correction UI and strict bridge are now source-complete
for testing, with exact list balances, saved-copy viewing/email, separate source
review and private notes. Mark paid proposes remaining due and asks for payment
confirmation. Confirmation opens the immutable receipt; Send latest copy prepares
one versioned balance document and opens its email draft. Neither action claims
customer delivery. Unknown operations keep immutable IDs and block Back; keyed
submit controls survive live balance changes. UI/bridge/replay and an independent
actual app/payment/purge journey still need new Actions proof. Native ledger/copy
acceptance does not implement void-and-replace or complete this milestone.

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
