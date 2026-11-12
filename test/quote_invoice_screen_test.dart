import 'dart:convert';

import 'package:clockrove/app_theme.dart';

import 'package:clockrove/business/quote_api.dart';
import 'package:clockrove/business/quote_invoice_api.dart';
import 'package:clockrove/business/quote_invoice_screen.dart';
import 'package:clockrove/business/quote_scope_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/map_preview.dart';

Map<String, Object?> _issued({bool hourly = false}) => {
  'quoteId': 'quote',
  'revision': 1,
  'number': 'Q-1000',
  'created': 1793494800000,
  'decision': 'ACCEPTED',
  'decisionNote': 'Explicit synthetic local acceptance',
  'payload': jsonEncode({
    'draft': {
      'id': 'quote',
      'title': 'Tile installation',
      'currency': 'USD',
      'taxUnits': 75000,
      'customerId': null,
      'jobId': null,
      'validUntil': null,
      'terms': '',
      'notes': '',
      'lines': [
        {
          'id': 'tile',
          'title': 'Install tile',
          'detail': '',
          'kind': hourly ? 'HOURS' : 'ITEM',
          'quantity': hourly ? '15000' : '260000',
          'unitPrice': hourly ? '4000' : '1250',
        },
      ],
    },
    'total': hourly ? '64.50' : '349.38',
    'subtotal': hourly ? '60.00' : '325.00',
    'tax': hourly ? '4.50' : '24.38',
    'customer': 'Accepted customer',
    'email': 'accepted@example.test',
    'digits': 2,
  }),
};

class _Scope extends QuoteScopeApi {
  _Scope({this.hourly = false});
  final bool hourly;
  @override
  Future<QuoteScopeData> read(
    String quoteId,
    int revision, {
    String? jobId,
  }) async => QuoteScopeData({
    'version': 7,
    'issued': _issued(hourly: hourly),
    'latestRevision': 1,
    'linkedLines': ['tile'],
    'bindings': [
      {
        'lineId': 'tile',
        'jobId': 'job',
        'taskId': 'task',
        'kind': hourly ? 'HOURS' : 'ITEM',
        'originalRevision': 1,
      },
    ],
    'jobs': [
      {
        'jobId': 'job',
        'name': 'Synthetic job',
        'mode': hourly ? 'HOURLY' : 'TASK',
        'currency': 'USD',
        'archived': false,
      },
    ],
    'target': null,
    'tasks': <Object?>[],
  });
}

class _Quotes extends QuoteApi {
  _Quotes({this.hourly = false});
  final bool hourly;
  @override
  Future<QuoteData> read(String id) async => QuoteData(
    7,
    null,
    QuoteRevision(_issued(hourly: hourly)),
    today: '2026-11-12',
    timezone: 'UTC',
  );
}

class _Invoices extends QuoteInvoiceApi {
  _Invoices({this.hourly = false});
  final bool hourly;
  bool failRead = false, claimed = false;
  int reads = 0, calculations = 0, calculationVersion = 7;
  final calls =
      <
        ({
          String id,
          int version,
          String invoice,
          QuoteInvoiceRequest request,
          QuoteInvoiceChoices choices,
        })
      >[];
  @override
  Future<QuoteInvoiceContext> read(QuoteInvoiceRequest request) async {
    reads++;
    if (failRead) {
      throw StateError('Synthetic read failure');
    }
    return QuoteInvoiceContext({
      'version': 7,
      'problem': null,
      'issued': _issued(hourly: hourly),
      'historical': false,
      'mode': hourly ? 'HOURLY' : 'TASK',
      'currency': 'USD',
      'digits': 2,
      'jobName': 'Synthetic job',
      'archived': false,
      'currentCustomer': 'Current customer',
      'currentEmail': 'current@example.test',
      'currentContact': 'Current address',
      'currentTaxUnits': 100000,
      'currentHourlyMinor': hourly ? '6000' : null,
      'acceptedHourlyMinor': hourly ? '4000' : null,
      'estimatedHoursUnits': hourly ? '15000' : '0',
      'tasks': (claimed || hourly)
          ? <Object?>[]
          : [
              {
                'taskId': 'task',
                'title': 'Actual tile installation',
                'lineId': 'tile',
                'actualQuantityUnits': '300000',
                'estimatedQuantityUnits': '260000',
                'currentUnitPriceMinor': '999',
                'acceptedUnitPriceMinor': '1250',
                'completedLabel': '2026-11-12T12:00:00Z',
              },
            ],
      'times': hourly
          ? [
              {
                'sessionId': 'session',
                'start': 1794484800000,
                'end': 1794488400000,
                'durationMillis': '3600000',
                'label': 'Finalized synthetic hour',
              },
            ]
          : <Object?>[],
      'excluded': <Object?>[],
      'legacy': <Object?>[],
      'unfinalized': <Object?>[],
    });
  }

