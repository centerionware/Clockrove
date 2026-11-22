/// English copy centralized for later localization.
abstract final class AppStrings {
  static const appName = 'Clockrove';
  static const locationTitle = 'Work location';
  static const locationPurpose =
      'Record location only while clocked in, targeting a sample about every ten '
      'minutes. Accuracy and delivery vary. Manual timekeeping works without '
      'location permission. Background geofence automation is not enabled here.';
  static const continueLabel = 'Continue';
  static const enableLocation = 'Enable work location';
  static const disableLocation = 'Stop location only';
  static const allowNotifications = 'Allow notifications';
  static const locationIdle = 'No location recorded while clocked out.';
  static const locationDenied =
      'Location permission denied. Manual timer still works.';
  static const locationRestricted =
      'Location unavailable or restricted. Manual timer still works.';
  static const locationRequesting =
      'Location requested; ten-minute delivery is a target.';
  static const locationStarting = 'Starting work location…';
  static const locationRecordingDisabled =
      'Work location recording is turned off in Settings or for this job. The timer is independent.';
  static const locationUnavailable =
      'Waiting for location. Missing GPS does not end work.';
  static const locationStopped =
      'Work location is stopped. The timer is independent.';
  static const locationApproximate =
      'Approximate location permission; accuracy is limited.';
  static const prototype =
      'Development build: location tracking is unavailable.';
}
