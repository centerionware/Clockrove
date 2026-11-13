const minimumThenExactBilling = 'MINIMUM_THEN_EXACT';
const roundTotalUpBilling = 'ROUND_TOTAL_UP';

String? minimumHoursError(String raw) {
  final value = raw.trim();
  if (!RegExp(r'^(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,6})?$').hasMatch(value)) {
    return 'Enter 0 or a positive number with up to 6 decimal places.';
  }
  return null;
}

String canonicalMinimumHours(String raw) {
  if (minimumHoursError(raw) != null) {
    throw const FormatException('Enter a valid minimum number of hours.');
  }
  final value = raw.trim();
  final parts = value.split('.');
  if (parts.length == 1) return parts.first;
  final fraction = parts.last.replaceFirst(RegExp(r'0+$'), '');
  return fraction.isEmpty ? parts.first : '${parts.first}.$fraction';
}

String billingModeLabel(String value) => switch (value) {
  roundTotalUpBilling => 'Round total time up to the next hour',
  _ => 'Minimum hours, then exact time',
};
