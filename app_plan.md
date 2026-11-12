# Clockrove — implementation plan and handoff to Luna

Prepared: 2026-11-11. Status: native tracking device acceptance pending; job/board/customer/billing increments passed Actions; combined business iteration validated and merged; public terms review pending; see `plan_status.md` for task-level status.

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
- Foundation debug build and signed setup AAB are proven in Actions. Physical-device tracking evidence remains unproven. Automatic Play internal
  uploads are confirmed; production and tester installation are not confirmed.
- Optional ignored local skills remain supplements, never contributor dependencies.

### Exact money contract (owner priority, 2026-11-12)

All money input, persistence, transport and calculations use canonical decimal
strings and arbitrary-precision decimal/integer libraries, never binary floating
point or fixed-width money arithmetic. Use pinned Dart `decimal` and native
`java.math.BigDecimal`/`BigInteger`; parse strings directly. Remove current 64-bit
rate/unit-price limits through a conservative migration preserving released values,
issued documents, source claims and command receipts. Reject malformed/excess
currency precision explicitly; never silently round input. Quantity and tax math
stay exact until the documented currency boundary: half-up once per task line,
hourly total and invoice-subtotal tax. Payments, credits and balances conserve each
minor unit and never narrow large invoice totals. Pin/lock new dependencies through
Actions only, and verify migration, large amounts, tiny fractions and conservation.

### Next concrete action

Invoice foundation #35 merged f935485 after full Actions 43182854787 (383 native /
44 actual Android, zero failures/errors/skips), including exact strings/money,
quantity/unit pricing, tax, coverage and retained invoices. XML and wide/1000-digit
PDF/UI captures were reviewed. Widget #36 corrected scroll scheduling, but main
then exposed initial adapter binding. #38 corrected the early-notification race
with revision/token/local-day adapter identity. Final c1595c7 passed 43195277559:
385 native / 44 actual Android, zero failures/errors/skips, three resize cycles,
live edit, exact final-row rendering and real board tap on both APIs. XML/logs and
widget captures reviewed. Merged 1c358bc at 2026-11-12 10:07:02 UTC. Main 43197481528 passed 385 native /
44 actual Android (downloaded XML, zero failures/errors/skips), and Play delivery
43199258376 accepted 0.1.0-internal.25.1 / 216642056 at 10:25:48 UTC using the
permanent key. Tester availability/physical upgrade remain separate. No VM
builds/tests ran.

Continue the immediate payment milestone in `.local/invoice-payments`, draft #37.
Baseline c21ef87 / 43201426802 passed 434 native and 46 actual Android cases,
zero failures/errors/skips, with immutable receipt/correction/balance copies and
all-style wide PDFs. Subsequent UI source 7d414ee / 43207931777 passed formatting,
analysis, Flutter behavior, debug build and 440 native cases. API 24 passed the
21 original cases plus billing; the short Classic balance copy then failed its
one-page assertion. Its copy-only layout is corrected in source; the isolated
payment UI journey and API 35 remain unverified for this increment.

The current draft adds native confirmed unpaid cancellation and atomic
credit-preserving replacement, strict lifecycle command/context bridge and source,
rollback, migration-compatible, concurrency and exact-money regressions. Actions
43210091600 stopped on a renderer Int/Float width mismatch, now corrected in
source; ed94bfc had stopped on a removed ledger local variable reference. Native baseline 40259d7 / 43211073661 now passed 476 native cases
(42 XML files inspected, zero failures/errors/skips), including 32 lifecycle and
10 bridge cases; actual Android checks remain live. The newer zero-due guard and
extended document cases still need their queued Actions result. Next: inspect this exact run, fix any
failures, then finish the lifecycle editor/relationships, historical receipt routing,
VOID/replacement documents and actual UI/PDF acceptance. Keep draft #37 unmerged
until the complete payment milestone passes. Schema 8 remains unreleased and moves
forward from released 7; preserve frozen fixtures, documents and prior money.

Then quotes, comments/supplies/actual costs and company reports. Provider settings,
encrypted backup/transactional merge, physical tracking/recovery and full launch
acceptance remain open. Owner Pixel 7 / Android 17 main Settings Save confirmation
is recorded; exact App info version was not transcribed, and financial/widget/GPS
physical acceptance remains separate.

## 2. Product decisions

| Item | Agreed behavior |
| --- | --- |
| Name | Clockrove; chosen by owner, provisional pending direct store-name verification |
| Repository | Public `centerionware/Clockrove`; use existing GitHub credentials |
| Application ID | Frozen as `io.github.centerionware.clockrove` at the first Play upload |
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
- No rounding of work intervals, break state, automatic unpaid deductions, or task-level timers at launch. The owner subsequently requested configurable hourly-rate reporting; use the current global rate or explicit job override for completed report time. Users clock out and back in for unpaid breaks.

### 3.2 Home-screen widget and ongoing notification

- Provide Timer, Tasks and Combined widget types. Combined uses a compact timer-only layout when small and adds a scrollable due-task list when large enough. Task taps open the corresponding job board without starting work.
- Task lists include overdue work and the next seven calendar days in the current device timezone, excluding completed tasks and archived jobs. A task widget can show all active jobs or one selected job. Keep empty, missing-job and stale-task states explicit.
- Each widget chooses a default job or picker mode.
- When idle, default-job mode starts that job. Picker mode opens the app to active jobs, with **New job** last.
- If the default job was archived or removed, open the picker with an explanation.
- When a timer is running, Timer and Combined widgets show the actual running job and offers clock-out, regardless of its configured default.
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

- Owner priority for the current invoice patch: tasks have a positive decimal
  quantity (default 1, up to four decimal places). Rename the existing price input
  to **Unit price**; it remains the only price input. Task total is quantity × unit
  price, rounded half up once per task to currency precision. Preserve old amounts
  with quantity 1. Carry quantity through templates, boards, reports and immutable
  invoice lines. Quantity/price edits never release an existing task invoice claim.

- Each new job starts with To do, Doing, Done.
- Add, rename, and reorder columns; keep at least one column.
- Deleting a populated column requires selecting a destination for its cards before committing.
- Cards have title, description, optional due date, and position. Support moving/reordering by drag and accessible menu actions.
- Card due dates are calendar dates, not UTC instants. Preserve them across timezone changes.
- Add private chronological task comments and a Supplies toggle in the owner-requested business iteration; see section 3.5a and the detailed lifecycle plan. No task attachments, team assignments or task timers; comments do not introduce an account system.

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

### 3.5a Billing lifecycle, quotes and business reports

The ordered owner-requested contract is [docs/INVOICE_LIFECYCLE_PLAN.md](docs/INVOICE_LIFECYCLE_PLAN.md).
After current widgets are validated and merged, implement invoice integrity/tax,
then payments (including partial), receipts, accounting CC and void/replacement.
Quotes follow; then task comments, labor/supplies classification and actual costs;
then a main-menu Reports icon with useful business/tax-preparation summaries.

- Bill each task and exact UTC portion of work only once, for unpaid and paid
  invoices alike. Native transactional source claims, safe legacy reconciliation,
  transparent exclusions and response-loss/concurrency tests are required.
- Derive unpaid/partially-paid/paid from an auditable payment ledger. Updated
  copies retain the original invoice number and total, showing payments and balance.
  Receipts never create another charge/payment or invoice number.
- Void only unpaid/partially-paid invoices. Partial payments require atomic Void &
  replace: prefill lines, preserve the original VOID document, transfer source
  claims and paid credit to the new invoice, and never count transferred credit
  as new cash. Fully paid invoices cannot be voided. No credit may be discarded.
- Main Settings has default tax percentage and per-job inherit/override, including
  explicit 0%. Entering 7.5 adds 7.5% of subtotal, rounded once in minor units.
  Freeze subtotal/rate/tax/total on issued invoices and quotes; existing documents
  are unchanged by later settings. Separate tax collected from revenue/profit.
- Main Settings has optional accounts-receivable email. Invoice/receipt/quote
  email drafts CC it when set, otherwise company primary email. Fill default-email
  To/CC/subject/body/PDF safely; the user sends. Keep both template guards.
- Quotes have their own Q-1000 sequence, immutable issued revisions and explicit
  local acceptance. Converting reviewed quote scope never fabricates work or
  bypasses source coverage. Quotes/estimates are not revenue or invoice payments.
- Task comments are private chronological records. A simple Supplies toggle
  classifies charges; actual costs are recorded separately. Labor revenue is
  not automatically profit; materials markup can contribute to profit.
- Reports show invoiced sales, cash receipts, tax, outstanding balances, labor/
  supplies/unclassified charges, actual costs and estimated profit/margins. Clearly
  distinguish cash/invoice dates, missing costs and currencies; preserve drilldowns,
  corrections and replacement history. Do not guess jurisdiction-specific tax rules.
- Every slice requires native transaction/migration/retry checks, real PDF and
  intent evidence in Actions. Physical email behavior and full acceptance remain
  separate. Plan status is not implementation evidence.

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

- Export a versioned password-encrypted archive of jobs, sites, automation settings, boards/cards/comments, sessions, location points, invoices/coverage/payments/receipts/replacements, quotes, expenses, revisions, and deletion markers.
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

### 3.8 Owner-requested business and combined test-release scope

