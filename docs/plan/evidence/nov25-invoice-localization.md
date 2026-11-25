# November 25 invoice localization, rounding and backup evidence

Product contract: [app_plan.md](../../../app_plan.md)

This file preserves the detailed evidence for the work summarized in
`plan_status.md`.

- PR #100 exact head `93c5248` / Validate run
  [45609863413](https://github.com/centerionware/Clockrove/actions/runs/45609863413)
  passed formatting, analysis, 312 Flutter behavior tests, Android debug/release
  builds, 783 native tests with zero failures/errors/skips, all API 24 and API 35
  journeys, 14 actual-document viewer tests, and locked release tooling.
- Invoice and quote documents now render their labels, line copy and email text
  from the selected language catalog. The release bundle keeps every native
  language catalog instead of installing only the phone language, and cached
  PDFs are re-rendered when a requested language differs from the cached copy.
  Invoice preparation, quote issuance and replacement pass the on-screen
  language through the same committed command.
- The hourly rounding mode now rounds the aggregated duration to the nearest
  whole hour and emits one customer-facing line without actual/billed/minimum
  detail or interval rows. Exact-time invoices keep their interval breakdown,
  now at minute precision instead of milliseconds. The rule label and all 17
  Flutter/Android translations were updated with the new keys.
- Review and invoice preparation now collapse one active invoice to one
  already-invoiced entry even when it owns several claim segments. Claims held
  by voided invoices are ignored, so a replacement cannot accumulate duplicate
  history entries.
- A fresh backup still failed restore with `INVALID_REVISION_GRAPH` when an
  older settings, job, customer, session or money row kept a head whose payload
  predated later columns. Database open now rebuilds every revision-backed row
  head through the same projection used by the import validator; the round-trip
  test damages stale non-financial heads and restores twice. Owner-device
  acceptance remains open. The Cloudflare Workers Builds check remains a
  pre-existing unrelated failure; the Android Validate run is authoritative.

## November 25 backup restore repair

- PR #97 exact head `9d33866` / Validate run
  [45541877722](https://github.com/centerionware/Clockrove/actions/runs/45541877722)
  passed and merged as `1558340`. Its language sample made the Android copies
  suite run six tests; the exact-count guard was updated rather than weakening
  the test.
- PR #98 exact head `126a726` / Validate run
  [45558471514](https://github.com/centerionware/Clockrove/actions/runs/45558471514)
  passed formatting, analysis, Flutter behavior, Android debug/release builds,
  instrumentation assembly, 778 native tests with zero failures/errors/skips,
  41 actual Android tests on each of API 24 and API 35 with zero
  failures/errors/skips, invoice-viewer validation, and locked release tooling.
- The restore validator rejected retained financial rows whose revision head an
  older job purge had tombstoned or redacted, including actual costs, invoice
  coverage/claims, payment destinations, and quote records. Schema-14 cost heads
  also lacked the customer columns, and a removed cost's tombstone was rejected
  even though its row was correctly soft-deleted. The branch treats actual costs
  as financial history, snapshots schema-14 heads, repairs retained financial
  heads when the database opens, accepts matching tombstones for soft-deleted
  rows, and avoids a false deletion conflict when the archive names the current
  local head.
- The round trip now purges a job with a retained cost, damages a cost and
  invoice-coverage head like an upgraded install, reopens the database, exports,
  and restores immediately and again. Owner-device acceptance remains open. A
  fresh export from the updated build is restorable; an older archive containing
  a live retained row whose old head was tombstoned or redacted still needs a
  fresh backup after the repair has run. PR #100 extends the repair from
  financial rows to every revision-backed row after a fresh archive still hit
  the same graph error.
