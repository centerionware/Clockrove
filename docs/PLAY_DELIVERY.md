# Automatic Play delivery

The owner reports the initial versionCode 1 bundle uploaded manually and authorizes
internal testing from main. `SERVICE_ACCOUNT_JSON` and all four `UPLOAD_*` secrets
are configured. The same upload keystore is used for internal and production.

## Main pushes

Every push to main enters the Validate workflow. Checks must all pass before the
separate Play delivery workflow handles that exact SHA. PR runs have no publishing
secrets and never trigger delivery. Queues retain up to GitHub's supported 100
pending runs instead of cancelling earlier pushes. One publishing queue covers
both tracks, from version allocation through store submission.

Internal versionName is `<pubspec base>-internal.<delivery run>.<attempt>`.
No manual version increment is required. The publisher opens a temporary Play
edit, enumerates AAB/APK codes and every track's releases, and chooses the greater
of `max published + 1` and seconds since 2020. The edit is then aborted. Invalid
history or Android's code limit stops the workflow. A retry re-reads Play history;
previously published versionCodes are never intentionally reused. Upload races
with a separate manual console operation fail rather than claiming success.

The workflow builds/signs an AAB with the committed lockfiles and saved key,
verifies the signature, and uses locked Fastlane to submit to internal testing.
No binary artifacts or keys are uploaded publicly by regular delivery. Temporary
credential files are cleaned even on failure. Setup/API permission failures are
reported as failures, never deployment successes. No SERVICE_ACCOUNT_JSON value
is committed or printed.

## Version tags

Push `vMAJOR.MINOR.PATCH` on a tested main-branch commit. This selects production
versionName exactly (for example v1.2.3 builds 1.2.3); no pubspec edit is needed.
The pipeline checks ancestry and an actual successful main validation run for the
same SHA. Malformed tags and untested/fork commits cannot publish.

Production is enabled once with repository variable `PRODUCTION_READY=true`, only
after the full app/readiness/device gates and Play testing/production eligibility
are satisfied. It is currently disabled. This is not a per-build approval or
manual version bump. Thereafter eligible tags automatically build/sign and submit
production with automatic release intent, subject to Google's review/account
rules. Do not use tags to publish the unfinished prototype as the paid product.

Internal and production success messages distinguish upload/submission from
approval and actual availability. Source: `.github/workflows/play-delivery.yml`.
Ruby 3.3.6, Bundler 2.5.23 and Fastlane 2.228.0 are pinned; Gemfile.lock was resolved
on Actions, not on the development VM. No local builds/tests are permitted.
