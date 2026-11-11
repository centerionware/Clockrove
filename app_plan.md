# Clockrove — implementation plan and handoff to Luna

Prepared: 2026-11-11. Status: foundation CI passed; milestone 2 prototype in progress; public terms review pending; see `plan_status.md` for task-level status.

## 1. Start here

Read `AGENTS.md`, then this file. Implement the milestones in section 8 in order, validating each against section 10. Update section 12 with evidence as work proceeds. Do not replace unimplemented native capabilities with simulated success.

The user explicitly requested this handoff after creating agent instructions and local skills. Deliver the application described here; do not infer that application code, builds, GitHub setup, or store releases already exist.

### Current workspace facts

- Workspace: `/workspace/TimesheetTracker`; protected root `.git` remains untouched.
- `scripts/git-local` uses ignored `.local/git` metadata with the intended origin.
- `centerionware/Clockrove` is public after explicit owner instruction on 2026-11-11.
  `docs/SOURCE_TERMS_DRAFT.md` remains a proposal, not an adopted license.
- User 1000's authenticated `gh` identity is `centerionware`.
- Android Flutter scaffold, pubspec.lock and pinned Gradle wrapper are committed.
- All builds/tests now run in GitHub Actions, per owner instruction. VM tool setup
  and attempted builds in the historical ledger predate that instruction.
- CI pins Flutter 3.47.9, Dart 3.13.7, Temurin 17.0.17+10, AGP 9.1.0,
  Kotlin 2.4.0, Gradle 9.3.1, API 24 minimum / 36 target, build tools 36.0.0,
  and NDK 28.2.13676358. Compatibility requires a successful Actions build.
- Foundation debug build and signed setup AAB are proven in Actions. Current native
  behavior and physical-device evidence are unproven. No store upload/publication
  is confirmed. Store setup is being handled by the owner.
- Optional ignored local skills remain supplements, never contributor dependencies.

### Next concrete action

Validate the native repository/widget/typed adapter and committed-version event
changes in Actions. Correct the reported test formatting issue first. Then connect
prototype manual controls and continue notification/location/map feasibility.
The one-time initial AAB and permanent upload-key secrets have been delivered;
that setup build does not establish finished-app readiness. See plan_status.md.

## 2. Product decisions

| Item | Agreed behavior |
| --- | --- |
| Name | Clockrove; chosen by owner, provisional pending direct store-name verification |
| Repository | Public `centerionware/Clockrove`; use existing GitHub credentials |
| Application ID | Proposed implementation default `io.github.centerionware.clockrove`; freeze before first store upload |
| Launch | Android phone app with home-screen widget; no computer desktop companion |
| Later platform | iOS app and widget, after Android launch |
| Price | USD $5.99 one-time paid store download |
| Future service | Optional team service, tentatively USD $1.99/month/user; not implemented at launch |
| Storage | Local-first, single-user, no account or server required |
| Public source | Audit/personal-use source-available rights, not an open-source license |
| Reuse intent | Private personal builds and changes permitted; redistribution/commercial reuse require permission; contributions permitted |
| Timer | Exactly one active job, editable timesheets and manual entries, local change history |
| Location history | Only while clocked in; target every ten minutes; keep until user deletes |
| Automation | Multiple circular sites per job; manual actions win; configurable departure grace and paid-travel mode |
| Boards | Custom columns and reorderable cards per job |
| Maps | Free/no-account OSM tiles by default; optional user-configured OSM-compatible tiles and Google Maps |
| Reports | Calendar/date range, list, map, job filters, CSV and optional separate GPS CSV |
| Backup | Password-encrypted full export; previewed conflict-aware merge restore |
| Release automation | Main to internal testing; version tags to production submission once setup is ready |
| Agent artifacts | Public `AGENTS.md`; local skills ignored by Git |

Exact-source-license wording, the name check, and store identity/account setup are explicit readiness gates. Do not quietly choose MIT/GPL/Apache in place of the owner's selected rights. Draft consistent source-available and contribution terms before public source publication, including the GitHub fork/PR permissions needed for contributions. Avoid a contradiction between a blanket redistribution prohibition and permitted GitHub contribution forks. Obtain owner review of the concrete terms; no license text is supplied by this handoff.

## 3. User-facing behavior

### 3.1 Jobs and manual timekeeping

- A job has a name, color, archive state, board, sites, and automation preferences.
- One running session globally. Switching job closes the old session and starts the new one at a single captured timestamp in one transaction.
- Add, correct, and delete time entries with an append-only local revision history. This is an audit aid, not tamper-proof evidence on a user-controlled device.
- Reject negative/zero completed durations and overlapping intervals, including overlaps introduced by corrections/import. Adjacent intervals are allowed. Use half-open intervals `[start, end)`.
- Manual entries and edits record their origin; never pretend manually supplied times were observed by GPS.
- Archive jobs without destroying history. Stop a job's running timer before archiving it; exclude archived jobs from start pickers and automation.
- Retain timekeeping when location permission is denied, approximate only, GPS is disabled, or the device is offline. Clearly show which location/automation capability is unavailable.
- No rounding, break state, automatic unpaid deductions, invoicing, pay-rate calculations, or task-level timers at launch. Users clock out and back in for unpaid breaks.

### 3.2 Home-screen widget and ongoing notification