The binding additional contract is [docs/NEXT_TEST_RELEASE.md](docs/NEXT_TEST_RELEASE.md).
It adds job detail/Kanban, reusable templates retained after job deletion, customer
management, hourly or task billing, report amounts owed, PDF/email invoices and
global company/contact/logo/email/header/footer settings with at least three
invoice styles. This replaces the earlier exclusions of pricing and invoicing.
The next internal test release must include all of these features together.
Archive/unarchive and red confirmed deletion after 30 continuous archived days
remain required. Reusable template/customer records are explicit independent
exceptions to deleted-job data removal and must not resurrect deleted work.

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
- Owner-prioritized next slice after widget merge: paid-invoice status, PAID receipts and accounting CC preference (section 3.5a).
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

Automatic delivery run 42988068348 failed before allocation/upload: Google Play
begin_edit rejected caller permission. Owner input needed for service-account app
visibility/testing-release access in Play Console. No upload success claimed;
avoid repeated unchanged permission attempts and continue app implementation.

Owner reports Play service-account invitation and asks to retry. Failed delivery
job in run 42988068348 was re-run after that change; result pending. Same upload
key/source and automatic code allocator apply. No upload acceptance claimed yet.

PR #1 passed complete Actions validation 42988271415 and was merged at 6713ddf.
Prototype controls are now on main; merged-main validation 42989132915 is live.
Play retry 42988068348 attempt 2 successfully read history/allocated a code after
owner invitation and is building the signed AAB; no upload acceptance yet.

First automatic internal delivery succeeded in run 42988068348 attempt 2.
Play accepted versionCode 216549708 at 08:45:37 UTC after signed AAB build and
verification with the permanent upload key. This proves upload/submission only;
no tester installation/review/production availability claimed. New manual-controls
merge 6713ddf is still in main validation 42989132915 for its subsequent delivery.

### 2026-11-11 — native measured-location storage and migration slice

Feature branch native-location-prototype adds schema-2 measured point storage,
active-session binding, sample ID integrity, coordinate/accuracy/time validation,
and transactional post-end pruning on timer stop/switch. Actual previous schema
captured as fixed synthetic migration fixture. Four Robolectric tests cover
validation/dedup, late/off-clock/pruned samples, preserved upgrade and failed
migration rollback. These storage changes are unvalidated until Actions runs;
no fused callbacks, foreground notification, device GPS or map success claimed.
All builds/tests remain on GitHub, never the VM.

### Foreground location prototype source

Added pinned fused-location dependency 21.3.0, declared location FGS type/permissions,
service callback/session binding, target cadence, notification with independent
native clock-out receiver, cleanup and stable measured-point mapper/test. Startup
permission/UI adapter is still pending; Flutter continues to show tracking
unavailable. These additions are source-only until Actions and real device checks
prove them. No synthetic GPS or guaranteed ten-minute delivery is claimed.

Storage PR #2 passed complete Actions run 42990150091 and merged at d6e4d9c.
Foreground service source is isolated on foreground-location-prototype for separate
validation; no runtime/device proof yet. Automatic manual-controls delivery
42989964414 accepted versionCode 216550241; this is not installation evidence.

### 2026-11-11 — explicit location startup and degraded state

Service PR #3 passed Actions 42991221956 and merged at a99faa9. Permission/start
follow-up is isolated on location-permission-flow: visible activity request,
fine/coarse and separate notification permissions, typed status and service-health
invalidation, purpose dialog before native calls and independent location stop.
Denied/restricted states preserve manual timing. New transport/permission-purpose
UI checks are pending Actions; no physical callback/FGS success is claimed.
No VM tests/builds/formatter ran. Geofence/map/recovery feasibility remains open.

PR #4 run 42992333516 failed formatting; applied reviewed Actions-generated patch.
Added native permission lifecycle tests and corrected concurrent enable/cancel
handling and asynchronous storage-error completion. New code remains unvalidated
until the next PR check; native test shadows are not physical-device evidence.
No local formatter/build/test executed.

### Owner-directed clean modern UI from prototype onward

Owner explicitly requests clean modern UI now. Added isolated modern-timer-ui
branch with consistent light/dark Material 3 theme, restrained forest/neutral
palette, clear active timer hierarchy, timestamp-derived tabular elapsed display,
readable content width and focused job/location controls. Only implemented actions
are shown. Added time-display/DST/long-session tests, large-text check and CI-only
synthetic rendered previews for visual inspection; no rendering success claimed.
Permission PR #4 passed Actions 42993338165 and merged at 0262d86. New visual work
requires separate CI and preview inspection. No VM builds/tests/formatting.

PR #5 run 42994742872 failed formatting before preview rendering. Applied reviewed
Actions-generated source patch and guarded display refresh against initial
background lifecycle state. No VM tests/build/format executed. New UI still needs
CI success and actual light/dark preview inspection; no visual success claimed.

UI run 42995204970 passed formatting, then analysis flagged two unnecessary imports.
Removed the flagged imports without weakening analysis. UI preview rendering is
still pending and visual quality has not yet been verified from rendered output.

UI run 42995708218 passed analysis and ten tests, including large text, but failed
journey scroll/tap frame timing and preview package-URI lookup unsupported by
Flutter tester. Corrected harness to settle scroll frames and load fonts through
explicit pinned FLUTTER_ROOT. Kept all assertions/preview generation enabled.
No rendering success or visual approval claimed; no VM validation executed.

First modern UI preview artifact 13843514422 from run 42996201315 was downloaded
and both themes visually inspected; hierarchy/contrast/spacing are sound. Fixed
preview debug ribbon and added scrolled location captures. Also added atomic
tracking-observation status with actual latest measured time/accuracy, choosing
measurement order over callback order; native/codec tests added. Changes need new
CI/visual review. No VM build/render/test ran and no physical GPS evidence claimed.

Modern design PR #5 passed complete Actions 42996201315, both preview themes were
visually inspected, and exact head dadb445 merged at 9e32c90. This validates the
prototype UI/layout, not launch/device readiness. Latest measured-status and
clean debug/scrolled preview changes are isolated on measured-tracking-status.
Reusable owner UI direction recorded in docs/UI_DESIGN.md for later features.

PR #6 run 42997495311 failed formatting; applied reviewed CI-generated source patch
without local formatting. Actual measured-status/preview validation remains
pending. Departure-state persistence follow-up remains separate and unscheduled.

### Prototype durable departure state

Dependent branch departure-state-persistence adds schema-3 pending exit/deadline
storage, timely re-entry cancellation, duplicate exit stability, manual clear and
retrospective end/pruning guarded by session ID. Forward migrations and four native
persistence/grace/recovery tests added. No scheduler/geofence callback/UI activation
is included; internals await CI and later integration. No GPS absence is treated
as departure and no new session is silently stopped. No VM checks executed.

### 2026-11-11 — clean UI refinement validated and merged

Measured-status PR #6 passed complete Actions run 42998378446 and merged at
db7c24b3110228550bf9107fd9858b9273757128. All four clean light/dark and scrolled
location previews from artifact 13844847336 were visually inspected. Timer/actions,
job groups and actual sample metadata are readable; no debug ribbon remains.
Dart/UI, Android build, native and version-policy checks passed in Actions only.
Departure PR #7 passed run 42998556045; rebased onto merged main after squash
ancestry conflict, with byte-identical source tree. New-head validation pending.

### Qualified geofence membership source

Isolated geofence-membership adds native membership/selection facts and six tests:
overlapping same-job sites, unknown/duplicate/late/conflicting observations,
archive/disabled filtering, 100-site capacity and unambiguous qualified entry.
Active sessions never select a new job; supplied suppression and arming filter
candidates. This does not persist suppression/arming or register callbacks, post
selection notifications or start timers. Native integration still requires those
steps and device evidence. Source is unvalidated until Actions completes; no VM
build, test, formatter or rendering was run.

Departure PR #7 passed rebased-head Actions 42999724652 and merged as
b0d93f5eb4d3a56a6bfb5a36ff38f87d42df820b. Membership source was rebased onto
that main commit; only append-only evidence sections needed conflict resolution.
Combined-head validation pending. Main UI validation 42999610367 also succeeded.

### Android visual identity follow-up

Isolated native-visual-identity replaces starter launcher rasters with vector
clock artwork, adaptive and monochrome layers, and matching light/dark launch
backgrounds including API-31 system splash attributes. No new runtime dependency.
Added an Actions-only native-resource rendering harness/artifact for image review;
it does not establish real launcher masking or device launch transitions. Source
and previews await CI. No VM rendering/build/test/formatter was run.

Departure PR #7 passed rebased-head Actions 42999724652 and merged as
b0d93f5eb4d3a56a6bfb5a36ff38f87d42df820b. Native visual source was rebased onto
that main commit; only append-only evidence sections needed conflict resolution.
New combined-head validation pending; no runtime automation/device proof inferred.

Native visual run 43000557414 passed Dart/UI checks and Android APK compilation,
then failed Kotlin test compilation: Robolectric getApplication has no type
parameter. Removed the unsupported generic argument. Rendering remains unproven;
all preview checks remain enabled. No VM tests/build/format/render were run.

### Native visual review and combined main validation

Corrected native visual run 43001359856 concluded SUCCESS: complete Dart/UI,
Android debug build, native tests/resource rendering and release version-policy
checks. Downloaded artifact 13845869738 and visually inspected all five PNGs:
legacy/adaptive/themed mark and light/dark launch backgrounds. No VM rendering.
Membership PR #8 passed exact-head run 43000880173 and merged at dc26e64.
Native visual branch rebased onto that main; conflicts were evidence appendices
only. Added public prototype device checklist and corrected outdated README and
timer contract status. New combined-head Actions validation pending; device and
runtime automation acceptance remain unproven. No production release submitted.

