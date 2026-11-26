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
| Public source | Open source text for audit and personal builds; not a free license, so the built app and forks may not be published, redistributed, sold or used commercially |
| Reuse intent | Private personal builds and changes permitted; redistribution/commercial reuse require permission; contributions permitted |
| Timer | Exactly one active job, editable timesheets and manual entries, local change history |
| Location history | Recording on by default; global setting with per-job inherit/on/off; ten-minute target with a five-minute ordinary-sample floor; only while clocked in |
| Automation | Multiple circular sites per job; manual actions win; configurable departure grace and paid-travel mode |
| Boards | Custom columns and reorderable cards per job |
| Maps | Keyless OpenStreetMap tiles by default; optional user-configured custom HTTPS raster tiles; Google Maps deferred beyond launch |
| Reports | Calendar/date range, list, map, job filters, CSV and optional separate GPS CSV |
| Backup | Password-encrypted full export; previewed conflict-aware merge restore |
| Release automation | Main to internal testing; version tags to production submission once setup is ready |
| Agent artifacts | Public `AGENTS.md`; local skills ignored by Git |

The source-available terms are now published as the repository `LICENSE` (Clockrove Source-Available License 1.0) with a mirrored reader page at `website/legal/license.html`; the source is open for reading, audit and personal builds, but the terms are not a free or OSI-approved license: the built app and any fork may not be published, redistributed, sold or used commercially, and only GitHub contribution forks are permitted. Do not quietly choose MIT/GPL/Apache in place of the owner's selected rights. The owner must still confirm the legal identity, contact address, effective date, governing-law statement and counsel review before store publication, and the name check and store identity/account setup remain readiness gates. The Privacy Policy, Data Use Policy and Terms of Use are published under `website/legal/` as the pages the Play listing links to.

## 3. User-facing behavior

### 3.0 Intuitive first-use experience

The owner's usability bar is that the interface should be “so intuitive a
trained monkey could use it.” Translate that into product behavior: a first-time
user should be able to complete common tasks without training or guessing. Keep
labels plain, make the next useful action obvious, use familiar and consistent
controls, and reveal advanced choices only when they are relevant. Explain
validation and permission failures in terms of what happened and how to fix it;
do not make users infer hidden gestures or recover from silent failures. Review
each feature's main path from a first-time user's perspective, and include
representative first-use journeys in the full-release E2E and screenshot review.

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
- Every widget has a transparent outer background with a visible, restrained
  border; do not render an opaque card as the widget surface. Keep text, icons
  and actions readable against varied launcher wallpapers in light and dark
  conditions, and preserve comfortable spacing and tap targets. Review rendered
  captures for each widget type at compact and expanded sizes against contrasting
  wallpapers before release.
- When idle, default-job mode starts that job. Picker mode opens the app to active jobs, with **New job** last.
- If the default job was archived or removed, open the picker with an explanation.
- When a timer is running, Timer and Combined widgets show the actual running job and offers clock-out, regardless of its configured default.
- An Android ongoing foreground-service notification shows the active job and clock-out action while location tracking is active.
- All actions enter the same serialized native command path. Deduplicate repeated taps and callbacks using command IDs and committed state; do not trust a widget's stale snapshot.
- Refresh widgets after committed changes, restore, configuration changes, and recovery. Display elapsed time from timestamps, not a counter that must wake every second.

### 3.3 Location recording and automation

- Main Settings has a **Record work locations** preference, enabled by default.
  Each job inherits that setting or explicitly overrides it on/off. Changing the
  effective setting while clocked in reconciles the location foreground service;
  turning recording off stops future collection but does not silently delete
  previously saved work samples. Manual timekeeping remains available without
  location permission, with recording disabled, or offline.
- Request fused-location updates on a ten-minute target while recording is
  enabled and a session is running. Persist no more than one ordinary sample in
  any five-minute window; a first/last session boundary fix may be stored without
  delaying clock-in/out. Android may deliver later or miss samples, so show actual
  timestamps and accuracy and never imply guaranteed continuous tracking.
