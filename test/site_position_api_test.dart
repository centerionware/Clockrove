import 'package:clockrove/automation/automation_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'setup fix codec preserves measured metadata and explicit purpose',
    () async {
      const channel = MethodChannel('clockrove/automation/v1');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'currentPosition') {
          expect((call.arguments as Map)['purposeAccepted'], isTrue);
          return {
            'latitude': 10.0,
            'longitude': 20.0,
            'accuracy': 25.0,
            'measured': 99000,
            'received': 100000,
          };
        }
        expect(call.method, 'cancelPosition');
        return null;
      });
      const api = AutomationApi();
      final fix = await api.currentPosition();
      expect(fix.latitude, 10.0);
      expect(fix.longitude, 20.0);
      expect(fix.accuracy, 25);
      expect(fix.measuredUtc.isUtc, isTrue);
      expect(fix.measuredUtc.millisecondsSinceEpoch, 99000);
      await api.cancelPosition();
    },
  );
}