### Native visual merge and default-map source

Previous goal turn: progress (UI/refinement and departure/membership merges,
reviewed native previews, accurate contributor/device handoff). Continued by
polling the same live validation 43002359530; it concluded SUCCESS. Exact tested
head a2eaea5 merged via PR #9 as f9207b5. Matching Android visual identity is on main;
actual launcher/launch-transition device validation remains pending.

Pinned flutter_map 8.3.2 / latlong2 0.10.1 / http 1.6.0 / url_launcher 6.3.2 and
obtained Actions-resolved lockfile in 43002798859 / artifact 13846228897. New
osm-map-prototype source adds a current stored-point map with measured timestamp,
accuracy circle, always-visible attribution, proper User-Agent, cache policy and
shared 429/503 backoff. Release manifest includes Internet permission. Native
status now returns the same latest observation's coordinates and clears on idle.
HTTP policy, synthetic success/failure/navigation/zoom UI checks and native/codec
checks added. CI map images use synthetic tiles; no live OSM/device proof inferred.
Source validation and new visual review pending. No VM build/test/format/render.

OSM source run 43003804914 failed at formatting before analysis/tests. Applied
its reviewed Actions-generated source patch. Added responsive summary scrolling
and a large-text landscape check while keeping map controls/attribution visible;
enabled semantics in map accessibility checks. Dependency preparation 43002798859
completed SUCCESS independently; new map behavior still awaits its own CI.
No local build, formatter, tests or rendering ran.

OSM run 43004367001 required one additional formatter wrapping correction in the
landscape test; applied its source patch. Added an actual TimerScreen journey
through failed map decoding back to manual clock-out and updated synthetic
tracking previews to include valid coordinates. No native/physical or live-tile
success claimed. Next Actions run must pass analysis, all tests and visual review.

OSM run 43004745776 stopped on import-group formatting in the new timer journey;
applied its exact CI correction. Map point/accuracy ink now stays forest green
with a white marker halo so standard light OSM tiles remain legible in dark mode.
The marker is centered on the measured coordinate; no displaced pin tip. New
validation remains required before merging. No VM validation executed.

OSM run 43005218796 passed formatting, then analysis reported two redundant
Dart typed-data imports provided by Flutter services. Removed only those imports;
checks remain enabled. No map behavior/native/device success is inferred before
the next full Actions run and rendered-image inspection. No VM validation ran.

OSM run 43005707318 passed formatting/analysis and 20 Dart/UI checks, including
the real timer-screen map-outage/manual-stop journey. Failed malformed Retry-After
handling (Dart HttpDate throws HttpException) and two end-of-test accessibility
handle cleanup checks. Corrected the exception type and moved semantic-handle
disposal into test-body finally blocks; assertions remain active. Compact map
controls use a horizontal arrangement and the large-text check now covers a
320-high landscape viewport with full button bounds. CI exported six synthetic
previews (artifact 13848461149); visual inspection pending. No native/device/live
OSM success inferred; no VM validation ran.

OSM run 43006137440 required two formatter wraps after the stronger cleanup/bounds
checks; applied exact Actions patch. First map light/dark and location-card previews
from artifact 13848461149 were visually inspected: restrained layout, readable
measured metadata/attribution and consistent action styling. Tiles are synthetic.
CI now continues independent behavior/build checks after collecting a formatter
correction, with an explicit final Require clean formatting failure gate. Failed
formatting cannot produce a successful validation or trigger Play delivery. This
improves Actions-only feedback without disabling any checks or exposing secrets.
New full map validation remains pending; no VM checks executed.

### OSM prototype validation and merge

Actions 43006507700 on exact head 94451c2 concluded SUCCESS: clean formatting,
analysis, 23 Dart/UI tests, Android debug APK, native unit/resource-rendering tests,
locked Ruby tooling and release-version policy. All six synthetic images from
artifact 13849240735 were visually inspected (timer, scrolled location and map,
each in light/dark). Exact tested source merged through PR #10 as
bb2a9eb1a5a78074434472cb601d8755168f95e5. No VM build/test/format/render executed.

This proves source compilation, synthetic map/overlay layout, HTTP policy and
manual-timer UI isolation during tile failure. It does not prove live OSM service,
physical GPS/background/widget operation, Google runtime-key initialization or
full reporting/site editing. Those gates remain open. Native visual merge f9207b5
also completed main validation 43003375715 and Play internal delivery 43004256117;
that is upload acceptance, not tester installation or production availability.

Disposable owner-dispatched validation 43007665309 at fa4437e deliberately changes
only Dart whitespace to exercise the final formatter failure gate. It has no PR
and is not intended for main. Result is pending; do not count the negative-path
check as passed until its final failure and preceding check results are observed.
Merged-map main validation 43007569229 is independently in progress.

Formatter negative-path run 43007665309 completed with the intended FAILURE at
Require clean formatting. Analysis, all 23 Dart/UI tests, Android compilation,
native tests and release-version policy passed first; the exported source patch
confirms the intentional whitespace difference. This proves continued diagnostic
checks do not turn invalid formatting into a green validation. Probe branch is
being removed after terminal evidence. Main map validation 43007569229 and the
documentation-only PR #11 run 43007883446 both completed SUCCESS independently.
The only new changes after those runs are evidence text; application/workflow
source is unchanged. Physical/device/provider gates and full app milestones remain
incomplete. No VM validation executed.

### Durable geofence policy and native event transaction source

Previous goal turn: progress (OSM prototype merged after full CI and six-image
review; formatter failure gate proven in Actions). Continued from clean main
4a50778. Added isolated durable-geofence-policy source: schema 4 site inventory,
settings/arming/suppression, registration generations, membership facts, persisted
event outcomes and late-event review state. Configuration edits rotate registration
IDs and clear obsolete candidates; labels preserve known geometry/facts. Manual
stop/switch updates suppression in the same transaction. Eligible native events
can start one job, require choice for multiple jobs, never switch an active job,
and reconcile departure/re-entry with retrospective pruning and delayed wake.
Raw Flutter timer commands still cannot claim geofence origin.

Added ten minimum-API native tests covering enable-inside/exit-entry, recovery,
manual suppression/explicit restart, overlapping sites and timestamp ordering,
late re-entry review, zero grace, paid travel, ambiguous jobs, capacity/settings
rollback, ID reuse, concurrent native exit/manual stop and fixed schema-3 upgrade
plus failed-upgrade rollback. Existing schema 1/2 and command tests remain enabled.
These changes await Actions. Platform geofence registration/receiver, background
permissions, scheduler, health/provisional UI and physical-device proof are not
included yet; source tests do not establish runtime automation. Full revisions,
site UI and backup format remain later milestone work. No VM validation ran.

Main evidence validation 43008859456 completed SUCCESS. OSM source bb2a9eb also
completed Play internal delivery 43008466949; this is upload acceptance, not tester
installation or physical behavior. New durable-policy source remains isolated
until its own full Actions validation passes.

Prepared an explicit native accept-current-history review action: it preserves
session boundaries, resolves the alert and disarms/rotates registration state until
new qualified exit/entry. Extended the late-reentry test to prove no silent history
rewrite. Renamed requested inventory to sitesToRegister so it cannot be confused
with actual Android registration success. Contract is in docs/GEOFENCE_CONTRACT.md.
Initial run 43011366016 passed Android compilation and is running native checks;
review-action additions need new validation. No VM tests/build/formatter ran.

Initial policy run 43011366016 passed Android compilation and 37 of 38 native
checks; the capacity rejection hit API-24 transaction dispatch of ROLLBACK TO.
Confirmed the platform behavior in Android 7 DatabaseUtils/SQLiteSession source.
Moved capacity checks before mutation within the same serialized transaction;
kept rejection/key-preservation assertions and full migration rollback checks.
Native JUnit XML will now be exported from Actions for precise failure diagnosis.

Tightened idle-data retention before platform activation: event IDs/fingerprints
are device-keyed HMAC-SHA256 receipts, persisted outcomes omit raw choice/warning
IDs, and review state holds one current alert per job until explicit acceptance.
Current membership facts remain; no historical raw off-clock event payload is
retained. Unit tests inject a public synthetic HMAC fixture and inspect persisted
receipts, so Android Keystore persistence is explicitly still a device gate.
Review action, capacity correction and minimized receipts need the next full CI.
No VM validation ran; automation is not exposed in the app yet.

Added an eleventh native policy case for unavailable receipt keys: a possibly
unprocessed re-entry blocks automatic expiry for review while manual clock-out
still works. This is tested with a synthetic provider error, not a claimed Android
Keystore hardware/provider result. Late-event acceptance preserves existing time
history and disarms until fresh outside/entry facts. New source remains pending CI.

Corrected policy run 43014180179 on 32ec72d completed SUCCESS: Dart/UI, Android
compilation, 38 native tests (0 failures/errors/skips, XML artifact inspected),
resource previews and release-version policy. This verifies the capacity correction,
minimized receipts and explicit review acceptance in native unit conditions.
The additional unavailable-key expiry guard/test is a new source change and must
pass a subsequent full Actions run before merge. Device Keystore, receiver and
scheduler behavior are still unproven; no VM validation executed.

