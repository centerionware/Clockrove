# November 13 geofence regression investigation

PR #39 reduced the new-site default from 150 m to 30 m. PR #43 introduced
a GPS recording opt-out while geofence clock-in still attempted to start the
recording service for opted-out jobs. The candidate fix restores the 150 m
default and checks the recording preference before automatic service startup.
Existing geometry and the custom radius range are preserved.

[Android recommends 100–150 m for more reliable transition delivery](https://developer.android.com/develop/sensors-and-location/location/geofencing#choose-the-optimal-radius-for-your-geofence).
Validate in Actions and upgrade from an affected build on a physical device.
Check an existing site and a new 150 m site with precise background permission.
Leave and re-enter with the app in the background and verify automatic start,
departure and GPS recording opt-out. The reported root cause and physical
recovery remain unverified.

### Affected-device report

The owner confirmed an existing 15 m site worked on November 13 and stopped
working afterward. Restoring the default for newly created sites cannot
explain or resolve that case. Keep the 15 m site in the upgrade reproduction
and add a separate 150 m site as a comparison. Record the app version before
and after upgrade, the monitoring status, and whether a fresh exit/re-entry
is delivered. No affected-device transition log is available yet.

### Cold-start callback investigation

Source tracing found that a callback can launch the app process after its
location timestamp has already been recorded by Android. Repository
initialization previously reset persisted work-site membership and advanced
the fact timestamp before the callback was processed. The pending-departure
review guard is still applied on cold start when a departure already exists.
An ordinary cold start without a pending departure preserves membership so a
valid delayed callback can still be evaluated against the saved state.
Boot and device-clock recovery continue to invalidate uncertain facts.

The original radius and GPS opt-out commit `43577cd` logged 741 native cases
with zero failures, errors or skips in
[Actions 45013523588](https://github.com/centerionware/Clockrove/actions/runs/45013523588).
That result predates the cold-start change and the 15 m upgrade regression.
The cold-start source regression needs
[Actions 45015265507](https://github.com/centerionware/Clockrove/actions/runs/45015265507)
and the owner-device check.

### Mirror-backed native test results

[Actions 45018944228](https://github.com/centerionware/Clockrove/actions/runs/45018944228)
passed the native suite on `5844e61`. The uploaded JUnit XML contains 752
tests with zero failures, errors or skips. It includes the cold-start
callback and GPS opt-out regressions on both configured SDKs and the schema
upgrade with a saved 15 m site. The run also passed 30 Android instrumentation
cases on each of API 24 and 35. The emulator results cover repository behavior
and Android integration. Callback delivery at the affected 15 m site still
requires verification on the owner's phone.

The updated-head [45024861185](https://github.com/centerionware/Clockrove/actions/runs/45024861185)
and main [45024792204](https://github.com/centerionware/Clockrove/actions/runs/45024792204)
both stopped before native tests could run. Maven Central returned HTTP 429
for Robolectric's pinned native runtime JAR. The next attempt applies the
same alternate Maven Central endpoint to Gradle's dependency resolution on
GitHub runners as well as Robolectric's runtime resolver.
