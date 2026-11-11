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
