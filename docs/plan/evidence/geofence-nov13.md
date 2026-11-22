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