- Save measured timestamp, reception timestamp where useful, coordinates, accuracy, and session association. Do not insert synthetic points for missed intervals.
- Save measured timestamp, reception timestamp where useful, coordinates, accuracy, and session association. Do not insert synthetic points for missed intervals.
- Use Android fused location, a location foreground service, and the platform geofencing API. Ordinary background timers and periodic WorkManager jobs are not evidence of ten-minute GPS delivery.
- Explain the purpose of location access before requesting it. Request background access separately when enabling geofencing. Show an honest degraded state for denied permissions and platform restrictions.
- Monitor enabled geofences while idle without keeping an off-clock trail. A fresh current-location fix saved for a site seeds membership only when its accuracy supports that classification, and that save starts work when the job is armed inside: the worker has not moved, so no repeat entry callback is required. The start is an automation session, not a fabricated arrival, and it never becomes off-clock session history. Keep raw fix accuracy and measurement time out of command receipts; coordinates remain the saved site center.
- Sites are circles selected by map tap, current location, or coordinates. If a site has no usable coordinates, opening the map first explains and requests one current-location fix, then centers on it; denied permission or a missing fresh fix leaves coordinate entry and the separate current-location action available with a clear message. Default radius 150 m for new sites. Existing radii are kept; supported initial range 1.8–10,000 m (about 10 m² at the lower bound). Explain that Android recommends 100–150 m for dependable geofence detection; small radii are supported for small properties but can miss automatic arrival/departure, and manual timekeeping remains available.
- Jobs support multiple sites. Enforce Android's active registration limit of 100 sites across the app with a visible capacity message; do not silently drop registrations. Recheck platform limits when implementing iOS.
- A job is considered inside if any of its enabled sites is inside. Exiting one of several overlapping sites does not count as leaving the job.
- Auto-start only when enabled, no session is active, and exactly one eligible job matches. If several match, post a selection notification/deep link; do not launch an activity unexpectedly from the background.
- Geofence events never silently change the running job.
- At manual clock-out, suppress auto-start for that job until the device has exited all its sites and subsequently re-entered. Persist this suppression. A later explicit manual start is allowed.
- Turning on automation while the last observation is inside starts work at that moment, in either order with setting a current-location fix: configuring the area you already stand in is the entry. With no observation and no usable fix, a save only records configuration and the next qualified entry starts work. Known outside state arms the next arrival, including when a job is re-enabled while away.
- A manual stop, an unresolved history review, an already open session and a disabled or non-starting policy all take precedence over the configuration start. Automation never switches a running job. The automation screen reports the resulting state instead of asking the user to confirm a start that just happened.

Each job selects one departure mode:

1. **Automatic departure:** configurable whole-minute grace from 0 through 1,440 minutes, default 30. Start grace only after Android reports a qualified departure from all job sites; grace does not poll for or guarantee delivery of an exit event. Persist a departure candidate when leaving all job sites. Re-entry into any site for the same job before expiry cancels it. Otherwise finalize at the detected departure timestamp, not at expiry. Zero means no intentional grace.
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
- Add private chronological comments on each task, with clear add/edit/delete
  actions and created/edited timestamps. Comments stay local, are not copied into
  task templates or customer documents, and introduce no accounts or assignments.
- A comment or task can attach an existing image or start the camera. For a new
  photo, launch Android's camera intent with a private temporary output URI, then
  show the captured image in a review screen with **Retake**, **Cancel** and
  **Attach photo**. Do not add the image to the task/comment until the user taps
  **Attach photo**; cancel/retake removes the temporary capture. Existing-image
  selection allows up to 10 photos per batch and shows every selected image in a
  swipeable review before import. **Attach photos** stores the batch as one
  transaction; a failure adds none of them. Source PNG/JPEG/WebP files may be up
  to 50 MB and 250 megapixels, with each dimension bounded at 32,768 pixels.
  Downsample and re-encode accepted images to metadata-free, private PNGs no
  larger than 1,024 pixels on either edge and 5 MB each. Limit a batch to 30 MB
  normalized, a task to 20 photos, and a job to 100 MB of photos. Show previews
  and allow removal. Opening any photo shows all task and comment photos in
  chronological order; swipe left for the previous photo and right for the next
  one. Deleting a task or comment deletes its owned images transactionally;
  templates never copy comments or images. Include attachments in encrypted
  backup with strict archive limits. No broad storage permission is required.
- Keep the Supplies toggle on task entry/edit as a charge classification. Full
  cost and tax-report behavior remains in section 3.5a and the lifecycle plan.

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

