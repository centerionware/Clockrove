import 'dart:async';

import 'package:clockrove/business/quote_api.dart';
import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/quote_editor.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/business/settings_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:clockrove/tracking/timer_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

const _company = CompanySettings(
  currency: 'USD',
  digits: 2,
  defaultHourlyMinor: null,
  companyName: '',
  companyEmail: '',
  companyPhone: '',
  companyAddress: '',
  companyWebsite: '',
  invoiceHeader: '',
  invoiceFooter: '',
  invoiceStyle: 'MODERN',
  emailSubject: '',
  emailBody: '',
);

class _Business extends BusinessSettingsApi {
  final List<Customer> customers = [];
  int version = 1;
  @override
  Future<BusinessSettings> read() async =>
      BusinessSettings(version, _company, List.unmodifiable(customers));

  @override
  Future<TimerOutcome> customer(
    String id,
    int version,
    Customer customer,
  ) async {
    customers.removeWhere((existing) => existing.id == customer.id);
    customers.add(customer);
    this.version++;
    return TimerOutcome(this.version, null);
  }
}

class _Timer extends TimerApi {
  @override
  Stream<int> get committedVersions => const Stream.empty();
}

class _Jobs extends ReportApi {
  @override
  Future<ReportContext> context() async => const ReportContext('UTC', []);
}

class _Templates extends BusinessApi {
  _Templates(this.values);
  final List<TaskTemplate> values;
  @override
  Future<List<TaskTemplate>> templates(String search) async => values
      .where(
        (template) =>
            template.title.toLowerCase().contains(search.toLowerCase()),
      )
      .toList();
}

class _Quotes extends QuoteApi {
  _Quotes({this.currency = 'USD'});
  final String currency;
  final calls = <({String id, int version, QuoteDraft draft})>[];
  @override
  Future<int> precision(String currency, {String? quoteId}) async =>
      currency == 'BIF' ? 0 : 2;
  @override
  Future<QuoteData> read(String id) async => QuoteData(
    1,
    QuoteDraft(
      id: id,
      title: 'Original quote',
      currency: currency,
      taxUnits: 75000,
      lines: const [
        QuoteLine('tile', 'Install tile', '', 'ITEM', '260000', '1250'),
      ],
    ),
    null,
  );
  @override
  Future<TimerOutcome> save(String id, int version, QuoteDraft draft) async {
    calls.add((id: id, version: version, draft: draft));
    if (calls.length == 1) {
      throw StateError('Synthetic lost response');
    }
    return const TimerOutcome(3, 'STALE_STATE');
  }
}

Future<void> _show(
  WidgetTester tester,
  _Quotes api, {
  _Business? business,
  List<TaskTemplate> templates = const [],
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        quoteApiProvider.overrideWithValue(api),
        businessApiProvider.overrideWithValue(_Templates(templates)),
        businessSettingsApiProvider.overrideWithValue(business ?? _Business()),
        reportApiProvider.overrideWithValue(_Jobs()),
        timerApiProvider.overrideWithValue(_Timer()),
      ],
      child: const MaterialApp(home: QuoteEditor(quoteId: 'quote')),
    ),
  );
  await tester.pumpAndSettle();
}

