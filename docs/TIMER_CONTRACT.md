# Prototype timer boundary v1

`TimerApi` is the Dart-facing typed adapter; `TimerHostApi` is its Kotlin codec
adapter on `clockrove/timer/v1`. Raw transport maps stay inside the adapters.
The channel uses a serial background task queue; the process-wide native repository
serializes transactions shared with the widget. This is an explicit typed adapter,
not raw SQL or a second Dart timer/database.

- `snapshot`: version, eligible job IDs/names and optional running session.
- `createJob(name)`: prototype creation and version increment; full defaults,
  revisions and board creation remain milestone 3 work.
- `execute(id, version, action, jobId)`: START, STOP or SWITCH. App origin is
  assigned natively, never accepted from Flutter. Native code captures UTC time,
  IANA zone and offset on first execution. Retries retain identical request fields
  and reuse persisted outcomes. Reused IDs with different intents are rejected.
- Outcomes carry original committed version or a conflict code. Storage and
  argument failures use sanitized platform errors. Unknown methods are rejected.
- A reconnect/resume must query a fresh snapshot. The committed-version event stream replays the current version at subscription
  and notifies after durable changes. Callers must not invent state from a
  presumed success.

The adapter refreshes widgets after completed calls and provides version
invalidation hints on clockrove/timer/versions/v1. Widgets also refresh after
their own command callbacks. Other native event sources must join this same
boundary as implemented. Foreground location and independent notification clock-out
now use this repository. Geofence registration and runtime automation remain pending.

The Dart contract tests use transport mocks and prove decoding/retry semantics
only. Native Robolectric tests exercise repository transactions separately.
Neither is evidence of a real device, widget receiver delivery or Flutter-engine
lifecycle integration. These checks must run on GitHub Actions, never the VM.