  @override
  Future<QuoteInvoiceCalculation> calculate(
    QuoteInvoiceRequest request,
    QuoteInvoiceChoices choices,
  ) async {
    calculations++;
    return QuoteInvoiceCalculation({
      'version': calculationVersion,
      'problem': null,
      'subtotalMinor': hourly ? '4000' : '37500',
      'taxMinor': hourly ? '300' : '2813',
      'totalMinor': hourly ? '4300' : '40313',
      'taxUnits': 75000,
      'currency': 'USD',
      'digits': 2,
      'customer': 'Accepted customer',
      'email': 'accepted@example.test',
    });
  }

  @override
  Future<TimerOutcome> prepare(
    String id,
    int version,
    String invoiceId,
    QuoteInvoiceRequest request,
    QuoteInvoiceChoices choices,
  ) async {
    calls.add((
      id: id,
      version: version,
      invoice: invoiceId,
      request: request,
      choices: choices,
    ));
    if (calls.length == 1) {
      throw StateError('Synthetic committed reply lost');
    }
    return const TimerOutcome(8, 'STALE_STATE');
  }
}

Future<void> _reach(WidgetTester tester, Finder target) async {
  await tester.scrollUntilVisible(
    target,
    220,
    scrollable: find.byType(Scrollable).first,
    maxScrolls: 60,
  );
  await tester.pumpAndSettle();
}

