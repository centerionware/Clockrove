# Exact money implementation checkpoint

Owner priority, 2026-11-12; part of the current invoice milestone before merge.
Read app_plan.md and plan_status.md. All dependency resolution, builds, tests and
formatting run in GitHub Actions only.

Completed source: exact money is merged in #35 / f935485. Final head eb74c74
passed full Actions 43182854787 (383 native / 44 actual Android cases, no failures,
errors or skips). Main validation and new internal delivery remain separate gates.
The implementation checklist below records the completed money design and the
ongoing requirements for later payments, credits, quotes and reports.

1. Pin Dart decimal 3.2.6, resolve/commit its lockfile via the existing owner-only
   Validate prepare_dependencies dispatch, and keep all money construction from
   strings. Decimal products remain exact; HALF_UP is explicit once at each task
   line, hourly job total and subtotal tax boundary. Never round user input.
2. Add canonical TEXT money persistence for business default hourly rate, job rate,
   task unit price and template unit price, linked to the same entity IDs. Migrate
   released integers by decimal-string conversion in one transaction. Preserve the
   released v5/v6 DDL fixture; schema 7 is still unreleased. Keep old columns only
   as a conservative legacy representation; wide values must never enter INTEGER
   affinity and silently become REAL. Reads prefer the canonical exact record.
3. Use BigInteger money values throughout Kotlin business/board/report contracts;
   java.math.BigDecimal handles exact decimal strings/formatting. Convert no money
   to Long, Double or Float during arithmetic or channel/revision/backup transport.
   A narrowly checked legacy-column compatibility write may use Long only when
   representable; otherwise retain NULL there and the full canonical TEXT value.
4. Preserve command fingerprints for released small money values explicitly; do
   not assume org.json wraps BigInteger like Long. Include wide values in immutable
   fingerprints without colliding with blank/unset prices. Retain exact-money
   revisions and template values after source job purge. Restore/backup must use
   these exact values by stable identity, never the lossy legacy column.
5. Remove the Dart/native Long input bounds only after the complete native path is
   ready. Keep explicit currency precision and bounded text-size validation; reject
   invalid/excess precision without changing saved values or losing the draft.
6. Verify real-v6 migration, small-value receipt replay, wide-value receipt identity,
   database type guards, rollback, template reuse after purge, no double billing,
   large hourly/task totals, subtotal/tax conservation, fractional-cent ties and
   adjacent values, and exact frozen PDF/email amounts. Existing issued documents
   remain byte-for-byte unchanged. Actual tax/quantity/invoice journey must pass
   on both APIs after money integration. Then merge and verify main/internal delivery.

Payments/credits/void replacements and reports must use the same precision and
conserve minor units; see INVOICE_LIFECYCLE_PLAN.md. A recurring decimal division
cannot always have a finite decimal representation: hourly time uses an exact
integer/rational numerator until the documented currency rounding boundary.

Verified checkpoint: native business/board/report fields use BigInteger; canonical
TEXT records migrate/save settings, job rates, task prices and template prices.
Wide legacy columns are NULL and exact rows are authoritative. Receipt identity,
rollback and template reuse are verified. Dart accepts up to 1024 minor-unit input
digits as a text-size guard; arithmetic totals can grow beyond that input length.
The decimal/rational lockfile was generated in Actions. Both actual Android APIs
verified a wide editor save/reopen, tax, source exclusion and retained PDF; all three
PDF styles retain full 1000-digit row/subtotal/total values through pagination.
Subsequent payment/credit/backup work must preserve this precision; it is not yet
implemented or validated by these completed foundation tests.
