import 'dart:async';

import 'package:clockrove/business/invoice_lifecycle_api.dart';
import 'package:clockrove/business/invoice_lifecycle_editor.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/payment_fixture.dart';

InvoiceLifecycleData _data({int version = 1, String paid = '3000', String due = '7750', String status = 'PARTIALLY_PAID'}) => InvoiceLifecycleData(
  paymentFixture(version: version, paid: paid, due: due, status: status),
  const [InvoiceEditLine(0, 'Install tile', '1 × 100.00 USD each\nCompleted synthetic work', '10000', quantity: '10000', unitPrice: '10000')],
  const [InvoiceSourceClaim('claim', 'Synthetic tile reservation')], 75000,
);
class _Events extends TimerApi {
  _Events(this.events);
  final Stream<int> events;
  @override
  Stream<int> get committedVersions => events;
}
class _Lifecycle extends InvoiceLifecycleApi {
  InvoiceLifecycleData data = _data();
  final replacements = <({String id, int version, InvoiceReplacementDraft draft})>[];
  final voids = <InvoiceVoidDraft>[];
  Future<TimerOutcome> Function()? onReplace;
  @override
  Future<InvoiceLifecycleData> read(String invoiceId) async => data;
  @override
  Future<TimerOutcome> replace(String id, int version, InvoiceReplacementDraft draft) async {
    replacements.add((id: id, version: version, draft: draft));
    return onReplace == null ? const TimerOutcome(2, null) : await onReplace!();
  }
  @override
  Future<TimerOutcome> voidInvoice(String id, int version, InvoiceVoidDraft draft) async {
    voids.add(draft); return const TimerOutcome(2, null);
  }
}
Future<void> _show(WidgetTester tester, _Lifecycle api, {bool replace = true, Stream<int> events = const Stream.empty()}) async {
  await tester.pumpWidget(ProviderScope(overrides: [invoiceLifecycleApiProvider.overrideWithValue(api), timerApiProvider.overrideWithValue(_Events(events))],
    child: MaterialApp(home: Builder(builder: (context) => Scaffold(body: TextButton(onPressed: () => Navigator.push<InvoiceLifecycleResult>(context,
      MaterialPageRoute<InvoiceLifecycleResult>(builder: (_) => InvoiceLifecycleEditor(invoiceId: 'invoice', replace: replace))), child: const Text('Open')))))));
  await tester.tap(find.text('Open')); await tester.pumpAndSettle();
}
Finder _field(String label) => find.byWidgetPredicate((widget) => widget is TextField && widget.decoration?.labelText == label);
Future<void> _reveal(WidgetTester tester, Finder target) async {
  FocusManager.instance.primaryFocus?.unfocus(); await tester.pumpAndSettle();
  final scroll = find.byType(Scrollable).first;
  tester.state<ScrollableState>(scroll).position.jumpTo(0); await tester.pumpAndSettle();
  await tester.scrollUntilVisible(target, 200, scrollable: scroll); await tester.pumpAndSettle();
}
Future<void> _enter(WidgetTester tester, String label, String value) async {
  await _reveal(tester, _field(label)); await tester.enterText(_field(label), value); await tester.pumpAndSettle();
}
Future<void> _review(WidgetTester tester) async {
  await _reveal(tester, find.text('Review correction')); await tester.tap(find.text('Review correction')); await tester.pumpAndSettle();
}
void main() {
  test('replacement preview conserves exact tax, credit and amounts beyond machine integers', () {
    final value = replacementTotals(const [InvoiceEditLine(0, 'Work', '', '12345')], '0', 75000, '3000');
    expect(value, (subtotal: '12345', tax: '926', total: '13271', credit: '3000', due: '10271'));
    final wide = '9' * 1050;
    final result = replacementTotals([InvoiceEditLine(0, 'Wide', '', wide)], '0', 0, '1');
    expect(result.total, wide); expect(result.due, (BigInt.parse(wide) - BigInt.one).toString());
    expect(() => replacementTotals(const [InvoiceEditLine(0, 'Work', '', '2999')], '0', 0, '3000'), throwsFormatException);
    expect(() => replacementTotals(const [InvoiceEditLine(0, 'Work', '', '1.0')], '0', 0, '0'), throwsFormatException);
    expect(() => replacementTotals(const [], '0', 1000001, '0'), throwsFormatException);
  });
  test('replacement draft captures source rows and releases without mutable aliases', () {
    final lines = [const InvoiceEditLine(0, 'Work', '', '100', quantity: '260000', unitPrice: '4')], claims = ['claim'];
    final draft = InvoiceReplacementDraft(invoiceId: 'old', replacementId: 'new', lines: lines, taxUnits: 75000,
      reason: 'Synthetic correction', timezone: 'UTC', releasedClaims: claims);
    lines.clear(); claims.clear();
    expect(draft.lines.single.amount, '100'); expect(draft.releasedClaims, ['claim']);
    expect(() => draft.lines.clear(), throwsUnsupportedError);
    expect((draft.encode()['lines'] as List).single, containsPair('amountMinor', '100'));
  });
  testWidgets('replacement below existing credit keeps the draft and cannot open confirmation', (tester) async {
    final api = _Lifecycle(); await _show(tester, api);
    await _enter(tester, 'Unit price (USD)', '1.00');
    await _enter(tester, 'Internal correction reason', 'Synthetic correction reason');
    await _review(tester);
    expect(find.byType(AlertDialog), findsNothing); expect(api.replacements, isEmpty);
    await _reveal(tester, _field('Unit price (USD)'));
    expect(tester.widget<TextField>(_field('Unit price (USD)')).controller!.text, '1.00');
  });
  testWidgets('oversized pasted money is preserved and rejected rather than truncated', (tester) async {
    final api = _Lifecycle(); await _show(tester, api);
    final invalid = '120.00${' ' * 4100}1';
    await _enter(tester, 'Unit price (USD)', invalid);
    expect(tester.widget<TextField>(_field('Unit price (USD)')).controller!.text, invalid);
    await _enter(tester, 'Internal correction reason', 'Synthetic invalid pasted price'); await _review(tester);
    expect(find.byType(AlertDialog), findsNothing); expect(api.replacements, isEmpty);
  });
  testWidgets('review cancellation writes nothing and unpaid void needs explicit confirmation', (tester) async {
    final api = _Lifecycle()..data = _data(paid: '0', due: '10750', status: 'UNPAID');
    await _show(tester, api, replace: false);
    await _enter(tester, 'Internal correction reason', 'Synthetic cancellation reason'); await _review(tester);
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.text('Keep editing')); await tester.pumpAndSettle(); expect(api.voids, isEmpty);
    await _review(tester); await tester.tap(find.text('Confirm review')); await tester.pumpAndSettle();
    await _reveal(tester, find.widgetWithText(FilledButton, 'Confirm void')); await tester.tap(find.widgetWithText(FilledButton, 'Confirm void')); await tester.pumpAndSettle();
    expect(api.voids.single.invoiceId, 'invoice'); expect(api.voids.single.reason, 'Synthetic cancellation reason');
    expect(find.text('Open'), findsOneWidget); expect(tester.takeException(), isNull);
  });
  testWidgets('unknown replacement reply retains the original command when live context becomes void', (tester) async {
    final api = _Lifecycle(), events = StreamController<int>.broadcast(), reply = Completer<TimerOutcome>();
    api.onReplace = () => reply.future;
    await _show(tester, api, events: events.stream);
    await _enter(tester, 'Unit price (USD)', '120.00'); await _enter(tester, 'Replacement tax (%)', '10');
    await _enter(tester, 'Internal correction reason', 'Synthetic revised tile price'); await _review(tester);
    expect(find.textContaining('Remaining balance: USD 102.00'), findsOneWidget);
    await tester.tap(find.text('Confirm review')); await tester.pumpAndSettle();
    await _reveal(tester, find.text('Issue replacement invoice')); await tester.tap(find.text('Issue replacement invoice')); await tester.pump();
    expect(api.replacements.length, 1); expect(api.replacements.single.draft.lines.single.unitPrice, '12000');
    expect(api.replacements.single.draft.lines.single.quantity, '10000');
    api.data = _data(version: 2, paid: '0', due: '0', status: 'VOID'); events.add(2);
    await tester.pump(); await tester.pump(); await tester.pump();
    reply.completeError(StateError('Native replacement reply was lost')); await tester.pumpAndSettle();
    await _reveal(tester, find.text('Retry same operation'));
    await tester.tap(find.byTooltip('Back')); await tester.pumpAndSettle(); expect(find.byType(InvoiceLifecycleEditor), findsOneWidget);
    api.onReplace = () async => const TimerOutcome(2, null);
    await tester.tap(find.text('Retry same operation')); await tester.pumpAndSettle();
    expect(api.replacements.length, 2);
    expect(api.replacements[0].id, api.replacements[1].id); expect(api.replacements[0].version, api.replacements[1].version);
    expect(identical(api.replacements[0].draft, api.replacements[1].draft), isTrue);
    expect(find.text('Open'), findsOneWidget); expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink()); await tester.pump(); await events.close();
  });
}
