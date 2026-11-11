import 'package:clockrove/tracking/history_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/business/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'history preserves UTC milliseconds, running bounds and cursor identity',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'version': 27,
          'entries': [
            {
              'id': 'entry',
              'jobId': 'job',
              'start': 2147483649123,
              'end': null,
              'timezone': 'America/New_York',
              'offsetSeconds': -18000,
              'origin': 'WIDGET',
              'needsReview': true,
            },
          ],
          'next': {'start': 2147483649123, 'id': 'entry'},
        };
      });
      final data = await const HistoryApi().history('job', limit: 1);
      final entry = data.entries.single;
      expect(data.version, 27);
      expect(entry.start, 2147483649123);
      expect(entry.end, isNull);
      expect(entry.timezone, 'America/New_York');
      expect(entry.offsetSeconds, -18000);
      expect(entry.origin, 'WIDGET');
      expect(entry.needsReview, isTrue);
      expect(() => data.entries.clear(), throwsUnsupportedError);
      await const HistoryApi().history('job', before: data.next, limit: 1);
      expect((calls.last.arguments as Map)['before'], {
        'start': 2147483649123,
        'id': 'entry',
      });
    },
  );

  test(
    'revision decoding preserves erased payload and explicit merge parents',
    () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => [
          {
            'id': 'revision',
            'changed': 123,
            'origin': 'MANUAL_DELETE',
            'deleted': true,
            'payload': null,
            'parents': ['parent-a', 'parent-b'],
          },
        ],
      );
      final revision = (await const HistoryApi().revisions(
        'job',
        'entry',
      )).single;
      expect(revision.deleted, isTrue);
      expect(revision.payload, isNull);
      expect(revision.parents, ['parent-a', 'parent-b']);
      expect(() => revision.parents.clear(), throwsUnsupportedError);
    },
  );

  test(
    'wall-time choices preserve both offsets without choosing for the user',
    () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => [
          {
            'instant': 1793511000123,
            'offsetSeconds': -14400,
            'label': '2026-11-01T01:30:00.123 -04:00',
          },
          {
            'instant': 1793514600123,
            'offsetSeconds': -18000,
            'label': '2026-11-01T01:30:00.123 -05:00',
          },
        ],
      );
      final choices = await const HistoryApi().resolve(
        '2026-11-01T01:30:00.123',
        'America/New_York',
      );
      expect(choices.length, 2);
      expect(choices[1].instant - choices[0].instant, 3600000);
      expect(choices.map((choice) => choice.offsetSeconds), [-14400, -18000]);
      expect(() => choices.clear(), throwsUnsupportedError);
    },
  );

  test(
    'unknown response propagates and explicit retry retains captured intent',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (calls.length == 1) {
          throw PlatformException(code: 'STORAGE_UNAVAILABLE');
        }
        return {'version': 31, 'conflict': null};
      });
      const draft = HistoryDraft(
        sessionId: 'entry',
        jobId: 'job',
        start: 2147483649123,
        end: 2147483659789,
        timezone: 'UTC',
        offsetSeconds: 0,
      );
      const api = HistoryApi();
      await expectLater(
        api.correct('same-command', 30, draft),
        throwsA(isA<PlatformException>()),
      );
      expect(calls.length, 1);
      final result = await api.correct('same-command', 30, draft);
      expect(result.committed, isTrue);
      expect(result.version, 31);
      expect(calls.last.arguments, calls.first.arguments);
      final value = (calls.last.arguments as Map)['value'] as Map;
      expect(value['end'], 2147483659789);
      expect(value.containsKey('origin'), isFalse);
    },
  );
}
