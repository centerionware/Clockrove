import 'dart:async';

import 'package:clockrove/business/invoice_detail_screen.dart';
import 'package:clockrove/business/payment_api.dart';
import 'package:clockrove/business/payment_editor.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/payment_fixture.dart';

class _Timer extends TimerApi {
  _Timer(this.events);
  final Stream<int> events;
  @override
  Stream<int> get committedVersions => events;
}

class _Payments extends PaymentApi {
  InvoicePayments current = paymentFixture();
  final calls = <({String id, int version, PaymentDraft draft})>[];
  final reversals = <PaymentReversal>[];
  final copies = <({String id, int version, String token, String invoice})>[];
  Future<TimerOutcome> Function()? onCopy;
  Future<TimerOutcome> Function()? onRecord;
  @override
  Future<InvoicePayments> read(String id) async => current;
  @override
  Future<TimerOutcome> record(
    String id,
    int version,
    PaymentDraft draft,
  ) async {
    calls.add((id: id, version: version, draft: draft));
    return onRecord == null ? const TimerOutcome(2, null) : await onRecord!();
  }

  @override
  Future<TimerOutcome> balanceCopy(
    String id,
    int version,
    String token,
    String invoiceId,
  ) async {
    copies.add((id: id, version: version, token: token, invoice: invoiceId));
    return onCopy == null ? const TimerOutcome(2, null) : await onCopy!();
  }

  @override
  Future<TimerOutcome> reverse(
    String id,
    int version,
    PaymentReversal draft,
  ) async {
    reversals.add(draft);
    return const TimerOutcome(2, null);
  }
}

class _Documents extends ReportApi {
  final emailed = <String>[];
  @override
  Future<InvoicePage> previewCopy(String id, String copyId, int page) async =>
      throw StateError('Actual PDF validation runs on Android');
  @override
  Future<void> emailCopy(String id, String copyId) async {
    emailed.add(copyId);
  }
}

Future<void> _show(
  WidgetTester tester,
  _Payments api,
  Widget page, {
  Stream<int> events = const Stream.empty(),
  ReportApi? documents,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        paymentApiProvider.overrideWithValue(api),
        timerApiProvider.overrideWithValue(_Timer(events)),
        if (documents != null) reportApiProvider.overrideWithValue(documents),
      ],
      child: MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => Navigator.push<String>(
                context,
                MaterialPageRoute<String>(builder: (_) => page),
              ),
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
}