Future<void> _show(
  WidgetTester tester,
  _Invoices api, {
  Size size = const Size(900, 1400),
  bool dark = false,
  double textScale = 1,
  GlobalKey? boundary,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        quoteApiProvider.overrideWithValue(_Quotes(hourly: api.hourly)),
        quoteScopeApiProvider.overrideWithValue(_Scope(hourly: api.hourly)),
        quoteInvoiceApiProvider.overrideWithValue(api),
      ],
      child: MaterialApp(
        theme: dark ? ClockroveTheme.dark() : ClockroveTheme.light(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(textScale)),
          child: RepaintBoundary(key: boundary, child: child!),
        ),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => Navigator.push<void>(
                context,
                MaterialPageRoute<void>(
                  builder: (_) =>
                      const QuoteInvoiceScreen(quoteId: 'quote', revision: 1),
                ),
              ),
              child: const Text('Open accepted invoice review'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open accepted invoice review'));
  await tester.pumpAndSettle();
  final source = api.hourly
      ? 'Finalized synthetic hour'
      : 'Actual tile installation';
  await _reach(tester, find.text(source));
  await tester.tap(find.text(source));
  await tester.pumpAndSettle();
  await _reach(
    tester,
    find.widgetWithText(TextFormField, 'Private invoice review note'),
  );
  await tester.enterText(
    find.widgetWithText(TextFormField, 'Private invoice review note'),
    'Reviewed actual installed quantity and accepted price',
  );
}

void main() {
  testWidgets(
    'review cancellation writes nothing and unknown preparation retries identical invoice/source intent',
    (tester) async {
      final api = _Invoices();
      await _show(tester, api);
      await _reach(tester, find.text('Review actual-work invoice'));
      await tester.tap(find.text('Review actual-work invoice'));
      await tester.pumpAndSettle();
      expect(find.text('Prepare this invoice?'), findsOneWidget);
      expect(find.text('Invoice total USD 403.13'), findsOneWidget);
      expect(api.calls, isEmpty);
      await tester.tap(find.text('Keep editing'));
      await tester.pumpAndSettle();
      expect(api.calls, isEmpty);
      await tester.tap(find.text('Review actual-work invoice'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm invoice review'));
      await tester.pumpAndSettle();
      await _reach(tester, find.text('Prepare reviewed invoice'));
      await tester.tap(find.text('Prepare reviewed invoice'));
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(1));
      final first = api.calls.single;
      expect(first.choices.taskIds, ['task']);
      expect(first.choices.currentPrices, isFalse);
      expect(first.version, 7);
      final before = api.reads;
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('Invoice actual work'), findsOneWidget);
      await tester.tap(find.text('Retry same operation'));
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(2));
      final retry = api.calls.last;
      expect(retry.id, first.id);
      expect(retry.invoice, first.invoice);
      expect(retry.version, first.version);
      expect(identical(retry.request, first.request), isTrue);
      expect(identical(retry.choices, first.choices), isTrue);
      expect(api.reads, before);
      expect(find.text('Prepare reviewed invoice'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'failed refresh keeps selected work and note; unavailable selections require an explicit clear',
    (tester) async {
      final api = _Invoices();
      await _show(tester, api);
      api.failRead = true;
      await tester.tap(find.byTooltip('Reload available quote work'));
      await tester.pumpAndSettle();
      await _reach(tester, find.text('Review actual-work invoice'));
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, 'Review actual-work invoice'),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<TextFormField>(
              find.widgetWithText(TextFormField, 'Private invoice review note'),
            )
            .controller!
            .text,
        'Reviewed actual installed quantity and accepted price',
      );
      api.failRead = false;
      api.claimed = true;
      await tester.tap(find.byTooltip('Reload available quote work'));
      await tester.pumpAndSettle();
      await _reach(tester, find.text('Review actual-work invoice'));
      await tester.tap(find.text('Review actual-work invoice'));
      await tester.pumpAndSettle();
      expect(api.calculations, 0);
      expect(api.calls, isEmpty);
      await _reach(tester, find.text('Clear unavailable selections'));
      expect(find.text('Clear unavailable selections'), findsOneWidget);
      await tester.tap(find.text('Clear unavailable selections'));
      await tester.pumpAndSettle();
      expect(find.text('Clear unavailable selections'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'changed calculation version requires refreshing and keeps the entered note',
    (tester) async {
      final api = _Invoices()..calculationVersion = 8;
      await _show(tester, api);
      await _reach(tester, find.text('Review actual-work invoice'));
      await tester.tap(find.text('Review actual-work invoice'));
      await tester.pumpAndSettle();
      expect(find.text('Prepare this invoice?'), findsNothing);
      expect(api.calls, isEmpty);
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, 'Review actual-work invoice'),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<TextFormField>(
              find.widgetWithText(TextFormField, 'Private invoice review note'),
            )
            .controller!
            .text,
        'Reviewed actual installed quantity and accepted price',
      );
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'hourly review requires explicit allocation of finalized time and never uses the estimate as worked time',
    (tester) async {
      final api = _Invoices(hourly: true);
      await _show(tester, api);
      await _reach(tester, find.text('Review actual-work invoice'));
      await tester.tap(find.text('Review actual-work invoice'));
      await tester.pumpAndSettle();
      expect(api.calculations, 0);
      expect(api.calls, isEmpty);
      final allocation = find.text(
        'The selected actual job time belongs to this quote',
      );
      await _reach(tester, allocation);
      await tester.tap(allocation);
      await tester.pumpAndSettle();
      await _reach(tester, find.text('Review actual-work invoice'));
      await tester.tap(find.text('Review actual-work invoice'));
      await tester.pumpAndSettle();
      expect(find.text('Invoice total USD 43.00'), findsOneWidget);
      expect(
        find.text('Finalized synthetic hour\n1h 0m actual finalized work'),
        findsOneWidget,
      );
      expect(api.calculations, 1);
      expect(api.calls, isEmpty);
      await tester.tap(find.text('Keep editing'));
      await tester.pumpAndSettle();
      expect(api.calls, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  for (final dark in [false, true]) {
    testWidgets(
      'quote invoice phone review remains scrollable at large text in ${dark ? 'dark' : 'light'} theme',
      (tester) async {
        await tester.runAsync(loadMapPreviewFonts);
        final key = GlobalKey();
        final api = _Invoices();
        await _show(
          tester,
          api,
          size: const Size(390, 844),
          dark: dark,
          textScale: 1.8,
          boundary: key,
        );
        FocusManager.instance.primaryFocus?.unfocus();
        await tester.pumpAndSettle();
        final list = tester.state<ScrollableState>(
          find.byType(Scrollable).first,
        );
        list.position.jumpTo(0);
        await tester.pumpAndSettle();
        await tester.runAsync(
          () => saveMapPreview(
            key,
            'quote-invoice-setup-${dark ? 'dark' : 'light'}',
          ),
        );
        await _reach(tester, find.text('Review actual-work invoice'));
        await tester.tap(find.text('Review actual-work invoice'));
        await tester.pumpAndSettle();
        expect(find.text('Prepare this invoice?'), findsOneWidget);
        await tester.scrollUntilVisible(
          find.text('Invoice total USD 403.13'),
          180,
          scrollable: find
              .descendant(
                of: find.byType(AlertDialog),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        await tester.pumpAndSettle();
        await tester.runAsync(
          () => saveMapPreview(
            key,
            'quote-invoice-review-${dark ? 'dark' : 'light'}',
          ),
        );
        expect(api.calls, isEmpty);
        await tester.tap(find.text('Keep editing'));
        await tester.pumpAndSettle();
        expect(api.calls, isEmpty);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
