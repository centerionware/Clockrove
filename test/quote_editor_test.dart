import 'package:clockrove/business/quote_api.dart';
import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/quote_editor.dart';
import 'package:clockrove/business/report_api.dart';
import 'package:clockrove/business/settings_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
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
  @override
  Future<BusinessSettings> read() async =>
      const BusinessSettings(1, _company, []);
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
      .where((template) =>
          template.title.toLowerCase().contains(search.toLowerCase()))
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
  List<TaskTemplate> templates = const [],
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        quoteApiProvider.overrideWithValue(api),
        businessApiProvider.overrideWithValue(_Templates(templates)),
        businessSettingsApiProvider.overrideWithValue(_Business()),
        reportApiProvider.overrideWithValue(_Jobs()),
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

      expect(find.text('Added as an editable quote line.'), findsOneWidget);
      expect(find.text('USD 12.50 each'), findsOneWidget);
      final descriptions = find.byWidgetPredicate(
        (widget) =>
            widget is TextField &&
            widget.decoration.labelText == 'Description',
      );
      expect(descriptions, findsNWidgets(2));
      await _reveal(tester, descriptions.at(1), 400);
      await tester.enterText(descriptions.at(1), 'Edited roof installation');
      final save = find.widgetWithText(FilledButton, 'Save quote draft');
      await _reveal(tester, save, 400);
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(api.calls, hasLength(1));
      expect(api.calls.single.draft.lines.last.title, 'Edited roof installation');
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
