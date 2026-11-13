# Next combined internal test release

Owner-requested scope, 2026-11-11. This supplements `app_plan.md` and replaces
its earlier exclusions of pricing and invoicing. It does not narrow the complete
application objective or declare physical-device acceptance complete.

The combined Play internal release must contain **all** the features below.
This gate passed: #29 exact-head checks 43117726325, merged-main checks
43120365067 and Play delivery 43121445107 accepted 0.1.0-internal.19.1
(versionCode 216603660) from cb2efc9 using the permanent upload key. Normal
automatic internal delivery is active again. Production readiness remains
disabled; tester installation and physical-device acceptance are separate.
Do not substitute a partial feature set for this contract.

## Job display, lifecycle and widget

- Provide a usable job detail page with overview, Kanban, report and job settings.
  Every destination must work; do not add placeholder tabs or mocked totals.
- Keep the clean light/dark UI, readable typography, large-text scrolling and
  accessible actions established in `docs/UI_DESIGN.md`.
- Make the home widget compact, targeting two columns by one row, with an
  accessible native clock-out action and actual running-job state. Existing
  launcher layouts may need user resizing/re-adding; real launcher proof is separate.
- Keep the current-location work-site button. Request precise foreground location
  only if missing, get one fresh fix and save a site only after explicit confirmation.
  Do not turn setup fixes into work history. Keep map/coordinate alternatives.
- Explain the separate Android background-permission settings step accurately.
- Archive a job without deleting history or automation preferences. Require the
  running job to be clocked out first. Archived jobs are absent from clock-in and
  automation choices.
- Unarchive restores those choices and preferences, respecting capacity and fresh
  outside/entry qualification. A later archive starts a new 30-day retention period.
- Permanent job deletion becomes available only after 30 continuous elapsed UTC
  days archived. Enforce this in native storage, independently of the UI. Unknown
  legacy archive dates start a fresh period on migration.
- Eligible deletion is styled red and requires explicit irreversible-loss
  confirmation. Purge job-specific work, GPS, boards and in-app invoice data
  transactionally. Retain opaque deletion metadata for safe future backup merge.
- **Exception requested by the owner:** reusable task templates remain available
  after job deletion. Customers remain independent records. Explain these retained
  independent records in deletion confirmation; they do not restore a deleted job.

## Full Kanban and reusable task templates

- Persist each job's board, at least one column, and cards with stable IDs.
- Start with To do, Doing and Done. Add, rename and reorder columns; deleting a
  populated column requires an explicit destination and one transaction.
- Cards have title, description, optional calendar-date due date, position,
  completion/billable state and optional fixed price with explicit currency.
- Support create/edit/delete, drag/reorder and accessible move/reorder actions.
  Completion semantics must be explicit, independent of a column's display name.
- Offer existing tasks/templates when creating a card, including tasks from other
  jobs and archived jobs and retained templates from deleted jobs.
- Reuse copies title, description and optional price/currency into a new card with
  a new ID. Do not copy job/customer association, work/GPS history, old completion
  state or due date, or start any timer.
- Retain reusable template records independently of a deleted job, with a way to
  manage/remove them. Remove deleted-job provenance/customer associations; retain
  only reusable task content and pricing. Reusing a template does not resurrect
  deleted job/card records or silently alter the original template.
- Task prices are fixed task amounts (one card = one task), not task timers.

## Main settings and hourly/task billing

- Provide a main settings page for a default hourly rate and explicit currency.
- Each job selects **hourly** or **fixed task** billing and may override the default
  hourly rate. Retain recorded time and task prices when switching modes.
- Hourly report amount uses the effective current hourly rate times finalized
  selected work duration. State the rate and basis. Rate changes may update new
  report calculations, but must not rewrite already prepared invoice snapshots.
- Task report amount uses completed/billable priced tasks, with clear selection
  and a line-item breakdown. Missing prices must be visible, never silently
  interpreted as zero. Permit explicit free tasks priced at zero.
- Hourly and task amounts are alternative billing bases; do not double-charge by
  automatically adding both. Time tracking remains usable for task-priced jobs.
- Use exact decimal/minor-unit arithmetic, explicit currency precision and a
  documented rounding rule. Do not round work intervals. Do not silently relabel
  or convert stored prices, or combine incompatible currencies.
- Show amount owed in the job report with the calculation basis. Payment tracking,
  automatic deductions, tax calculations and recurring billing are not introduced.

## Reporting and exports

- Implement the planned date/range, calendar, list, totals and map reports, with
  shared job filters, explicit report timezone and DST-safe UTC clipping.
- Show active/provisional work separately; only finalized selected intervals feed
  hourly billing and final CSV/PDF totals. Show relevant task completion dates for
  task-priced reports; due dates remain calendar dates, not billing timestamps.
- Keep CSV quoting, formula protection and optional finalized GPS export.
- Place a **PDF invoice** action next to CSV export. It must generate a real,
  readable document with the same selected report basis and correct totals.

## Customers and job contact details

- Add a local customer view with create, view, edit and archive/manage actions.
  Support customer name, email and billing/contact information.
- Assign a customer to a job. Job settings include an editable customer email
  field, defaulted from the selected customer, with an explicit job override
  rather than silently changing every job's customer record.
- Keep customer/job references valid. Archive customers in use instead of losing
  referenced contacts; unused-customer deletion requires confirmation.
- These are local business contacts, not app login accounts or a hosted CRM.

## Global invoice settings and at least three styles

- Global settings include company name and contact information, invoice email
  subject/body templates, optional invoice header/footer information and logo.
