import 'dart:async';

import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class ControlledApi extends TimerApi {
  final calls = <TimerIntent>[];
  bool loseFirstResponse = false;
  int snapshotVersion = 1;
  TimerOutcome outcome = const TimerOutcome(2, null);
  Completer<TimerOutcome>? response;

  @override
  Stream<int> get committedVersions => const Stream<int>.empty();

  @override
  Future<TimerSnapshot> snapshot() async =>
      TimerSnapshot(snapshotVersion, const [], null);

  @override
  Future<TimerOutcome> execute({
    required String id,
    required int version,
    required TimerAction action,
    String? jobId,
  }) async {
    calls.add(TimerIntent(id, version, action, jobId));
    if (loseFirstResponse && calls.length == 1) {
      snapshotVersion = 2;
      throw PlatformException(code: 'RESPONSE_LOST_AFTER_COMMIT');
    }
    if (response != null) return response!.future;
    return outcome;
  }
}

void main() {
  const displayed = TimerSnapshot(1, [], null);

  test('lost response blocks new intent and retries original ID', () async {
    final api = ControlledApi()..loseFirstResponse = true;
    final container = ProviderContainer(
      overrides: [timerApiProvider.overrideWithValue(api)],
    );
    addTearDown(container.dispose);
    final actions = container.read(timerActionsProvider.notifier);
    await actions.submit(displayed, TimerAction.start, jobId: 'job-a');
    expect(container.read(timerActionsProvider).pending, isNotNull);
    await actions.submit(displayed, TimerAction.start, jobId: 'job-b');
    expect(api.calls, hasLength(1));
    await actions.retry();
    expect(api.calls, hasLength(2));
    expect(api.calls.last.id, api.calls.first.id);
    expect(api.calls.last.version, api.calls.first.version);
    expect(api.calls.last.jobId, 'job-a');
    expect(container.read(timerActionsProvider).pending, isNull);
    expect((await container.read(timerSnapshotProvider.future)).version, 2);
  });

  test('simultaneous taps cannot issue concurrent app commands', () async {
    final api = ControlledApi()..response = Completer<TimerOutcome>();
    final container = ProviderContainer(
      overrides: [timerApiProvider.overrideWithValue(api)],
    );
    addTearDown(container.dispose);
    final actions = container.read(timerActionsProvider.notifier);
    final first = actions.submit(displayed, TimerAction.start, jobId: 'job-a');
    expect(container.read(timerActionsProvider).working, isTrue);
    await actions.submit(displayed, TimerAction.start, jobId: 'job-b');
    expect(api.calls, hasLength(1));
    api.response!.complete(const TimerOutcome(2, null));
    await first;
    expect(container.read(timerActionsProvider).working, isFalse);
  });

  test('stale conflict re-queries authoritative state without retrying', () async {
    final api = ControlledApi()
      ..snapshotVersion = 9
      ..outcome = const TimerOutcome(9, 'STALE_STATE');
    final container = ProviderContainer(
      overrides: [timerApiProvider.overrideWithValue(api)],
    );
    addTearDown(container.dispose);
    final actions = container.read(timerActionsProvider.notifier);
    await actions.submit(displayed, TimerAction.stop);
    expect(container.read(timerActionsProvider).conflict, 'STALE_STATE');
    expect(container.read(timerActionsProvider).pending, isNull);
    await actions.retry();
    expect(api.calls, hasLength(1));
    expect((await container.read(timerSnapshotProvider.future)).version, 9);
  });
}
