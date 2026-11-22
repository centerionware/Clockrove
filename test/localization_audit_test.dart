import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import '../tool/localization_audit.dart';

void main() {
  test('finds literal copy passed indirectly and with interpolation', () {
    final findings = auditDart('lib/screen.dart', r'''
void example(String name) {
  // 'This comment is ignored.'
  final message = 'Record your work ' 'location.';
  final label = 'Hello, $name';
  final channel = 'clockrove/language/v1';
  final code = 'INVALID_LANGUAGE';
}
''');
    expect(findings.map((finding) => finding.text.trim()), [
      'Record your work location.',
      'Hello,',
      'clockrove/language/v1',
    ]);
  });

  test('invoice, payment, and quote invoice screens use catalog copy', () {
    const screens = {
      'invoice_detail_screen',
      'invoice_history_screen',
      'invoice_lifecycle_editor',
      'payment_editor',
      'quote_invoice_screen',
    };
    const identifiers = {
      'balance-',
      'receipt-',
      'void-',
      'invoice-balance-copy-command',
      'invoice-correction-price-',
      'invoice-correction-quantity-',
      'invoice-correction-reason',
      'invoice-correction-tax',
      'invoice-lifecycle-command',
      'invoice-lifecycle-submit',
      'invoice-payment-amount',
      'invoice-payment-command',
      'invoice-payment-note',
      'invoice-payment-submit',
      'invoice-job/',
      'invoice-task/',
      'invoice-time/',
      'quote-invoice-first',
      'quote-invoice-last',
      'quote-invoice-zone',
      'quote-invoice-note',
      'quote-invoice/',
    };
    for (final screen in screens) {
      final path = 'lib/business/$screen.dart';
      final source = File(path).readAsStringSync();
      final lines = source.split('\n');
      final candidates = auditDart(path, source).where(
        (finding) =>
            !lines[finding.line - 1].startsWith('import ') &&
            !identifiers.contains(finding.text.trim()),
      );
      expect(candidates, isEmpty, reason: path);
    }
  });
}