- Each widget chooses a default job or picker mode.
- When idle, default-job mode starts that job. Picker mode opens the app to active jobs, with **New job** last.
- If the default job was archived or removed, open the picker with an explanation.
- When a timer is running, every widget shows the actual running job and offers clock-out, regardless of its configured default.
- An Android ongoing foreground-service notification shows the active job and clock-out action while location tracking is active.
- All actions enter the same serialized native command path. Deduplicate repeated taps and callbacks using command IDs and committed state; do not trust a widget's stale snapshot.
- Refresh widgets after committed changes, restore, configuration changes, and recovery. Display elapsed time from timestamps, not a counter that must wake every second.

### 3.3 Location recording and automation

- Target a location sample at clock-in, approximately every ten minutes while working, and at clock-out when available without delaying clock-out.
- Save measured timestamp, reception timestamp where useful, coordinates, accuracy, and session association. Do not insert synthetic points for missed intervals.
- Use Android fused location, a location foreground service, and the platform geofencing API. Ordinary background timers and periodic WorkManager jobs are not evidence of ten-minute GPS delivery.
- Explain the purpose of location access before requesting it. Request background access separately when enabling geofencing. Show an honest degraded state for denied permissions and platform restrictions.
- Monitor enabled geofences while idle without keeping an off-clock trail. Current readings for site setup must not become session history.
- Sites are circles selected by map tap, current location, or coordinates. Default radius 150 m; supported initial range 150–10,000 m. Display that accuracy and event latency vary.
- Jobs support multiple sites. Enforce Android's active registration limit of 100 sites across the app with a visible capacity message; do not silently drop registrations. Recheck platform limits when implementing iOS.
- A job is considered inside if any of its enabled sites is inside. Exiting one of several overlapping sites does not count as leaving the job.
- Auto-start only when enabled, no session is active, and exactly one eligible job matches. If several match, post a selection notification/deep link; do not launch an activity unexpectedly from the background.
- Geofence events never silently change the running job.
- At manual clock-out, suppress auto-start for that job until the device has exited all its sites and subsequently re-entered. Persist this suppression. A later explicit manual start is allowed.
- Turning on automation while already inside does not silently start a timer: offer explicit start and wait for a subsequent qualified entry for automatic start.

Each job selects one departure mode:

1. **Automatic departure:** configurable whole-minute grace from 0 through 1,440 minutes, default 30. Persist a departure candidate when leaving all job sites. Re-entry into any site for the same job before expiry cancels it. Otherwise finalize at the detected departure timestamp, not at expiry. Zero means no intentional grace.
2. **Manual departure / paid travel:** departure never ends the session. Continue sampling until a manual stop or explicit manual job switch.

The grace period includes travel when a same-job site is reached before expiry. It is not a guarantee of paid travel beyond expiry; use manual-departure mode for that case. Leaving for another job does not auto-switch.

Persist pending deadlines and reconcile them after process recovery. Use platform-compliant scheduling; a delayed wake may delay finalization but must not change the intended recorded boundary. Do not request exact-alarm permission solely for grace expiry. Reconcile known re-entry events before finalizing. Show unresolved/late-event conflicts for correction rather than silently overwriting subsequent manual work.

While departure is pending, show provisional status. On retrospective finalization, remove points after the resulting end. Export only finalized intervals. Manual shortening of a session must also prune points outside the corrected interval; extending a session does not manufacture GPS data.

Missing GPS alone is not proof of departure. Reboot/process death/force-stop must not fabricate clock-out times. Recover valid persisted state, re-register geofences when permitted, and explain if the user must reopen the app or grant permission. The app cannot promise continued execution after Android force-stop.

### 3.4 Kanban

- Each new job starts with To do, Doing, Done.
- Add, rename, and reorder columns; keep at least one column.
- Deleting a populated column requires selecting a destination for its cards before committing.
- Cards have title, description, optional due date, and position. Support moving/reordering by drag and accessible menu actions.
- Card due dates are calendar dates, not UTC instants. Preserve them across timezone changes.
- No attachments, team assignments, comments, or task timers at launch.

### 3.5 Calendar, list, map, and CSV

- Select a single date or inclusive date range. Share the same selection and job filters between list, totals, map, and export.
- Store UTC instants and original IANA timezone/offset context. Use the device's current timezone for report day boundaries, clearly labeled. Recalculate display when it changes.
- Split sessions at report-day boundaries for reporting without splitting source records. Compute duration from UTC instants, including daylight-saving transitions.
- Show active/pending sessions separately from final exportable totals.
- Render timestamped, accuracy-aware GPS markers and a chronological trace per session. Break traces across sessions and missing-data gaps; connecting samples must not imply a verified route.
- Export completed portions intersecting the selected days, clipped to the selection. Include session ID, job ID/name, source start/end, exported start/end with offsets, duration seconds, report timezone, and origin (manual/widget/geofence/import as appropriate).
- Offer separate GPS CSV with session ID, job ID/name, measured timestamp, latitude, longitude, accuracy. Include only points within the selection and finalized session boundaries.
- Explain why active/pending sessions are excluded. Use UTF-8, consistent headers, correct quoting/newlines, and spreadsheet-formula protection for user-entered text.
- Share/save exports through Android's document/share interfaces; do not require broad filesystem permissions.

### 3.6 Maps and privacy

