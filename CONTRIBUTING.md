# Contributing

Publication and external contributions are pending owner review of
[the proposed source terms](docs/SOURCE_TERMS_DRAFT.md). No contribution license
agreement is currently in force. Do not assume an open-source license.

Read AGENTS.md and app_plan.md. Implement one milestone at a time and record
actual validation in plan_status.md and the plan evidence ledger. Use synthetic
data. Never include credentials, personal databases, location traces or local skills.

Android native code owns database writes; Flutter consumes typed snapshots and
commands. Tests for transactions, concurrent commands, migrations, time boundaries,
geofencing, CSV safety and restore integrity belong with each behavior.
