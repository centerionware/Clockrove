# Clockrove development instructions

Read `app_plan.md` before implementing or changing behavior. It is the product contract and ordered handoff to Luna. Update its milestone evidence as work completes; do not mark work complete based on scaffolding or mocked native behavior.

## Working agreement

- Owner instruction: do not build or test on this VM. All builds and tests run through GitHub Actions. Local file editing and read-only review are allowed.

- Implement one milestone at a time, in dependency order. Continue through independent work when credentials or devices block another step.
- Inspect the actual environment, repository, and installed tools before assuming setup exists. Do not overwrite an existing remote or unrelated user work.
- Record concrete validation results and unresolved blockers in the plan. Never claim an Android build, device test, store upload, or publication passed without evidence.
- Keep changes small enough to review. Avoid speculative server infrastructure and unrelated refactors.
- Pin toolchains and dependencies when selected, commit application lockfiles, and document setup commands. Do not use floating `latest` versions in CI.
- Add tests for time calculations, concurrent commands, migrations, geofence transitions, CSV safety, and backup merge integrity as these behaviors are implemented.

## Architecture and invariants

- Flutter/Dart UI and shared domain code; Riverpod presentation state; native Kotlin Android integrations; SQLite persistence. Future iOS adapters implement the same contracts.
- The native Android repository owns database writes. App, widgets, notification actions, and geofence events use the same serialized command boundary and committed state.
- At most one open session; reject overlapping finalized intervals. Clock changes and duplicate callbacks must not create negative or duplicate work.
- Manual actions win over automation. Geofences never silently switch the running job. Missing GPS does not prove departure.
- Store UTC timestamps, timezone context, actual location timestamps, and accuracy. Ten-minute sampling is a target, not a delivery guarantee.
- Record location history only during work. When a departure is finalized retrospectively, remove points beyond the resulting session end.
- Default maps work without credentials. Map failures never block manual timekeeping, boards, reports, or exports.
- No analytics, server, account system, task timers, or recurring billing at launch.
- Backup merge is previewed and transactional, resolves conflicts explicitly, and never starts timers or silently enables geofences.

## Public repository and release boundaries

- Intended repository: `centerionware/Clockrove`, public. Intended application ID: `io.github.centerionware.clockrove`.
- Publish this file, the plan, tests, workflows, and contributor documentation. Keep local skills out of Git.
- Do not commit signing keys, store credentials, map credentials, real location data, personal backups, or private diagnostic logs. Use synthetic test fixtures.
- Do not weaken `.gitignore` or force-add ignored files to publish local configuration. Review the staged file list before a push.
- Untrusted pull-request code must never access signing/publishing secrets. Production tags must refer to tested main-branch commits.
- Store credentials and account/listing setup are supplied manually by the owner. Missing credentials block publication, not local development.
- The source-available licensing intent is documented in the plan. Do not substitute an open-source license or claim audit history is tamper-proof.

## Optional local skills

The original workspace includes ignored skills in `.local-skills/`:

- `clockrove-milestones/SKILL.md`: milestone implementation and evidence-based handoff.
- `clockrove-native-tracking/SKILL.md`: native location, widgets, and recovery validation.
- `clockrove-release/SKILL.md`: release readiness and publishing workflow.

These are local supplements, not build dependencies. If they are absent in a clone, use this file and `app_plan.md`. To use them in this workspace, read the relevant `SKILL.md` explicitly; automatic discovery is not assumed. Do not require contributors to have private skills or a particular AI model.
