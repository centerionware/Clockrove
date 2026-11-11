import 'package:clockrove/app_theme.dart';
import 'package:clockrove/tracking/history_api.dart';
import 'package:clockrove/tracking/history_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

const _entry = HistoryEntry(id: 'entry', jobId: 'job', start: 1793511000123, end: 1793514600789,
  timezone: 'America/New_York', offsetSeconds: -14400, origin: 'WIDGET', needsReview: true);
class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
  @override
  Future<TimerSnapshot> snapshot() async => const TimerSnapshot(12, [], null);
}
class _History extends HistoryApi {
  HistoryEntry? entry = _entry;
  int version = 12;
  bool loseReply = false, gap = false;
  final calls = <(String, int, HistoryDraft)>[];
  final deletions = <(String, int, String)>[];
  final auditCursors = <int?>[];
  @override
  Future<HistoryContext> context() async => const HistoryContext('UTC', 1793514600789);
  @override
  Future<HistoryData> history(String jobId, {HistoryCursor? before, int limit = 200}) async => HistoryData(version, entry == null ? [] : [entry!], null);
  @override
  Future<HistoryDetail> detail(String jobId, String sessionId) async => HistoryDetail(version, entry);
  @override
  Future<HistoryTime> format(int instant, String timezone) async {
    final local = timezone == 'UTC' ? DateTime.fromMillisecondsSinceEpoch(instant, isUtc: true).toIso8601String().replaceAll('Z', '') : instant == _entry.start ? '2026-11-01T01:30:00.123' : '2026-11-01T01:30:00.789';
    return HistoryTime(local, instant == _entry.start ? -14400 : -18000, local);
  }
  @override
  Future<List<HistoryTimeChoice>> resolve(String localTime, String timezone) async {
    if (gap) return [];
    if (timezone == 'UTC') return [HistoryTimeChoice(DateTime.parse('${localTime}Z').millisecondsSinceEpoch, 0, '$localTime +00:00')];
    final fraction = localTime.endsWith('123') ? 123 : 789;
    return [HistoryTimeChoice(1793511000000 + fraction, -14400, '$localTime -04:00'), HistoryTimeChoice(1793514600000 + fraction, -18000, '$localTime -05:00')];
  }
  @override
  Future<TimerOutcome> correct(String id, int version, HistoryDraft draft) async {
    calls.add((id, version, draft));
    if (loseReply && calls.length == 1) throw PlatformException(code: 'LOST_REPLY');
    return TimerOutcome(version + 1, null);
  }
  @override
  Future<TimerOutcome> create(String id, int version, HistoryDraft draft) => correct(id, version, draft);
  @override
  Future<TimerOutcome> delete(String id, int version, String sessionId) async {
    deletions.add((id, version, sessionId));
    if (loseReply && deletions.length == 1) throw PlatformException(code: 'LOST_REPLY');
    return TimerOutcome(version + 1, null);
  }
  @override
  Future<HistoryAudit> audit(String jobId, {int? before, int limit = 100}) async {
    auditCursors.add(before);
    return HistoryAudit(version, [HistoryEvent(before == null ? 5 : 4, 'deleted-entry', HistoryRevision(
      id: 'revision-${before ?? 5}', changed: 1793514600789, origin: before == null ? 'MANUAL_DELETE' : 'MANUAL_ENTRY',
      deleted: before == null, payload: before == null ? '{"id":"deleted-entry"}' : '{"start_ms":1793511000123,"end_ms":1793514600789,"timezone":"America/New_York"}', parents: []))], before == null ? 5 : null);
  }
  @override
  Future<List<HistoryRevision>> revisions(String jobId, String sessionId) async => (await audit(jobId)).events.map((e) => e.revision).toList();
}
Future<void> _show(WidgetTester tester, _History api, Widget screen, {bool dark = false, double scale = 1, GlobalKey? capture}) async {
  tester.view.physicalSize = const Size(420, 920);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(ProviderScope(overrides: [historyApiProvider.overrideWithValue(api), timerApiProvider.overrideWithValue(_Timer())],
    child: RepaintBoundary(key: capture, child: MaterialApp(theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
      debugShowCheckedModeBanner: false, builder: (context, child) => MediaQuery(data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)), child: child!),
      home: Builder(builder: (context) => Scaffold(body: TextButton(onPressed: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => screen)), child: const Text('Open')))),
    )));
  await tester.tap(find.text('Open')); await tester.pumpAndSettle();
}
Future<void> _reveal(WidgetTester tester, String label) async {
  final scrollable = find.byType(Scrollable).first;
  tester.state<ScrollableState>(scrollable).position.jumpTo(0);
  await tester.pumpAndSettle();
  await tester.scrollUntilVisible(find.text(label), 200, scrollable: scrollable);
  await tester.pumpAndSettle();
}
void main() {
  testWidgets('correction preserves exact bounds and selected repeated-hour offsets; lost response retries same intent', (tester) async {
    final api = _History()..loseReply = true;
    await _show(tester, api, const HistoryEditor(jobId: 'job', sessionId: 'entry'));
    await _reveal(tester, 'Save correction');
    await tester.tap(find.text('Save correction')); await tester.pumpAndSettle();
    expect(api.calls.single.$3.start, _entry.start); expect(api.calls.single.$3.end, _entry.end);
    expect(api.calls.single.$3.offsetSeconds, -14400);
    expect(find.text('Retry same operation'), findsOneWidget);
    await tester.pageBack(); await tester.pumpAndSettle();
    expect(find.text('Work entry'), findsOneWidget);
    await _reveal(tester, 'Retry same operation');
    await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
    expect(api.calls.length, 2); expect(api.calls[1].$1, api.calls[0].$1);
    expect(api.calls[1].$2, 12); expect(identical(api.calls[1].$3, api.calls[0].$3), isTrue);
    expect(find.text('Open'), findsOneWidget);
  });
  testWidgets('edited ambiguous time requires an explicit offset and a clock gap cannot save', (tester) async {
    final api = _History();
    await _show(tester, api, const HistoryEditor(jobId: 'job', sessionId: 'entry'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Start local time'), '2026-11-01T01:30:00.123');
    await _reveal(tester, 'Review times and UTC offsets');
    await tester.tap(find.text('Review times and UTC offsets')); await tester.pumpAndSettle();
    await _reveal(tester, 'Save correction');
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save correction')).onPressed, isNull);
    expect(api.calls, isEmpty);
    api.gap = true;
    await _reveal(tester, 'Review times and UTC offsets');
    await tester.tap(find.text('Review times and UTC offsets')); await tester.pumpAndSettle();
    expect(find.textContaining('does not exist'), findsOneWidget);
    expect(api.calls, isEmpty);
  });
  testWidgets('draft survives changed saved entry; refresh never silently authorizes overwrite', (tester) async {
    final api = _History();
    await _show(tester, api, const HistoryEditor(jobId: 'job', sessionId: 'entry'));
    api.version = 13;
    api.entry = const HistoryEntry(id: 'entry', jobId: 'job', start: 1793511000123, end: 1793514600999,
      timezone: 'America/New_York', offsetSeconds: -14400, origin: 'MANUAL', needsReview: false);
    await _reveal(tester, 'Check for changes · keep draft');
    await tester.tap(find.text('Check for changes · keep draft')); await tester.pumpAndSettle();
    expect(find.textContaining('saved entry changed'), findsOneWidget);
    await _reveal(tester, 'Save correction');
    await tester.tap(find.text('Save correction')); await tester.pumpAndSettle();
    expect(api.calls.single.$2, 12); expect(api.calls.single.$3.end, _entry.end);
  });
  testWidgets('delete confirmation locks cancel and retains same command after lost response', (tester) async {
    final api = _History()..loseReply = true;
    await _show(tester, api, const HistoryEditor(jobId: 'job', sessionId: 'entry'));
    await _reveal(tester, 'Delete work entry');
    await tester.tap(find.text('Delete work entry')); await tester.pumpAndSettle();
    expect(api.deletions, isEmpty);
    await tester.tap(find.text('Delete entry')); await tester.pumpAndSettle();
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, 'Cancel')).onPressed, isNull);
    await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
    expect(api.deletions.length, 2); expect(api.deletions[1], api.deletions[0]);
    expect(find.text('Open'), findsOneWidget);
  });
  testWidgets('deleted entries remain reachable through paginated job change history', (tester) async {
    final api = _History()..entry = null;
    await _show(tester, api, const HistoryAuditScreen(jobId: 'job'));
    expect(find.text('manual delete'), findsOneWidget);
    await tester.tap(find.text('Load older changes')); await tester.pumpAndSettle();
    expect(api.auditCursors, [null, 5]); expect(find.text('manual entry'), findsOneWidget);
    await tester.tap(find.text('manual delete')); await tester.pumpAndSettle();
    expect(find.text('Entry changes'), findsOneWidget); expect(find.text('Work entry removed'), findsNothing);
    expect(find.textContaining('Work entry removed'), findsOneWidget);
  });
  testWidgets('running entry explains why correction and deletion are unavailable', (tester) async {
    final api = _History()..entry = const HistoryEntry(id: 'entry', jobId: 'job', start: 1793511000123, end: null,
      timezone: 'UTC', offsetSeconds: 0, origin: 'MANUAL', needsReview: false);
    await _show(tester, api, const HistoryEditor(jobId: 'job', sessionId: 'entry'));
    expect(find.textContaining('Clock out before'), findsOneWidget);
    expect(find.text('Save correction'), findsNothing); expect(find.text('Delete work entry'), findsNothing);
  });
  testWidgets('new manual entry uses native current timezone and keeps millisecond bounds', (tester) async {
    final api = _History();
    await _show(tester, api, const HistoryEditor(jobId: 'job'));
    await _reveal(tester, 'Save work entry'); await tester.tap(find.text('Save work entry')); await tester.pumpAndSettle();
    expect(api.calls.single.$3.start, 1793511000789); expect(api.calls.single.$3.end, 1793514600789);
    expect(api.calls.single.$3.timezone, 'UTC'); expect(api.calls.single.$3.sessionId, isNotEmpty);
  });
  for (final dark in [false, true]) {
    testWidgets('history views ${dark ? 'dark' : 'light'} synthetic preview', (tester) async {
      await loadMapPreviewFonts();
      for (final item in <(String, Widget)>[
        ('list', const HistoryScreen(jobId: 'job', jobName: 'Riverside renovation')),
        ('editor', const HistoryEditor(jobId: 'job', sessionId: 'entry')),
        ('changes', const HistoryAuditScreen(jobId: 'job')),
      ]) {
        final key = GlobalKey();
        await _show(tester, _History(), item.$2, dark: dark, capture: key);
        expect(tester.takeException(), isNull);
        await saveMapPreview(key, 'clockrove-history-${item.$1}-${dark ? 'dark' : 'light'}');
        await tester.pumpWidget(const SizedBox.shrink());
      }
    });
  }
  testWidgets('large-text editor stays scrollable to save and delete actions', (tester) async {
    await _show(tester, _History(), const HistoryEditor(jobId: 'job', sessionId: 'entry'), scale: 2);
    await _reveal(tester, 'Save correction'); expect(find.text('Save correction').hitTestable(), findsOneWidget);
    await _reveal(tester, 'Delete work entry'); expect(find.text('Delete work entry').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
