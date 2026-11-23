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

### November 22 in-place setup follow-up

The owner reports automatic clock-in still fails at the site with work-site
automation enabled. A one-shot current-location fix was saved at the site, but
the timer still did not start. No private coordinates or device diagnostics
were collected.

Source tracing explains why saving the current position does not itself start
work: changing a site invalidates old membership facts and disarms auto-start.
The current-location fix is setup input, not a geofence transition. A fresh
confirmed exit and later entry are still required, by design; the product
contract also requires an explicit start offer for the already-at-site case.
The old flow explained the reset but did not make that start action available.

The existing 15 m radius is supported for small properties, but Android
recommends 100–150 m for more reliable transitions. This is a plausible
contributor to missed normal arrivals; it does not prove the owner device's
original November 13 regression cause. Android location services may use
network location and may delay geofence notifications, so CI cannot establish
the affected phone's callback behavior.

The follow-up source adds an explicit confirmation after saving a current
location with auto-start enabled, routes acceptance through the serialized
manual timer command, warns about enabled sites under 100 m, and expands the
in-app Help explanation. Flutter coverage is added for accept, timer-command
identity, the small-radius warning and Help copy. After the first run's
formatting correction, rebased exact-head run
[45085754438](https://github.com/centerionware/Clockrove/actions/runs/45085754438)
passed formatting but Analyze stopped on a null-aware collection lint in the
radius warning. The lint correction passed formatting and Analyze in
[45086050642](https://github.com/centerionware/Clockrove/actions/runs/45086050642),
then Flutter reported 296 passed, 2 failed, and 14 skipped. One finder matched
both the site subtitle and confirmation; the other expected an off-screen
notice in the active-timer case. The test now targets the confirmation and
checks the timer snapshot, with no prompt or command for another running job.
Run [45087185929](https://github.com/centerionware/Clockrove/actions/runs/45087185929)
passed formatting and Analyze, then reported 297 passed, 1 failed, and 14
skipped because the success notice was off-screen in the scrolled list.
Clock-in outcomes now use visible snackbars. Run
[45087756279](https://github.com/centerionware/Clockrove/actions/runs/45087756279)
stopped at formatting; the exact workflow correction was applied. Final
rebased exact-head validation
[45088251947](https://github.com/centerionware/Clockrove/actions/runs/45088251947)
passed the full Validate workflow. PR #83 merged to main as `e126bf1`;
merged-main validation
[45091148930](https://github.com/centerionware/Clockrove/actions/runs/45091148930)
passed. Automatic internal Play delivery
[45094669012](https://github.com/centerionware/Clockrove/actions/runs/45094669012)
completed successfully. The in-place setup prompt addresses clock-in while
already at a site. CI does not establish native callback delivery at the
owner's 15 m site; that physical-device check remains pending. No native
geofence behavior is claimed as fixed by this UI change. Installation from the
internal track must still be confirmed on the owner's device.

### November 23 departure follow-up

The owner clarified that automatic arrival failed at the monitored job, so they
started it manually and installed the in-place setup patch while still clocked
in. They later left without an automatic clock-out. The site used a 1-minute
grace; after updating the app, the owner changed its radius from 15 m to 150 m
and waited about 20 minutes after leaving, seeing the GPS status icon twice.
The site location was unchanged; 15 m had been set manually before this edit.
Settings and location permissions were already enabled, but the owner had to
tap **Retry site monitoring**. The previously reported current-location save
was part of the failed-arrival flow before the app update; it cannot establish
that the updated app received a fresh fix. No fresh fix was reported with the
later radius-only edit, so that edit does not exercise the fix now under review.
The report does not include the health state after retry or native transition
logs. A flashing GPS icon is not proof that Android delivered an exit event.

The owner separately created a 150 m site around home. Arrival eventually
started the timer, later in the approach than the full radius. They manually
stopped it while still at home, so that is not a departure test. The 1-minute
grace starts after Android reports a qualified exit; it does not poll for or
guarantee an exit callback. Android recommends 100–150 m for more reliable
transitions and may delay background delivery.

Source tracing identified a missing departure baseline: every site edit rotates
registration keys and resets old membership to `UNKNOWN`. When a current fix is
included while saving a site during an already-running session, the old flow
discarded that fix as transition state. The later first exit therefore could
not qualify as a known inside-to-outside transition. The follow-up now passes
the measured setup fix to the native repository and seeds membership only when
the fix's accuracy supports it. It records no session location point and does
not start a timer. The fix's accuracy and timestamp are bound into the command
receipt via a digest; its coordinates remain the saved site center. A
weak-accuracy fix remains `UNKNOWN`; it cannot create a false departure.
Registration still reconciles after configuration changes, and the Retry
control remains the recovery action when Android reports monitoring
unavailable. The first code run stopped at Dart formatting; the exported
correction was applied. Follow-up run
[45103688030](https://github.com/centerionware/Clockrove/actions/runs/45103688030)
passed formatting, Analyze, Flutter tests and Android builds, then reported 757
native tests with one failure: a label-only site rename rotated its geofence
key. The invalidation condition was corrected to preserve that pre-existing
behavior. Corrected exact-head Validate run
[45104682175](https://github.com/centerionware/Clockrove/actions/runs/45104682175)
passed on `7b64341`: 757 native tests with zero failures, errors or skips;
40 actual Android tests on each of API 24 and 35 with zero failures, errors or
skips; plus formatting, Analyze, Flutter tests, Android builds and release Java.
The owner's 15 m to 150 m radius-only edit does not exercise this code path: the
location was not refreshed after the app update. Install the fixed build and
save a fresh current-location fix at the site before using the owner device to
check departure. Physical geofence callback delivery remains unverified.

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

The geofence fix merged as `abe6f5f`. [Main validation 45028310174](https://github.com/centerionware/Clockrove/actions/runs/45028310174)
passed. The [internal delivery attempt](https://github.com/centerionware/Clockrove/actions/runs/45031370218)
stopped before uploading a bundle because its signing job did not install
CI’s Flutter Gradle repository setup. The follow-up applies the same setup
to the trusted Play workflow. The signed upload and owner-device check remain open.
