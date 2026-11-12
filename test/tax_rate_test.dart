import 'package:clockrove/business/tax_rate.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('percentage units stay exact through parsing and display', () {
    for (final entry in {'0': 0, '7.5': 75000, '0.0001': 1, '100.0000': 1000000, ' 07.5000 ': 75000}.entries) {
      expect(parseTaxPercent(entry.key), entry.value);
      expect(parseTaxPercent(taxPercent(entry.value)), entry.value);
    }
    expect(taxPercent(75000), '7.5');
  });
  test('invalid percentages are rejected with correction guidance', () {
    for (final value in ['', ' ', '-1', '+1', '.5', '7.', '7,5', '1e2', 'NaN', 'Infinity', '100.0001', '0.00001', '99999999999999999999999999999999999999']) {
      expect(() => parseTaxPercent(value), throwsFormatException, reason: value);
      expect(taxPercentError(value), isNotNull);
    }
    expect(taxPercentError('7.5'), isNull);
  });
}