Finder _field(String label) => find.widgetWithText(TextField, label);
Future<void> _reveal(
  WidgetTester tester,
  Finder finder,
  double direction,
) async {
  await tester.scrollUntilVisible(
    finder,
    direction,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('quote form opens customer management and refreshes the selector', (
    tester,
  ) async {
    final api = _Quotes();
    final business = _Business();
    await _show(tester, api, business: business);

    final selector = find.byType(DropdownButtonFormField<String>).first;
    expect(
      tester.getTopLeft(find.text('Manage customers')).dy,
      greaterThan(tester.getBottomLeft(selector).dy),
    );
    await tester.tap(find.text('Manage customers'));
    await tester.pumpAndSettle();
    expect(find.text('Customers'), findsOneWidget);
    business.customers.add(
      const Customer(
        id: 'new-quote-customer',
        name: 'New quote customer',
        email: 'customer@example.test',
      ),
    );
    ProviderScope.containerOf(
      tester.element(find.text('Customers')),
    ).invalidate(businessSettingsProvider);
    await tester.pumpAndSettle();
    expect(find.text('New quote customer'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.tap(find.byType(DropdownButtonFormField<String>).first);
    await tester.pumpAndSettle();
    expect(find.text('New quote customer'), findsOneWidget);
  });

  testWidgets(
    'quote task template becomes a separate editable line with quantity and price',
    (tester) async {
      final api = _Quotes();
      await _show(
        tester,
        api,
        templates: const [
          TaskTemplate(
            'old-task',
            'Install roof tile',
            'Set tile in mortar',
            '1250',
            'USD',
            'Archived garage',
            '260000',
          ),
        ],
      );
      final useTemplate = find.text('Add line from task template');
      await _reveal(tester, useTemplate, 400);
      await tester.tap(useTemplate);
      await tester.pumpAndSettle();
      expect(find.text('Choose a task template'), findsOneWidget);
      expect(find.textContaining('Archived garage'), findsOneWidget);
      await tester.tap(find.text('Install roof tile'));
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.text('Line 2'),
        400,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      final descriptions = find.byWidgetPredicate(
        (widget) =>
            widget is TextField &&
            widget.decoration?.labelText == 'Description',
      );
      expect(descriptions, findsWidgets);
      expect(
        tester.widget<TextField>(descriptions.last).controller?.text,
        'Install roof tile',
      );
      final quantities = find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.labelText == 'Quantity',
      );
      expect(tester.widget<TextField>(quantities.last).controller?.text, '26');
      final prices = find.byWidgetPredicate(
        (widget) =>
            widget is TextField &&
            widget.decoration?.labelText == 'Unit price (USD)',
      );
      expect(tester.widget<TextField>(prices.last).controller?.text, '12.50');
      await tester.ensureVisible(descriptions.last);
      await tester.enterText(descriptions.last, 'Edited roof installation');
      final save = find.widgetWithText(FilledButton, 'Save quote draft');
      await _reveal(tester, save, 400);
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(1));
      expect(
        api.calls.single.draft.lines.last.title,
        'Edited roof installation',
      );
      expect(api.calls.single.draft.lines.last.quantity, '260000');
      expect(api.calls.single.draft.lines.last.unitPrice, '1250');
    },
  );
  testWidgets(
    'unknown quote save locks fields and retries the same immutable command',
    (tester) async {
      final api = _Quotes();
      await _show(tester, api);
      await tester.enterText(_field('Quote title'), 'Revised quote title');
      final save = find.widgetWithText(FilledButton, 'Save quote draft');
      await _reveal(tester, save, 400);
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(1));
      expect(api.calls.single.draft.title, 'Revised quote title');
      expect(find.text('Retry same operation'), findsOneWidget);
      await _reveal(tester, _field('Quote title'), -400);
      expect(tester.widget<TextField>(_field('Quote title')).enabled, isFalse);
      await _reveal(tester, find.text('Retry same operation'), 400);
      await tester.tap(find.text('Retry same operation'));
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(2));
      expect(api.calls[1].id, api.calls[0].id);
      expect(api.calls[1].version, api.calls[0].version);
      expect(identical(api.calls[1].draft, api.calls[0].draft), isTrue);
      expect(
        find.textContaining('Data changed. Your draft is kept.'),
        findsOneWidget,
      );
      await _reveal(tester, _field('Quote title'), -400);
      expect(
        tester.widget<TextField>(_field('Quote title')).controller!.text,
        'Revised quote title',
      );
      expect(tester.widget<TextField>(_field('Quote title')).enabled, isTrue);
    },
  );
  testWidgets(
    'native zero-decimal currency rejects fractional price and preserves the pasted input',
    (tester) async {
      final api = _Quotes(currency: 'BIF');
      await _show(tester, api);
      final price = _field('Unit price (BIF)');
      await _reveal(tester, price, 400);
      await tester.enterText(price, '1.5');
      final save = find.widgetWithText(FilledButton, 'Save quote draft');
      await _reveal(tester, save, 400);
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(api.calls, isEmpty);
      expect(
        find.textContaining('This currency allows 0 decimal places.'),
        findsOneWidget,
      );
      await _reveal(tester, price, -400);
      expect(tester.widget<TextField>(price).controller!.text, '1.5');
      expect(tester.widget<TextField>(price).enabled, isTrue);
    },
  );
}
