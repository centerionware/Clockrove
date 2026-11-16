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
