# Architecture

Flutter screens use Riverpod presentation state over typed platform contracts.
Kotlin owns a single SQLite database and serializes all commands from the app,
widgets, notifications, services and geofences. Compound writes are transactions.
Committed versions trigger snapshot re-queries; reconnect always reads current state.

There is at most one open session globally. Final intervals use half-open UTC
boundaries and cannot overlap. Reports use current-device timezone day boundaries.
Manual commands take precedence over automation. Idle location readings never
become history. Revisions aid auditing but are not tamper-proof.

No server, accounts, analytics, task timers or subscriptions belong in launch scope.
See app_plan.md for contracts, recovery semantics and the full acceptance matrix.

Money precision: decimal strings cross Flutter/native channels; Dart decimal 3.2.6
and Kotlin BigInteger/BigDecimal perform exact arithmetic. In unreleased schema 7,
settings_money, job_money, card_money and template_money store canonical TEXT with
validation and revisions. These exact records are authoritative; old INTEGER
columns retain only checked representable compatibility values. Invoice snapshots
freeze exact line quantities/unit prices and totals; rounding occurs only once at
the defined currency boundaries. Backup/merge must carry exact monetary records by
stable entity identity. This migration remains under Actions validation.
