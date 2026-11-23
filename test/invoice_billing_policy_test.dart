import 'package:flutter_test/flutter_test.dart';
import 'package:clockrove/business/invoice_billing_policy.dart';

void main() {
  test('minimum hours accepts exact decimal text without floating point', () {
    expect(minimumHoursError('0'), isNull);
    expect(minimumHoursError('1.250000'), isNull);
    expect(canonicalMinimumHours('1.250000'), '1.25');
    expect(canonicalMinimumHours('1000.000'), '1000');
    expect(minimumHoursError('1e2'), isNotNull);
    expect(minimumHoursError('-1'), isNotNull);
    expect(minimumHoursError('1.0000001'), isNotNull);
  });

  test('billing mode labels remain understandable', () {
    expect(
      billingModeMessage(minimumThenExactBilling).english,
      'Minimum hours, then exact time',
    );
    expect(
      billingModeMessage(roundTotalUpBilling).english,
      'Round total time up to the next hour',
    );
  });
}
