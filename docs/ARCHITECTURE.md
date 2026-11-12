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
and Kotlin BigInteger/BigDecimal perform exact arithmetic. In schema 7,
settings_money, job_money, card_money and template_money store canonical TEXT with
validation and revisions. These exact records are authoritative; old INTEGER
columns retain only checked representable compatibility values. Invoice snapshots
freeze exact line quantities/unit prices and totals; rounding occurs only once at
the defined currency boundaries. Backup/merge must carry exact monetary records by
stable entity identity. This migration remains under Actions validation.

Invoice and financial-copy metadata are read separately from cached PDFs/logos.
The 20 MB PDF and 2 MB logo bounds are checked before allocation; large BLOBs use
Android's read-only SQLite descriptor API, with exact-length/EOF checks, rather
than requiring the whole binary value to fit a CursorWindow. Small binary rows
are independently bounded. See [SQLiteStatement](https://developer.android.com/reference/android/database/sqlite/SQLiteStatement#simpleQueryForBlobFileDescriptor())
and [Android 7 window config](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-7.0.0_r1/core/res/res/values/config.xml#L1283).
The actual storage-boundary test uses opaque synthetic bytes; genuine PDF render
and email/URI evidence is tracked separately in the plan.
