# Contributing

Clockrove's source is open under the [Clockrove Source-Available License](LICENSE).
It is not a free license: reading, audit, personal noncommercial builds and
contribution forks are allowed, but publishing or redistributing the built app
or a fork, and commercial use, are not.
Contributions are accepted under section 3 of that license.

Read AGENTS.md and app_plan.md. Implement one milestone at a time and record
actual validation in plan_status.md and the plan evidence ledger. Use synthetic
data. Never include credentials, personal databases, location traces or local skills.

Android native code owns database writes; Flutter consumes typed snapshots and
commands. Tests for transactions, concurrent commands, migrations, time boundaries,
geofencing, CSV safety and restore integrity belong with each behavior.
