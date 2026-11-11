import 'package:clockrove/l10n/app_strings.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:clockrove/tracking/timer_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class ScreenApi extends TimerApi {
  int version = 1;
  final jobs = [const JobSummary('job-a', 'Synthetic first')];
  RunningSession? running;
  final intents = <TimerAction>[];

  @override
  Stream<int> get committedVersions => const Stream<int>.empty();

  @override
  Future<TimerSnapshot> snapshot() async =>
      TimerSnapshot(version, List.unmodifiable(jobs), running);

  @override
  Future<String> createJob(String name) async {
    jobs.add(JobSummary('job-b', name));
    version++;
    return 'job-b';
  }

  @override
  Future<TimerOutcome> execute({
    required String id,
    required int version,
    required TimerAction action,
    String? jobId,
  }) async {
    if (version != this.version) {
      return TimerOutcome(this.version, 'STALE_STATE');
    }
    intents.add(action);
    running = action == TimerAction.stop
        ? null
        : RunningSession(
            'synthetic-session',
            jobId!,
            jobs.singleWhere((job) => job.id == jobId).name,
            DateTime.utc(2026, 11, 1),
          );
    this.version++;
    return TimerOutcome(this.version, null);
  }
}

void main() {
  testWidgets('manual journey keeps New job last', (tester) async {
    final api = ScreenApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [timerApiProvider.overrideWithValue(api)],
        child: const MaterialApp(home: TimerScreen()),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text(AppStrings.idle), findsOneWidget);
    await tester.tap(find.text(AppStrings.clockIn));
    await tester.pumpAndSettle();
    expect(find.text(AppStrings.clockOut), findsOneWidget);
    await tester.tap(find.text(AppStrings.newJob));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Synthetic second');
    await tester.tap(find.text(AppStrings.create));
    await tester.pumpAndSettle();
    final tiles = tester.widgetList<ListTile>(find.byType(ListTile)).toList();
    expect((tiles.last.title! as Text).data, AppStrings.newJob);
    final second = find.widgetWithText(ListTile, 'Synthetic second');
    await tester.tap(
      find.descendant(of: second, matching: find.byType(TextButton)),
    );
    await tester.pumpAndSettle();
    expect(api.running!.jobId, 'job-b');
    await tester.tap(find.text(AppStrings.clockOut));
    await tester.pumpAndSettle();
    expect(find.text(AppStrings.idle), findsOneWidget);
    expect(api.intents, [
      TimerAction.start,
      TimerAction.switchJob,
      TimerAction.stop,
    ]);
  });
}
