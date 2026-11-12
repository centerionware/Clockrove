import 'dart:async';

import 'package:clockrove/app_theme.dart';
import 'package:clockrove/business/invoice_review_screen.dart';
import 'package:clockrove/business/legacy_covered_time_dialog.dart';
import 'package:clockrove/business/legacy_invoice_api.dart';
import 'package:clockrove/business/legacy_invoice_screen.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/tracking/history_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

class _Versions extends TimerApi {
  final events = StreamController<int>.broadcast();
  @override
  Stream<int> get committedVersions => events.stream;
}

class _Legacy extends LegacyInvoiceApi {
  _Legacy(this.versions, {this.loseReply = false, this.failReload = false});
  final _Versions versions;
  final bool loseReply, failReload;
  bool saved = false;
  int reads = 0;
  final calls = <(String, int, LegacyInvoiceDraft)>[];
  @override
  Future<LegacyInvoiceContext> context(String id) async {
    reads++;
    if (saved && failReload) throw StateError('Synthetic read failure after commit');
    return LegacyInvoiceContext(version: saved ? 2 : 1, invoiceId: id, number: 'INV-1000', jobId: 'job', jobName: 'Riverside renovation',
      total: '100.00', currency: 'USD', period: '2026-11-01 · UTC', timezone: 'UTC', legacy: !saved, jobDeleted: false,
      note: saved ? calls.first.$3.note : '', tasks: [
        LegacyInvoiceTask('task', 'Install tiles', '2026-11-01T12:00:00.000Z', saved ? 'legacy' : null, saved ? 'INV-1000' : null),
        const LegacyInvoiceTask('reserved', 'Reserved task', null, 'other', 'INV-999'),
      ], reservedTimes: const []);
  }
  @override
  Future<TimerOutcome> reconcile(String id, int version, LegacyInvoiceDraft draft) async {
    calls.add((id, version, draft)); saved = true; versions.events.add(2);
    if (loseReply && calls.length == 1) throw StateError('Synthetic reply lost after commit');
    return const TimerOutcome(2, null);
  }
}

class _Reports extends ReportApi {
  @override
  Future<InvoiceBillingPreview> invoiceBillingPreview(ReportSelection selection) async => const InvoiceBillingPreview(
    version: 1, jobId: 'job', jobName: 'Riverside renovation', currency: 'USD', taxUnits: 75000,
    subtotal: '200.00', tax: '15.00', total: '215.00', lines: [InvoiceBillingLine('Install kitchen fixtures', 'Completed 2026-11-02', '200.00')],
    excluded: [InvoiceCoverageLink('legacy', 'INV-1000', title: 'Install tiles', detail: 'Previously invoiced task')],
  );
}

// Presentation choice handling only; native and actual Android suites prove DST.
class _Times extends HistoryApi {
  static final early = DateTime.utc(2026, 11, 1, 5, 30).millisecondsSinceEpoch;
  static final late = DateTime.utc(2026, 11, 1, 6, 30).millisecondsSinceEpoch;
  static final finish = DateTime.utc(2026, 11, 1, 7, 30).millisecondsSinceEpoch;
  @override
  Future<List<HistoryTimeChoice>> resolve(String input, String timezone) async {
    if (input.contains('02:15')) return [];
    if (input.contains('01:30')) return [HistoryTimeChoice(early, -14400, 'Earlier UTC-04:00'), HistoryTimeChoice(late, -18000, 'Later UTC-05:00')];
    if (input.contains('02:30')) return [HistoryTimeChoice(finish, -18000, 'End UTC-05:00')];
    return [HistoryTimeChoice(early, -14400, 'Start UTC-04:00')];
  }
}