Added a twelfth policy case for invalid event clocks and provider faults, with a
finite native problem API for upcoming registration/permission/recovery adapters.
Known processing uncertainty now holds automatic expiry for explicit review instead
of losing a possible re-entry, while manual commands remain independent. This is
new source beyond the previously successful run and still needs Actions validation.

Refined recovery to avoid unnecessary approval loops: idle faults disarm current
facts and recover on fresh outside/entry, and known timely re-entry clears current
departure uncertainty automatically. Review alerts now reference affected work
sessions, not an idle movement history. Manual stop resolves its current uncertainty;
historical alerts cannot block expiry of a different manually started session.
Added a thirteenth native policy case for automatic recovery with fresh facts.
Provider/clock recovery additions still need the next full Actions run.

Review semantics are now scoped to affected work-session IDs. Benign pre-manual
callbacks do not create idle history or an approval loop. Idle provider faults
reset/disarm facts, and fresh outside/entry recovers automatically; known timely
re-entry clears active departure uncertainty. A historical alert cannot block a
new manually started session's expiry. Explicit review acceptance still preserves
recorded times and resets the job's automation context. Thirteen policy cases
are prepared; all latest refinements await a new Actions run.

Separated registration-generation time from current-fact freshness. The late-entry
case now includes an intervening idle provider fault: recovery must not hide a
valid late re-entry relevant to a finalized work session. Configuration changes
still invalidate obsolete keys; current facts after faults require fresh evidence.
Latest recovery refinements remain isolated and unvalidated until the next CI run.

Added a fourteenth policy case: a historical late-exit ambiguity survives later
provider faults and manual clock-out, while a newer manually started session can
still finalize normally. A sticky history-review flag prevents transient recovery
from silently accepting ambiguous past work. Only explicit review resolves it.
Latest source remains pending Actions; no VM validation executed.

### Milestone 2 — departure wake-up source (pending CI)

Added a native inexact AlarmManager adapter, private deadline/recovery receivers,
and commit subscription on the shared repository singleton. The SQLite writer
checks expected candidate deadline/session, due time and review before expiry;
re-entry/manual changes cancel wake-ups. Boot or clock changes hold uncertain
work instead of inventing an exit. No exact-alarm permission or GPS timer added.
Seven synthetic cases target API 24 and 35: inexact immutable alarm configuration,
delayed pruning, early/stale delivery, cancellation, recovery hold/manual action,
reopening and malformed broadcasts. They have not run yet; all builds/tests stay
in GitHub Actions. Platform geofence registration and app activation UI remain
unfinished, and physical wake/recovery evidence is still missing.

Durable policy exact head 87f566c passed full Actions run 43020856586. Inspected
JUnit XML: 42 native tests, zero failures/errors/skips, including fourteen new
policy cases. PR #12 merged as 87539048b9c5f468f8f52ebec88aac38de981b8b.
Dart/UI, Android build and release-version checks passed in that run. Alarm
adapter additions remain separate and need their own CI; milestone 2 is incomplete.

Alarm adapter run 43021499336 compiled the Android app, but native test
compilation rejected a nullable PackageInfo permission array in the new manifest
assertion. Added an explicit empty-array fallback without changing the permission
assertions. Runtime tests remain unverified until the corrected Actions run.

### Milestone 2 — Android geofence adapter source (pending CI)

Added Play Services inventory replacement, explicit mutable callback token/private
receiver, precise/background permission checks, asynchronous readiness tracking
and a cleanup-only preference. The registry rejects stale readiness results and
avoids endless provider retries. Startup holds uncertain prior registrations before
an alarm can finalize work. Callback/alarm work shares one native executor; events
use measured time without retaining off-clock coordinates, honor replay outcomes,
recheck the running session for FGS handoff and offer manual job selection for
ambiguous entries. No background permission request or automation activation UI
has been added yet. Fifteen synthetic cases target API 24/35; they use fake
platform completions and a public HMAC fixture, not real registration or Keystore
proof. All source still requires Actions validation and device acceptance.

### Milestone 2 — alarm evidence and automation controls source

Alarm head c76e806 passed full Actions run 43022813646. Inspected native XML:
56 tests, zero failures/errors/skips, including seven alarm cases on API 24 and
35. PR #13 merged f845937ecf90838da0b1f7dc6c6da15e779e0bd4. Policy main
validation 43021843305 and Play internal delivery 43022739286 also succeeded.
Geofence adapter head e42b37e passed run 43023461269 with 86 native tests,
zero failures/errors/skips (fifteen adapter cases on API 24/35). PR #14 was rebased
onto merged alarm code; its new exact head d0ef67b needs final validation.

Added unvalidated work-site UI source with native versioned save/remove/settings
commands, background-purpose education, separate precise/background permission
flows, measured pending-departure display and recorded-work review. Acceptance
preserves shown history and explicitly resets pending automation. Command retries
retain their original ID/version. The timer surface links to real site controls and
shows pending/review state. Six native API cases target API 29/35; nine Dart/UI
cases include light/dark site/editor/departure previews and large-text layout.
All tests, formatting and rendering remain in Actions. These additions do not
prove physical geofence/FGS/alarm delivery, nor complete milestones 2 or 5.

Final geofence adapter run 43024844407 passed on exact head d0ef67b. Inspected
JUnit XML: 86 tests, zero failures/errors/skips. PR #14 merged as
edc7dddaa40c4218a766fbc6ad4befb2f42bcf04. Work-site/permission/review UI
remains isolated in PR #15 and awaits formatting, behavior and visual validation.

First controls run 43026170277 exported formatter corrections but stopped at
eight Dart flow-control brace lints. Applied the Actions-generated patch and
added braces; no local formatter/test/build executed. Behavior and previews
remain pending the corrected full run.

Controls run 43026618644 passed formatting/analyze and all Dart/UI checks;
Android/native checks were still live at this observation. Reviewed the six new
site/editor/departure and two changed timer previews in both themes (artifact
13857262562): clear spacing, wrapping and consistent hierarchy. Review revealed
a copy ambiguity: pending work held for review must not promise automatic expiry.
Added conditional held-work copy and explicit year/seconds/UTC offset to review
boundaries; these source refinements require the next CI. Coordinate editing is
implemented; map-tap/current-position selection and full correction UI remain
planned, along with physical acceptance. No VM builds/tests/rendering ran.

Controls revision run 43027348013 passed formatting/analyze and 31/32 Dart/UI
cases. The added held-departure copy assertion searched an off-screen, lazily
unbuilt card after scrolling to the review action. The test now reveals the
pending card before checking that copy, then reveals recorded boundaries and
acceptance separately. Both assertions remain; a corrected Actions run is required.

### Milestone 2 — verified control UI and site selection source

Controls exact head 8a266c4 passed full Actions run 43028074243. Native XML:
98 tests, zero failures/errors/skips; Dart/UI checks and Android build passed.
Reviewed final light/dark departure images plus the previously reviewed site,
editor and timer previews. PR #15 merged 897e120bc0c802eb6ff42adfb4aab430a93851e0.
Main geofence adapter validation 43026215698 and internal delivery 43027191550
also succeeded. This is automated/visual source evidence, not device acceptance.

Added unvalidated map-tap/current-location site selection on that merged base.
OSM picker reuses identified/cached/backed-off tiles, radius overlay, attribution,
zoom and coordinate fallback. Polar coordinates remain editable without forcing
invalid Web Mercator rendering. A foreground-only cancellable fused one-shot
requests a fresh fix with a 15-second provider duration and a 20-second UI timeout; measured time/accuracy are displayed
in the draft, and no setup point enters work history. Backgrounding/detach cancels
and late callbacks cannot overwrite a newer request. The site form builds all
validators in one scroll view so off-screen fields cannot evade validation.
Seven native controller cases (API 24/35), one bridge case (API 29/35), eight UI/map
cases and one Dart codec case are prepared. All builds, formatter, tests and preview
rendering remain in Actions. Actual fused fixes, live OSM, device permissions and
background tracking remain unproven; milestones 2 and 5 are incomplete.

First site-selection run 43064678311 exported formatter changes, then stopped
at an invalid const Semantics construction and an unused test import. Applied
the Actions formatter patch, corrected both analyzer issues and retained all
behavior assertions. No VM validation ran; corrected CI evidence is still needed.

Owner reiterated the current-location work-site button with a precise permission
request only when missing. That flow is implemented in the site editor/native
preflight; added a direct UI case asserting one precise request, no background
request, a fresh fix retry, and explicit Save site. The already-granted case now
asserts zero permission requests. These refinements await Actions validation.

Site selection run 43065083546 passed full CI on 8d26499: inspected native
XML shows 114 tests, zero failures/errors/skips. Reviewed map/editor previews
from artifact 13875397031. The dark marker needed contrast refinement; reviewed
that corrected light/dark rendering from artifact 13874933679. Owner-specific
permission case then passed all behavior/build checks in run 43066607243 on
53f08f8, including 114 clean native tests; only the final formatting gate failed.
Applied its Actions-generated formatter patch. Final exact-head CI is pending;
actual device fixes and live tiles remain unproven. No VM validation ran.

### Owner-directed job lifecycle (implementation in progress)

The owner requested a red, confirmed permanent Delete job action, then refined
that policy: jobs must first be archived continuously for 30 days before deletion.
Archived jobs can be unarchived to return to clock-in and automation choices.
Implement archive/restore without losing history or preferences; require clock-out
before archiving the running job. Restoration resets the retention period and
site detection, preserves automation preferences, and respects the 100-site limit.

