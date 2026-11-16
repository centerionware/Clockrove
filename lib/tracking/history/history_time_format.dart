String historyUtc(int instant) {
  try {
    return '${DateTime.fromMillisecondsSinceEpoch(instant, isUtc: true).toIso8601String()} UTC';
  } on ArgumentError {
    return '$instant milliseconds since Unix epoch';
  }
}
