## 9. Release and rollout boundaries

The application must be complete for the paid Android launch. The ordered milestones are implementation slices, not permission to omit requested features from v1. Main builds may be internal tests throughout development. Production tags require all launch acceptance criteria and account prerequisites. Do not publish a prototype as the USD $5.99 product.

Account creation, owner credentials, concrete license review, and physical devices are real dependencies. Ask for the specific missing input only when needed after preparing the reviewable work. Continue unaffected implementation where possible. Do not repeatedly ask the owner to approve choices already recorded in this plan.

## 10. Acceptance matrix

| Area | Required evidence |
| --- | --- |
| Timer concurrency | Simultaneous app/widget/notification/geofence commands yield at most one open session; duplicate commands return consistent results |
| Editing | Negative, zero and overlapping completed intervals rejected; manual edits create revisions and reconcile GPS association |
| Clock/timezone | DST, midnight, timezone changes and wall-clock adjustments cannot silently create invalid work; report clipping/totals verified |
| Geofences | Multiple same-job sites, overlapping jobs, disabled/archive states, capacity, small-radius limitations, zero/custom grace, re-entry and paid travel |
| Manual precedence | Manual stop suppresses restart until exit/re-entry; auto events do not switch active jobs |
| Recovery | Process death, reboot, force-stop/relaunch, permission revocation, notification permission limitations and battery saver |
| GPS | Actual sample times/accuracy shown; gaps truthful; no retained idle/post-finalization trail; deletion does not delete timesheets |
| GPS recording controls | Global default-on and per-job inherit/on/off behavior; ten-minute request target, five-minute ordinary persistence floor, session-boundary samples, and service reconciliation |
| Widgets | Default and picker mode, New job last, missing default, stale state, running-other-job state, multiple widgets |
| Boards | Column/card reorder, populated-column deletion, accessible moves, due dates across timezones |
| Task comments/images | Comment add/edit/delete and ordering; multi-image gallery batches and camera-intent paths; review/atomic attach, retake/cancel, private storage, EXIF removal, 50 MB/250 MP source limits, 5 MB normalized-image limit, 10-at-once/20-per-task/100 MB-per-job caps, ordered swipeable viewer and parent cleanup |
| Quotes | Template selection creates editable lines; no required notes; customer selector has the same Manage customers flow as Job settings and refreshes options on return; accepted quote can prefill a reviewed new job or map to an existing job without duplicate tasks or fabricated work |
| Reports | Date/job filters match list/map/export; active/provisional exclusions explained; GPS points linked correctly |
| CSV | Unicode, quotes, commas, multiline fields, spreadsheet formulas, exact durations and offset/timezone fields |
| Maps | Keyless OSM default; attribution/cache/backoff; custom HTTPS template and authentication validation; provider change/restart; offline fallback |
| Backup | Authenticated round-trip, wrong password, tampering, bounds, unsupported version, ID dedupe, ancestry/divergence, tombstones, overlaps, open sessions, crash rollback |
| Migration | Install and upgrade across each released schema; no record loss or credentials in export |
| CI/security | Fork validation has no secrets; pinned actions; tag/main validation; unique build numbers; no private files in artifacts |
| Full-release E2E | Every feature, screen and selectable option has an actual-app journey, persisted/native assertions, and a reviewed screenshot on minimum and current release APIs |
| End-user documentation | Versioned static guides cover every shipped feature with reviewed screenshots; deployed on Cloudflare Pages for public release with synthetic data only. The Pages deploy workflow, cache/security headers and post-deploy legal-page check are in place; the first publish waits on the owner's Cloudflare project and token |
| Store | Reviewed privacy policy reachable in-app and at a public URL, completed Data safety declaration before closed testing, internal install/upgrade, applicable closed testing, and submission and approval recorded separately. The static pages exist at `website/legal/privacy.html`, `data-use.html`, `terms.html` and `license.html`; deployment, the store contact/effective-date confirmation and the in-app policy link are still open |

Use unit/domain tests, native integration tests, widget/UI tests and physical-device tests according to the behavior being checked. Keep private traces in ignored `device-evidence/` and summarize sanitized results publicly. Do not add superficial tests that only assert documentation wording or mirror implementation statements.

## 11. Defaults and remaining gates

Implementation defaults in this plan fill gaps without changing the owner's choices: 150 m default site radius with a 1.8 m minimum, 0–1,440 minute grace, three initial board columns, no task timers, English initial UI, provider-independent records, current-device report timezone, and no automatic OS backup of sensitive data. Preserve them unless evidence reveals an incompatibility; document any necessary revision before implementing a changed behavior.

Remaining gates are concrete, not assumed complete:

1. Toolchain/checkout availability and GitHub identity.
2. Owner review of drafted source/contribution rights before public publication.
3. Direct name and permanent app-identity verification before store setup.
4. OSM/custom HTTPS map behavior and background-service feasibility demonstrated on Android; Google Maps is deferred beyond launch.
5. Physical-device evidence, not available from this documentation-only handoff.
6. Store profiles, account eligibility, signing/store credentials supplied by owner.
