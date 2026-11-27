/// Zillow's homes URL accepts a coordinate pair and centres its search there.
/// No address lookup or account is required; the external browser handles it.
Uri zillowUriForCoordinates(double latitude, double longitude) {
  if (!latitude.isFinite ||
      !longitude.isFinite ||
      latitude.abs() > 90 ||
      longitude.abs() > 180) {
    throw ArgumentError('Invalid coordinates');
  }
  return Uri.https(
    'www.zillow.com',
    '/homes/${latitude.toStringAsFixed(6)},'
        '${longitude.toStringAsFixed(6)}_rb/',
  );
}
