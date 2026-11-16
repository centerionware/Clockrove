# Clockrove — implementation plan and handoff

Prepared 2026-11-11. This is the concise product-contract index. The detailed contract, ordered milestones, acceptance gates, and evidence are kept in the linked documents below.

## Start here

1. Read [AGENTS.md](AGENTS.md) and the project constraints in [getting-started.md](docs/plan/getting-started.md).
2. Follow the behavior and architecture rules in [product-contract.md](docs/plan/product-contract.md).
3. Implement [milestones.md](docs/plan/milestones.md) in order and verify each against [release-readiness.md](docs/plan/release-readiness.md).
4. Keep [plan_status.md](plan_status.md) current and append evidence to the [implementation evidence archive](docs/plan/evidence/).

## Product contract

Clockrove is an offline-first Flutter/Dart timekeeping and job-management app with native Android persistence and integrations. The native repository owns writes; app, widgets, notifications, and geofence events share a serialized command boundary. Preserve exact money and time values, safe backup merging, manual control precedence, privacy by default, and graceful behavior when GPS or map services fail. The full behavior contract is in [product-contract.md](docs/plan/product-contract.md).

## Ordered delivery

The required sequence is repository and CI foundation; native feasibility; persistence/domain correctness; manual tracking and widgets; location and geofencing; boards, reporting, maps, and exports; quote/task-image and remaining business features; encrypted backup and merge; whole-app usability and exhaustive E2E/screenshots; release readiness; then deferred iOS. See [milestones.md](docs/plan/milestones.md) for scope, and [release-readiness.md](docs/plan/release-readiness.md) for acceptance and rollout gates.

## Supporting documents

- [GitHub Actions, store delivery, and source references](docs/plan/actions-and-references.md)
- [Current task status](plan_status.md)
- [Oversized-file audit and safe split order](docs/plan/maintainability-audit.md)
- [Historical implementation evidence](docs/plan/evidence/)
- [Archived validation history](docs/plan/status-history/)

Maintained-file policy: keep source, tests, and documentation to 1,000 lines or fewer. The audited existing code exceptions and non-conflicting split sequence are recorded in the maintainability audit; defer splits of files actively changed by open PRs until those PRs land.
