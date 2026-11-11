import 'package:clockrove/l10n/app_strings.dart';
import 'package:clockrove/tracking/elapsed_display.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('elapsed display preserves long sessions and UTC DST duration', () {
    final start = DateTime.utc(2026, 1, 1);
    expect(
      elapsedLabel(start, start.add(const Duration(seconds: 59))),
      '00:00:59',
    );
    expect(
      elapsedLabel(start, start.add(const Duration(hours: 49))),
      '49:00:00',
    );
    expect(
      elapsedLabel(
        DateTime.parse('2026-11-01T01:30:00-04:00'),
        DateTime.parse('2026-11-01T01:30:00-05:00'),
      ),
      '01:00:00',
    );
    expect(
      elapsedLabel(start, start.subtract(const Duration(milliseconds: 1))),
      AppStrings.checkDeviceTime,
    );
  });
}