Deletion is unavailable for active jobs or before 30 elapsed UTC days. The native
serialized writer must enforce this independently of UI state. Eligible deletion
must show a red action and explicit permanent-loss confirmation, remove the job's
work/sites/location data transactionally, and keep only opaque deletion metadata
needed to prevent future silent backup resurrection. Command receipt payloads
must not retain deleted names/coordinates/times; deduplication must still work.
Legacy archived rows without an archive date start a fresh retention period on
migration rather than becoming immediately deletable. These owner-requested job
operations add foundation work while physical milestone-2 gates remain pending;
no broader milestone-3 completion is implied.


### Owner-directed next iteration scope

Owner reports the current main build generally works, but the widget occupies too
much home-screen space. GPS tracking has not yet been tested; permission prompting
worked, and background access required app settings. This is owner-reported
feedback without a device/OS log, not a completed physical acceptance matrix.

Deliver alongside current site-selection and archive work:
- A compact home-screen widget with the same native clock-out boundary.
- A real job detail display and the planned Kanban board: columns/cards, due dates,
  reorder/move controls and accessible actions, with committed native persistence.
- A main settings page for a default hourly rate and report currency, with an
  optional per-job hourly-rate override.
- Job reports showing completed time and amount owed using the effective current
  hourly rate. Exclude active/provisional work. Apply the rate to total finalized
  duration, round money once for the report using the currency's precision, and
  clearly state the calculation; payments/taxes are not added; the later owner request explicitly adds PDF invoicing and email drafts.
- Archive/unarchive and red, confirmed permanent deletion only after 30 continuous
  archived days, as specified above. Unarchive restores eligibility/preferences
  with fresh site detection and restarts the retention period if archived again.
- Clearer guidance for Android's separate background-permission settings step;
  do not pretend the foreground permission dialog includes an all-time option.

This explicitly replaces the original exclusion of hourly-rate calculations.
Implement native schema/commands and migrations before the corresponding screens;
maintain the single writer, revision/deletion integrity and real CI evidence.

Compact widget source now requests a 2×1 launcher footprint (110×56 dp legacy
minimum), uses one horizontal row and a 48 dp icon action, and retains native
versioned clock-out. Large text prioritizes job/action over the elapsed second
line. Added API-24/35 RemoteViews layout/accessibility cases and CI-rendered idle/
running previews. These are pending Actions and actual launcher/device review;
an installed widget's existing host size may require resizing or re-adding.


### Expanded combined-release request and temporary publishing hold

Owner added fixed task prices, per-job hourly/task billing mode, reusable task
content even from deleted jobs, full reports, customer management and job email,
PDF invoice/email handoff, global company/contact/logo/header/footer/email
settings and at least three invoice styles. These are fully recorded in the
linked next-test-release contract. No implementation of these additions is
claimed by documenting them.

At the owner's request that the next test release include the whole set, disabled
Play delivery workflow 414395901 temporarily; verified GitHub state
`disabled_manually`. Validate remains enabled. Re-enable normal automatic Play
delivery only when the complete combined iteration is ready, then merge/publish
from validated main. Production readiness remains disabled. Do not ship partial
intermediate test builds while this hold is in effect.

Compact-widget run 43071623833 built the Android app but native test compilation
failed because the new widget fixtures used Dart snapshot argument ordering for
the Kotlin type. Switched fixtures to named fields; all layout/state assertions
remain. Corrected Actions validation is pending; no VM checks ran.

### Native business persistence foundation — source awaiting Actions

Prepared schema 5 for the owner-requested combined iteration: job archive dates,
billing/customer fields, real three-column boards, cards, independent reusable
templates, customers, company/email/invoice settings, logo assets and prepared
invoice records. Currency/rates remain explicit and unset rates are not invented.
Money columns reject floating-point values; card placement has a composite
job/column foreign key. Legacy archived dates begin at migration, not a guessed past.

Added revision heads and ancestry rows, recording actual job/site/preferences and
manual/automatic session changes under the existing serialized native transaction.
Migration captures roots without operational geofence facts; new jobs seed boards
and revisions atomically. This is not a claim of tamper-proof history or a completed
backup merge implementation. Eight API-24 cases cover released-schema-4 migration,
tracking/receipt preservation, default boards, ancestry/dedup, retrospective ends,
site tombstones, FK/money guards and rollback. Source is unvalidated until Actions;
customer/board/pricing/invoice commands and screens remain unfinished. No VM
build, dependency resolution, formatter or test was run.


### Native foundation and compact widget merged; Kanban command source

Widget exact head 1cfc12a passed Actions 43072986373; reviewed real RemoteViews
idle/running renders and merged PR #18 as 45670b2fac16b04929a8f7cb3ec564965a829b0d.
Business foundation exact head dec72f4 passed 43073787801: inspected JUnit XML,
127 native tests with zero failures/errors/skips, including all eight migration/
revision cases. PR #19 merged f94179e02f33a91e4631a9a273606a0b78abb220. These
are CI/resource proofs, not actual launcher or owner-data upgrade acceptance.

Prepared real native board commands for column/card CRUD/order/moves, explicit
completion roles/dates, fixed task prices and reusable templates. All operations
share the serialized versioned command ledger and record revision ancestry.
Column deletion requires a destination when populated and keeps at least one;
copying templates creates a new identity without old due/completion/timekeeping
state. Twelve API-24 cases cover integrity, concurrency, rollback, native
completion, currency/date validation and archived-source templates. These new
commands still need Actions and transport/UI integration. Job deletion, customers,
reports, billing settings and invoices remain in progress/pending; no partial
internal release is authorized while the publication hold is active.

### Monitoring retry and upgrade recovery continuation

Rebased the pending explicit monitoring retry/app-upgrade recovery source onto
current main without changing the combined next-release contract or publication
hold. Prior Actions 43065982993 stopped at formatting; applied its source artifact.
Fresh exact-head Actions validation is required. This remains source-level recovery
behavior, not physical GPS/process/upgrade proof. Play delivery remains disabled.
### Combined business iteration evidence (continuation)

PR #20 (7be840e) passed 43077477547: 139 native tests, twelve new board cases.
PR #21 (493814c) passed 43078381276: 150 native tests, eleven business command
cases. PR #22 (5972331) passed 43078930808: 160 native tests, ten DST/exact billing/
CSV cases. PR #23 (65d4734) passed 43080530052: 166 native tests; typed board UI
and interaction checks plus light/dark previews reviewed. PR #24 (4875670) passed
43085253088: customer/company/job billing journeys and six reviewed previews.
These increments merged; none implies the combined iteration or milestone 3 is
fully accepted. Native lifecycle source passed an earlier run; corrected exact
heads and UI remain under validation. Actual PDF checks moved from unsupported
Robolectric PdfDocument to pinned Android emulator instrumentation in Actions.
Logo import, document/provider/integrated report UI checks and release acceptance
remain open. Play workflow 414395901 stays disabled; no partial release publishes.

### Current combined-iteration validation evidence

Recovered actual Actions artifacts after the service interruption. #26 run
43093219562 contains two actual PDF instrumentation cases per API (24/35),
zero failures/errors/skips. #27/#28 runs 43093223910/43093226704 each contain
191 passing native cases. #29 earlier head 042216c / 43101008984 contains
198 passing native cases, including logo-store and DST-report checks; its actual
framework/logo/viewer sequence is still running. Six synthetic report/map views
were reviewed in light/dark themes. Latest #29 adds all-style multipage captures
and requires all ten expected device cases per API. Final exact-head checks,
actual document visual acceptance and main validation remain required; no partial
Play release is authorized. Task-level status is maintained in plan_status.md.
### Independent job-command correctness continuation

Prepared versioned/idempotent job creation through the native command boundary,
retained app response-loss intent and strict integral timer versions. Covers atomic
board/revision creation, stale/duplicate requests, rollback and deleted-ID fences.
Eight API-24/35 native cases plus retry/controller/codec journeys await Actions;
remaining milestone-3/4/7 implementation and physical acceptance remain open.
### Independent milestone-3 session history continuation

Prepared native closed-session manual CRUD and history/revision queries through
the serialized repository. Positive/half-open overlap and explicit timezone
validation, transactional GPS pruning/deletion fences, immutable invoices and
current-timer isolation have fourteen new API-24 cases awaiting Actions. No UI,
bridge or full manual correction journey is claimed yet; Play remains held for
the separately specified combined business iteration.

### Combined business iteration merged to main

PR #29 exact head 8e0e19e0f6bac377ed9acbb1a3aa70a6507054ce passed full
Actions 43117726325. Inspected artifacts contain 238 native cases and thirteen
actual Android cases on each API (24/35), with zero failures/errors/skips. All
six decoded light/dark viewer captures match the reviewed actual-document
images. Native multipage first/middle/final pages were reviewed for all three
styles; physical email-app and GPS/launcher compatibility remain unproven.

Merged #29 as cb2efc9d9aa3dd8fb2b183ebc2eb457e71c514ec after confirming no
older main validation remained active. Re-enabled Play workflow 414395901;
PRODUCTION_READY is unset. Main validation 43120365067 passed and automatically started queued Play
delivery 43121445107; upload/acceptance for this source is pending. No VM build/test was performed.

### Manual history UI milestone prepared

