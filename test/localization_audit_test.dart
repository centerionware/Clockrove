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
}
