/// Units are 0.0001 percentage point. Never parse a percentage as a double.
int parseTaxPercent(String input) {
  final value = input.trim();
  if (value.isEmpty || value.length > 32 ||
      !RegExp(r'^[0-9]+(\.[0-9]{1,4})?$').hasMatch(value)) {
    throw const FormatException('Enter a tax percentage from 0 to 100, with up to four decimal places.');
  }
  final parts = value.split('.');
  final units = BigInt.parse(parts[0]) * BigInt.from(10000) +
      BigInt.parse((parts.length == 1 ? '' : parts[1]).padRight(4, '0'));
  if (units > BigInt.from(1000000)) {
    throw const FormatException('Tax percentage must be between 0 and 100.');
  }
  return units.toInt();
}

String taxPercent(int units) {
  if (units < 0 || units > 1000000) {
    throw const FormatException('Invalid saved tax percentage.');
  }
  final whole = units ~/ 10000;
  final fraction = (units % 10000).toString().padLeft(4, '0').replaceFirst(RegExp(r'0+$'), '');
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