- Default: OpenStreetMap public raster tiles, no API key or billing account.
- Follow visible attribution, identifying User-Agent, HTTPS, caching, and server backoff requirements. Honor cache headers (at least seven days if headers cannot be interpreted). No bulk prefetch/download-for-offline feature.
- Public tiles are best-effort, not guaranteed free hosting at arbitrary scale. Do not advertise uninterrupted availability or an SLA. Show a useful failure state and keep the rest of the app working.
- Optional compatible raster provider: user-configured HTTPS tile template and authentication settings. Validate templates, do not log key-bearing URLs, and preserve provider attribution.
- Optional Google Maps: user's own billing-enabled project and key, with instructions for Android application and signing-certificate restrictions. No developer-owned billing project required by the default experience.
- Native Google SDK initialization must be proven with a user-provided key before committing to a Flutter plugin wrapper. Use a small native platform-view adapter if the standard plugin cannot support initialization from secure runtime settings. Changing provider/key can require app restart, disclosed in settings.
- Store credentials in Android Keystore-backed protected storage. They cannot be treated as unextractable secrets when used by a client SDK; provider restrictions remain necessary. Never include them in logs, CSV, backup, or Git.
- No address autocomplete, geocoding, routes, Street View, satellite/offline download feature at launch. Basic site placement needs no paid API.
- Map requests reveal viewed map areas and network metadata to the provider; reflect this in the privacy policy and store disclosures. Do not claim the app has no network data flows.
- Retain GPS until deletion. Allow deleting GPS by date range independently of timesheets. Clear relevant cached displays afterward. Existing exported files/backups are not retroactively erased; tell the user.
- No analytics or remote crash reporting initially. Exclude sensitive databases/credentials from unencrypted OS backup pathways; user-controlled encrypted export is the supported backup flow.

### 3.7 Encrypted backup and merge restore

- Export a versioned password-encrypted archive of jobs, sites, automation settings, boards/cards, sessions, location points, revisions, and deletion markers.
- Use a maintained implementation of authenticated encryption (AES-256-GCM) and a salted memory-hard password KDF (Argon2id). Pin a compatible library and parameters after testing on the minimum supported device. Store version/KDF parameters in a bounded authenticated envelope; reject excessive attacker-supplied work factors before deriving keys. Do not invent cryptographic primitives.
- Exclude map keys, caches, device widget configuration, and live automation registration state. No password recovery; explain this before export.
- Stop timers before restore. Backups may contain an open/provisional session captured at backup time; require an explicit close-time choice or omission during preview. Never turn it into a live timer automatically or invent an end time.
- Use size/count limits and schema validation; authenticate before parsing decrypted content. Reject unsupported future versions and invalid references without changing local records.
- Stable IDs deduplicate identical entities. Model revisions with parent revision IDs, including merge parents, so ancestry can be distinguished from divergent edits; wall-clock `updated_at` alone is insufficient.
- Automatically accept unambiguous descendant revisions. Require local-versus-backup resolution for divergent content or deletion conflicts; keep both parents in a merge revision.
- Treat GPS as immutable identified points except deletion. Range deletions need revisioned tombstones/coverage so an older backup does not silently resurrect intentionally removed points.
- Preview proposed additions, changes, deletions, unresolved references, and time overlaps. Distinct overlapping sessions require choosing a record or correcting intervals. Do not auto-double-count.
- Commit the fully validated merge transactionally; a failed/interrupted merge leaves the previous database intact. Guard against source data changing after preview.
- Keep imported jobs' geofence automation disabled until the user explicitly enables it. Refresh UI/widgets after commit. Do not merge credentials or device registrations.

## 4. Architecture and interfaces

Use Flutter/Dart for the application UI and shared business rules, Riverpod for presentation state, Kotlin for native Android integrations, and SQLite for durable records. Do not introduce a server, speculative sync engine, or a second database just for widgets.

### Ownership

- Android native repository is the single database writer. Serialize all writes through it and use SQLite transactions for compound changes. Read operations return snapshots through the same typed boundary.
- Native widget, receiver, service, and notification operations run without a Flutter engine. They use the repository directly.
- Flutter calls generated typed platform interfaces (Pigeon or an equivalently typed adapter), never raw SQL or an independent timer.
- Emit committed state-change versions; Flutter re-queries affected views and native code refreshes widgets. On event reconnect, read the latest snapshot instead of assuming all events were received.
- Native provider adapters own permissions, location, geofences, secure key storage, map views where needed, document sharing, and lifecycle recovery.
- Keep platform contracts portable for later Swift/iOS implementations. iOS still needs its own repository adapter and extension-safe access design; do not promise a Kotlin widget/service can be reused unchanged.

### Minimum typed contracts

| Contract | Responsibilities |
| --- | --- |
| Timer commands | Start, stop, switch; command ID, expected state/version, event source and timestamp; return committed session state or typed conflict |
| Job/site operations | CRUD, archive, site capacity/validation, automation preferences |
| Board operations | Column/card CRUD and transactional reorder/move |
| Record operations | Manual entry, edit, delete; overlap validation and revision history |
| Reports | Date-range/timezone/job-filter query returning sessions, final/provisional totals and location points |
| Tracking status | Permission/service/geofence readiness, last observation, pending departure, recovery action |
| Export/backup | Validated export request; restore inspection, conflict choices and guarded atomic commit |
| Provider settings | Public configuration separately from protected credential handles; explicit restart-required result |