The ordered owner-requested contract is [docs/INVOICE_LIFECYCLE_PLAN.md](../INVOICE_LIFECYCLE_PLAN.md).
The invoice lifecycle, payments/receipts/CC/void-replacement, quotes,
quote usability, task comments/images, map repair and GPS controls are merged.
PR #44 adds invoice-only hourly minimum/rounding and task-rate minimum-fee
policies; its exact-head Actions passed and it is squash-merged as
`48fe0f89727bc017c4d8b30f8fd60f4915b16cb6`. Main validation `43574603359`
failed in the API-24 billing instrumentation journey when the test could not
scroll back to the job-tax field; the captured app remained on Job settings and
did not show an application crash. The automatic Play delivery run
`43575491279` was skipped, so that merge has no new internal-upload evidence.
The test helper now uses real scroll gestures and must pass a fresh Actions run
before the main baseline is considered green. PR #45 adds task Labor/Supplies
classification, expanded task-photo handling, report-map repair, leading-
decimal payments and quote customer management. Its exact-head Actions run
`43590405687` passed formatting, analysis, Flutter behavior tests and Android
build, but the first native run had 15 of 594 failures: stale v12 schema
assertions, a retry test that changed its expected state version, and full-BLOB
reads exceeding SQLite's cursor window. The next run corrected the schema and
retry failures, reducing the native failures to four, all where chunked image
reads returned no image. At head `1e568f1` / run `43592371710`, formatting, analysis, Flutter behavior,
Android build and all 594 native tests passed, including exact-byte reads above
2 MB. The following real-Android instrumentation compilation failed because the
synthetic gallery fixture chained `Intent.setClipData`, which returns `void`.
The fixture now sets clip data and URI permission flags separately. At head
`372c15a` / run `43593950549`, the quote customer add/save/return/select Flutter
test passed along with formatting, analysis, Android build and all 594 native
tests; PDF instrumentation exposed a stale expected `LogoImagesTest` count.
That count is corrected to five. At head `0a06901` / run `43595000940`,
formatting, analysis, Flutter tests, Android build and all 594 native tests
passed, as did the first 25 real Android PDF tests. The subsequent API-24 billing
journey failed because its return-to-top helper never exposed **Billing basis**
after invalid tax input, so later emulator journeys and API 35 were skipped.
The quote customer test itself passed; no Android-specific quote customer
journey ran. Actual-cost records,
tax-preparation reports and secure map-provider settings remain in M6.6. M7
encrypted backup follows. M7.6 is the required whole-app UI overhaul and
exhaustive end-to-end/screenshots gate after M6.6 and M7; it must pass before
closed testing. Physical map/GPS/camera acceptance remains separate from
emulator evidence.

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
- Main Settings also has invoice-only hourly billing policies with global defaults
  and per-job inherit/override: a billing mode, plus a minimum billable-hours
  amount (0 disables the minimum). Provide both **minimum hours, then exact time**
  (5 minutes with a 1-hour minimum bills as 1 hour; 1 hour 1 minute bills as
  1 hour 1 minute) and **round total time up to the next hour** (5 minutes bills
  as 1 hour; 1 hour 1 minute bills as 2 hours). Apply these only when generating
  hourly invoices. Recommended scope is to aggregate the exact, unbilled selected
  seconds per job/rate on that invoice. In exact mode use `max(minimumHours,
  exactSeconds / 3600)`; in round-up mode use
  `max(minimumHours, ceil(exactSeconds / 3600))`. Show exact worked duration and
  billed hours separately so the invoice is transparent.
  Main Settings has a separate optional minimum fee for task-rate invoices, also
  independently inherited/overridden per job; if a nonempty task invoice subtotal
  is below it, add a clearly named adjustment line to reach the minimum. A zero
  minimum disables it. Use exact decimal strings for settings and calculations,
  apply the normal invoice tax policy to any fee adjustment, and do not alter task
  prices or source claims. Keep all three controls out of quotes unless requested
  separately. Session timestamps, actual hours, GPS, history, widgets and time
  exports remain exact and unmodified; reports distinguish actual from billed
  hours. Snapshot effective policies on each issued invoice so later setting
  changes do not mutate documents; resending/receipts/balance copies reuse the
  snapshot. A void/replacement flow must preview any changed billable quantities,
  minimum-fee adjustment and paid credit before confirmation. Adopt the recommended
  scope: aggregate selected unbilled work by job and effective hourly rate once
  per invoice; apply task minimum fees per job per invoice. This keeps repeated
  sessions from multiplying minimums or hour round-ups.
- Main Settings has optional accounts-receivable email. Invoice/receipt/quote
  email drafts CC it when set, otherwise company primary email. Fill default-email
  To/CC/subject/body/PDF safely; the user sends. Keep both template guards.
- Invoice, receipt, correction, replacement and quote PDFs place customer billing
  details on the left and company details on the right. Each text column is at
  most 47% of printable width; wrap long names/contact/address text within its
  column and paginate overflow. Align issued date with the company column. Align
  subtotal and tax rows to the invoice's right edge; keep labels and tax rate
  regular weight while bolding only the subtotal/tax amount digits. Review all
  three styles and actual rendered copies in Actions.
