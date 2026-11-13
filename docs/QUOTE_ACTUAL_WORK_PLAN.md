# Accepted quotes and actual-work invoicing

Implementation contract for the remaining part of the quotes milestone. Planning
scope is separate from billing; source in PR #39 is not completion evidence.
Follow the exact-money and serialized native writer rules in app_plan.md.

## Entry and review

From an accepted revision, show its mapped jobs and related invoices, including
historical, archived and removed-job records. A mixed estimate keeps every group
visible: fixed items use task-rate jobs and each distinct hourly rate uses an
hourly job. Historical accepted revisions need an explicit acknowledgement.

Prepare invoice from quote starts with a job, date range and timezone. Read one
native snapshot containing the accepted revision, mappings, current job/customer,
completed billable tasks, finalized intervals and invoice-coverage exclusions.
Show why active/provisional, incomplete, already billed or removed work is absent.
Unreconciled legacy coverage blocks preparation and links to the existing review.
No completed task or interval is created by this flow.

For task-rate work, only completed, billable, unclaimed tasks mapped to lines in
this accepted revision are selectable. A mapping from an older revision must first
be explicitly linked to this revision through the planning review. The actual
quantity comes from the completed task, not its estimated quantity. Additional
unmapped work needs an explicit quote revision/mapping or the ordinary job invoice
flow; never silently include it in the accepted agreement.

Hourly work has no task timers. Select real unclaimed job intervals and explicitly
confirm their allocation to this quote's compatible hourly-rate group. Display
session/date/start/end/timezone and exact duration; a checklist estimate never
establishes worked time. Different rate groups cannot share a job mapping.

## Price, tax and customer decisions

Default to the accepted unit/hourly price and accepted tax. Compare them with the
current job/task values and show estimated quantity/time beside actual selected
quantity/time and resulting subtotal/tax/total. Existing work/settings stay intact.
Allow an explicit choice to use current prices or current job tax with a private
change note; make the chosen values visible in the final confirmation. Missing
current prices disable that choice, without blocking accepted-price billing.
Never silently choose today's defaults over the accepted agreement.

Show accepted and current customer/contact information when they differ. Default
to the accepted party snapshot; changing the billed party requires an explicit
reviewed choice. Email preparation uses that frozen addressee, with current AR
email or company primary email as CC under the existing accounting-copy rule.
Currency/precision mismatches block conversion, rather than reinterpret an amount.
An archived job can be invoiced for actual historical work; deleted jobs retain
existing documents but cannot fabricate missing source work.

Each line uses the existing exact half-up minor-unit boundary, hourly amounts
round once over selected duration, and invoice tax rounds once over subtotal.
Splitting work among invoices can change tax pennies versus a whole-job estimate;
show the selected actual invoice total and explain that comparison. Never adjust
prices, quantities, duration or payments to force agreement with an estimate.

## Native preparation and provenance

One versioned command captures quote/revision, job/date range/timezone, selected
stable task IDs or exact available session slices, explicit price/tax/party
choices, review note and confirmation flags. Capture IDs once and keep the same
command on a lost reply. Before any write, re-read/validate acceptance, mappings,
source eligibility and selections in the serialized transaction. Reject stale or
changed selections with an actionable conflict and keep the form draft.

Use the same InvoiceCoverageStore reservations as ordinary invoices. Selected
sources must match available native source identities exactly; the client cannot
invent amounts or time. Reserve sources, freeze invoice data/provenance, allocate
one number and record revisions atomically. A trigger or later failure rolls back
all of them. Repeating the same operation returns the prior outcome; a new
operation cannot bill already held sources, regardless of payment state.

Freeze a structured original-issue provenance record with quote ID/number/revision,
line IDs, mapping IDs, accepted prices/quantities/tax/customer, selected actual
source identities, chosen price/tax/party policy and private review note. Keep
private notes out of customer PDF/email. Customer-facing lines show the quote
number/revision and enough actual quantity/rate information to reconcile charges.
Use a retained indexed invoice-to-quote relationship for history without parsing
all documents. Preserve it across job deletion and invoice correction chains.

A replacement retains the original quote origin and predecessor relationship,
while its corrected lines, tax, current claims and payment credit remain governed
by the existing replacement policy. Label original-issue provenance as historical;
do not present its old amounts as the replacement's current charges. Voiding an
unpaid invoice releases only the explicitly released sources; partial-payment
credit transfers exactly once under the existing atomic replacement transaction.

## Required evidence before marking the quote milestone complete

- Native/API tests on API 24/35: accepted versus unaccepted/historical revisions;
  mixed groups; exact accepted/current pricing and tax; changed customer; missing
  current price; zero/three-decimal currencies; wide amounts and fractional lines.
- Completed-task and real-finalized-time billing; incomplete/active/provisional
  exclusions; selected actual quantities/durations; no estimate-to-work mutation.
- Duplicate/concurrent/replayed/stale preparation; cross-flow exclusions; paid
  work exclusions; immutable claims/provenance; late-failure complete rollback.
- Original, partial-payment, paid, void/replacement, archived/deleted-job history;
  original-issue provenance retained and no double credit/cash/source claim.
- Flutter review/cancel/error/draft preservation and unknown-response tests, plus
  actual Android quote issue/accept/plan/work/invoice journeys with real storage.
- Genuine three-style invoice/quote PDFs, pagination, actual text, preview and
  private attachment byte/recipient identity; reviewed light/dark/large-text views.
- Successful fresh full Actions on the exact head before merge. Main validation
  and Play acceptance are separate from source, device simulation and owner
  physical/email acceptance. No VM builds, tests, formatting or PDF generation.
