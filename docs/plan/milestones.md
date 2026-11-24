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
- Prove default OSM rendering and user-configured custom HTTPS raster tiles, including attribution, credential handling, provider restart, and failure behavior. Google Maps is deferred beyond launch.
- Run physical-device screen-off/battery tests before committing to the full UI. Verify current foreground-service start rules for manual and geofence entry paths.
- Acceptance: measured callbacks and session transitions on a real device; verified widget operation without Flutter running; OSM and custom HTTPS provider behavior demonstrated. If a device is unavailable, mark physical validation blocked and proceed only with independent work—never mark the prototype validated.

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
- The base board/calendar/map/CSV foundation is implemented; user-reported map
  blank screens and GPS controls are addressed in M6.5 below.
- Acceptance: board operations preserve cards; list/map/export filters agree; DST/range-boundary totals are correct; provider outage does not block local work; CSV formula/escaping tests pass.

### Milestone 6.5 — quote usability, task comments and images, maps and GPS controls

Implement this owner-prioritized milestone immediately after the merged quote
baseline. It is one complete vertical slice; scaffolding or mocked native flows
do not satisfy acceptance.

- Make quote entry quick and obvious. Provide a searchable task-template picker
  over reusable templates from active, archived and deleted jobs. Selecting a
  template creates an ordinary editable quote line; users can change title,
  details, quantity and unit price without mutating the template. New quote lines
  remain available. Keep titles/line descriptions required where needed to
  produce a meaningful quote, but notes/terms are optional everywhere; never
  require a note to accept or decline. Explicit confirmation records the decision
  against the issued revision. Email/PDF viewing is never treated as acceptance.
- After acceptance, offer a plainly labeled **Create job from quote** action.
  Pre-fill a new job's board with the accepted quote's task items, quantities and
  agreed prices, preserving quote/line provenance. Let the owner review job name,
  customer, billing mode, tasks and destination before committing. Also retain an
  explicit **Add to existing job** path for a deliberate destination. Repeating
  either conversion reuses the existing mapping and cannot create duplicate
  jobs/tasks, worked time, completion, invoices or payments.
- Add durable task comments and task/comment image attachments per section 3.4.
  Test adding, editing, deleting, empty/cancelled/error states, gallery selection,
  multi-image selection and one-command atomic attachment, camera capture, review
  acceptance, retake, cancel, EXIF removal, private URI access, expanded image
  limits, previous/next full-screen photo swipes, parent deletion and
  migration/restore behavior.
- Repair every map entry point (site picker, current-location selection and
  report history) so it never becomes an unexplained white screen. When a site
  has no location, request permission/fix with a clear prompt and center on the
  user's current position if available. With coordinates, center on that point.
  Test loaded tiles, slow/failing/offline tiles, permission denial, invalid/polar
  coordinates, retry and external-map fallback; map failure must not block jobs,
  manual timekeeping, reports or exports.
- For the owner-reported report-map blank screen, test the actual job → report →
  map route on API 24 and 35 with 49 synthetic persisted GPS measurements. Wait
  for a decoded tile or a clear unavailable state, keep the selected coordinates,
  route overlays and attribution visible, provide an external map action, and
  capture the real Android screen. Deterministic tile-error widget coverage must
  confirm failed tiles render a visible fallback instead of a white surface.
  Tile provider headers must be mutable per screen because flutter_map injects
  its package User-Agent while initializing the tile layer.
- Add the global GPS recording preference and per-job inherit/on/off override
  from section 3.3. Verify default-on, permission-degraded states, clock-in via
  manual/app/widget actions, service reconciliation after changes, ten-minute
  request configuration, five-minute persistence throttling, exact real
  timestamps/accuracy, and no off-clock/post-finalization samples. Sampling
  limits and location-history pruning must share the serialized native boundary.
- Acceptance requires migration tests from the released v9 database, native
  storage/rollback/privacy tests, Flutter behavior tests, and actual Android
  journeys on API 24 and current stable API. Capture every distinct M6.5 screen
  and option state from Actions, inspect the images, and record exact commit/run/
  artifacts. Physical GPS sampling and camera hardware claims remain separate
  from emulator evidence and must be recorded honestly.

### Milestone 6.6 — complete remaining requested business features

Finish the planned launch features that remain after M6.5 before declaring the
application functionally complete. This includes invoice-only hourly
minimum/rounding modes and task-rate minimum fees; the Supplies classification,
actual cost ledger and main-menu tax-preparation reports. The OSM/custom HTTPS
map-provider settings are implemented; only physical owner-device acceptance
remains for that map scope. Follow the detailed contracts in sections 3.5a/3.6 and
`docs/INVOICE_LIFECYCLE_PLAN.md`. Test native calculations, exact money, tax and
cost reconciliation, settings inheritance, migrations, provider failures and
report drilldowns. Do not classify an unimplemented item as covered by UI tests.

