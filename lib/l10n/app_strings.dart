/// English copy centralized for later localization.
abstract final class AppStrings {
  static const appName = 'Clockrove';
  static const workday = 'Your workday';
  static const workLocationMap = 'Recorded location';
  static const viewLocationMap = 'View on map';
  static const recordedLocation = 'Last recorded work location';
  static const zoomIn = 'Zoom in';
  static const zoomOut = 'Zoom out';
  static const mapPrivacy =
      'Map requests share the viewed area with OpenStreetMap.';
  static const mapUnavailable =
      'Map tiles are unavailable. Your recorded location is still available.';
  static const linkUnavailable = 'Could not open the link.';
  static const running = 'Clocked in';
  static const chooseJob = 'Choose a job below to start your workday.';
  static const elapsed = 'Elapsed this session';
  static const checkDeviceTime = 'Check device time';
  static const locationTitle = 'Work location';
  static const lastSample = 'Last sample';
  static const accuracy = 'accuracy';
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
  static const idle = 'Not clocked in';
  static const started = 'Started';
  static const jobs = 'Jobs';
  static const newJob = 'New job';
  static const jobName = 'Job name';
  static const cancel = 'Cancel';
  static const create = 'Create';
  static const refresh = 'Refresh timer';
  static const retry = 'Retry same action';
  static const clockIn = 'Clock in';
  static const clockOut = 'Clock out';
  static const switchJob = 'Switch job';
  static const storageUnavailable = 'Timer unavailable. Tap to try again.';
  static const uncertain =
      'The action may have completed. Retry to confirm its result.';
  static const commandConflict =
      'The action could not be applied. Review the current timer and try again.';
  static const jobCreateFailed =
      'Job was not created. Check its name, refresh the list and try again.';
}
