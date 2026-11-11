import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'timer_api.dart';

final timerApiProvider = Provider<TimerApi>((ref) => const TimerApi());

final timerVersionsProvider = StreamProvider<int>(
  (ref) => ref.watch(timerApiProvider).committedVersions,
);

final timerSnapshotProvider = FutureProvider<TimerSnapshot>((ref) {
  ref.watch(timerVersionsProvider);
  return ref.watch(timerApiProvider).snapshot();
});

class TimerIntent {
  const TimerIntent(this.id, this.version, this.action, this.jobId);
  final String id;
  final int version;
  final TimerAction action;
  final String? jobId;
}

class TimerActionState {
  const TimerActionState({this.working = false, this.pending, this.conflict});
  final bool working;
  final TimerIntent? pending;
  final String? conflict;
}

final timerActionsProvider = NotifierProvider<TimerActions, TimerActionState>(
  TimerActions.new,
);

/// Presentation coordination only. Native snapshots and outcomes remain authoritative.
class TimerActions extends Notifier<TimerActionState> {
  bool _active = true;

  @override
  TimerActionState build() {
    _active = true;
    ref.onDispose(() => _active = false);
    return const TimerActionState();
  }

  Future<void> submit(
    TimerSnapshot displayed,
    TimerAction action, {
    String? jobId,
  }) async {
    if (state.working || state.pending != null) return;
    final random = Random.secure();
    final id = List.generate(16, (_) => random.nextInt(256))
        .map((value) => value.toRadixString(16).padLeft(2, '0'))
        .join();
    await _attempt(TimerIntent(id, displayed.version, action, jobId));
  }

  Future<void> retry() async {
    final pending = state.pending;
    if (pending == null || state.working) return;
    await _attempt(pending);
  }

  Future<void> _attempt(TimerIntent intent) async {
    state = TimerActionState(working: true, pending: intent);
    try {
      final outcome = await ref.read(timerApiProvider).execute(
        id: intent.id,
        version: intent.version,
        action: intent.action,
        jobId: intent.jobId,
      );
      if (!_active) return;
      state = TimerActionState(conflict: outcome.conflict);
    } catch (_) {
      if (!_active) return;
      // A lost response does not prove the native transaction failed. Keep its ID.
      state = TimerActionState(pending: intent);
    }
    if (_active) ref.invalidate(timerSnapshotProvider);
  }

  Future<void> createJob(String name) async {
    if (state.working || state.pending != null || name.trim().isEmpty) return;
    state = const TimerActionState(working: true);
    try {
      await ref.read(timerApiProvider).createJob(name.trim());
      if (_active) state = const TimerActionState();
    } catch (_) {
      if (_active) state = const TimerActionState(conflict: 'JOB_CREATE_FAILED');
    }
    if (_active) ref.invalidate(timerSnapshotProvider);
  }
}
