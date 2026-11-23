final class TaxPercentOutOfRange extends FormatException {
  const TaxPercentOutOfRange()
    : super('TAX_PERCENT_OUT_OF_RANGE');
}

/// Units are 0.0001 percentage point. Never parse a percentage as a double.
int parseTaxPercent(String input) {
  final value = input.trim();
  if (value.isEmpty ||
      value.length > 32 ||
      !RegExp(r'^[0-9]+(\.[0-9]{1,4})?$').hasMatch(value)) {
    throw const FormatException(
      'INVALID_TAX_PERCENTAGE',
    );
  }
  final parts = value.split('.');
  final units =
      BigInt.parse(parts[0]) * BigInt.from(10000) +
      BigInt.parse((parts.length == 1 ? '' : parts[1]).padRight(4, '0'));
  if (units > BigInt.from(1000000)) {
    throw const TaxPercentOutOfRange();
  }
  return units.toInt();
}

String taxPercent(int units) {
  if (units < 0 || units > 1000000) {
    throw const FormatException('INVALID_SAVED_TAX_PERCENTAGE');
  }
  final whole = units ~/ 10000;
  final fraction = (units % 10000)
      .toString()
      .padLeft(4, '0')
      .replaceFirst(RegExp(r'0+$'), '');
  return fraction.isEmpty ? '$whole' : '$whole.$fraction';
}

String? taxPercentError(String input) {
  try {
    parseTaxPercent(input);
    return null;
  } on FormatException catch (error) {
    return error.message;
  }
}
