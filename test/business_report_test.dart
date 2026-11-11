import 'dart:async';
import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/archive_screen.dart';
import 'package:clockrove/business/invoice_screen.dart';
import 'package:clockrove/business/lifecycle_api.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/business/report_map.dart';
import 'package:clockrove/business/report_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Versions extends TimerApi {
  final versions = StreamController<int>.broadcast();
  @override Stream<int> get committedVersions => versions.stream;
}
class _Reports extends ReportApi {
  _Reports(this.versions);
  final _Versions versions;
  final attempts = <(String, int, String)>[];
  int version = 1;
  ReportSelection? exported;
  @override Future<ReportContext> context() async => const ReportContext('UTC', [ReportJob('job', 'Synthetic renovation', false)]);
  @override Future<WorkReport> report(ReportSelection s) async => WorkReport(version, '', '', [const ReportTotal('job', 'Synthetic renovation', 'TASK', 'USD', 2, '3600000', null, '12345', 0)], const [], [const ReportTask('task', 'job', 'Install tiles', '2026-11-01T12:00:00.000Z', 'USD', '12345')], const [], const []);
  @override Future<TimerOutcome> prepare(String id, int expected, String invoiceId, ReportSelection s) async {
    attempts.add((id, expected, invoiceId));
    if (attempts.length == 1) { version = 2; versions.versions.add(2); throw StateError('Synthetic response loss after native commit'); }
    return const TimerOutcome(2, null);
  }
  @override Future<InvoicePage> preview(String id, int page) async => throw StateError('Synthetic unavailable preview; no mocked PDF success');
  @override Future<bool> saveCsv(ReportSelection s, int version, bool gps) async { exported = s; return true; }
}
class _Archive extends LifecycleApi {
  @override Future<ArchiveData> archived() async => const ArchiveData(1, [ArchivedJob('waiting', 'Synthetic recent archive', 10000, false), ArchivedJob('eligible', 'Synthetic older archive', 10000, true)]);
}
void main() {
  test('display preserves fractional elapsed seconds and selection equality', () {
    expect(reportDuration('600'), '0h 0m 0.6s'); expect(reportDuration('3600000'), '1h 0m');
    expect(ReportSelection('2026-11-01', '2026-11-02', 'UTC', ['job']), ReportSelection('2026-11-01', '2026-11-02', 'UTC', ['job']));
  });
  test('traces do not bridge session boundaries, long gaps or the antimeridian', () {
    ReportPoint point(String id, String session, int time, double longitude) => ReportPoint(id, session, 'job', 'Synthetic', time, '', 1, longitude, 10);
    final segments = reportSegments([point('a','one',0,1), point('b','one',600000,2), point('c','one',3000000,3), point('d','two',5000,4), point('e','two',10000,5)]);
    expect(segments.map((s) => s.map((p) => p.id).toList()).toList(), [['a','b'], ['d','e']]);
    expect(reportSegments([point('a','one',0,179), point('b','one',600000,-179)]), isEmpty);
  });
  testWidgets('invoice response loss survives native refresh and retries original ID, version and invoice', (tester) async {
    final versions = _Versions(); addTearDown(versions.versions.close); final api = _Reports(versions);
    await tester.pumpWidget(ProviderScope(overrides: [reportApiProvider.overrideWithValue(api), timerApiProvider.overrideWithValue(versions)], child: MaterialApp(theme: ClockroveTheme.light(), home: const ReportScreen(jobId: 'job'))));
    await tester.pumpAndSettle(); expect(find.text('USD 123.45'), findsWidgets);
    await tester.scrollUntilVisible(find.text('PDF invoice'), 200, scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('PDF invoice')); await tester.pumpAndSettle();
    expect(find.text('Retry same operation'), findsOneWidget); expect(api.attempts.length, 1);
    await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
    expect(api.attempts.length, 2); expect(api.attempts[0], api.attempts[1]); expect(find.byType(InvoiceScreen), findsOneWidget); expect(find.text('Retry PDF preview'), findsOneWidget); expect(tester.takeException(), isNull);
  });
  testWidgets('CSV exports use exactly the displayed report selection', (tester) async {
    final versions = _Versions(); addTearDown(versions.versions.close); final api = _Reports(versions);
    await tester.pumpWidget(ProviderScope(overrides: [reportApiProvider.overrideWithValue(api), timerApiProvider.overrideWithValue(versions)], child: const MaterialApp(home: ReportScreen(jobId: 'job'))));
    await tester.pumpAndSettle(); await tester.scrollUntilVisible(find.text('CSV export'), 200, scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('CSV export')); await tester.pumpAndSettle();
    expect(api.exported!.jobs, ['job']); expect(api.exported!.timezone, 'UTC'); expect(find.text('CSV saved.'), findsOneWidget);
  });
  testWidgets('archive exposes destructive deletion only for native eligible jobs', (tester) async {
    final versions = _Versions(); addTearDown(versions.versions.close);
    await tester.pumpWidget(ProviderScope(overrides: [lifecycleApiProvider.overrideWithValue(_Archive()), timerApiProvider.overrideWithValue(versions)], child: MaterialApp(theme: ClockroveTheme.light(), home: const ArchiveScreen())));
    await tester.pumpAndSettle(); expect(find.text('Permanently delete job'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Permanently delete job'), 200, scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('Permanently delete job')); await tester.pumpAndSettle();
    expect(find.textContaining('This cannot be undone.'), findsOneWidget); expect(find.text('Delete permanently'), findsOneWidget); expect(find.textContaining('Independent reusable task templates and customers are kept.'), findsOneWidget);
  });
}
