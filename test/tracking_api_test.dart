import 'package:clockrove/tracking/tracking_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/tracking/v1');
  const api = TrackingApi();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'denied location and notifications remain explicit native states',
    () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => {
          'permission': 'denied',
          'service': 'STOPPED',
          'working': true,
          'notificationsAllowed': false,
          'lastMeasuredMillis': 1000,
          'lastAccuracyMeters': 24.5,
        },
      );
      final status = await api.enable();
      expect(status.permission, 'denied');
      expect(status.working, isTrue);
      expect(status.notificationsAllowed, isFalse);
      expect(status.service, 'STOPPED');
      expect(status.lastObservationUtc!.isUtc, isTrue);
      expect(status.lastObservationUtc!.millisecondsSinceEpoch, 1000);
      expect(status.lastAccuracyMeters, 24.5);
    },
  );

  test(
    'restricted startup does not become a fabricated successful status',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        throw PlatformException(code: 'START_RESTRICTED');
      });
      await expectLater(api.enable(), throwsA(isA<PlatformException>()));
      messenger.setMockMethodCallHandler(channel, (call) async => null);
      await expectLater(api.status(), throwsFormatException);
    },
  );
}
