# Dependency inventory

| Component | Version | License | Status |
| --- | --- | --- | --- |
| Flutter SDK | 3.47.9 | BSD-3-Clause and bundled notices | Download checksum verified; CLI runs on ARM64 |
| Dart SDK | 3.13.7 (bundled) | BSD-3-Clause | Version and analysis verified |

Flutter archive SHA-256:
`25ed8c3c29d51a7353f2a944310b478af8214920639524b19c50a19e6c044c5a`
Source: https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json

Add runtime/transitive dependencies and their notices after dependency resolution.
CI JDK selected: Eclipse Temurin 17.0.17+10 (GPLv2 with Classpath Exception).
Gradle 9.3.1 (Apache-2.0), AGP 9.1.0 (Apache-2.0), Kotlin 2.4.0 (Apache-2.0).
Gradle wrapper SHA-256: `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`.
Gradle distribution checksum is recorded in gradle-wrapper.properties.
Android API 24 minimum / 36 target, build-tools 36.0.0, NDK 28.2.13676358.
Flutter lints 6.0.0 and transitive dependencies are pinned in pubspec.lock.

Riverpod/typed adapters and pinned publishing tools are validated in Actions.
Crypto selection and full dependency license review remain incomplete.

CI Android command-line tools: 19.0, archive 13114758 (the upstream filename ends
in `_latest.zip` but the build ID and checksum are fixed). SHA-256:
`7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee`.
The job installs this into its own SDK root instead of using runner PATH state.

Native prototype test dependencies: JUnit 4.13.2 (EPL-1.0) and Robolectric 4.16.1
(MIT), test-only. Full transitive notice review remains a release gate.

Prototype presentation: flutter_riverpod 3.0.3 / riverpod 3.0.3 (MIT). Exact
transitive versions are in Actions-resolved pubspec.lock. Manual controls are merged after successful Actions validation.

Native location prototype: Google Play services location 21.3.0, pinned Maven
coordinate `com.google.android.gms:play-services-location:21.3.0`. Google SDK
terms/third-party notices require review before release. No map key is involved.

OSM prototype direct dependencies: flutter_map 8.3.2, latlong2 0.10.1, http 1.6.0,
and url_launcher 6.3.2. Lockfile resolved in owner-only Actions run 43002798859
(artifact 13846228897); new map source needs its own validation. Includes native
path_provider/url_launcher adapters and transitive packages pinned in pubspec.lock.
Their complete notices/terms still need the launch review.

flutter_map 8.3.2 caching source was inspected: server freshness/ETag/Last-Modified,
conditional refresh, and seven-day fallback for missing freshness metadata.
Built-in cache is a best-effort OS cache, not an offline-download feature.