- Import/change/remove a company logo with the Android image/document picker.
  Validate type/size, preserve aspect ratio and copy it to app-private storage.
  No server upload or broad storage permission is required. Do not publish real
  logos, company/customer data or invoice content in repository artifacts.
- Offer at least **Minimal**, **Classic** and **Modern** invoice style templates.
  They must have genuinely distinct, polished layouts, a usable preview and the
  same actual line items/totals. All styles support the optional logo/header/footer
  and readable multipage content. Optional blank fields disappear cleanly.
- Email templates use documented safe placeholders such as company/customer/job
  name, invoice number, amount and currency. Preserve body newlines; do not execute
  template code or allow recipient/subject header injection.
- Use stable invoice numbering/IDs and snapshots of the selected data, prices,
  customer/company details, template and branding. Later edits must not silently
  change an existing prepared invoice. Repeated taps/retries must not allocate
  duplicate invoices for the same generation intent.

New invoice sequences start at 1000 (displayed as INV-1000), then increment
automatically. Existing prepared invoices retain their immutable numbers and
content. An older unconsumed counter below 1000 is raised when the next invoice
is committed; already higher sequences continue without resetting.

## Email handoff

- Generating/sending an invoice should open the default email app (or the system
  email-app choice if no default exists), fill the known recipient, subject and
  body, and attach the generated PDF so the user can review it and press Send.
- Use Android content URIs/FileProvider and temporary read grants, not file URIs
  or broad filesystem access. Validate the effective job/customer email address.
- Do not send email from Clockrove or add SMTP/server credentials. Opening a
  composer is not evidence the email was sent or delivered.
- Handle absent/incompatible email apps and cancelled composition honestly;
  preserve a PDF save/share fallback and never lose the job/report.

## Data integrity, backup and validation

- Keep the native repository as the serialized writer. Migrate existing real
  jobs/sessions/sites safely; do not replace the owner's data with new scaffolding.
- Add revision ancestry/deletion integrity for new entities. Future encrypted
  backup/merge covers boards/tasks/templates, customers, pricing, settings,
  branding and invoice snapshots, and cannot silently resurrect deleted work.
- Preserve response-loss deduplication while purging deleted personal command
  payloads; opaque receipts/deletion fences are distinct from recoverable job data.
- Add meaningful tests for migrations, archive age/reset/concurrency, transaction
  rollback, deleted-template reuse, board ordering/column moves, exact pricing and
  rounding, DST/report agreement, CSV safety, PDF styles/logo/pagination, template
  escaping, customer references, and email Intent/attachment grants.
- Use only synthetic fixtures and CI-rendered previews. All dependency resolution,
  formatting, builds, tests and rendering run in GitHub Actions, never this VM.
- Record exact run/head/artifact evidence in `plan_status.md`. Do not mark complete
  from mocks or claim actual GPS/launcher/email delivery/device behavior without
  corresponding evidence. Internal testing may carry explicit device validation
  gaps; production readiness remains a separate gate.

## Owner-prioritized follow-up after quote baseline #39

This is the next implementation milestone after the quote baseline and PDF preview
were merged. It is separate from the earlier combined release above, and must be
complete before beginning the whole-app UI overhaul and exhaustive E2E gate.

- In quote editing, search and select saved task templates from active, archived
  and deleted jobs. Add each as a normal editable quote line. Notes/terms and
  decision notes are optional; a customer can be recorded as accepted or declined
  through a clear reviewed action without writing a note.
- Once accepted, offer **Create job from quote** to pre-fill a new job's task
  board with the accepted lines, quantities and prices. Let the owner review the
  generated job/tasks before saving, and keep an explicit path for adding the
  accepted scope to an existing job. Preserve quote provenance and prevent
  duplicate jobs/tasks on retry. Never fabricate worked hours, completion,
  invoices or payments.
- Add private task comments and attach images either to the task or to one
  comment. Support Android image selection and camera capture. A camera photo is
  first written to a private temporary URI and shown in an in-app review; only
  **Attach photo** commits it. Retake/cancel discards it. Existing images receive
  a review before import. Enforce bounded type, size, count and storage; strip
  EXIF/GPS metadata; support preview/removal and delete images with their parent.
- Repair blank maps for site selection, current-location setup and report history.
  With no saved site location, ask permission as needed and center on a fresh user
  location if available. Keep coordinate/manual alternatives and clear offline,
  tile-error, permission-denied and unsupported-coordinate states.
- Add a global work-location recording option, default on, with per-job inherit,
  on and off settings. Request a ten-minute fused update interval and persist no
  more than one ordinary point per five-minute window, plus available session
  boundary points. Record only actual measured points while work is running;
  preserve manual timekeeping when disabled or permission is unavailable.
- Validate the migration from released v9, storage limits/rollback, permission
  paths, quote conversion idempotency, metadata stripping and cleanup. Add native,
  Flutter and actual Android UI journeys on API 24 and current stable API. Capture
  and review each new screen and distinct option in Actions; emulator proof does
  not stand in for physical camera hardware or background GPS delivery.

## Follow-on closed-testing gate

After this follow-up, finish the remaining launch implementation milestones,
including encrypted backup/merge and the planned business reporting/billing work.
Then overhaul the entire app UI for intuitive first use and run actual-app E2E
journeys for every feature, screen, selectable option, permission/error/empty
state and destructive confirmation. Assert persisted and native side effects and
retain a reviewed screenshot for every screen/state on API 24 and current stable
Android. Do not move from internal to closed testing until all launch features and
this full-app E2E/UI acceptance pass. Internal iterations can continue while the
work is underway. Public Cloudflare Pages documentation remains part of the full
open-release stage, built from the reviewed synthetic screenshots.
