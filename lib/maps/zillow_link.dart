/// Uses the reverse-geocoded property address first, then the job customer's
/// saved address as a fallback.
String? zillowSearchAddress({
  String? propertyAddress,
  String? customerAddress,
}) {
  for (final candidate in [propertyAddress, customerAddress]) {
    final value = candidate?.trim();
    if (value != null && value.isNotEmpty) return value;
  }
  return null;
}

/// Zillow resolves a location slug to a property search. A coordinate pair only
/// resolves to a city/region, so callers must pass a house-level address.
Uri zillowUriForAddress(String address) {
  final normalized = address.trim().replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.isEmpty) {
    throw ArgumentError('Address is required');
  }
  final slug = normalized.replaceAll(RegExp(r'\s+'), '-');
  return Uri.https('www.zillow.com', '/homes/${slug}_rb/');
}