Future<void> _reveal(WidgetTester tester, String label) async {
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pumpAndSettle();
  final scrollable = find.byType(Scrollable).first;
  tester.state<ScrollableState>(scrollable).position.jumpTo(0);
  await tester.pumpAndSettle();
  await tester.scrollUntilVisible(
    find.text(label),
    250,
    scrollable: scrollable,
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'partial payment validates exact amount, preserves invalid input and records frozen currency',
    (tester) async {
      final api = _Payments();
      await _show(
        tester,
        api,
        const InvoicePaymentEditor(invoiceId: 'invoice'),
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Payment amount (USD)'),
        '30.001',
      );
      await _reveal(tester, 'Confirm payment');
      await tester.tap(find.text('Confirm payment'));
      await tester.pumpAndSettle();
      expect(api.calls, isEmpty);
      await _reveal(tester, 'Payment amount (USD)');
      final field = tester.widget<TextField>(
        find.widgetWithText(TextField, 'Payment amount (USD)'),
      );
      expect(field.controller!.text, '30.001');
      expect(field.decoration!.errorText, contains('2 decimal places'));
      await tester.enterText(
        find.widgetWithText(TextField, 'Payment amount (USD)'),
        '30.00',
      );
      await _reveal(tester, 'Confirm payment');
      await tester.tap(find.text('Confirm payment'));
      await tester.pumpAndSettle();
      expect(api.calls.single.draft.amount, '3000');
      expect(api.calls.single.draft.currency, 'USD');
      expect(api.calls.single.draft.received, 1793494800000);
      expect(find.text('Open'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'mark paid proposes remaining balance and unknown reply retries the same intent after committed refresh',
    (tester) async {
      final api = _Payments(),
          events = StreamController<int>.broadcast(),
          reply = Completer<TimerOutcome>();
      api.onRecord = () => reply.future;
      await _show(
        tester,
        api,
        const InvoicePaymentEditor(invoiceId: 'invoice', settle: true),
        events: events.stream,
      );
      final amount = tester.widget<TextField>(
        find.widgetWithText(TextField, 'Payment amount (USD)'),
      );
      expect(amount.controller!.text, '107.50');
      await _reveal(tester, 'Confirm payment');
      await tester.tap(find.text('Confirm payment'));
      await tester.pump();
      expect(api.calls.length, 1);
      api.current = paymentFixture(
        version: 2,
        paid: '10750',
        due: '0',
        status: 'PAID',
      );
      events.add(2);
      await tester.pump();
      await tester.pump();
      await tester.pump();
      reply.completeError(StateError('Native commit reply was lost'));
      await tester.pumpAndSettle();
      await _reveal(tester, 'Retry same operation');
      expect(find.text('Retry same operation'), findsOneWidget);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(find.byType(InvoicePaymentEditor), findsOneWidget);
      api.onRecord = () async => const TimerOutcome(2, null);
      await tester.tap(find.text('Retry same operation'));
      await tester.pumpAndSettle();
      expect(api.calls.length, 2);
      expect(api.calls[0].id, api.calls[1].id);
      expect(api.calls[0].version, api.calls[1].version);
      expect(identical(api.calls[0].draft, api.calls[1].draft), isTrue);
      expect(api.calls[1].draft.amount, '10750');
      expect(find.text('Open'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await events.close();
    },
  );
  testWidgets(
    'latest-copy response loss retains its command while live payment removes the action row',
    (tester) async {
      final api = _Payments(),
          events = StreamController<int>.broadcast(),
          reply = Completer<TimerOutcome>(),
          documents = _Documents();
      api.onCopy = () => reply.future;
      await _show(
        tester,
        api,
        const InvoiceDetailScreen(invoiceId: 'invoice'),
        events: events.stream,
        documents: documents,
      );
      await _reveal(tester, 'Send latest copy');
      await tester.tap(find.text('Send latest copy'));
      await tester.pump();
      expect(api.copies.length, 1);
      api.current = paymentFixture(
        version: 3,
        paid: '10750',
        due: '0',
        status: 'PAID',
      );
      events.add(3);
      await tester.pump();
      await tester.pump();
      await tester.pump();
      reply.completeError(StateError('Copy reply was lost'));
      await tester.pumpAndSettle();
      await _reveal(tester, 'Retry same operation');
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();
      expect(find.byType(InvoiceDetailScreen), findsOneWidget);
      api.onCopy = () async => const TimerOutcome(2, null);
      await tester.tap(find.text('Retry same operation'));
      await tester.pumpAndSettle();
      expect(api.copies.length, 2);
      expect(api.copies[0], api.copies[1]);
      expect(documents.emailed.single, 'balance-${api.copies[0].token}');
      expect(api.calls, isEmpty);
      expect(find.text('Retry PDF preview'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await events.close();
    },
  );
  testWidgets(
    'correction requires an audit reason and keeps original payment identity',
    (tester) async {
      const payment = InvoicePayment(
        id: 'payment',
        originalInvoiceId: 'invoice',
        amount: '3000',
        currency: 'USD',
        received: 1793494799000,
        timezone: 'UTC',
        offsetSeconds: 0,
        receivedLabel: 'Synthetic received date',
        method: 'Transfer',
        reference: '',
        note: '',
        recorded: 1793494800000,
      );
      final api = _Payments()
        ..current = paymentFixture(
          paid: '3000',
          due: '7750',
          status: 'PARTIALLY_PAID',
          payments: [payment],
        );
      await _show(
        tester,
        api,
        const InvoicePaymentEditor(invoiceId: 'invoice', reversal: payment),
      );
      await _reveal(tester, 'Confirm reversal');
      await tester.tap(find.text('Confirm reversal'));
      await tester.pumpAndSettle();
      expect(api.reversals, isEmpty);
      await _reveal(tester, 'Reason for correction');
      await tester.enterText(
        find.widgetWithText(TextField, 'Reason for correction'),
        'Recorded against wrong reference',
      );
      await _reveal(tester, 'Confirm reversal');
      await tester.tap(find.text('Confirm reversal'));
      await tester.pumpAndSettle();
      expect(api.reversals.single.paymentId, 'payment');
      expect(api.reversals.single.note, 'Recorded against wrong reference');
      expect(api.calls, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'deleted-job invoice detail still offers payments and original document without requiring coverage review',
    (tester) async {
      final api = _Payments()
        ..current = paymentFixture(deleted: true, legacy: true);
      await _show(tester, api, const InvoiceDetailScreen(invoiceId: 'invoice'));
      expect(
        find.text('Job deleted · financial records retained'),
        findsOneWidget,
      );
      expect(find.text('Record payment'), findsOneWidget);
      expect(find.text('Mark paid'), findsOneWidget);
      expect(find.text('Review billed work'), findsNothing);
      expect(find.text('Unpaid'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
