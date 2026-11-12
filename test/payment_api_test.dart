import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/payment_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/business/v1');
  final calls = <MethodCall>[];
  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return {'version': 2, 'conflict': null};
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test('payment input preserves extended totals and rejects excess currency precision', () {
    final wide = '1234567890' * 105;
    expect(parsePaymentMoney('$wide.01', 2), '${wide}01');
    expect(money('${wide}01', 2), '$wide.01');
    expect(() => parseMoney('$wide.01', 2), throwsFormatException);
    for (final invalid in ['1.001', '1e3', '-1', 'NaN', 'Infinity', '1,00']) {
      expect(() => parsePaymentMoney(invalid, 2), throwsFormatException);
    }
  });
  test(
    'payment draft retry transmits the same IDs and exact string amount',
    () async {
      final amount = '9' * 1050;
      final draft = PaymentDraft(
        'payment',
        'invoice',
        amount,
        'USD',
        1793494800000,
        'UTC',
        'Transfer',
        'Synthetic reference',
        'Private note',
      );
      const api = PaymentApi();
      await api.record('same-command', 1, draft);
      await api.record('same-command', 1, draft);
      expect(calls.length, 2);
      expect(calls[0].method, 'recordInvoicePayment');
      expect(calls[0].arguments, calls[1].arguments);
      final args = calls[0].arguments as Map;
      expect(args['id'], 'same-command');
      expect((args['value'] as Map)['amountMinor'], amount);
      expect((args['value'] as Map)['received'], 1793494800000);
    },
  );
  test('reversal and balance-copy transport retain separate payment and copy identities', () async {
    const api = PaymentApi();
    await api.reverse(
      'reverse-command',
      2,
      const PaymentReversal(
        'reversal',
        'invoice',
        'payment',
        1793494800000,
        'UTC',
        'Corrected wrong reference',
      ),
    );
    await api.balanceCopy('copy-command', 3, 'copy', 'invoice');
    expect(calls[0].method, 'reverseInvoicePayment');
    expect(
      ((calls[0].arguments as Map)['value'] as Map)['paymentId'],
      'payment',
    );
    expect(
      ((calls[0].arguments as Map)['value'] as Map)['reversalId'],
      'reversal',
    );
    expect(calls[1].method, 'prepareInvoiceBalanceCopy');
    expect(((calls[1].arguments as Map)['value'] as Map)['copyToken'], 'copy');
  });
}
