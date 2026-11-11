import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('clockrove/timer/v1');
  const api = TimerApi();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('snapshot preserves UTC and immutable job IDs', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      return {
        'version': 7,
        'jobs': [
          {'id': 'job-a', 'name': 'Synthetic job'},
        ],
        'running': {
          'id': 'session-a',
          'jobId': 'job-a',
          'jobName': 'Synthetic job',
          'startUtcMillis': 1000,
        },
      };
    });
    final state = await api.snapshot();
    expect(state.version, 7);
    expect(state.jobs.single.id, state.running!.jobId);
    expect(state.running!.startUtc.isUtc, isTrue);
    expect(state.running!.startUtc.millisecondsSinceEpoch, 1000);
    expect(() => state.jobs.clear(), throwsUnsupportedError);
  });

  test('response-loss retry preserves the original intent', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (calls.length == 1) {
        throw PlatformException(code: 'SIMULATED_RESPONSE_LOSS');
      }
      return {'version': 8, 'conflict': null};
    });
    Future<TimerOutcome> submit() => api.execute(
      id: 'synthetic-intent',
      version: 7,
      action: TimerAction.switchJob,
      jobId: 'job-b',
    );
    await expectLater(submit(), throwsA(isA<PlatformException>()));
    final result = await submit();
    expect(result.committed, isTrue);
    expect(calls.last.arguments, calls.first.arguments);
    final payload = calls.last.arguments as Map<Object?, Object?>;
    expect(payload['action'], 'SWITCH');
    expect(payload.containsKey('atUtcMillis'), isFalse);
    expect(payload.containsKey('origin'), isFalse);
  });

  test('job creation retries preserve command, job identity, version and name', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (calls.length == 1) throw PlatformException(code: 'RESPONSE_LOST');
      return {'version': 8, 'conflict': null};
    });
    Future<TimerOutcome> create() => api.createJob(
      id: 'command', version: 7, jobId: 'new-job', name: 'Synthetic',
    );
    await expectLater(create(), throwsA(isA<PlatformException>()));
    expect((await create()).committed, isTrue);
    expect(calls.last.method, 'createJob');
    expect(calls.last.arguments, calls.first.arguments);
    expect(calls.last.arguments, {
      'id': 'command', 'version': 7, 'jobId': 'new-job', 'name': 'Synthetic',
    });
  });

  test('stale and missing responses never look committed', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      return {'version': 9, 'conflict': 'STALE_STATE'};
    });
    final result = await api.execute(
      id: 'stale',
      version: 7,
      action: TimerAction.stop,
    );
    expect(result.committed, isFalse);
    expect(result.version, 9);
    expect(result.conflict, 'STALE_STATE');
    messenger.setMockMethodCallHandler(channel, (call) async => null);
    await expectLater(api.snapshot(), throwsFormatException);
  });
}
