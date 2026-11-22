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
