import '../l10n/app_localizations.dart';

const minimumThenExactBilling = 'MINIMUM_THEN_EXACT';
const roundTotalUpBilling = 'ROUND_TOTAL_UP';

String? minimumHoursError(String raw) {
  final value = raw.trim();
  if (!RegExp(r'^(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,6})?$').hasMatch(value)) {
    return 'INVALID_MINIMUM_HOURS';
  }
  return null;
}

String canonicalMinimumHours(String raw) {
  if (minimumHoursError(raw) != null) {
    throw const FormatException('INVALID_MINIMUM_HOURS');
  }
  final value = raw.trim();
  final parts = value.split('.');
  if (parts.length == 1) return parts.first;
  final fraction = parts.last.replaceFirst(RegExp(r'0+$'), '');
  return fraction.isEmpty ? parts.first : '${parts.first}.$fraction';
}

AppMessage billingModeMessage(String value) => switch (value) {
  roundTotalUpBilling => AppMessage.billingModeRoundTotalUp,
  _ => AppMessage.billingModeMinimumThenExact,
};
