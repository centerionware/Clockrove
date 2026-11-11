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
          'lastLatitude': 51.5,
          'lastLongitude': -0.1,
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
      expect(status.lastLatitude, 51.5);
      expect(status.lastLongitude, -0.1);
      expect(status.hasMapObservation, isTrue);
    },
  );

  test(
    'map action requires a measured time and finite complete coordinates',
    () {
      expect(
        const TrackingStatus(
          'precise',
          'REQUESTING',
          true,
          true,
          lastLatitude: 1,
          lastLongitude: 2,
        ).hasMapObservation,
        isFalse,
      );
      expect(
        TrackingStatus(
          'precise',
          'REQUESTING',
          true,
          true,
          lastObservationUtc: DateTime.utc(2026),
          lastLatitude: double.nan,
          lastLongitude: 2,
        ).hasMapObservation,
        isFalse,
      );
      expect(
        TrackingStatus(
          'precise',
          'REQUESTING',
          true,
          true,
          lastObservationUtc: DateTime.utc(2026),
          lastLatitude: 1,
          lastLongitude: 181,
        ).hasMapObservation,
        isFalse,
      );
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
