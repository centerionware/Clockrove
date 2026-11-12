import 'package:clockrove/business/email_template.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('both template fields reject unknown, nested and unfinished placeholders', () {
    for (final subject in [false, true]) {
      for (final value in ['{unknown}', '{', '}', '{company_name', '{{company_name}}', '{company_name}}', '{ company_name}']) {
        expect(emailTemplateError(value, subject: subject), isNotNull, reason: value);
      }
      for (final value in ['', 'Plain text with dollars \$1 and backslash \\', 'Invoice {invoice_number}: {amount} {currency}']) {
        expect(emailTemplateError(value, subject: subject), isNull);
      }
    }
  });
  test('subject stays a safe single line while body supports multiline text', () {
    for (final value in ['Hello\n{customer_name}', 'Hello\rworld', 'Hello\tworld', 'Hello\u0085world']) {
      expect(emailTemplateError(value, subject: true), isNotNull);
      expect(emailTemplateError(value, subject: false), isNull);
    }
    expect(emailTemplateError('Hello\u0000world', subject: false), isNotNull);
    expect(emailTemplateError('x' * 500, subject: true), isNull);
    expect(emailTemplateError('x' * 501, subject: true), isNotNull);
    expect(emailTemplateError('x' * 20000, subject: false), isNull);
    expect(emailTemplateError('x' * 20001, subject: false), isNotNull);
  });
}