Persist UUIDs, schema version, revision ancestry, entity tombstones, session boundaries/origin, measured location data, automation suppression, pending deadlines, and command deduplication outcomes. Define foreign keys and indexes for job/date/session/location queries. Use versioned forward migrations with transactional migration tests. Keep raw platform DTOs out of screen widgets.

Suggested feature structure: app shell/navigation; jobs; tracking; boards; history/maps; export/backup; settings; shared domain/interfaces. Favor a small, explicit architecture over a generic framework. Use English initially with externalized UI strings, accessible text/actions, and light/dark themes.

## 5. Security and public source

- `.gitignore` must keep local skills, tools/configuration, signing material, credentials, real databases, exports/backups, logs, build output, and device evidence private. Public `AGENTS.md`, lockfiles, test fixtures and Gradle wrapper remain tracked.
- Use synthetic location/time fixtures; never publish real travel traces as test evidence.
- Document source-available rights accurately. Dependency licensing must permit the planned distribution; maintain notices and an inventory.
- Keep signed store artifacts and secrets out of publicly accessible CI artifacts/caches. Never execute fork code in a privileged `pull_request_target` workflow.
- Store signing/upload keys and store API credentials in GitHub Actions secrets/environments, supplied by the owner. Use least-privilege workflow/service-account permissions.
- Protect release refs, verify tag ancestry against main, and separate validation from privileged publishing.
- Do not label local revision history immutable proof against the device owner. Future recipient restrictions must be enforced by a server.

## 6. GitHub Actions and store delivery

### Workflow design

1. **PR validation:** pinned Flutter/Dart, Java and Android build tools; formatting check, analysis, unit/native tests, migration/backup tests as introduced, and Android build validation. No release secrets.
2. **Main validation/internal release:** same checks; once readiness is enabled, signed release AAB delivered automatically to Play internal testing.
3. **Version-tag release:** validate semantic version/tag consistency, main ancestry and checks; assign a unique monotonically increasing Android versionCode; build/sign and submit production automatically. Serialize publishing to avoid racing store edits/version codes. Failed reruns must not reuse an already-published versionCode incorrectly.
4. **Manual recovery:** workflow dispatch can retry explicitly selected eligible releases and prepare store testing tracks without bypassing checks. This supplements, not replaces, automatic main/tag delivery.

Pin external actions to reviewed commit SHAs and toolchains to exact versions. Commit lockfiles. Use Fastlane with a locked Ruby bundle for publishing. Temporary secret files must be cleaned up and never uploaded/cached. In GitHub summaries distinguish validation, signing, upload, submission, approval, and live release.

Before account/credentials are supplied, run validation and report **publishing not configured**. A missing credential is an explicit skip/block, never a successful deployment. Do not upload public binaries as a substitute for the paid store release.

Default production intent is automatic release after approval. Store review, account verification, paid-app agreements, closed testing, and policy eligibility cannot be bypassed by Actions. Owner manually supplies app/store keys and creates the store profile when ready. Document any first manual upload required before API-based updates.

### Owner setup checklist (supply when needed, not before coding)

- [ ] Verify GitHub access and final application ID; review source/contribution license before public publication.
- [ ] Confirm Clockrove via direct Google Play and Apple searches and store-name entry when available; do not claim trademark clearance from search results.
- [ ] Create/verify Play developer account, listing, paid-app/payment setup, USD $5.99 base price, and supported territories/local prices.
- [ ] Complete required internal/closed testing and production-access application for the account type.
- [ ] Enable Play App Signing, establish upload key, and add required upload/store service credentials to Actions manually.
- [ ] Publish support/privacy URLs and complete background-location/foreground-service declarations, data-safety forms, rating, screenshots and review instructions.
- [ ] Perform any first-upload/manual console initialization, then enable internal publishing and test a complete automated cycle before production tags.
- [ ] Later: Apple developer account, bundle identity, certificates/profiles, App Store Connect key, TestFlight and App Store setup.

## 7. Source references and feasibility notes

Research during planning used these primary sources; recheck time-sensitive requirements during implementation and release:

- [Android background location limits](https://developer.android.com/about/versions/oreo/background-location-limits): ordinary background location is throttled; a ten-minute target is not a guarantee.
- [Android geofencing](https://developer.android.com/develop/sensors-and-location/location/geofencing): registration capacity, recommended radius, permission and callback behavior.
- [Flutter native integration](https://docs.flutter.dev/platform-integration): shared UI with platform-native adapters.
- [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/): attribution, identifying requests, caching, best-effort service and no bulk offline downloads.
- [Google Maps Android usage/billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing): billing-enabled project/API credentials required; do not promise every optional API is free.
- [Play account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): applicable newer personal accounts currently require at least 12 opted-in testers continuously for 14 days before applying for production access. Internal testing alone does not satisfy this requirement.
- [Google Play publishing API](https://developers.google.com/android-publisher): automated upload and publishing capabilities.
- [Apple background location](https://developer.apple.com/documentation/corelocation/handling-location-updates-in-the-background): iOS lifecycle constraints require native design and testing.
- [App Store Connect API](https://developer.apple.com/app-store-connect/api/): build and review/release automation.

Exact-name web searches and searches restricted to indexed Google Play/Apple App Store pages returned no Clockrove matches during planning. This is only preliminary evidence; direct listings and private/reserved names were not exhaustively checked.

## 8. Ordered implementation milestones

### Milestone 1 — repository, license, toolchain, and CI foundation

- Verify signed-in GitHub owner and existing remote state. Resolve writable checkout safely. Create the public repository only after reviewing the concrete source/contribution terms and public file set.
- Preserve handoff files. Draft license, contribution rules, README setup instructions, dependency inventory, and architecture notes.
- Install/pin Flutter stable, compatible JDK/Android tools, Ruby/Fastlane as needed; select and document minimum Android API from current supported dependencies. Target the Play-required API at release.
- Scaffold Android Flutter project with the agreed application ID and app display name. Commit reproducible build inputs and application lockfiles.
- Implement nonprivileged CI for formatting, analysis, initial tests and Android build. Use synthetic fixtures.
- Acceptance: a clean authorized checkout builds locally/CI with documented versions, private files stay excluded, and public rights are explicit. Store publishing is visibly disabled until configured.

### Milestone 2 — native feasibility prototype

- Prove native SQLite command ownership, a manual timer, a widget action and ongoing notification end-to-end.
- Add foreground location and geofence callbacks, persisted pending-departure state, and process recovery in a thin prototype.
- Prove default OSM rendering and secure runtime configuration of a user-supplied Google key; document restart behavior.
- Run physical-device screen-off/battery tests before committing to the full UI. Verify current foreground-service start rules for manual and geofence entry paths.
- Acceptance: measured callbacks and session transitions on a real device; verified widget operation without Flutter running; map provider experiment succeeds. If a device is unavailable, mark physical validation blocked and proceed only with independent work—never mark the prototype validated.

### Milestone 3 — persistence and domain correctness

- Finalize schema/migrations and typed contracts from the prototype.
- Implement stable IDs, revisions/tombstones, deduplicated commands, serial transactions and overlap checks.
- Implement jobs/sites/settings, board entities, session correction/history and filtered query support.
- Acceptance: tests prove atomic switching, command idempotency, valid migrations, no overlaps, referential integrity, and reproducible snapshots/events.

### Milestone 4 — usable manual tracker and widgets

- Build app navigation, jobs/archive, start picker, current timer, manual entry/correction/history, widget configuration and notification actions.
- Connect only to the repository's authoritative state; handle denied location and stale widget settings.
- Acceptance: complete manual tracking journey works offline, is accessible, survives process recovery, and stays consistent across surfaces.

### Milestone 5 — complete location and geofencing

- Implement onboarding, background permission flow, site editor, capacity checks, automatic entry and both departure modes.
- Complete suppression, grace expiry/re-entry, late-event reconciliation, location pruning, and tracking-health UI.
- Add recovery paths for reboot/service loss/permission changes, preserving honest uncertainty.
- Acceptance: automated state-machine matrix and physical-device checks pass; off-clock points do not remain in finalized history.

### Milestone 6 — boards, calendar, maps and CSV

- Implement customizable boards and accessible card moves.
- Build date selection, filtered list/totals, timezone handling and map history.
- Add optional provider settings, credential handling, OSM attribution/caching, and CSV/document sharing.
- Acceptance: board operations preserve cards; list/map/export filters agree; DST/range-boundary totals are correct; provider outage does not block local work; CSV formula/escaping tests pass.

### Milestone 7 — encrypted backup and safe merge

- Select and pin compatible crypto/KDF libraries; benchmark supported minimum devices and define bounded format parameters.
- Implement export envelope/schema, restore preview, revision ancestry resolution, deletion/overlap conflicts and transactional commit.
- Exclude secrets and disable imported automation; resolve open backed-up sessions explicitly.
- Acceptance: round-trip, conflict, wrong-password, corrupt/tampered archive, malicious-size/KDF, interrupted transaction and duplicate import tests pass without partial writes or credential leakage.

### Milestone 8 — release readiness and automatic Android delivery

- Finish accessibility/theme/error states, privacy/support docs, store assets and disclosures, and contributor onboarding.
- Complete the physical-device matrix, dependency/license review, release build and upgrade migration tests.
- Configure protected publishing workflows and Fastlane using owner-supplied credentials. Demonstrate internal delivery before production.
- Complete applicable closed testing/production eligibility. Publish paid release from a validated version tag, subject to store review.
- Acceptance: separate evidence for tests, signed AAB, internal installation, upgrade behavior, submission and approval. A submitted build is not yet a live app.

### Milestone 9 — later iOS (deferred)

- Implement Swift location/geofence/widget adapters and an extension-safe repository; share Flutter UI/domain contracts.
- Re-evaluate region limits, background delivery, permission onboarding, Google/OSM integration and backup portability on iOS.
- Add macOS CI, TestFlight, signing and App Store review automation after owner setup.
- Acceptance: iOS-specific physical-device evidence and a tested cross-platform backup path; Android results are not substitutes.

### Future service (not a launch milestone)

If demand justifies it, design optional accounts, team invitations, board collaboration and timesheet sharing. Keep local use independent of a subscription. Recipients can view shared timesheets but cannot change them; the server must enforce ownership/authorization. Stable IDs and revisions support later design, but do not implement speculative sync, subscriptions, ACLs or a backend now.

## 9. Release and rollout boundaries

The application must be complete for the paid Android launch. The ordered milestones are implementation slices, not permission to omit requested features from v1. Main builds may be internal tests throughout development. Production tags require all launch acceptance criteria and account prerequisites. Do not publish a prototype as the USD $5.99 product.

Account creation, owner credentials, concrete license review, and physical devices are real dependencies. Ask for the specific missing input only when needed after preparing the reviewable work. Continue unaffected implementation where possible. Do not repeatedly ask the owner to approve choices already recorded in this plan.

## 10. Acceptance matrix

| Area | Required evidence |
| --- | --- |
| Timer concurrency | Simultaneous app/widget/notification/geofence commands yield at most one open session; duplicate commands return consistent results |
| Editing | Negative, zero and overlapping completed intervals rejected; manual edits create revisions and reconcile GPS association |
| Clock/timezone | DST, midnight, timezone changes and wall-clock adjustments cannot silently create invalid work; report clipping/totals verified |
| Geofences | Multiple same-job sites, overlapping jobs, disabled/archive states, capacity, zero/custom grace, re-entry and paid travel |
| Manual precedence | Manual stop suppresses restart until exit/re-entry; auto events do not switch active jobs |
| Recovery | Process death, reboot, force-stop/relaunch, permission revocation, notification permission limitations and battery saver |
| GPS | Actual sample times/accuracy shown; gaps truthful; no retained idle/post-finalization trail; deletion does not delete timesheets |
| Widgets | Default and picker mode, New job last, missing default, stale state, running-other-job state, multiple widgets |
| Boards | Column/card reorder, populated-column deletion, accessible moves, due dates across timezones |
| Reports | Date/job filters match list/map/export; active/provisional exclusions explained; GPS points linked correctly |
| CSV | Unicode, quotes, commas, multiline fields, spreadsheet formulas, exact durations and offset/timezone fields |
| Maps | Default without keys; OSM attribution/cache/backoff; optional valid/invalid keys; provider change/restart; offline failure |
| Backup | Authenticated round-trip, wrong password, tampering, bounds, unsupported version, ID dedupe, ancestry/divergence, tombstones, overlaps, open sessions, crash rollback |
| Migration | Install and upgrade across each released schema; no record loss or credentials in export |
| CI/security | Fork validation has no secrets; pinned actions; tag/main validation; unique build numbers; no private files in artifacts |
| Store | Required declarations, internal install/upgrade, applicable closed testing, successful submission distinguished from approval |

Use unit/domain tests, native integration tests, widget/UI tests and physical-device tests according to the behavior being checked. Keep private traces in ignored `device-evidence/` and summarize sanitized results publicly. Do not add superficial tests that only assert documentation wording or mirror implementation statements.

## 11. Defaults and remaining gates

Implementation defaults in this plan fill gaps without changing the owner's choices: 150 m site radius, 0–1,440 minute grace, three initial board columns, no task timers, English initial UI, provider-independent records, current-device report timezone, and no automatic OS backup of sensitive data. Preserve them unless evidence reveals an incompatibility; document any necessary revision before implementing a changed behavior.

Remaining gates are concrete, not assumed complete:

1. Toolchain/checkout availability and GitHub identity.
2. Owner review of drafted source/contribution rights before public publication.
3. Direct name and permanent app-identity verification before store setup.
4. Runtime Google-key and background-service feasibility demonstrated on Android.
5. Physical-device evidence, not available from this documentation-only handoff.
6. Store profiles, account eligibility, signing/store credentials supplied by owner.

## 12. Progress and evidence ledger

| Deliverable | Status | Evidence / next action |
| --- | --- | --- |
| Product plan | Written | This file captures decisions, milestones, defaults and external gates |
| Public agent instructions | Written | `AGENTS.md` |
| Local skills | Written; basic checks passed | All three passed direct frontmatter/name/description/body checks. Bundled skill validator could not run because Python `yaml` is unavailable; rerun when PyYAML is installed |
| Ignore rules | Validated | Isolated temporary Git metadata: 12 private paths correctly ignored; 8 public/build-input paths correctly allowed. Workspace `.git` was not changed |
| GitHub repository | Identity verified; not created | centerionware via user 1000; intended repository absent; publication awaits terms review |
| Source/contribution license | Drafted; review pending | docs/SOURCE_TERMS_DRAFT.md and CONTRIBUTING.md; owner review requested before publication |
| Toolchain/app scaffold | In progress | Flutter/Dart run; lockfile and analysis pass; JDK configured; Android build running |
| Milestones 2–8 | Not started | Follow dependencies and acceptance checks above |
| iOS/server | Deferred | Preserve contracts; do not implement during Android launch work |

For each implemented milestone append: date, commit/build reference, changed behavior, commands/checks run, results, device details where applicable, remaining blocker and next action. Never use this ledger to imply implementation or testing that has not happened.

### 2026-11-11 — milestone 1 foundation in progress

No commit/build reference yet. User 1000 authenticates via gh as centerionware;
intended repository does not exist. No remote created or public files pushed.
Protected root .git preserved; scripts/git-local uses ignored alternate metadata.
Source terms drafted for owner review in docs/SOURCE_TERMS_DRAFT.md, with
contribution rules, README, architecture and dependency notes. Publication remains
gated on concrete terms review.

Verified Flutter 3.47.9 archive checksum; host is ARM64, so verified matching Dart
3.13.7 ARM64 archive and rebuilt Flutter CLI. Version command succeeds. Generated
Android scaffold uses io.github.centerionware.clockrove and Clockrove display name.
Dependency resolution produced pubspec.lock; dart format checked two files and
flutter analyze reported no issues. No timer or native behavior implemented yet.
OS backup disabled and release debug-signing fallback removed.

Android build attempted and failed: no JDK/JAVA_HOME. Doctor located an external
Android SDK (API/build-tools 36); device discovery failed on sandbox adb socket
permissions. No Android build, device test, CI, upload or publication passed.
Next: pinned JDK setup, Android tool compatibility/build verification, lock build
inputs and nonprivileged CI. Full milestone/task status is in plan_status.md.

### 2026-11-11 — JDK and nonprivileged CI continuation

Found and copied existing ARM64 Temurin 17.0.20.1+1 JDK into ignored local tools;
version command succeeds. Gradle 9.3.1 wrapper jar matches upstream checksum and
its distribution checksum is pinned. Minimum API 24 / compile and target 36,
build tools 36.0.0 and NDK 28.2.13676358 are explicit. Validation-only workflow
uses immutable action commits and pinned tool versions; no store credentials or
binary uploads. GitHub workflow execution still pending publication/repository.
Locked offline pub resolution, format check, and analysis pass. No substantive
application tests exist yet. JDK-configured Android build is live in session
15110; completion unproven. Concrete license review requested from owner.
See plan_status.md for detailed validation and the next action after build output.

JDK-configured build ended with an x64 AAPT2 execution failure after 238.4s.
Validated existing ARM64 SDK AAPT2 and configured an ignored local override;
retry session 56789 is running. Added .kotlin/ generated-state ignore. Android
build success remains unproven until that process completes.

### Owner constraint — GitHub-hosted validation only

Owner explicitly prohibits building/testing on this VM. All future builds and
tests, including formatting/analysis validation, run via GitHub Actions. Local
edits and read-only inspection continue. This overrides earlier local-build setup
instructions and local-build portions of milestone acceptance; clean GitHub CI
must prove build reproducibility. Local retry session 56789 was cancelled and
closed with no APK success evidence; do not count it as passed. Intended repository
may remain private for CI until concrete source terms review permits publication.

Private staging repository created and verified at https://github.com/centerionware/Clockrove.
Reviewed 40 staged public-safe source paths and pushed foundation commit
be442a3c417b8dc163e3845c24e4998894679b98. GitHub validation run 42980408630 is
in progress. Public visibility remains gated on owner terms review. No build/test
will be run on the VM following the owner's instruction.

First Actions run failed in setup-java before build/test because 17.0.20.1+1
has unsupported SemVer notation. CI corrected to exact verified x64 Temurin
17.0.17+10. No local validation used for the correction.

### 2026-11-11 — Actions SDK setup correction

Run 42980514022 passed JDK/verified Flutter setup, then failed with sdkmanager
not found (127). Workflow now downloads checksum-pinned command-line tools 19.0
(build 13114758), installs the selected Android packages in a job-local SDK root,
and exports that root. No VM build/test executed. Task details in plan_status.md.

### 2026-11-11 — foundation checks passed; compilation live

Same run 42980838050 passed JDK/Flutter/Android SDK setup, enforced lockfile,
formatting and analysis. No application behavior tests exist yet; the conditional
test step is not proof of native behavior. Debug APK compilation started at
07:39:33 UTC and is still in progress. Native unit task and job conclusion pending.
No VM validation ran. Public terms review remains pending independently.

### 2026-11-11 — first successful Actions build and native repository slice

Run 42980838050 on d6dbe94 concluded success. Logs show debug APK built and
native test task NO-SOURCE. Foundation checks/build are proven; native behavior
was not tested by that run. Public license/visibility gate remains pending.

Started independent milestone 2 implementation: native SQLite timer repository
and five Robolectric tests; serialized transactions, persisted retry outcomes,
version conflicts, UTC context, one-open-session index and atomic switch.
Automation rejects commands until policy is implemented. Test/build validation
for this code is pending GitHub Actions. No UI/widget/notification integration,
location/map experiment or physical-device success is claimed. Full migration
and revision schema remains milestone 3 work. No VM build/test was executed.

### 2026-11-11 — native widget action implementation

Added prototype AppWidgetProvider, layout/resources and receiver declaration.
It reads the native repository, displays actual running job and timestamp-based
elapsed time, and submits deduplicated version-checked clock-out commands without
Flutter. Native surface commands persist the first tap timestamp/timezone and
reuse outcomes for retries. Sixth repository test covers retry/stale-tap safety.
Idle widget opens the app; configuration, typed Flutter controls, complete refresh
propagation, ongoing location notification/service, maps and physical validation
remain incomplete. No VM validation ran; code awaits Actions and runtime evidence.

### 2026-11-11 — explicit public publication authorization

Owner instructed “make it public now.” gh changed the intended repository to
PUBLIC and verified isPrivate=false at https://github.com/centerionware/Clockrove.
This overrides the earlier publication wait. Draft terms remain draft; no license
adoption is inferred from the visibility instruction. No build/test ran on VM.

### 2026-11-11 — typed prototype bridge

Added explicit Dart/Kotlin timer adapters with background native handling,
engine lifecycle registration, eligible-job snapshots and widget refresh after app
commands. Raw transport values stay inside adapters. Added three codec/retry tests
for Actions; mocks do not prove device/native delivery. Full UI, event stream,
notification/service/location/maps and physical feasibility still incomplete.
Public repository state is reflected in README/status; draft terms remain draft.
No VM builds/tests executed. See docs/TIMER_CONTRACT.md for contract details.

### Owner-directed initial Play setup artifact

Owner explicitly requests a signed first-upload AAB before full app completion,
using Actions only. This is a setup/testing bundle, not the paid production launch.
Owner accepts public artifact availability and one-day minimum retention, authorizes
new upload-key generation and repository-secret persistence, and requires key
reuse for all future CI uploads. Prepared initial-play-bundle workflow pinned to
validated foundation d6dbe94 / run 42980838050, versionCode 1. Private recovery is
CMS-encrypted; keys/passwords must never be plaintext artifacts or Git content.
SERVICE_ACCOUNT_JSON exists; no Play API upload is performed for this request.
No signing/build result is claimed until Actions finishes. See
 docs/INITIAL_PLAY_UPLOAD.md and plan_status.md for the operation and key lifecycle.

### 2026-11-11 — initial setup AAB build and key persistence verified

One-time Actions run 42984008259 succeeded: validated foundation source d6dbe94,
version 0.1.0+1, RSA-4096 upload signing and strict jarsigner verification. Signed
artifact 13839295891 expires 2026-11-12T08:08:17Z (owner accepted one-day public
artifact). CI-generated upload keystore/passwords saved in UPLOAD_KEYSTORE_BASE64,
UPLOAD_STORE_PASSWORD, UPLOAD_KEY_ALIAS and UPLOAD_KEY_PASSWORD repository secrets.
Temporary encrypted recovery artifact and bootstrap recipient secret deleted;
remaining artifact includes AAB and public upload certificate only. Same permanent
key must be used for future CI. No Play upload or finished-app release claimed.

### 2026-11-11 — native committed-version propagation

Added repository subscriptions, version replay on reconnect and event-channel
invalidation hints. Events publish only after committed changes; failures/retries
produce no false changes. Engine cleanup cancels subscriptions/queued delivery.
Seventh repository test covers commit/replay/unsubscribe semantics. Corrected long
Dart test descriptions flagged by Actions; formatter failures now print source
diffs in CI for read-only diagnosis. No VM format/test/build executed. Validation
for current prototype remains pending; no runtime/widget/device proof claimed.

Current run 42985050236 on fa360ff passes formatting/analysis/Dart tests; Android
compilation remains live. Preserve that run until terminal. API-24 compatibility
correction and minimum-API native tap test are prepared locally after source
review found undesugared java.time usage. These pending edits are unvalidated.

### Owner handoff to automatic internal and tagged production delivery

Owner confirms initial Play upload and authorizes each validated main push to
internal testing. Version tags select production versionName and trigger release
once eligibility/app readiness gates are satisfied. CI must allocate strictly
increasing versionCodes for both paths with no manual bump, serialize uploads,
and reuse the saved key. Owner confirmation is not API upload verification.

Native run 42985050236 failed Kotlin compilation on three mixed SQL argument
arrays; explicit Any? arrays prepared, plus API-24 capture fix/eighth native test.
Dart checks passed. Preparing pinned Ruby/Fastlane lockfile via Actions before
publishing workflow activation. No VM validation or Play upload attempted.

### Automatic main/internal and version-tag/production implementation

Locked release tools resolved by successful Actions run 42986036667. Added Play
delivery workflow, Fastlane lanes and automatic version policy tests. Main builds
publish only after successful validation of exact main SHA; vX.Y.Z pins production
versionName without pubspec editing and requires tested main ancestry/readiness.
Store-wide code inspection plus time floor allocates increasing unique codes
under a shared publishing queue, including reruns. Main validation queues instead
of cancelling pushes. Production gate is one-time readiness, not per-release
approval. Saved upload key is reused; credentials cleaned and no public binary
artifacts in regular delivery. No actual internal/production upload proven yet.
See docs/PLAY_DELIVERY.md and plan_status.md for operational status.

Native run 42986013201 built debug APK but failed host-test task graph validation:
AGP packaging lacked dependency on copyFlutterAssetsDebug. Added explicit producer
dependency for variant host-test packaging. No test execution success or automatic
internal upload is claimed. Subsequent validation must pass before Play delivery.

### 2026-11-11 — prototype manual controls on feature branch

Actions-resolved Riverpod 3.0.3 lockfile applied. Added prototype Flutter controls
for native job creation/start/stop/switch, New job last, resume refresh and explicit
location-unavailable state. Riverpod consumes native version invalidations and
snapshots; uncertain commands retain IDs and block competing app intents pending
retry. Added three presentation tests; physical/native/UI runtime proof pending.
Branch remains separate from main until validated; no VM builds/tests/formatting.

Actions dependency preparation 42987027150 on e52b1fd concluded success, including
Android compilation, native test task, version-policy tests and Fastfile syntax.
This validates the pre-controls branch's native repository tests and build only.
New UI/controller commit 787916b is isolated in draft PR #1, validation 42987698700;
its behavior is not covered by the earlier successful run. Main compilation and
first automatic internal delivery remain in progress/unproven respectively.

Main run 42986727241 passed all checks and automatically triggered Play delivery
42988068348 (live; upload acceptance pending). PR #1 formatting failed in run
42987698700; applied reviewed Actions-generated source patch and added prototype
screen journey test. No VM formatter/test/build executed. New UI remains unmerged
and unvalidated until the next PR run passes.