Source progress: task Labor/Supplies classification, the actual-cost ledger and
filtered business financial reports are implemented on PR #45. The ledger has
schema v14, exact-money CRUD, soft deletion/revision history, equal-timestamp
keyset paging, and a Reports editor reachable from the main menu. Cost entries
support optional job/task/vendor/reference/notes; Supplies task classification
does not book costs automatically. Schema v15 records customer snapshots for
new costs while migrated historical costs remain unattributed. Reports include
exact currency-grouped summaries, event-date drilldowns, job/customer filters,
and CSV/PDF export; CSV amount columns preserve exact minor-unit integers as
text and PDF export is bounded.

Exact code head `c9d20cc` / Actions run
[43610282595](https://github.com/centerionware/Clockrove/actions/runs/43610282595)
passed formatting, analysis, Flutter behavior, Android debug build, 614 native
tests, PDF generation/rendering, invoice-viewer checks, and 35 actual Android
tests each on API 24 and 35 with zero failures/errors/skips. The API-24 billing
settings scroll journey and the 49-sample map route both passed on API 24/35.
The report tests and exact-money/cost migration checks passed in CI. Secure
custom raster-provider settings are implemented; API-24/35 emulator evidence is
recorded in `plan_status.md`. Google Maps is deferred beyond launch by the owner
decision dated 2026-11-15. Physical owner-device acceptance of OSM/custom HTTPS
behavior remains open.
The first exact-head run on `040961a` passed format and analysis but failed
Flutter behavior tests before Android build/native/device stages. The follow-up
at `309996e` also passed format and analysis and reduced the Flutter failures
from 19 to 6 (187 passed, 14 skipped). Remaining failures exposed synthetic
map tests querying `FlutterMap` before the asynchronous provider-settings load
had materialized it, plus two settings-form assertions. The provider-form
message/scroll assertions and map test sequencing are corrected; exact-head
Actions must validate the changes.

The Android provider-settings source adds an optional custom HTTPS raster-tile
provider beside the keyless OSM default. Provider templates, attribution and
header fields are validated; public settings snapshots contain only a credential
presence flag, while the secret is retrieved separately for tile requests.
Authentication travels in request headers only, redirects are disabled so
credentials cannot cross hosts, and transport diagnostics redact URLs. Android
stores the complete configuration as AES-GCM ciphertext under an Android
Keystore key in app-private storage; reset removes both. If protected settings
cannot be read, maps fall back to OSM.

Owner decision dated 2026-11-15: Google Maps is deferred beyond launch, is not implemented, and is not a launch acceptance criterion or gate. Launch map providers are keyless OSM and the user-configured custom HTTPS raster provider. The technical record below is retained as historical feasibility evidence.
The official [Flutter configuration guide](https://developers.google.com/maps/flutter-package/config)
and [Android project setup](https://developers.google.com/maps/documentation/android-sdk/config)
configure the Android key as `com.google.android.geo.API_KEY` application
manifest metadata. `MapsInitializer` exposes initialization methods that take
an Android `Context`, not a runtime key
([Google Play services reference](https://developers.google.com/android/reference/com/google/android/gms/maps/MapsInitializer)).
No officially supported runtime-key initialization path for the standard
Flutter/Android Maps SDK integration was found. A native platform-view adapter
would still use that SDK and would not resolve this constraint.

Do not accept a Google SDK key in launch settings or claim Google Maps is
available at launch. The runtime-key research above remains relevant only to a
post-launch reassessment, which requires a separate explicit owner decision. For
launch, use the working keyless OSM and protected custom HTTPS raster providers.

### Milestone 7 — encrypted backup and safe merge

- Select and pin compatible crypto/KDF libraries; benchmark supported minimum devices and define bounded format parameters.
- Implement export envelope/schema, restore preview, revision ancestry resolution, deletion/overlap conflicts and transactional commit.
- Exclude secrets and disable imported automation; resolve open backed-up sessions explicitly.
- Acceptance: round-trip, conflict, wrong-password, corrupt/tampered archive, malicious-size/KDF, interrupted transaction and duplicate import tests pass without partial writes or credential leakage.

### Milestone 7.6 — whole-app UI overhaul and exhaustive end-to-end verification

After M6.5, M6.6 and M7 have implemented all launch functionality, review and
overhaul the complete application for an intuitive first-use experience before
entering closed testing.

- Inventory every feature, screen, navigation route, setting, selectable option,
  permission state, empty state, success/error flow and destructive confirmation.
  Simplify labels, ordering, affordances and recovery guidance across the whole
  app; preserve the product contracts and native data invariants.
- Create an actual-app end-to-end path for every inventory item. Assert native
  persistence and important side effects, not only rendered labels. Cover fresh
  install and upgrade, light/dark and large text, cancellation/retry, invalid
  inputs, permissions, map outages, image picker/camera review, quote conversion,
  timekeeping, widgets, invoices/payments, reports, export and restore.
- Run on API 24 and current Play-supported stable API with synthetic data. Save
  a reviewed screenshot for every screen and distinct option/state, keep
  per-case results and sanitized logs, and fix every unexplained failure or
  usability blocker. All test/build/render runs execute in GitHub Actions.
- **Closed-testing gate:** do not move the app from internal to closed testing
  until all launch features and this full E2E/UI acceptance pass. Internal
  iterations may continue while implementation and validation are underway.
- Add an offline Help flow in Settings, with links from the relevant screens.
  Cover first use, location permission and geofence recovery, backup and restore,
  data controls, and support. Include app-version information. Verify navigation,
  offline use, large text and TalkBack in the M7.6 inventory.
- Include versioned Terms of Use, Privacy Policy and Data Usage Policy in Help.
  Keep the bundled text and public site in sync. Review actual app and map-provider
  data flows before publishing the policies and completing Play disclosures.
- Reuse this complete inventory for the final public-release gate below; the
  static Cloudflare Pages documentation site remains a full public-release task.

### App localization — release readiness

- Bundle offline catalogs as `localizations/<language-tag>.yaml`. Default to
  device language preferences, fall back to English, and persist a manual
  language override until the user returns to the device default.
- Migrate all static user-facing copy, including accessibility labels, errors,
  Help, native notifications, widgets, and built-in document text. Preserve
  user-authored content and stable data identifiers. Use placeholder and plural
  rules for dynamic copy and localized formatting for displayed values.
- Accept only after catalog parity, device-language changes, override
  persistence and reset, right-to-left layouts, and native surfaces are verified
  in GitHub Actions and on the release device matrix.

### Milestone 8 — release readiness and automatic Android delivery

- Finish accessibility/theme/error states, privacy/support docs, store assets and disclosures, and contributor onboarding.
- Before closed testing, publish the reviewed privacy policy at a stable
  public URL on the Cloudflare Pages site, link it from the app and Play
  listing, and complete the Play Data safety declaration for the signed
  candidate. Keep the approved Terms of Use, Data Usage Policy, and support
  contact available from in-app Help and the site. Use the
  [Play Data safety review](../policies/PLAY_DATA_SAFETY_REVIEW.md) to reconcile
  disclosures with device evidence.
- Complete the physical-device matrix, dependency/license review, release build and upgrade migration tests.
- Configure protected publishing workflows and Fastlane using owner-supplied credentials. Demonstrate internal delivery before production.
- Complete applicable closed testing/production eligibility. Publish paid release from a validated version tag, subject to store review.
- Acceptance: separate evidence for tests, signed AAB, internal installation, upgrade behavior, submission and approval. A submitted build is not yet a live app.

### Final full-open-release gate — end-to-end coverage and visual documentation

This final gate is required before the full public launch. The exhaustive app UI
and E2E inventory is completed under M7.6 before closed testing; this gate adds
the public documentation deployment and checks that coverage remains complete for
the release candidate.

- Maintain a release coverage inventory generated from the app navigation, every
  user-facing feature/screen, and every selectable setting or action. Require an
  end-to-end path through the actual Android app and native persistence for each
  entry; include relevant success, validation/error, empty, cancellation and
  confirmation paths. A mocked UI-only test does not count as end-to-end evidence.
- Run the complete journey on the minimum supported Android API and the latest
  Play-supported stable API at release, using fresh and upgraded databases and
  synthetic data. Preserve per-case results, logs with private values removed,
  and a screenshot for every screen and distinct feature/option state. Review the
  captures for clipping, unreadable text, broken navigation and stale information.
- Internal delivery may continue during implementation. Closed testing requires
  all launch features and M7.6 acceptance. Gate full open release on complete coverage, zero
  unexplained failures, reviewed screenshots, and closure of all release-blocking
  findings.
- Build a static, end-user documentation site from the reviewed screenshots and
  step-by-step task guides. Include accessible captions/alt text, current app
  version labels, privacy-sensitive workflows, and feature limitations. Use only
  synthetic/example data; never publish customer details, real locations, account
  identifiers, diagnostic dumps or test credentials.
- Keep the documentation source and screenshots versioned with the project.
  Configure Cloudflare Pages to host the static site and deploy the public docs
  as part of full public release; validate links, mobile layout and image loading
  in the Pages deployment. No runtime backend is needed for documentation.
  Cloudflare builds and deploys the site itself from this repository; the cache and
  security headers, the publishable-site check and the read-only live check are in
  place, and the Cloudflare project settings are recorded in
  `docs/WEBSITE_DEPLOYMENT.md`. Confirming the deployed legal pages remains open.
- Acceptance: every inventory item has a passing end-to-end case and reviewed
  screenshot; the public static docs build and Pages deployment are verified.

### Milestone 9 — later iOS (deferred)

- Implement Swift location/geofence/widget adapters and an extension-safe repository; share Flutter UI/domain contracts.
- Re-evaluate region limits, background delivery, permission onboarding, Google/OSM integration and backup portability on iOS.
- Add macOS CI, TestFlight, signing and App Store review automation after owner setup.
- Acceptance: iOS-specific physical-device evidence and a tested cross-platform backup path; Android results are not substitutes.

### Future service (not a launch milestone)

If demand justifies it, design optional accounts, team invitations, board collaboration and timesheet sharing. Keep local use independent of a subscription. Recipients can view shared timesheets but cannot change them; the server must enforce ownership/authorization. Stable IDs and revisions support later design, but do not implement speculative sync, subscriptions, ACLs or a backend now.
