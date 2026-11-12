# Billing, quotes, task notes and business reporting

Owner-requested implementation queue, 2026-11-12. This is a contract for future
work, not implemented-feature or acceptance evidence. Finish and merge the current
Timer/Tasks/Combined widget task first. Follow app_plan.md and the Actions-only
working agreement. Keep each slice independently reviewable.

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
   tax percentage, immutable totals and invoice navigation.
2. Payment history, partial/full payment, balance copies, receipts, accounting CC,
   and unpaid/partially-paid void-and-replace.
3. Quotes and their reviewed connection to actual jobs and invoices.
4. Kanban comments, labor/supplies classification and actual expense records.
5. Main-menu business Reports icon, financial summaries and exports using the
   validated invoice/payment/cost data.

Carry the owner-requested payment/receipt/CC work as the immediate next iteration
after widgets. These dependent slices are one business roadmap; scaffolding or
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
report dates/currencies and CSV safety. Actual Android PDFs in all three styles
must show correct tax/paid/due/VOID/QUOTE states and paginate cleanly. Verify native
email To/CC/body/attachment intents; physical mail-app behavior is separate.

Future encrypted backup/merge must include invoice claims, payments/allocations,
replacements, quotes/revisions, tax snapshots, comments and expenses by stable
identity. Import may not duplicate payment/credit or release already-billed work;
ambiguous histories need previewed conflicts and transactional resolution. No
email sending, timers or automation are triggered by restore.