- Put **Preview invoice** directly below the Invoice style selector in main
  Settings. Open a paginated preview rendered by the real native PDF renderer,
  using the currently selected style/currency, the saved company logo if present,
  and synthetic example content for company/customer/contact, job, dates, line
  items, subtotal, tax, total, header and footer. Mark it clearly as sample data;
  preview must not save an invoice, reserve work, consume a number or open email.
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
- Google Maps is deferred beyond launch by owner decision dated 2026-11-15. It is not a launch feature, launch acceptance criterion, or launch gate; do not expose a Google SDK key setting or claim Google Maps availability at launch.
- Store credentials in Android Keystore-backed protected storage. They cannot be treated as unextractable secrets when used by a client SDK; provider restrictions remain necessary. Never include them in logs, CSV, backup, or Git.
- The site-centre picker offers an explicit, user-triggered address lookup through the keyless OpenStreetMap Nominatim service. It works with the default OSM tiles and with a user-configured custom HTTPS tile provider, sends only the query the user submits, rate-limits requests, caches results in memory, and shows OSM attribution. No address autocomplete, routes, Street View, satellite or offline download feature at launch. Basic site placement needs no paid API or account.
- Map requests reveal viewed map areas and network metadata to the provider; reflect this in the privacy policy and store disclosures. Do not claim the app has no network data flows.
- Retain GPS until deletion. Allow deleting GPS by an inclusive local-calendar date range independently of timesheets, scoped to the current job or all jobs. Resolve the selected timezone's calendar boundaries to a UTC half-open interval over measured timestamps. Preview the point count, preserve sessions/timesheets, and refresh history/map/report displays after the committed deletion. Persist immutable `location_deletion_ranges` coverage (a null `job_id` means all jobs) and per-known-point tombstones in the same transaction. Existing exported files/backups are not retroactively erased; tell the user.
- No analytics or remote crash reporting initially. Exclude sensitive databases/credentials from unencrypted OS backup pathways; user-controlled encrypted export is the supported backup flow.

### 3.7 Encrypted backup and merge restore

- Export a versioned password-encrypted archive of jobs, sites, automation settings, boards/cards/comments, sessions, location points, invoices/coverage/payments/receipts/replacements, quotes, expenses, revisions, and deletion markers.
- Use a maintained implementation of authenticated encryption (AES-256-GCM) and a salted memory-hard password KDF (Argon2id). Pin a compatible library and parameters after testing on the minimum supported device. Store version/KDF parameters in a bounded authenticated envelope; reject excessive attacker-supplied work factors before deriving keys. Do not invent cryptographic primitives.
- Exclude map keys, caches, device widget configuration, and live automation registration state. No password recovery; explain this before export.
- Stop timers before restore. Backups may contain an open/provisional session captured at backup time; require an explicit close-time choice or omission during preview. Never turn it into a live timer automatically or invent an end time.
- Use size/count limits and schema validation; authenticate before parsing decrypted content. Reject unsupported future versions and invalid references without changing local records.
- Stable IDs deduplicate identical entities. Model revisions with parent revision IDs, including merge parents, so ancestry can be distinguished from divergent edits; wall-clock `updated_at` alone is insufficient.
- Automatically accept unambiguous descendant revisions. Require local-versus-backup resolution for divergent content or deletion conflicts; keep both parents in a merge revision.
- Treat GPS as immutable identified points except deletion. Point revision payloads contain only point/session IDs, never coordinates or measured timestamps. Range deletions need revisioned tombstones/coverage so an older backup does not silently resurrect intentionally removed points. A newly recorded point links to every applicable covering range revision as a causal parent. During restore, a covered incoming point without provable ancestry after the deletion is an explicit preview conflict; preserve the deletion by default and require an explicit resolution. Do not compare state versions, counters, or wall-clock timestamps across archive databases to infer ancestry.
- Preview proposed additions, changes, deletions, unresolved references, and time overlaps. Distinct overlapping sessions require choosing a record or correcting intervals. Do not auto-double-count.
- Commit the fully validated merge transactionally; a failed/interrupted merge leaves the previous database intact. Guard against source data changing after preview.
- Keep imported jobs' geofence automation disabled until the user explicitly enables it. Refresh UI/widgets after commit. Do not merge credentials or device registrations.

### 3.8 Owner-requested business and combined test-release scope

The binding additional contract is [docs/NEXT_TEST_RELEASE.md](../NEXT_TEST_RELEASE.md).
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