Future<void> _show(WidgetTester tester, _Legacy api, Widget screen, {Key? boundary, bool dark = false, double scale = 1}) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pumpAndSettle();
  await tester.pumpWidget(RepaintBoundary(key: boundary, child: ProviderScope(overrides: [
    timerApiProvider.overrideWithValue(api.versions), legacyInvoiceApiProvider.overrideWithValue(api),
    reportApiProvider.overrideWithValue(_Reports()), historyApiProvider.overrideWithValue(_Times()),
  ], child: MaterialApp(theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
    builder: (context, child) => MediaQuery(data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)), child: child!),
    home: Builder(builder: (context) => Scaffold(body: TextButton(onPressed: () => Navigator.push<bool>(context,
      MaterialPageRoute<bool>(builder: (_) => screen)), child: const Text('Open')))),
  ))));
  await tester.tap(find.text('Open')); await tester.pumpAndSettle();
}
Future<void> _reveal(WidgetTester tester, String label) async {
  final scroll = find.byType(Scrollable).first;
  tester.state<ScrollableState>(scroll).position.jumpTo(0);
  await tester.pumpAndSettle();
  await tester.scrollUntilVisible(find.text(label), 200, scrollable: scroll);
  await tester.pumpAndSettle();
}
Future<void> _noteAndConfirm(WidgetTester tester) async {
  await _reveal(tester, 'Why this work belongs to the invoice');
  await tester.enterText(find.widgetWithText(TextField, 'Why this work belongs to the invoice'), 'Checked the original invoice and selected its billed work.');
  await _reveal(tester, 'I checked this against the original invoice');
  await tester.tap(find.text('I checked this against the original invoice')); await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final failReload in [false, true]) {
    testWidgets('legacy review keeps its original intent across lost reply and native refresh (read failure: $failReload)', (tester) async {
      final versions = _Versions(); addTearDown(versions.events.close);
      final api = _Legacy(versions, loseReply: true, failReload: failReload);
      await _show(tester, api, const LegacyInvoiceScreen(invoiceId: 'legacy'));
      await _reveal(tester, 'Install tiles');
      await tester.tap(find.widgetWithText(CheckboxListTile, 'Install tiles')); await tester.pumpAndSettle();
      await _reveal(tester, 'Reserved task');
      expect(tester.widget<CheckboxListTile>(find.widgetWithText(CheckboxListTile, 'Reserved task')).onChanged, isNull);
      await _noteAndConfirm(tester);
      await tester.tap(find.text('Save coverage review')); await tester.pumpAndSettle();
      expect(find.text('Retry same operation'), findsOneWidget); expect(api.calls.length, 1); expect(api.reads, greaterThan(1));
      await tester.binding.handlePopRoute(); await tester.pumpAndSettle();
      expect(find.byType(LegacyInvoiceScreen), findsOneWidget);
      await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
      expect(api.calls.length, 2); expect(api.calls[0].$1, api.calls[1].$1); expect(api.calls[0].$2, api.calls[1].$2);
      expect(api.calls[0].$3.encode(), api.calls[1].$3.encode()); expect(api.calls[0].$3.tasks, ['task']);
      expect(find.text('Open'), findsOneWidget); expect(tester.takeException(), isNull);
    });
  }
  testWidgets('missing-source mapping needs explicit acknowledgment, a note and confirmation', (tester) async {
    final versions = _Versions(); addTearDown(versions.events.close); final api = _Legacy(versions);
    await _show(tester, api, const LegacyInvoiceScreen(invoiceId: 'legacy'));
    await tester.tap(find.text('Save coverage review')); await tester.pumpAndSettle(); expect(api.calls, isEmpty);
    await _noteAndConfirm(tester);
    await tester.tap(find.text('Save coverage review')); await tester.pumpAndSettle(); expect(api.calls, isEmpty);
    await _reveal(tester, 'No source records can be linked');
    await tester.tap(find.text('No source records can be linked')); await tester.pumpAndSettle();
    await _reveal(tester, 'I checked this against the original invoice');
    await tester.tap(find.text('I checked this against the original invoice')); await tester.pumpAndSettle();
    await tester.tap(find.text('Save coverage review')); await tester.pumpAndSettle();
    expect(api.calls.single.$3.noSources, isTrue); expect(api.calls.single.$3.confirmed, isTrue);
    expect(api.calls.single.$3.tasks, isEmpty); expect(api.calls.single.$3.times, isEmpty);
    expect(find.text('Open'), findsOneWidget); expect(tester.takeException(), isNull);
  });
  testWidgets('covered time requires an explicit offset for a repeated local time', (tester) async {
    LegacyCoveredTime? saved;
    await tester.pumpWidget(ProviderScope(overrides: [historyApiProvider.overrideWithValue(_Times())], child: MaterialApp(home: Builder(builder: (context) => Scaffold(body: TextButton(
      onPressed: () async { saved = await showDialog<LegacyCoveredTime>(context: context, builder: (_) => const LegacyCoveredTimeDialog(timezone: 'America/New_York', otherTimes: [], reservedTimes: [])); }, child: const Text('Add')))))));
    await tester.tap(find.text('Add')); await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Covered start'), '2026-11-01T01:30:00.000');
    await tester.enterText(find.widgetWithText(TextField, 'Covered end'), '2026-11-01T02:30:00.000');
    await tester.ensureVisible(find.text('Review times and UTC offsets'));
    await tester.tap(find.text('Review times and UTC offsets')); await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Use covered time')).onPressed, isNull);
    await tester.ensureVisible(find.byType(DropdownButton<HistoryTimeChoice>));
    await tester.tap(find.byType(DropdownButton<HistoryTimeChoice>)); await tester.pumpAndSettle();
    await tester.tap(find.text('Later UTC-05:00').last); await tester.pumpAndSettle();
    await tester.tap(find.text('Use covered time')); await tester.pumpAndSettle();
    expect(saved!.start, _Times.late); expect(saved!.end, _Times.finish); expect(saved!.timezone, 'America/New_York');
    expect(tester.takeException(), isNull);
  });
  testWidgets('nonexistent and already-reserved times stay uncommittable with the draft kept', (tester) async {
    await tester.pumpWidget(ProviderScope(overrides: [historyApiProvider.overrideWithValue(_Times())], child: MaterialApp(home: Builder(builder: (context) => Scaffold(body: TextButton(
      onPressed: () => showDialog<LegacyCoveredTime>(context: context, builder: (_) => LegacyCoveredTimeDialog(timezone: 'America/New_York', otherTimes: const [], reservedTimes: [
        LegacyReservedTime(LegacyCoveredTime(start: _Times.early, end: _Times.finish, timezone: 'America/New_York'), 'other', 'INV-999'),
      ])), child: const Text('Add')))))));
    await tester.tap(find.text('Add')); await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Covered start'), '2026-03-08T02:15:00.000');
    await tester.enterText(find.widgetWithText(TextField, 'Covered end'), '2026-11-01T02:30:00.000');
    await tester.ensureVisible(find.text('Review times and UTC offsets'));
    await tester.tap(find.text('Review times and UTC offsets')); await tester.pumpAndSettle();
    expect(find.textContaining('does not exist'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Use covered time')).onPressed, isNull);
    await tester.enterText(find.widgetWithText(TextField, 'Covered start'), '2026-11-01T00:30:00.000');
    await tester.ensureVisible(find.text('Review times and UTC offsets'));
    await tester.tap(find.text('Review times and UTC offsets')); await tester.pumpAndSettle();
    expect(find.textContaining('already reserved'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Use covered time')).onPressed, isNull);
    expect(tester.widget<TextField>(find.widgetWithText(TextField, 'Covered start')).controller!.text, '2026-11-01T00:30:00.000');
    expect(tester.takeException(), isNull);
  });
  for (final dark in [false, true]) {
    testWidgets('render invoice review and legacy source forms ${dark ? 'dark' : 'light'}', (tester) async {
      tester.view.physicalSize = const Size(390, 844); tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize); addTearDown(tester.view.resetDevicePixelRatio);
      final versions = _Versions(); addTearDown(versions.events.close); final api = _Legacy(versions);
      final key = GlobalKey(); await tester.runAsync(loadMapPreviewFonts);
      await _show(tester, api, InvoiceReviewScreen(selection: ReportSelection('2026-11-01', '2026-11-02', 'UTC', ['job'])), boundary: key, dark: dark);
      expect(find.text('USD 215.00'), findsOneWidget);
      await tester.runAsync(() => saveMapPreview(key, 'invoice-review-${dark ? 'dark' : 'light'}'));
      await _show(tester, api, const LegacyInvoiceScreen(invoiceId: 'legacy'), boundary: key, dark: dark, scale: 1.5);
      expect(tester.takeException(), isNull);
      await tester.runAsync(() => saveMapPreview(key, 'legacy-review-${dark ? 'dark' : 'light'}'));
    });
  }
}