Added job work-history navigation, paginated entries, manual entry/correction
forms, exact millisecond and original-zone formatting, explicit repeated-time
offset selection and nonexistent-time rejection. Unknown command replies retain
the original intent and lock editing/navigation; stale drafts are kept and a
changed saved entry cannot silently advance its expected version. Delete requires
a warning confirmation. Job-wide paginated revision history includes deleted
entries, ordered by local insertion sequence despite clock rollback. Native
detail reads are scoped to the requested job; purged jobs remain inaccessible.

New native query/bridge, real-framework time-round-trip and UI/retry/accessibility
checks are prepared. They await GitHub Actions; no UI/device acceptance is claimed
from source alone. Widget configuration and full M3/M4 acceptance remain open.

### Complete business iteration accepted by Play internal track

Main cb2efc9 passed Actions 43120365067. Automatic Play delivery 43121445107
completed successfully using the permanent upload key; logs allocate versionCode
216603660, build versionName 0.1.0-internal.19.1, and confirm successful upload
at 2026-11-11 23:45:25 UTC. This satisfies the combined iteration's internal
upload gate. Tester availability/installation, physical tracking/email behavior
and production acceptance are not inferred. Production readiness stays unset.

### M4 widget configuration repository contract prepared

Prepared schema 6 device-local widget bindings through the serialized native
repository. Each widget keeps picker mode or an active default job and a fresh
configuration token. Archived/deleted defaults remain distinguishable from a
chosen picker without retaining job names or private work. Widget IDs/bindings
are device-local and must not be included in future portable backup/merge.

Versioned configuration commands retain original outcomes. A guarded native
widget-start command replays committed duplicate taps, fences replaced/deleted
bindings, rejects stale global versions and cannot switch another running job.
Widget removal is transactional and idempotent. Schema-5 migration preserves
existing work/settings/boards/receipts and has a rollback case. Twenty-two
API-24/35 native executions are prepared, pending Actions. The launcher activity,
RemoteViews integration and physical configuration journey remain unfinished;
this contract does not mark M4 widgets accepted.

Platform references: Android widget configuration lifecycle
https://developer.android.com/develop/ui/views/appwidgets/configuration and
foreground-service widget interaction exemptions
https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start.

### Manual history UI accepted in Actions and merged

Exact #32 head 6c0f4df passed 43123810250. Inspected XML confirms 242 native
cases plus fourteen actual Android cases per API (24/35), zero failures/errors/
skips. Eight final history captures match reviewed original-resolution views.
Pending BusinessCommand controls survive eviction from lazy lists and retry
their original captured intent; the unchanged lost-response/scroll regression
passes. Merged as 863c9a0; main validation and its internal upload remain pending.
M4 widget configuration and physical manual/recovery acceptance are still open.

### Priority interruption: main Settings Save crash

Owner reports an Android process crash saving main Settings, while job settings
save normally. Prioritize reproduction/fix ahead of widget UI. Actual Flutter +
native channel + SQLite save/reopen instrumentation and synthetic logcat capture
are prepared for Actions, without assuming a cause from mocked form tests.

Email subject and body templates must both be validated safely, with field-level
feedback and correction guidance. Invalid placeholders/lengths/control characters
never bypass the native transaction guard. Use a bounded literal scanner rather
than platform-sensitive regex initialization, and refuse malformed stored templates
before invoice-number allocation. Validation/reproduction evidence is pending
in the priority settings-crash branch; see plan_status.md.

Owner refinement: new invoice numbering starts at 1000 (INV-1000) and advances
automatically. Existing prepared invoices keep their numbers/content. Older
unconsumed counters below 1000 use a floor at allocation; higher counters never
reset. Invalid template validation must not consume or advance the sequence.

### Main Settings crash reproduced and guarded source checked

Unfixed diagnostic head 2cdf329 / Actions 43128896688 reached Save settings on
actual API 24 and killed the app process. Captured AndroidRuntime trace shows
BusinessStore.saveSettings -> InvoiceEmailTemplate.<clinit> -> PatternSyntaxException
for the unescaped closing brace, wrapped in ExceptionInInitializerError. This
confirms the user's process-crash report and the source cause.

Guarded head a30d0b7 / 43128476006 passed 244 native host cases and sixteen
actual cases on each API (24/35), zero native/device failures/errors/skips,
including Settings save/reopen and both-template invalid/correction cases. The
whole run failed formatting only; final formatted source plus the requested
INV-1000 allocator and actual invoice preparation awaits 43129744209.

### Priority settings fix merged

Full Actions 43129744209 passed exact head 145870a. Final run-specific XML
contains 245 native cases and sixteen actual cases on each API (24/35), zero
failures/errors/skips. Actual Settings save/reopen and native invoice creation
with INV-1000 and correct expanded subject/body pass. Merged #34 as
c5e19d15a06ccd31e85455e5ab4782bcdf36a88d. Its main validation and Play upload
are separate and pending; production readiness stays unset.

### Priority hotfix internal delivery accepted

Main c5e19d1 passed 43131240701; run-specific XML confirms 245 native cases and
32 actual Android cases, zero failures/errors/skips. Play delivery 43132531984
succeeded with the permanent key and accepted versionName 0.1.0-internal.21.1 /
versionCode 216609104 at 2026-11-12 01:17:19 UTC. Settings crash correction, both
template guards and INV-1000 numbering are delivered to the internal track.
This does not establish tester installation or physical device acceptance.
Resume widget implementation; production readiness remains unset.

### Owner-expanded M4 widget scope

Owner requests responsive combined widgets plus separate timer-only and task-list
widgets. Small combined widgets retain only timer controls; larger widgets add a
scrollable native due-task collection. Task taps open the correct board. Implement
all three alongside default/picker configuration, engine-independent timer actions
and stale/deleted-job handling. Initial due window is today plus six subsequent
calendar days and all overdue tasks, excluding completed/archived work. Native
collections and size-aware layouts must be verified, not represented by mock
success. Physical launcher behavior remains a separate acceptance gate.

### Responsive widgets source prepared for Actions

Implemented three native providers, compact/expanded Combined layout, scrollable
due-task collection, per-widget active-job/picker filtering and Flutter setup with
immutable command retries. Task taps enter the corresponding board through a
separate activity; native clock-in/out preserves serialized timer ownership.
Restored launcher IDs deliberately reset to picker mode without starting work.
Added native date/filter/large-collection and size checks plus Flutter lost-reply
and unavailable-default checks. Prior combined foundation run 43133758021 passed;
this new source still requires its own Actions and launcher evidence. No local
builds or tests were run. Physical launcher acceptance remains open.

### Widget emulator iteration: real startup regression caught

Actions 43136781343 passed format/analyze/Flutter, Android build and 272 native
cases (zero failures/errors/skips). API 24 real host scrolling, all three providers
and task PendingIntent handoff passed. Existing Settings UI and new setup UI
correctly failed: custom initial routes were accidentally combined with `home`,
which Flutter rejects at startup. Replaced redundant `home` with an explicit `/`
route and added full-app route construction plus actual board-render assertions.
This failed run is not acceptance and no new widget build has been published.

### Owner handoff: paid invoices and accounting copies next

Owner explicitly schedules paid marking and invoice-derived PAID receipts as the
next task after the current widget merge. All invoice/receipt email drafts CC the
optional accounts-receivable email when set, otherwise the company primary email.
This scope is planned, not implemented or validated. Current widget work continues.

### Widget-host test lifecycle correction

Actions 43138373244 passed Flutter startup regression, Android build and 272 native
cases. Actual Settings save/reopen passed on API 24. The host test then detached
a live Flutter view by replacing MainActivity content, provoking a Flutter JNI
accessibility null-parent abort. Move the real AppWidgetHost into a native debug-only
activity so Flutter lifecycle is preserved; this test activity is absent from
release builds. Keep the actual service/collection/board/setup checks and strict
zero-failure gates. This failed emulator run is not widget acceptance.

### Owner clarification: invoice coverage is exclusive

Work already assigned to any invoice for the same job must be excluded from new
invoices, especially after payment. Current preparation snapshots report data
without a source-coverage ledger, so this remains explicit next-task work rather
than an existing guarantee. Implement native transactional task/interval claims,
legacy reconciliation, transparent exclusions and tests alongside paid receipts.
The original invoice is reused for resends and receipt generation.

### Owner roadmap expanded while widget checks continue

Recorded partial payments and unchanged-original balance copies; Q-1000 quotes
and reviewed conversion; unpaid/partially-paid void/replacement with conserved
credit; task comments and supplies classification with separate actual costs;
main-menu company reporting; default tax percent with explicit per-job override.
The detailed plan in docs/INVOICE_LIFECYCLE_PLAN.md defines order, states, exact
money/tax arithmetic, native coverage/payment invariants, correction examples,
PDF/email behavior, migrations, backup dependencies and acceptance checks. These
features are planned, not implemented. Current widgets remain the active task.

### Real widget setup passed; board lookup and diagnostic retrieval corrected

Partial API-24 evidence from 43140436576 confirms actual main Settings save, real
widget configuration save/cancel and all provider renders passed. The remaining
assertion expected a standalone `To do` label, while Flutter merges column title
and task-count subtitle. Match the exact visible semantic line and always finish
the monitored board activity, including failed assertions. The run then stalled
attaching post-test logcat; retain the continuous stream and bound fixture pulls.
No passing full emulator acceptance or widget publication is claimed yet.

### Actual widget choice verification strengthened

43143381176 passed Flutter/build/native checks and API-24 Settings, actual task
scrolling/board rendering and all providers. Setup intermittently left the picker
selected: pinned Flutter AccessibilityBridge acknowledges ACTION_CLICK even when
the addressed node has no TAP semantic action. Require a clickable target, search
children before merged ancestors, and verify the actual checked choice before
Save/Back. Keep the native binding and cancel assertions; do not count a transport
acknowledgement as user selection or acceptance. Full Actions rerun is required.

### First complete API-24 widget journey verified; API-35 board-body observation open

6033cbe / 43144846081 passed 272 native cases and all 19 actual API-24 cases,
zero failures/errors/skips. API 35 passed 18/19, including actual setup checked
choice/save/cancel and Settings. Its board header and 0-of-30 loaded task summary
were visible, but the column subtree was absent from cached accessibility nodes.
Refresh virtual nodes and retain a separate actual board screenshot before the
unchanged column assertion; later setup screenshots must not overwrite failure
evidence. Full API-35 board acceptance remains open, not assumed from its header.

### Collection publication and native timer-surface checks

43146802062 retained all assertions and caught an intermittent first-bind task
collection failure on API 24 before board capture. Source review identified
concurrent widget refresh callers that could publish stale size/state after a
newer refresh. Serialize refresh publication; load the remote factory on creation
as well as dataset change; key adapters by per-widget configuration token. Return
task filtering metadata in the same native read as its rows. These are source
corrections awaiting Actions, not an assertion that this failed run passed.

Also retain the configured task job's visible/accessible label when a different
job is running. Added an actual denied-location, no-Flutter-screen clock-in/out
journey through real PendingIntents and two widgets, asserting native WIDGET
origin, positive duration, no location history, and separate task-filter context.
The required actual widget class count becomes four per API (20 total per API).
All builds/tests remain Actions-only; full acceptance is still pending.

### Actual board capture and native clock-in observation

43148476659 passed 272 native cases; API-24 task binding, scrolling, actual board
rendering/column lookup, provider layouts, setup save/cancel and Settings passed.
Reviewed the separate actual board image: populated To do cards and Doing column
render correctly. The new native action case reached WidgetStartActivity and
committed its configured job, but incorrectly expected ActivityMonitor.getHits()
to count a PendingIntent launch. Observe the matched activity directly (as the
board test already does), assert no resumed Flutter screen, then continue through
the actual clock-out broadcast. Also publish final bindings before creating host
views so the test never clicks a cached pre-configuration version. Full 20-case
checks on both APIs remain pending. Launcher descriptions now distinguish all
three widget choices. No test/build ran on the VM and no widget release is claimed.

### Actual API-24 surface journey passed; API-35 reapply and pending-form bugs fixed in source

bea20be / 43150196358 passed all 20 actual API-24 cases, including real native
clock-in/out with denied location, WIDGET origin, no samples, and separate task
job context. API 35 caught a real RemoteViews reapply bug: addView appended timer
rows instead of replacing them. Clear the container before adding its one timer;
repeat real updates and assert exactly one row.

API-35 setup also exposed a commit-version-before-response race: timer snapshot
reload replaced the form, disposing its pending BusinessCommand before the save
reply and leaving controls locked. Keep prior data during reload/read failure,
key the pending command, and add deterministic delayed-read/reply Flutter cases.
Move platform widget-availability checks inside the native save command after
receipt replay so widget removal resolves an original save without resurrecting
bindings; fresh unavailable writes return a known conflict. Added native retry/
removal checks. Preserve per-test screenshots and require actual board window
focus; the column assertion stays required. New source awaits Actions.

### Board-body failure identified as a real framework assertion

4b1951a / 43153617082 passed the new Flutter save-race regressions, native checks
and all 20 actual API-24 cases. API 35 passed native clock-in/out, repeated-update
row count, separate task scope and setup save/cancel; only the board-body check
failed. Its preserved per-test screenshot shows a real `_InactiveElements.remove`
assertion in the board, not merely missing accessibility labels. Keep that test.

The board's width-only LayoutBuilder was building tooltip/overlay-bearing cards
during layout. Replace it with a normal build-phase Builder and window-relative
MediaQuery width; give vertical columns independent scroll controllers. Similar
upstream OverlayPortal/layout failures are documented at
https://github.com/flutter/flutter/issues/180569 and
https://github.com/flutter/flutter/issues/183168. This is an evidence-informed
workaround, not a claim that the app's first stack has already proven that cause.
Debug builds now forward bounded Flutter error type/stack only to local Android
logcat for CI diagnosis, preserving normal presentation and excluding field/widget
dumps; release error handling is unchanged. Apply Actions-generated formatting,
finish removed-widget setup as cancelled, and revalidate the full source.

### 2026-11-12 payment source checkpoint

Draft #37 arithmetic/native ledger head c00eb1a ran Actions 43190055836: 407
native cases, two failures in the old widget v5 migration simulation; all seven
payment arithmetic and 16 API-24/35 ledger cases passed. This is not full branch
acceptance. The simulation lowered a current database's version and left new
payment tables behind. It is replaced with an independent frozen-v5 DDL seed.
Actions also exported released v7 DDL/seed statements, frozen as schema_v7.json
with only platform-owned android_metadata omitted. New 7→8 preservation/rollback
cases are prepared; the one-time generator/export step is removed. AR/CC and
ordered ledger additions also require the next Actions run. Main/internal delivery
remains held by the separately investigated widget binding failure.

PR #37 had documentation merge conflicts after #36 landed, so its b9869b2 push
did not schedule pull-request Actions. Resolved those conflicts by retaining the
newer payment checkpoint and consolidating duplicate status rows; validation must
run on the merged branch head. No validation is inferred from that missing run.

Payment draft 6177d71 / 43192830791 stopped in Flutter behavior: the AR invalid
address and preserved-draft assertions passed, then the corrected email's selection
overlay intercepted scrolling to Save. Native checks did not run. Applied the
Actions formatter patch, configured company/AR fields for email input without
autocorrect/suggestions, and dismiss text editing before the regression scroll.
Next Actions evidence must verify these changes and the frozen-fixture migrations.

### 2026-11-12 payment baseline and immutable copy increment

811fb7d / Actions 43193679201 passed formatting, analysis, Flutter behavior, debug
build and all 414 native cases (downloaded XML: zero failures/errors/skips). This
verifies the frozen 5/7→8 migrations, ledger, AR validation and native CC baseline.
Its actual Android stage failed the known old widget initial-binding case; that is
being corrected separately in #38. This is not full payment milestone acceptance.

Next source increment retains an immutable receipt intent in the same transaction
as each payment, and a correction copy with each reversal. Explicit balance copies
freeze a command version, paid/due and historical event boundary without consuming
invoice numbers or creating cash. Original issued payload/PDF remains unchanged.
Payment events freeze received/effective labels and UTC offset as well as UTC and
IANA timezone. Customer documents exclude internal notes. Retained copy metadata
and revisions survive job purge; derived PDFs use separate private content URIs,
read-only grants and the same three native styles. Save captures the selected
bytes; email keeps current AR/company CC and guards oversized/failed handoffs.

Seven native copy cases per API, two additional handoff cases per API and one
comprehensive actual Android copy/PDF case per API are prepared. The latter covers
partial/paid/balance/correction documents in all three styles, real attachments,
and 1000-digit receipt balances, with required image/PDF artifacts and exact API-35
text checks. New source still needs Actions evidence and image review. Payment UI,
bridge wiring and credit-preserving void/replacement remain outstanding.

### 2026-11-12 widget release hold resolved at PR level

#38 c1595c7 / 43195277559 passed 385 native and 44 actual Android cases with no
failures/errors/skips. The prior factory-only change reproduced the empty third
resize even while the factory held 30 rows and the host adapter remained null.
AOSP Android-7 pre-connection notify path omitted the attachment callback; replacing
immediate notifications with revision/token/day adapter identity passed all three
resize cycles, a committed live edit and final-row/real-board checks on both APIs.
Merged 1c358bc; main and internal upload are separate gates, not yet proven here.
The payment draft now includes the tested source through a main merge.

### 2026-11-12 receipt validation and isolation correction

9562f08 / 43197625440 passed all 434 native cases (downloaded XML, zero failures,
errors/skips), plus Flutter/formatting/analysis/debug gates. The new all-style
actual receipt/correction/balance/wide-PDF case passed API 24, but the shared suite
then failed its unchanged first-invoice settings regression: six earlier receipt
fixtures had consumed INV-1000, so it observed INV-1006. API 35 was not reached.
Keep both first-number assertions and move copy lifecycle instrumentation to a
fresh process/app-data run after prior XML/captures have been retained, following
the existing financial-journey isolation. New copy run also asserts INV-1000; no
counter reset or weakened expected number is used. Revised full Actions evidence
and all-style image/API-35 text review are still required before acceptance.

### 2026-11-12 immutable-copy acceptance and payment UI increment

c21ef87 / 43201426802 passed full Actions: 434 native cases and 46 actual Android
cases across API 24/35, zero failures/errors/skips (40 native XML files and six
actual XML files inspected). Both settings and isolated copy processes preserve
INV-1000 first-number checks. Partial/paid receipt captures in all three API-35
styles were reviewed; short-copy footer pagination exposed on API 24 and balance
copies needs improvement. Source now removes redundant status/section spacing,
keeps a prominent header state, shortens the fixed copy note and requires short
financial fixtures to fit one page. Wide values still paginate without truncation.
All revised visuals require fresh Actions review; not every prior capture was
reviewed yet.

Native payment/receipt metadata and strict command bridge, invoice-list balances,
Dart 4096-digit payment parsing and the invoice detail/payment/correction screens
are prepared. One invoice journey exposes original PDFs, source review, payments,
saved historical copies, current AR/company CC and Send latest copy. Mark paid
proposes the remaining amount; payment/correction confirmation opens its saved
receipt. Native copies retain original invoice/cash identity. Stable keyed submit
controls preserve unknown-response retries when a live paid balance changes form
layout. Regressions cover exact input, response loss, concurrent refresh, transport,
retained deleted-job invoices and audited correction. A new isolated actual Flutter
journey covers AR Save, rejected precision, partial/full payments, corrections,
real receipt views/To/CC/attachment and settlement after job purge. Existing legacy
review navigation is updated for the unified detail screen. All new UI/bridge and
layout source needs Actions evidence. Void/credit-preserving replacement remains
unimplemented and blocks merging the complete payment milestone.

Payment UI cea30dc / 43205517843 stopped at analysis with five missing-brace lint
findings; behavior/build/native/device stages did not run. Applied the Actions
formatter patch and fixed all five reported control-flow blocks. Revalidate the
same prepared UI/bridge/layout journey in Actions; no VM formatter or tests ran.

Payment UI 3731b80 / 43206450476 passed formatting and analysis but remained in
Flutter behavior tests during observation. Source review identified two test
cleanup paths closing a broadcast stream while offstage Riverpod subscribers can
remain paused. Dispose the ProviderScope first, then close the stream; preserve
all exact-money, command identity, retry and navigation assertions. This is a
test-lifecycle correction, not evidence of a passed behavior/native/device gate.
Fresh Actions validation is required; no local builds or tests ran.

### Native void/replacement source checkpoint (2026-11-12)

Prior turn made progress: pushed 7d414ee to correct paused-stream test cleanup and
updated draft #37 with accurate scope. Actions 43207931777 now passed formatting,
analysis, Flutter behavior, debug build and native unit gates; actual Android
document/payment journey remains live. Do not treat the whole run as passed.

New native source prepares confirmed/audited unpaid cancellation and atomic
replacement with edited existing rows, explicit owned-claim release and additions
from actual unbilled work. The default keeps the entire old claim bundle; old line
titles/prices are never used to guess source identity. Original documents remain
unchanged, cancelled predecessors have no collectible balance, and unresolved
legacy coverage stays unresolved on successors. Replacement freezes existing
credit at issue. Positive fully paid invoices reject void/replacement; a zero-total
Nothing due invoice may be cancelled to release reservations. Below-credit totals
are rejected. Transfers preserve payment IDs/dates and emit zero-cash, ordered
credit/tax/sales adjustments; corrections follow the current allocation. Cancellation
copies are separate immutable intents. Native regressions cover concurrency, replay,
rollback, exact wide/split money, source ownership, hourly intervals, legacy and
retained deleted-job history. Source only: new Actions validation, lifecycle bridge/
editor UI, actual replacement/VOID PDFs and physical email acceptance remain open.
This is still draft #37, not a completed or published payment milestone.

7d414ee / 43207931777 passed formatting, analysis, Flutter behavior, debug and
440 native cases (41 XML files, zero failures/errors/skips). Actual API 24 passed
21 original and one billing journey; copy generation then failed the explicit
one-page assertion on the short Classic paid-balance copy (two payments). The
new payment UI journey and API 35 were not reached. Reviewed the actual Classic
paid receipt PNG: values are correct and readable, but duplicated event detail
layout needs less vertical space. Copy-only renderer source now uses a full-width
compact event-detail paragraph and less header spacing; original invoice layout
and immutable copy payloads are unchanged. Preserve the one-page assertion and
all complete-value checks. New validation is required.

Lifecycle bridge source now exposes exact editable frozen rows, active claim IDs,
predecessor/successor links, immutable cancellation details and each payment's
current allocation. Strict void/replace commands preserve string amounts, integer
versions/tax and explicit confirmation; two additional native bridge cases cover
transport rejection and credit/relationship/cancellation-copy round trips. Native
replacement build 43209333407 remains live; no new native result is claimed yet.
Lifecycle editor UI and actual VOID/replacement PDF/interaction acceptance remain
next. Financial UI must route original receipts to their original invoice and
corrections to the current invoice, and must not offer collection on VOID records.

Replacement ed94bfc / 43209333407 stopped at native compilation: a refactored
ledger read still referenced the removed local `total` for tax allocation. Source
now uses `balance.total`; no arithmetic policy changed. Native lifecycle tests and
actual replacement/VOID documents have not passed. Superseded queued/intermediate
commits share this identified compile error; validate the corrected head in Actions.

551a748 / 43210091600 also stopped at native compilation: the full-width copy
paragraph passed a Float width to the existing Int layout API. Corrected the
width conversion; preserve exact content and the one-page check. Added late
VOID-copy failure injection to prove rollback after credit has already moved,
and cancellation immutability/retention across a subsequent job purge. These new
cases still require Actions evidence; no native lifecycle success is claimed.

Actual Android document test source now extends every invoice style through
partial-payment replacement: immutable VOID copy, new invoice with credited
payments at issue, final settlement receipt, frozen old PDF/receipt bytes, exact
API-35 extraction and actual To/CC/private attachment reads. The Actions copy gate
requires all 24 PDF/PNG pairs per API, including nine new lifecycle captures, while
retaining the short-document one-page and 1000-digit completeness assertions.
These are prepared cases, not acceptance; current 40259d7 / 43211073661 is live
on the preceding source and the extended document cases need their own run.

Native balance review removed a second competing due accessor: `balance.due`
itself is now zero for VOID records, while the original total and immutable
credited/cancelled split remain retained. This prevents a future native consumer
from accidentally collecting a cancelled predecessor by reading the ordinary due
field. Added a direct pure arithmetic regression and kept the native/index/bridge
zero-due assertions. This follow-up requires Actions validation too.

### Verified native lifecycle checkpoint

40259d7 / 43211073661 passed formatting, analysis, Flutter behavior, debug build
and 476 native tests. Downloaded/inspected 42 native XML files: zero failures,
errors or skips; InvoiceLifecycleTest has 32 executions and InvoicePaymentBridgeTest
has 10 across API 24/35. This validates native cancellation/replacement, ordered
zero-cash credit/tax changes, command replay/concurrency, early/late rollback,
coverage/legacy/retained history and bridge baseline. Real Android stage remains
live. Subsequent 7d84a5b adds the single ordinary due-field guard and all-style
actual lifecycle PDFs; those additions do not inherit acceptance from this older
head. Lifecycle editor/relationships and complete physical acceptance remain open.

### Lifecycle editor and actual UI checkpoint

Previous goal turn made concrete progress: native cancellation/replacement and
strict bridging were committed, 476 native XML results inspected, and current
source/remaining acceptance recorded. No milestone was marked complete.
40259d7 / 43211073661 completed 48 actual Android executions: API 24 all 24
passed; API 35 original/billing/copies passed but the payment UI missed Partially
paid after Back (47 pass / 1 fail total, no errors/skips). Actual screenshot shows
correct 30.00 paid / 77.50 due, with the status above the viewport. The test now
waits for the actual invoice-detail route before scrolling, preserving all balance/
status assertions. Short-copy one-page gates passed on both APIs. New baseline
ad3f06b / 43212272285 passed 477 native cases (42 XML files inspected, zero
failures/errors/skips); extended actual document/UI validation remains separate.

Prepared lifecycle UI now edits frozen descriptions/quantity/unit price or flat
amounts, previews exact subtotal/tax/credit/due, adds actual unbilled report work,
explicitly reviews source releases and requires a red confirmation before native
commit. Cancellation/Back writes nothing. Live version changes keep the draft but
require reviewed refresh; unknown replies retain the same immutable command across
a VOID transition. Invoice details link predecessor/successor, route receipts to
the original invoice and corrections to their current allocation, and expose VOID
credit/cancelled amounts without collection actions. Historical view/email/share
adds a current VOID warning without rewriting old PDF bytes or printing private
reasons. Added presentation/math/immutable-retry and native handoff regressions;
actual PDF checks also verify historical attachment retention. This new UI/handoff
source requires fresh Actions formatting/analysis/tests/build/device proof and
visual review. Complete payment milestone remains draft #37.

Actual UI source now extends the isolated financial journey through reviewed
partial-payment replacement, cancelled review with no writes, preserved payment
ID/credit/tax, original-owner receipt with historical notice, predecessor VOID
details/copy, and confirmed unpaid cancellation with released unbilled work.
Fifteen actual UI captures per API are required alongside the 24 all-style PDF
pairs. Cancellation view has its own title and zero-due explanation. New money/
tax fields disable Flutter's default max-length truncation; oversized pasted
values remain intact and fail exact parsers. Added a regression for a valid-looking
prefix followed by over-limit content, preventing a truncated valid prefix from
being saved. All new UI/format/handoff/interaction evidence remains pending.
The intermediate ad3f06b run's live Android stage is superseded by this concrete
Back-synchronization/editor/actual-flow source, not treated as a timeout failure;
its inspected 477 native results remain the accepted baseline.
